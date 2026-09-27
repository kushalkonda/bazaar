package dev.bazaarmacro.order;

import dev.bazaarmacro.bazaar.HypixelBazaarClient;
import dev.bazaarmacro.flipper.BazaarOrderFlow;
import dev.bazaarmacro.flipper.CancelRejectionWatcher;
import dev.bazaarmacro.flipper.FlipperCoordinates;
import dev.bazaarmacro.flipper.FlipperManager;
import dev.bazaarmacro.flipper.FlipperSettings;
import dev.bazaarmacro.flipper.FlipperSide;
import dev.bazaarmacro.flipper.OrderSetupWatcher;
import dev.bazaarmacro.flipper.SellClaimWatcher;
import dev.bazaarmacro.macro.ExecutionContext;
import dev.bazaarmacro.macro.MacroDefinition;
import dev.bazaarmacro.macro.MacroExecutor;
import dev.bazaarmacro.macro.MacroStep;
import dev.bazaarmacro.macro.MacroWorkerThread;
import dev.bazaarmacro.util.ClientUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Keeps any number of items listed as Bazaar sell offers at the top of their books, re-pricing
 * whenever one is undercut, until each is sold.
 *
 * <p><b>Declarative, not a state machine.</b> A caller says <em>what it wants listed</em> via
 * {@link #want}; every {@link #tick()} this reads what is actually open in the manage-orders menu
 * and does whatever closes the gap - place what's missing, re-price what's been undercut, claim
 * what's sold, and report anything that's finished. Nothing is remembered about where an order
 * sits or what step a sequence reached, so there is no state to get out of sync with the game:
 * if a poll is missed, a click silently fails, the player cancels an order by hand, or the client
 * reconnects mid-cycle, the next tick simply observes reality again and corrects. That is what
 * makes this robust across arbitrarily many concurrent orders rather than only the single one the
 * old position-based code could track.
 *
 * <p>One menu open per tick covers every tracked listing, instead of one per order - fewer real
 * in-game actions, which is what the rate limiter is rationing.
 *
 * <p>Orders are matched to listings by price (see {@link OrderMenuReader}). Two listings of the
 * same item at indistinguishable prices genuinely cannot be told apart in the menu, so this
 * refuses to act on either rather than risk claiming or cancelling the wrong one - see
 * {@link #tick()}.
 */
public final class SellOrderEngine {

    /**
     * One thing the caller wants listed.
     *
     * @param itemTag     Bazaar product tag, for price lookups.
     * @param enchantId   NBT enchant id if this is an enchant book, else null. Books all share the
     *                    display name "Enchanted Book", so they must be found by NBT, not by name.
     * @param level       NBT enchant level, paired with {@code enchantId}.
     * @param displayName Hypixel's real display name. Used to find non-book items in the
     *                    inventory, and to match chat confirmations when trusted.
     * @param displayNameTrusted whether {@code displayName} was read from a real chat line rather
     *                    than derived from the tag - decides whether claims may be matched by name.
     */
    public record Listing(String itemTag, String enchantId, int level,
                           String displayName, boolean displayNameTrusted) {

        /** An enchant book, located in the inventory by its NBT enchant id and level. */
        public static Listing ofBook(String itemTag, String enchantId, int level,
                                      String displayName, boolean displayNameTrusted) {
            return new Listing(itemTag, enchantId, level, displayName, displayNameTrusted);
        }

        /**
         * Any normal (non-book) item, located in the inventory by its display name. Nothing calls
         * this yet - {@link dev.bazaarmacro.flipper.FlipperEngine} is the obvious caller and hasn't
         * been moved onto these engines - but the engine handles this path, so it's here rather
         * than making callers pass nulls to the constructor.
         */
        public static Listing ofItem(String itemTag, String displayName) {
            return new Listing(itemTag, null, 0, displayName, true);
        }

        boolean isBook() {
            return enchantId != null;
        }
    }

    /** Live state for one listing. Package-private: callers interact through the engine. */
    private static final class Tracked {
        final Listing listing;
        final Runnable onSoldOut;
        double price;
        long qty;
        long collectedProceeds;
        long expectedProceeds;
        int notListedStrikes;
        long nextFillCheckMs;

        Tracked(Listing listing, Runnable onSoldOut) {
            this.listing = listing;
            this.onSoldOut = onSoldOut;
        }
    }

    private static final int SCAN_START = 0;
    private static final int SCAN_END = 200;

    private final BooleanSupplier cancelled;
    private final String macroPrefix;
    private final long minFillCheckMs;
    private final long maxFillCheckMs;
    private final double priceTolerancePercent;
    private final List<Tracked> tracked = new ArrayList<>();

    public SellOrderEngine(String macroPrefix, BooleanSupplier cancelled,
                            long minFillCheckMs, long maxFillCheckMs, double priceTolerancePercent) {
        this.macroPrefix = macroPrefix;
        this.cancelled = cancelled;
        this.minFillCheckMs = minFillCheckMs;
        this.maxFillCheckMs = maxFillCheckMs;
        this.priceTolerancePercent = priceTolerancePercent;
    }

    /** Declare something that should be listed. {@code onSoldOut} runs once it's fully sold, after which it stops being tracked. */
    public synchronized void want(Listing listing, Runnable onSoldOut) {
        tracked.add(new Tracked(listing, onSoldOut));
    }

    /** Stop tracking everything. Does NOT cancel live listings - they keep selling on their own. */
    public synchronized void clear() {
        tracked.clear();
    }

    public synchronized int trackedCount() {
        return tracked.size();
    }

    public synchronized boolean isEmpty() {
        return tracked.isEmpty();
    }

    /**
     * One poll across every tracked listing. Places what isn't listed yet, then - if anything is
     * live - opens the menu once and reconciles all of them against what's really there.
     */
    public synchronized void tick() {
        if (tracked.isEmpty()) return;

        for (Tracked t : new ArrayList<>(tracked)) {
            if (cancelled.getAsBoolean()) return;
            if (t.qty == 0) tryPlace(t);
        }

        boolean anyLive = tracked.stream().anyMatch(t -> t.qty > 0);
        if (!anyLive || cancelled.getAsBoolean()) return;

        boolean anyDueForFillCheck = tracked.stream()
                .anyMatch(t -> t.qty > 0 && System.currentTimeMillis() >= t.nextFillCheckMs);
        List<Tracked> undercut = findUndercut();

        if (undercut.isEmpty() && !anyDueForFillCheck) return;

        BazaarOrderFlow.respectRateLimit();
        if (!OrderMenuReader.openMenu(cancelled)) {
            return; // couldn't look this tick - conclude nothing, just retry next poll
        }
        List<OrderMenuReader.MenuOrder> open = OrderMenuReader.readOpenOrders();

        for (Tracked t : new ArrayList<>(tracked)) {
            if (cancelled.getAsBoolean()) return;
            if (t.qty == 0) continue;
            reconcile(t, open, undercut.contains(t));
        }
    }

    /** Which tracked listings the live order book says are no longer sole top - checked over HTTP, so it costs nothing in-game. */
    private List<Tracked> findUndercut() {
        List<Tracked> result = new ArrayList<>();
        for (Tracked t : tracked) {
            if (t.qty == 0) continue;
            try {
                FlipperManager.TopCheckResult check =
                        FlipperManager.checkTop(t.listing.itemTag(), FlipperSide.SELL_OFFER, t.price);
                // A tie counts: Hypixel breaks ties by recency, so a same-priced offer listed after
                // ours sells first.
                if (check.status() == FlipperManager.TopCheckResult.Status.BEHIND
                        || check.status() == FlipperManager.TopCheckResult.Status.TIED) {
                    result.add(t);
                }
            } catch (Exception e) {
                // Price check failed this tick - leave it alone rather than acting on no information.
            }
        }
        return result;
    }

    /** Reconcile one tracked listing against what the menu actually shows, with the menu already open. */
    private void reconcile(Tracked t, List<OrderMenuReader.MenuOrder> open, boolean isUndercut) {
        OrderMenuReader.MenuOrder live = OrderMenuReader.match(
                open, FlipperSide.SELL_OFFER, t.listing.displayNameTrusted() ? t.listing.displayName() : null,
                t.price, priceTolerancePercent);

        boolean anyAmbiguous = ambiguous(open, t);
        if (anyAmbiguous) {
            ClientUtils.sendMessage("§eTwo sell offers for " + t.listing.itemTag() + " look identical in the "
                    + "manage-orders menu (same price) - leaving both alone this tick rather than risk acting on "
                    + "the wrong one.");
            return;
        }

        if (live == null) {
            t.notListedStrikes++;
            // A single "gone" reading isn't trusted while nothing has been collected: a real
            // render-lag race produced a false empty across consecutive re-opens before.
            if (t.collectedProceeds == 0 && t.notListedStrikes < BazaarOrderFlow.NOT_LISTED_STRIKE_LIMIT) {
                return;
            }
            finish(t, "no longer listed - treating as fully sold (observed "
                    + ExecutionContext.formatDisplay(t.collectedProceeds) + " of an expected "
                    + ExecutionContext.formatDisplay(t.expectedProceeds) + " coins via our own claims)");
            return;
        }

        t.notListedStrikes = 0;
        // Correct our tracked price from the order's own displayed price - ground truth, and it
        // stops a stale estimate from permanently masking a real undercut.
        t.price = live.pricePerUnit();

        if (isUndercut) {
            ClientUtils.sendMessage("§eUndercut on " + t.listing.itemTag() + " - claiming, cancelling and re-pricing.");
            claimAt(t, live.slotIndex());
            if (claimThenCancel(t, live.slotIndex())) {
                t.qty = 0; // next tick re-lists at a fresh top price
            }
            t.nextFillCheckMs = System.currentTimeMillis()
                    + BazaarOrderFlow.randomFillCheckIntervalMs(minFillCheckMs, maxFillCheckMs);
            return;
        }

        if (System.currentTimeMillis() < t.nextFillCheckMs) return;
        t.nextFillCheckMs = System.currentTimeMillis()
                + BazaarOrderFlow.randomFillCheckIntervalMs(minFillCheckMs, maxFillCheckMs);
        claimAt(t, live.slotIndex());
        if (t.expectedProceeds > 0 && t.collectedProceeds >= t.expectedProceeds * 0.98) {
            finish(t, "fully filled - " + ExecutionContext.formatDisplay(t.collectedProceeds) + " coins collected");
        }
    }

    /** Whether more than one live order could plausibly be this tracked listing - see {@link OrderMenuReader#match}. */
    private boolean ambiguous(List<OrderMenuReader.MenuOrder> open, Tracked t) {
        int matches = 0;
        for (OrderMenuReader.MenuOrder order : open) {
            if (order.side() != FlipperSide.SELL_OFFER) continue;
            if (OrderMenuReader.withinTolerance(order.pricePerUnit(), t.price, priceTolerancePercent)) matches++;
        }
        return matches > 1;
    }

    private void finish(Tracked t, String reason) {
        ClientUtils.sendMessage("§aSell offer for " + t.listing.itemTag() + " " + reason + ".");
        tracked.remove(t);
        if (t.onSoldOut != null) t.onSoldOut.run();
    }

    /**
     * Clicks an order to collect whatever it has sold, with the menu already open, and credits only
     * what Hypixel's own chat confirmation reports. Never a purse delta: a purse rise can't be told
     * apart from unrelated income, and over-crediting could push a still-open offer past its
     * completion threshold and abandon it.
     */
    private void claimAt(Tracked t, int slotIndex) {
        MacroDefinition claim = new MacroDefinition(macroPrefix + "SellClaim");
        claim.steps.add(MacroStep.clickSlot(String.valueOf(slotIndex), 0, "PICKUP"));
        claim.steps.add(MacroStep.waitMs(700));
        claim.steps.add(MacroStep.closeScreen());
        MacroExecutor.runBlocking(claim, new ExecutionContext(), cancelled);

        long confirmed = drainClaim(t);
        if (confirmed == 0) {
            MacroWorkerThread.sleep(500); // give this click's own confirmation a moment to land
            confirmed = drainClaim(t);
        }
        if (confirmed > 0) {
            t.collectedProceeds += confirmed;
            ClientUtils.sendMessage("§aSell offer for " + t.listing.itemTag() + " paid out "
                    + ExecutionContext.formatDisplay(confirmed) + " coins ("
                    + ExecutionContext.formatDisplay(t.collectedProceeds) + "/"
                    + ExecutionContext.formatDisplay(t.expectedProceeds) + ").");
        }
    }

    private long drainClaim(Tracked t) {
        if (t.listing.displayNameTrusted() && t.listing.displayName() != null) {
            return SellClaimWatcher.drainMatching(t.listing.displayName());
        }
        return SellClaimWatcher.drainMatchingByPrice(t.price, priceTolerancePercent);
    }

    /** Claims anything ready, then cancels the remainder. Hypixel refuses to cancel an order with unclaimed goods on it, so this retries with a fresh claim pass. */
    private boolean claimThenCancel(Tracked t, int slotIndex) {
        FlipperCoordinates coords = FlipperCoordinates.get();
        for (int attempt = 1; attempt <= BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT; attempt++) {
            CancelRejectionWatcher.drainRejected();

            BazaarOrderFlow.respectRateLimit();
            if (!OrderMenuReader.openMenu(cancelled)) return false;
            // Re-locate it: cancelling or claiming another order can move things, so the slot index
            // from before the last click is not safe to reuse.
            OrderMenuReader.MenuOrder live = OrderMenuReader.match(OrderMenuReader.readOpenOrders(),
                    FlipperSide.SELL_OFFER, null, t.price, priceTolerancePercent);
            if (live == null) return true; // already gone - nothing left to cancel

            MacroDefinition cancel = new MacroDefinition(macroPrefix + "SellCancel");
            cancel.steps.add(MacroStep.clickSlot(String.valueOf(live.slotIndex()), 0, "PICKUP"));
            BazaarOrderFlow.addTransitionWait(cancel);
            cancel.steps.add(MacroStep.clickSlotReal(coords.cancelButton, 0));
            cancel.steps.add(MacroStep.waitMs(700));
            cancel.steps.add(MacroStep.clickSlot(coords.orderConfirm, 0, "PICKUP"));
            cancel.steps.add(MacroStep.waitMs(500));
            cancel.steps.add(MacroStep.closeScreen());
            boolean ok = MacroExecutor.runBlocking(cancel, new ExecutionContext(), cancelled);

            if (ok && !CancelRejectionWatcher.drainRejected()) return true;
            if (attempt < BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT) {
                ClientUtils.sendMessage("§7Hypixel rejected cancelling the " + t.listing.itemTag()
                        + " sell offer - claiming again and retrying (" + (attempt + 1) + "/"
                        + BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT + ").");
            }
        }
        ClientUtils.sendMessage("§cCouldn't cancel the " + t.listing.itemTag() + " sell offer - leaving it open and "
                + "continuing to watch it.");
        return false;
    }

    /** {@code /bz} -> click the item in the player's own inventory -> Sell Offer -> Top Order preset -> Confirm. Lists the whole held stack; there's no amount-entry step on this path. */
    private void tryPlace(Tracked t) {
        long held = heldCount(t.listing);
        if (held < 1) return; // nothing to sell yet

        FlipperCoordinates coords = FlipperCoordinates.get();
        if (!coords.isSellOrderFlowConfigured()) {
            ClientUtils.sendMessage("§cSell Offer coordinates aren't set up - fill in flipper_coordinates.json.");
            return;
        }

        HypixelBazaarClient.OrderBook book;
        try {
            book = HypixelBazaarClient.getOrderBook(t.listing.itemTag());
        } catch (Exception e) {
            ClientUtils.sendMessage("§cCouldn't fetch the order book for " + t.listing.itemTag() + ": " + e.getMessage());
            return;
        }
        if (book.topSellPrice() <= 0) return;

        // Price off the outlier-resistant top so one thin, far-off offer can't drag ours with it.
        double target = Math.max(0.1, book.stableTopSellPrice() - FlipperSettings.get().priceIncrement);
        if (book.topBuyPrice() > 0 && target <= book.topBuyPrice()) {
            ClientUtils.sendMessage("§c" + t.listing.itemTag() + "'s market is crossed right now - waiting rather "
                    + "than listing a degenerate offer.");
            return;
        }

        BazaarOrderFlow.respectRateLimit();
        ClientUtils.sendMessage("§dListing " + held + "x " + t.listing.itemTag() + " at ~"
                + ExecutionContext.formatDisplay(target) + " each.");

        long placed = submit(t, coords);
        if (placed <= 0) {
            ClientUtils.sendMessage("§cCouldn't place the sell offer for " + t.listing.itemTag() + " - will retry.");
            return;
        }

        t.qty = placed;
        t.collectedProceeds = 0;
        t.notListedStrikes = 0;
        t.price = BazaarOrderFlow.refreshPlacedSellPrice(t.listing.itemTag(), target);
        double afterTax = 1.0 - FlipperSettings.get().taxRatePercent / 100.0;
        t.expectedProceeds = Math.max(1, Math.round(placed * t.price * afterTax));
    }

    private long heldCount(Listing listing) {
        return listing.isBook()
                ? ClientUtils.countEnchantBooksAtLevel(listing.enchantId(), listing.level())
                : ClientUtils.countItem(listing.itemTag());
    }

    private long submit(Tracked t, FlipperCoordinates coords) {
        MacroDefinition open = new MacroDefinition(macroPrefix + "SellOpen");
        open.steps.add(MacroStep.command(coords.claimOrdersCommand));
        open.steps.add(MacroStep.waitForScreen("Bazaar", 10_000));
        open.steps.add(MacroStep.waitMs(400));
        if (!MacroExecutor.runBlocking(open, new ExecutionContext(), cancelled)) return 0;

        MacroDefinition macro = new MacroDefinition(macroPrefix + "SellOrder");
        if (t.listing.isBook()) {
            int slot = ClientUtils.findEnchantBookSlotInOpenScreen(
                    t.listing.enchantId(), t.listing.level(), SCAN_START, SCAN_END);
            if (slot < 0) {
                // Not announced loudly or dumped to chat: the held-count check above already
                // passed, so this is most often the screen not having finished rendering, and the
                // next tick retries. A crash report captures the detail if it's real.
                BazaarOrderFlow.closeScreen(macroPrefix + "Close", cancelled);
                return 0;
            }
            macro.steps.add(MacroStep.clickSlot(String.valueOf(slot), 0, "PICKUP"));
        } else {
            macro.steps.add(MacroStep.findItemSlot(t.listing.displayName(), SCAN_START, SCAN_END, 0, "PICKUP"));
        }
        macro.steps.add(MacroStep.waitMs(500));
        macro.steps.add(MacroStep.clickSlot(coords.sellOfferButton, 0, "PICKUP"));
        macro.steps.add(MacroStep.waitMs(500));
        macro.steps.add(MacroStep.clickSlot(coords.sellPricePreset, 0, "PICKUP"));
        macro.steps.add(MacroStep.waitMs(500));
        macro.steps.add(MacroStep.clickSlot(coords.orderConfirm, 0, "PICKUP"));
        macro.steps.add(MacroStep.waitMs(500));
        macro.steps.add(MacroStep.closeScreen());
        MacroExecutor.runBlocking(macro, new ExecutionContext(), cancelled);

        return drainSetup(t);
    }

    /** Hypixel's own "Sell Offer Setup!" line - by name where one is known, falling back to price. A missed confirmation means a real live order goes untracked, which has really happened here, so both routes are tried. */
    private long drainSetup(Tracked t) {
        if (t.listing.displayName() != null) {
            Long byName = OrderSetupWatcher.drainMatchingSell(t.listing.displayName());
            if (byName == null) {
                MacroWorkerThread.sleep(500);
                byName = OrderSetupWatcher.drainMatchingSell(t.listing.displayName());
            }
            if (byName != null) return byName;
        }
        double expected;
        try {
            expected = HypixelBazaarClient.getOrderBook(t.listing.itemTag()).stableTopSellPrice();
        } catch (Exception e) {
            expected = t.price > 0 ? t.price : 1;
        }
        OrderSetupWatcher.PriceConfirmed byPrice =
                OrderSetupWatcher.drainMatchingSellByPrice(expected, priceTolerancePercent);
        return byPrice != null ? byPrice.quantity() : 0;
    }
}

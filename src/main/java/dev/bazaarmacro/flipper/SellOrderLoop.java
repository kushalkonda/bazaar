package dev.bazaarmacro.flipper;

import dev.bazaarmacro.bazaar.HypixelBazaarClient;
import dev.bazaarmacro.macro.ExecutionContext;
import dev.bazaarmacro.macro.MacroDefinition;
import dev.bazaarmacro.macro.MacroExecutor;
import dev.bazaarmacro.macro.MacroStep;
import dev.bazaarmacro.macro.MacroWorkerThread;
import dev.bazaarmacro.util.ClientUtils;

import java.util.function.BooleanSupplier;

/**
 * "Keep one enchant book listed as a Bazaar sell offer at the top of the book, re-pricing on every
 * undercut, until it's fully sold" - the entire sell side of every engine in this mod, in one
 * place.
 *
 * <p>{@code ScubaSellEngine}, {@code ScubaCraftScript}, {@code LegionCraftScript} and
 * {@code BookFlipperEngine} each carried their own near-identical copy of this (~500 lines between
 * them). Legion's and BookFlipper's were line-for-line identical apart from which field held the
 * item tag. Keeping four copies meant every fix had to be hand-applied four times, which is
 * exactly how a fix landing in one engine and not the others kept happening here.
 *
 * <p>An <em>instance</em> per order rather than static state, so an engine that eventually runs
 * several concurrent listings (Book Flipper's deferred multi-slot phase) just holds several of
 * these instead of needing this rewritten.
 *
 * <h2>The one thing that genuinely differs between callers: how a chat confirmation is matched</h2>
 * Hypixel's "Sell Offer Setup!" / "Claimed ... Sell Offer" lines carry an item name, but every
 * unapplied enchant book shares the generic held name "Enchanted Book", and the real display name
 * is only reliably known for some items:
 * <ul>
 *   <li>{@code displayNameTrusted = true} (Scuba: "Scuba V", read off a real chat line) - claims
 *       are matched by name, which is unambiguous.</li>
 *   <li>{@code displayNameTrusted = false} (Legion, Book Flipper) - the name is a best-effort guess
 *       derived from the product tag, so it's tried first on setup but never trusted alone for
 *       crediting coins; claims match by the order's own per-unit price instead.</li>
 * </ul>
 * Setup confirmation always falls back to price matching if the name doesn't match, including for a
 * trusted name: a missed setup confirmation means a real, live order goes completely untracked,
 * which has really happened in this project (a 275,947-coin offer, recovered by hand).
 */
public final class SellOrderLoop {

    /**
     * @param itemTag             Bazaar product tag, for order-book price lookups.
     * @param enchantId           NBT enchant id used to find the book in the player's inventory -
     *                            never a display name, since unapplied books all share one.
     * @param level               NBT enchant level to match alongside {@code enchantId}.
     * @param displayName         Hypixel's real name for the item, or null if unknown.
     * @param displayNameTrusted  whether {@code displayName} came from a real observed chat line
     *                            rather than being derived from the tag - see the class doc.
     * @param macroPrefix         prefix for generated macro names, so logs say which engine ran a step.
     * @param priceTolerancePercent  how far a chat line's implied per-unit price may sit from this
     *                            order's own price and still be considered the same order.
     */
    public record Config(String itemTag, String enchantId, int level,
                          String displayName, boolean displayNameTrusted,
                          String macroPrefix, long minFillCheckMs, long maxFillCheckMs,
                          double priceTolerancePercent) {
    }

    /** Same wide scan range every engine already used on this screen type - matching is by NBT, so a wide range can't collide with a menu button. */
    private static final int SCAN_START = 0;
    private static final int SCAN_END = 200;

    private final Config config;
    private final BooleanSupplier cancelled;
    private final Runnable onSoldOut;

    private double price;
    private long qty;
    private long collectedProceeds;
    private long expectedProceeds;
    private int notListedStrikes;
    private long nextFillCheckMs;

    /**
     * @param onSoldOut runs once the offer is confirmed fully sold - what each engine does next
     *                  differs entirely (restart a craft cycle, free the slot, keep listing more
     *                  stock), so that decision stays with the engine.
     */
    public SellOrderLoop(Config config, BooleanSupplier cancelled, Runnable onSoldOut) {
        this.config = config;
        this.cancelled = cancelled;
        this.onSoldOut = onSoldOut;
    }

    public boolean hasActiveOrder() {
        return qty > 0;
    }

    public long quantity() {
        return qty;
    }

    public double price() {
        return price;
    }

    /** Clears all tracking. Does NOT cancel a live listing - a listed offer keeps selling on its own. */
    public void reset() {
        qty = 0;
        collectedProceeds = 0;
        expectedProceeds = 0;
        notListedStrikes = 0;
        nextFillCheckMs = 0L;
    }

    /**
     * One poll: place an offer if there isn't one, otherwise re-price it if it's been undercut and
     * opportunistically claim whatever's sold. Safe to call on any cadence; the expensive real
     * in-game fill check paces itself between {@code minFillCheckMs} and {@code maxFillCheckMs}.
     */
    public void tick() {
        if (qty == 0) {
            tryPlace();
            return;
        }

        FlipperManager.TopCheckResult result;
        try {
            result = FlipperManager.checkTop(config.itemTag(), FlipperSide.SELL_OFFER, price);
        } catch (Exception e) {
            ClientUtils.sendMessage("§cSell order book check failed for " + config.itemTag() + ": " + e.getMessage());
            return;
        }

        // TIED counts as behind: Hypixel breaks a price tie by order recency, so a same-priced
        // offer listed after ours sells first.
        if (result.status() == FlipperManager.TopCheckResult.Status.BEHIND
                || result.status() == FlipperManager.TopCheckResult.Status.TIED) {
            ClientUtils.sendMessage("§eUndercut on the " + config.itemTag() + " sell offer - collecting, canceling, and re-pricing.");
            collect(); // grab whatever already sold before pulling the rest back
            if (qty > 0 && claimThenCancel()) {
                qty = 0;
            }
            scheduleNextFillCheck();
            return;
        }

        if (System.currentTimeMillis() < nextFillCheckMs) return;
        scheduleNextFillCheck();
        collect();
    }

    private void scheduleNextFillCheck() {
        nextFillCheckMs = System.currentTimeMillis()
                + BazaarOrderFlow.randomFillCheckIntervalMs(config.minFillCheckMs(), config.maxFillCheckMs());
    }

    private void tryPlace() {
        long held = ClientUtils.countEnchantBooksAtLevel(config.enchantId(), config.level());
        if (held < 1) return; // nothing to sell yet

        FlipperCoordinates coords = FlipperCoordinates.get();
        if (!coords.isSellOrderFlowConfigured()) {
            ClientUtils.sendMessage("§cSell Offer coordinates aren't set up yet - fill in flipper_coordinates.json.");
            return;
        }

        HypixelBazaarClient.OrderBook book;
        try {
            book = HypixelBazaarClient.getOrderBook(config.itemTag());
        } catch (Exception e) {
            ClientUtils.sendMessage("§cFailed to fetch the order book for " + config.itemTag() + ": " + e.getMessage());
            return;
        }

        if (book.topSellPrice() <= 0) {
            ClientUtils.sendMessage("§cOrder book returned a degenerate top sell price for " + config.itemTag()
                    + " - skipping this cycle rather than pricing off it.");
            return;
        }

        // Price off the outlier-resistant top so one thin, far-off offer can't drag ours with it.
        double target = Math.max(0.1, book.stableTopSellPrice() - FlipperSettings.get().priceIncrement);
        double topBuy = book.topBuyPrice();
        if (topBuy > 0 && target <= topBuy) {
            ClientUtils.sendMessage("§cComputed sell price for " + config.itemTag() + " would meet or cross the top "
                    + "buy order - this market is currently crossed. Waiting rather than listing a degenerate offer.");
            return;
        }

        BazaarOrderFlow.respectRateLimit();
        ClientUtils.sendMessage("§dListing " + held + "x level " + config.level() + " \"" + config.enchantId()
                + "\" as a sell offer (targeting ~" + ExecutionContext.formatDisplay(target) + " each).");

        long placedQty = submitSellOffer(coords);
        if (placedQty <= 0) {
            ClientUtils.sendMessage("§cFailed to place the sell order for " + config.itemTag() + " - will retry.");
            return;
        }

        qty = placedQty;
        collectedProceeds = 0;
        notListedStrikes = 0;
        price = BazaarOrderFlow.refreshPlacedSellPrice(config.itemTag(), target);
        double afterTax = 1.0 - FlipperSettings.get().taxRatePercent / 100.0;
        expectedProceeds = Math.max(1, Math.round(placedQty * price * afterTax));
    }

    /**
     * {@code /bz} (any Bazaar screen shows the player's own inventory) -> click the book, found by
     * NBT rather than name -> Sell Offer -> Top Order preset -> Confirm -> close. There's no
     * amount-entry step: this lists the player's entire held stack.
     *
     * @return the quantity Hypixel itself confirmed listing, or 0 if nothing confirmed it.
     */
    private long submitSellOffer(FlipperCoordinates coords) {
        MacroDefinition open = new MacroDefinition(config.macroPrefix() + "SellOrderOpen");
        open.steps.add(MacroStep.command(coords.claimOrdersCommand));
        open.steps.add(MacroStep.waitForScreen("Bazaar", 10_000));
        open.steps.add(MacroStep.waitMs(400));
        if (!MacroExecutor.runBlocking(open, new ExecutionContext(), cancelled)) {
            return 0;
        }

        int slot = ClientUtils.findEnchantBookSlotInOpenScreen(config.enchantId(), config.level(), SCAN_START, SCAN_END);
        if (slot < 0) {
            ClientUtils.sendMessage("§cCouldn't find the level " + config.level() + " \"" + config.enchantId()
                    + "\" book in the Bazaar screen - stopping this attempt. Dumping your real inventory below:");
            ClientUtils.debugDumpInventory();
            BazaarOrderFlow.closeScreen(config.macroPrefix() + "CloseScreen", cancelled);
            return 0;
        }

        MacroDefinition macro = new MacroDefinition(config.macroPrefix() + "SellOrder");
        macro.steps.add(MacroStep.clickSlot(String.valueOf(slot), 0, "PICKUP"));
        macro.steps.add(MacroStep.waitMs(500));
        macro.steps.add(MacroStep.clickSlot(coords.sellOfferButton, 0, "PICKUP"));
        macro.steps.add(MacroStep.waitMs(500));
        macro.steps.add(MacroStep.clickSlot(coords.sellPricePreset, 0, "PICKUP"));
        macro.steps.add(MacroStep.waitMs(500));
        macro.steps.add(MacroStep.clickSlot(coords.orderConfirm, 0, "PICKUP"));
        macro.steps.add(MacroStep.waitMs(500));
        macro.steps.add(MacroStep.closeScreen());
        MacroExecutor.runBlocking(macro, new ExecutionContext(), cancelled);

        return drainSetupConfirmation();
    }

    /** Hypixel's own "Sell Offer Setup!" line: by name when there is one, falling back to the order's implied per-unit price. */
    private long drainSetupConfirmation() {
        if (config.displayName() != null) {
            Long byName = OrderSetupWatcher.drainMatchingSell(config.displayName());
            if (byName == null) {
                MacroWorkerThread.sleep(500);
                byName = OrderSetupWatcher.drainMatchingSell(config.displayName());
            }
            if (byName != null) return byName;
        }
        OrderSetupWatcher.PriceConfirmed byPrice =
                OrderSetupWatcher.drainMatchingSellByPrice(expectedListingPrice(), config.priceTolerancePercent());
        return byPrice != null ? byPrice.quantity() : 0;
    }

    /** The price a just-placed offer should have landed at - re-read live, since the "Top Order" preset sets the real price server-side at click time. */
    private double expectedListingPrice() {
        try {
            return HypixelBazaarClient.getOrderBook(config.itemTag()).stableTopSellPrice();
        } catch (Exception e) {
            return price > 0 ? price : 1;
        }
    }

    /**
     * Hypixel's own claim-confirmation line is the <em>only</em> thing ever credited as proceeds -
     * never a purse delta, which can't be told apart from unrelated income (a talisman, a booster,
     * a mob drop) and, if wrongly credited, could push the total past the completion threshold and
     * abandon a still-open offer.
     */
    private long drainClaimConfirmation() {
        if (config.displayNameTrusted() && config.displayName() != null) {
            return SellClaimWatcher.drainMatching(config.displayName());
        }
        return SellClaimWatcher.drainMatchingByPrice(price, config.priceTolerancePercent());
    }

    private void credit(long coins) {
        collectedProceeds += coins;
        ClientUtils.sendMessage("§aSell offer for " + config.itemTag() + " paid out "
                + ExecutionContext.formatDisplay(coins) + " coins ("
                + ExecutionContext.formatDisplay(collectedProceeds) + "/"
                + ExecutionContext.formatDisplay(expectedProceeds) + ").");
    }

    /**
     * Claims whatever's sold so far without touching the order itself. Whether the offer is
     * <em>done</em> is decided by whether its slot in the manage-orders menu still holds an item at
     * all - not by inferring it from proceeds, which a concurrent order or unrelated income can
     * move. A single empty read isn't trusted while nothing has been collected yet: real gameplay
     * showed a false-empty read persist across consecutive re-opens (a render-lag race), so that
     * needs {@link BazaarOrderFlow#NOT_LISTED_STRIKE_LIMIT} consecutive empties to count.
     */
    private void collect() {
        long alreadyConfirmed = drainClaimConfirmation();
        if (alreadyConfirmed > 0) credit(alreadyConfirmed);

        FlipperCoordinates coords = FlipperCoordinates.get();
        if (!coords.isClaimFlowConfigured()) return;

        BazaarOrderFlow.respectRateLimit();
        boolean stillListed = BazaarOrderFlow.openMenuAndCheckSlot(config.macroPrefix(), coords.sellClaimSlot, cancelled);

        if (stillListed) {
            notListedStrikes = 0;
            MacroDefinition claim = new MacroDefinition(config.macroPrefix() + "SellClaimClick");
            claim.steps.add(MacroStep.clickSlot(coords.sellClaimSlot, 0, "PICKUP"));
            claim.steps.add(MacroStep.waitMs(700));
            claim.steps.add(MacroStep.closeScreen());
            MacroExecutor.runBlocking(claim, new ExecutionContext(), cancelled);

            long confirmed = drainClaimConfirmation();
            if (confirmed == 0) {
                MacroWorkerThread.sleep(500); // give this click's own confirmation a moment to arrive
                confirmed = drainClaimConfirmation();
            }
            if (confirmed > 0) credit(confirmed);
        } else {
            BazaarOrderFlow.closeScreen(config.macroPrefix() + "CloseScreen", cancelled);
            notListedStrikes++;
            if (collectedProceeds == 0 && notListedStrikes < BazaarOrderFlow.NOT_LISTED_STRIKE_LIMIT) {
                return; // ambiguous miss with nothing corroborating it - recheck next poll
            }
        }

        if (!stillListed) {
            ClientUtils.sendMessage(collectedProceeds >= expectedProceeds
                    ? "§aSell offer for " + config.itemTag() + " fully filled - "
                            + ExecutionContext.formatDisplay(collectedProceeds) + " coins collected."
                    : "§aSell offer for " + config.itemTag() + " no longer listed - treating as fully filled (only "
                            + "observed " + ExecutionContext.formatDisplay(collectedProceeds) + " of the expected "
                            + ExecutionContext.formatDisplay(expectedProceeds) + " coins via our own claims).");
            finish();
        } else if (collectedProceeds >= expectedProceeds * 0.98) {
            ClientUtils.sendMessage("§aSell offer for " + config.itemTag() + " fully filled - "
                    + ExecutionContext.formatDisplay(collectedProceeds) + " coins collected.");
            finish();
        }
    }

    private void finish() {
        qty = 0;
        notListedStrikes = 0;
        onSoldOut.run();
    }

    /** Claims anything ready on the offer, then cancels the rest - retrying, since Hypixel refuses to cancel an order with unclaimed goods still on it. */
    private boolean claimThenCancel() {
        FlipperCoordinates coords = FlipperCoordinates.get();
        String claimSlot = coords.sellClaimSlot;

        for (int attempt = 1; attempt <= BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT; attempt++) {
            CancelRejectionWatcher.drainRejected(); // clear any stale rejection from before this attempt

            BazaarOrderFlow.respectRateLimit();
            MacroDefinition claim = new MacroDefinition(config.macroPrefix() + "SellClaimBeforeCancel");
            claim.steps.add(MacroStep.command(coords.claimOrdersCommand));
            claim.steps.add(MacroStep.waitForScreen("Bazaar", 10_000));
            claim.steps.add(MacroStep.waitMs(400));
            claim.steps.add(MacroStep.clickSlot(coords.claimOrdersMenu, 0, "PICKUP"));
            BazaarOrderFlow.addTransitionWait(claim);
            claim.steps.add(MacroStep.clickSlot(claimSlot, 0, "PICKUP"));
            claim.steps.add(MacroStep.waitMs(500));
            claim.steps.add(MacroStep.closeScreen());
            MacroExecutor.runBlocking(claim, new ExecutionContext(), cancelled);

            BazaarOrderFlow.respectRateLimit();
            MacroDefinition cancel = new MacroDefinition(config.macroPrefix() + "SellCancel");
            cancel.steps.add(MacroStep.command(coords.claimOrdersCommand));
            cancel.steps.add(MacroStep.waitForScreen("Bazaar", 10_000));
            cancel.steps.add(MacroStep.waitMs(400));
            cancel.steps.add(MacroStep.clickSlot(coords.claimOrdersMenu, 0, "PICKUP"));
            BazaarOrderFlow.addTransitionWait(cancel);
            cancel.steps.add(MacroStep.clickSlot(claimSlot, 0, "PICKUP"));
            BazaarOrderFlow.addTransitionWait(cancel);
            cancel.steps.add(MacroStep.clickSlotReal(coords.cancelButton, 0));
            cancel.steps.add(MacroStep.waitMs(700));
            cancel.steps.add(MacroStep.clickSlot(coords.orderConfirm, 0, "PICKUP"));
            cancel.steps.add(MacroStep.waitMs(500));
            cancel.steps.add(MacroStep.closeScreen());
            boolean canceled = MacroExecutor.runBlocking(cancel, new ExecutionContext(), cancelled);

            if (canceled && !CancelRejectionWatcher.drainRejected()) return true;
            if (attempt < BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT) {
                ClientUtils.sendMessage("§7Hypixel rejected canceling the sell offer - it still had unclaimed goods "
                        + "on it. Claiming again and retrying (attempt " + (attempt + 1) + "/"
                        + BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT + ").");
            }
        }
        ClientUtils.sendMessage("§cFailed to cancel the sell offer for " + config.itemTag() + " after "
                + BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT + " attempts - will keep watching it.");
        return false;
    }

    }

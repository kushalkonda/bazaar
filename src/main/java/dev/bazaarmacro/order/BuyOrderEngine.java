package dev.bazaarmacro.order;

import dev.bazaarmacro.bazaar.HypixelBazaarClient;
import dev.bazaarmacro.flipper.BazaarOrderFlow;
import dev.bazaarmacro.flipper.CancelRejectionWatcher;
import dev.bazaarmacro.flipper.FlipperCoordinates;
import dev.bazaarmacro.flipper.FlipperManager;
import dev.bazaarmacro.flipper.FlipperSettings;
import dev.bazaarmacro.flipper.FlipperSide;
import dev.bazaarmacro.flipper.OrderSetupWatcher;
import dev.bazaarmacro.macro.ExecutionContext;
import dev.bazaarmacro.macro.MacroDefinition;
import dev.bazaarmacro.macro.MacroExecutor;
import dev.bazaarmacro.macro.MacroStep;
import dev.bazaarmacro.macro.MacroWorkerThread;
import dev.bazaarmacro.macro.SlotCoordinate;
import dev.bazaarmacro.util.ClientUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps any number of Bazaar buy orders working at the top of their books, re-pricing whenever one
 * is outbid, until each has bought the quantity asked for.
 *
 * <p>The buy-side counterpart to {@link SellOrderEngine}, and built the same way: callers declare
 * what they want bought via {@link #want}, and every {@link #tick()} reconciles that against what
 * {@link OrderMenuReader} says is genuinely open - placing what's missing, re-pricing what's been
 * outbid, claiming what's filled. No slot positions or sequence steps are remembered between
 * polls, so a missed tick, a silently-failed click, a hand-cancelled order or a reconnect all
 * self-correct on the next observation instead of leaving the engine stuck.
 *
 * <p>Buying navigates differently from selling and that's why these are separate engines, not one
 * with a flag: a buy goes through a {@code /bz} text search and a typed quantity, whereas a sell
 * clicks the item directly in the player's own inventory and lists the whole stack. The search step
 * is also where buying can go wrong in a way selling can't - search text is not always the item's
 * real name - so a result is only ever clicked once its own displayed name <em>and</em> its own
 * displayed prices both match what was expected. See {@link #locateVerifiedResult}.
 */
public final class BuyOrderEngine {

    /**
     * Something the caller wants bought.
     *
     * @param itemTag        Bazaar product tag, for price lookups.
     * @param searchCandidates search texts to try in {@code /bz}, in order. More than one because a
     *                       tag doesn't always yield the real searchable name on the first guess;
     *                       each is verified against the real result before anything is clicked.
     * @param expectedName   the display name a correct search result should have, per candidate index.
     * @param targetQty      how many to buy in total across however many orders that takes.
     */
    public record Request(String itemTag, List<String> searchCandidates, List<String> expectedName, long targetQty) {
    }

    private static final class Tracked {
        final Request request;
        final LongConsumer onClaimed;
        long obtained;
        double price;
        long orderQty;
        long claimedThisOrder;
        int notListedStrikes;
        long nextFillCheckMs;

        Tracked(Request request, LongConsumer onClaimed) {
            this.request = request;
            this.onClaimed = onClaimed;
        }
    }

    private static final int SEARCH_RESULT_SCAN_START = 10;
    private static final int SEARCH_RESULT_SCAN_END = 42;
    private static final Pattern MAX_VOLUME = Pattern.compile("(?i)(?:buy|sell) up to ([\\d,]+)");

    private final BooleanSupplier cancelled;
    private final String macroPrefix;
    private final long minFillCheckMs;
    private final long maxFillCheckMs;
    private final double priceTolerancePercent;
    private final List<Tracked> tracked = new ArrayList<>();

    public BuyOrderEngine(String macroPrefix, BooleanSupplier cancelled,
                           long minFillCheckMs, long maxFillCheckMs, double priceTolerancePercent) {
        this.macroPrefix = macroPrefix;
        this.cancelled = cancelled;
        this.minFillCheckMs = minFillCheckMs;
        this.maxFillCheckMs = maxFillCheckMs;
        this.priceTolerancePercent = priceTolerancePercent;
    }

    /** Declare something to buy. {@code onClaimed} is handed each batch actually claimed into the inventory, so the caller can react (list it for sale, merge it, count toward a target). */
    public synchronized void want(Request request, LongConsumer onClaimed) {
        tracked.add(new Tracked(request, onClaimed));
    }

    public synchronized void clear() {
        tracked.clear();
    }

    public synchronized boolean isEmpty() {
        return tracked.isEmpty();
    }

    public synchronized int trackedCount() {
        return tracked.size();
    }

    /** One poll across every tracked request: place what isn't open, then reconcile what is against the real menu. */
    public synchronized void tick() {
        if (tracked.isEmpty()) return;

        for (Tracked t : new ArrayList<>(tracked)) {
            if (cancelled.getAsBoolean()) return;
            if (t.orderQty == 0 && t.obtained < t.request.targetQty()) tryPlace(t);
        }

        boolean anyLive = tracked.stream().anyMatch(t -> t.orderQty > 0);
        if (!anyLive || cancelled.getAsBoolean()) return;

        List<Tracked> outbid = findOutbid();
        boolean anyDue = tracked.stream()
                .anyMatch(t -> t.orderQty > 0 && System.currentTimeMillis() >= t.nextFillCheckMs);
        if (outbid.isEmpty() && !anyDue) return;

        BazaarOrderFlow.respectRateLimit();
        if (!OrderMenuReader.openMenu(cancelled)) return;
        List<OrderMenuReader.MenuOrder> open = OrderMenuReader.readOpenOrders();

        for (Tracked t : new ArrayList<>(tracked)) {
            if (cancelled.getAsBoolean()) return;
            if (t.orderQty == 0) continue;
            reconcile(t, open, outbid.contains(t));
        }
    }

    private List<Tracked> findOutbid() {
        List<Tracked> result = new ArrayList<>();
        for (Tracked t : tracked) {
            if (t.orderQty == 0) continue;
            try {
                FlipperManager.TopCheckResult check =
                        FlipperManager.checkTop(t.request.itemTag(), FlipperSide.BUY_ORDER, t.price);
                if (check.status() == FlipperManager.TopCheckResult.Status.BEHIND
                        || check.status() == FlipperManager.TopCheckResult.Status.TIED) {
                    result.add(t);
                }
            } catch (Exception e) {
                // No price information this tick - leave it rather than act blind.
            }
        }
        return result;
    }

    private void reconcile(Tracked t, List<OrderMenuReader.MenuOrder> open, boolean isOutbid) {
        int matches = 0;
        for (OrderMenuReader.MenuOrder order : open) {
            if (order.side() == FlipperSide.BUY_ORDER
                    && OrderMenuReader.withinTolerance(order.pricePerUnit(), t.price, priceTolerancePercent)) {
                matches++;
            }
        }
        if (matches > 1) {
            ClientUtils.sendMessage("§eTwo buy orders at the same price are indistinguishable in the menu - "
                    + "leaving them alone this tick rather than acting on the wrong one.");
            return;
        }

        OrderMenuReader.MenuOrder live =
                OrderMenuReader.match(open, FlipperSide.BUY_ORDER, null, t.price, priceTolerancePercent);

        if (live == null) {
            t.notListedStrikes++;
            if (t.claimedThisOrder == 0 && t.notListedStrikes < BazaarOrderFlow.NOT_LISTED_STRIKE_LIMIT) return;
            ClientUtils.sendMessage("§aBuy order for " + t.request.itemTag() + " is no longer listed - treating it "
                    + "as fully filled (" + t.obtained + "/" + t.request.targetQty() + " obtained).");
            t.orderQty = 0;
            t.notListedStrikes = 0;
            finishIfDone(t);
            return;
        }

        t.notListedStrikes = 0;
        t.price = live.pricePerUnit(); // ground truth from the order's own display

        if (isOutbid) {
            ClientUtils.sendMessage("§eOutbid on " + t.request.itemTag() + " - claiming, cancelling and re-pricing.");
            claimAt(t, live.slotIndex());
            if (claimThenCancel(t)) t.orderQty = 0;
            t.nextFillCheckMs = System.currentTimeMillis()
                    + BazaarOrderFlow.randomFillCheckIntervalMs(minFillCheckMs, maxFillCheckMs);
            return;
        }

        if (System.currentTimeMillis() < t.nextFillCheckMs) return;
        t.nextFillCheckMs = System.currentTimeMillis()
                + BazaarOrderFlow.randomFillCheckIntervalMs(minFillCheckMs, maxFillCheckMs);
        claimAt(t, live.slotIndex());
        if (t.claimedThisOrder >= t.orderQty) {
            t.orderQty = 0;
            finishIfDone(t);
        }
    }

    private void finishIfDone(Tracked t) {
        if (t.obtained >= t.request.targetQty()) {
            ClientUtils.sendMessage("§aFinished buying " + t.obtained + "x " + t.request.itemTag() + ".");
            tracked.remove(t);
        }
    }

    /**
     * Clicks the order to collect whatever has filled, measuring the result from the real inventory
     * count before and after - bought items land in the inventory, which is directly observable,
     * unlike sell proceeds (coins, indistinguishable from other income).
     */
    private void claimAt(Tracked t, int slotIndex) {
        long before = ClientUtils.countItem(t.request.itemTag());

        MacroDefinition claim = new MacroDefinition(macroPrefix + "BuyClaim");
        claim.steps.add(MacroStep.clickSlot(String.valueOf(slotIndex), 0, "PICKUP"));
        claim.steps.add(MacroStep.waitMs(700));
        claim.steps.add(MacroStep.closeScreen());
        MacroExecutor.runBlocking(claim, new ExecutionContext(), cancelled);
        MacroWorkerThread.sleep(400); // let the inventory update actually arrive before reading it

        long gained = Math.max(0, ClientUtils.countItem(t.request.itemTag()) - before);
        if (gained > 0) {
            t.obtained += gained;
            t.claimedThisOrder += gained;
            ClientUtils.sendMessage("§aClaimed " + gained + "x " + t.request.itemTag() + " ("
                    + t.obtained + "/" + t.request.targetQty() + ").");
            if (t.onClaimed != null) t.onClaimed.accept(gained);
        }
    }

    private boolean claimThenCancel(Tracked t) {
        FlipperCoordinates coords = FlipperCoordinates.get();
        for (int attempt = 1; attempt <= BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT; attempt++) {
            CancelRejectionWatcher.drainRejected();

            BazaarOrderFlow.respectRateLimit();
            if (!OrderMenuReader.openMenu(cancelled)) return false;
            OrderMenuReader.MenuOrder live = OrderMenuReader.match(OrderMenuReader.readOpenOrders(),
                    FlipperSide.BUY_ORDER, null, t.price, priceTolerancePercent);
            if (live == null) return true; // already gone

            MacroDefinition cancel = new MacroDefinition(macroPrefix + "BuyCancel");
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
                ClientUtils.sendMessage("§7Hypixel rejected cancelling the buy order - claiming again and retrying ("
                        + (attempt + 1) + "/" + BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT + ").");
            }
        }
        ClientUtils.sendMessage("§cCouldn't cancel the " + t.request.itemTag() + " buy order - continuing to watch it.");
        return false;
    }

    private void tryPlace(Tracked t) {
        FlipperCoordinates coords = FlipperCoordinates.get();
        if (!coords.isBuyOrderFlowConfigured()) {
            ClientUtils.sendMessage("§cBuy Order coordinates aren't set up - fill in flipper_coordinates.json.");
            return;
        }

        HypixelBazaarClient.OrderBook book;
        try {
            book = HypixelBazaarClient.getOrderBook(t.request.itemTag());
        } catch (Exception e) {
            ClientUtils.sendMessage("§cCouldn't fetch the order book for " + t.request.itemTag() + ": " + e.getMessage());
            return;
        }
        if (book.topBuyPrice() <= 0) return;

        double target = book.stableTopBuyPrice() + FlipperSettings.get().priceIncrement;
        if (book.topSellPrice() > 0 && target >= book.topSellPrice()) {
            ClientUtils.sendMessage("§c" + t.request.itemTag() + "'s market is crossed - a limit buy here would cost "
                    + "more than instant-buying. Waiting.");
            return;
        }

        long remaining = t.request.targetQty() - t.obtained;
        long room = ClientUtils.freeInventoryCapacity(t.request.itemTag());
        long qty = Math.min(remaining, room);
        if (qty < 1) return;

        int slot = locateVerifiedResult(t, book);
        if (slot < 0) return; // already reported and dumped by the locator

        long placed = submit(t, coords, slot, qty, target);
        if (placed <= 0) {
            ClientUtils.sendMessage("§cCouldn't place the buy order for " + t.request.itemTag() + " - will retry.");
            return;
        }
        t.orderQty = placed;
        t.claimedThisOrder = 0;
        t.notListedStrikes = 0;
        t.price = BazaarOrderFlow.refreshPlacedBuyPrice(t.request.itemTag(), target);
        t.nextFillCheckMs = System.currentTimeMillis()
                + BazaarOrderFlow.randomFillCheckIntervalMs(minFillCheckMs, maxFillCheckMs);
        ClientUtils.sendMessage("§aBuy order placed: " + placed + "x " + t.request.itemTag() + " at ~"
                + ExecutionContext.formatDisplay(t.price) + " each (" + t.obtained + "/" + t.request.targetQty() + ").");
    }

    /**
     * Searches {@code /bz} for each candidate text in turn and returns the slot of a result whose
     * own displayed name AND own displayed prices both match what's expected - never clicking one
     * on faith, since search text isn't reliably an item's real name.
     *
     * <p>Each candidate gets one retry after a longer settle first: a live run showed a search
     * verify correctly, then fail moments later on a re-place right after a rapid claim+cancel -
     * a screen-not-finished-rendering race, not a wrong search text. Every failed attempt dumps the
     * real screen so a genuine mismatch is diagnosable from the log without a reproduction.
     */
    private int locateVerifiedResult(Tracked t, HypixelBazaarClient.OrderBook book) {
        List<String> candidates = t.request.searchCandidates();
        for (int i = 0; i < candidates.size(); i++) {
            String searchText = candidates.get(i);
            String expectedName = i < t.request.expectedName().size() ? t.request.expectedName().get(i) : null;

            BazaarOrderFlow.respectRateLimit();
            MacroDefinition open = new MacroDefinition(macroPrefix + "BuySearch");
            open.steps.add(MacroStep.command("/bz " + searchText));
            open.steps.add(MacroStep.waitForScreen("Bazaar", 10_000));
            open.steps.add(MacroStep.waitMs(600));
            if (!MacroExecutor.runBlocking(open, new ExecutionContext(), cancelled)) continue;

            int slot = findVerifiedSlot(expectedName, book);
            if (slot < 0) {
                MacroWorkerThread.sleep(1500); // real lag, not necessarily a genuine miss
                slot = findVerifiedSlot(expectedName, book);
            }
            if (slot >= 0) return slot;

            ClientUtils.sendMessage("§7Search \"" + searchText + "\" didn't yield a confirmed match even after a "
                    + "retry" + (i == candidates.size() - 1 ? "." : " - trying the next candidate.")
                    + " Dumping the real screen:");
            ClientUtils.debugDumpOpenScreen();
            BazaarOrderFlow.closeScreen(macroPrefix + "Close", cancelled);
        }
        return -1;
    }

    private int findVerifiedSlot(String expectedName, HypixelBazaarClient.OrderBook book) {
        int end = Math.min(SEARCH_RESULT_SCAN_END, ClientUtils.openScreenSlotCount() - 1);
        for (int i = SEARCH_RESULT_SCAN_START; i <= end; i++) {
            String name = ClientUtils.getOpenSlotName(i).trim();
            if (name.isBlank()) continue;
            if (expectedName != null && !name.equalsIgnoreCase(expectedName)) continue;

            List<String> lore = ClientUtils.getOpenSlotLore(i);
            Double shownBuy = parsePrice(lore, "Buy price:");
            Double shownSell = parsePrice(lore, "Sell price:");
            if (shownBuy == null || shownSell == null) continue;

            // Swap convention (documented in HypixelBazaarClient): the in-game "Buy price"
            // (instant-buy cost) lines up with the top SELL offer, and vice versa.
            if (OrderMenuReader.withinTolerance(shownSell, book.topBuyPrice(), priceTolerancePercent)
                    && OrderMenuReader.withinTolerance(shownBuy, book.topSellPrice(), priceTolerancePercent)) {
                return i;
            }
        }
        return -1;
    }

    private static Double parsePrice(List<String> lore, String label) {
        Pattern pattern = Pattern.compile(Pattern.quote(label) + "\\s*([\\d,]+) coins");
        for (String line : lore) {
            Matcher m = pattern.matcher(line);
            if (m.find()) {
                try {
                    return Double.parseDouble(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    /** Result page -> Buy Order -> amount field -> typed quantity -> Top Order preset -> Confirm. */
    private long submit(Tracked t, FlipperCoordinates coords, int resultSlot, long qty, double targetPrice) {
        MacroDefinition phaseA = new MacroDefinition(macroPrefix + "BuyOpen");
        phaseA.steps.add(MacroStep.clickSlot(String.valueOf(resultSlot), 0, "PICKUP"));
        phaseA.steps.add(MacroStep.waitMs(800));
        phaseA.steps.add(MacroStep.clickSlot(coords.buyOrderButton, 0, "PICKUP"));
        phaseA.steps.add(MacroStep.waitMs(900));
        if (!MacroExecutor.runBlocking(phaseA, new ExecutionContext(), cancelled)) return 0;

        long clamped = clampToStatedMax(coords, qty);

        MacroDefinition phaseB = new MacroDefinition(macroPrefix + "BuyAmount");
        phaseB.steps.add(MacroStep.clickSlot(coords.amountField, 0, "PICKUP"));
        phaseB.steps.add(MacroStep.waitMs(500));
        phaseB.steps.add(MacroStep.submitSignText(String.valueOf(clamped)));
        phaseB.steps.add(MacroStep.waitMs(2000));
        if (!MacroExecutor.runBlocking(phaseB, new ExecutionContext(), cancelled)) return 0;

        // The price-preset click needs the real mouse-input path: a reproduced failure showed the
        // ordinary slot-click having literally zero effect on this one screen.
        MacroDefinition phaseC = new MacroDefinition(macroPrefix + "BuyPreset");
        phaseC.steps.add(MacroStep.clickSlotReal(coords.orderPricePreset, 0));
        phaseC.steps.add(MacroStep.waitMs(700));
        if (!MacroExecutor.runBlocking(phaseC, new ExecutionContext(), cancelled)) return 0;

        MacroDefinition phaseD = new MacroDefinition(macroPrefix + "BuyConfirm");
        phaseD.steps.add(MacroStep.clickSlot(coords.orderConfirm, 0, "PICKUP"));
        phaseD.steps.add(MacroStep.waitMs(500));
        phaseD.steps.add(MacroStep.closeScreen());
        MacroExecutor.runBlocking(phaseD, new ExecutionContext(), cancelled);

        Long byName = OrderSetupWatcher.drainMatchingBuy(t.request.itemTag());
        if (byName != null) return byName;
        OrderSetupWatcher.PriceConfirmed byPrice =
                OrderSetupWatcher.drainMatchingBuyByPrice(targetPrice, priceTolerancePercent);
        if (byPrice == null) {
            MacroWorkerThread.sleep(500);
            byPrice = OrderSetupWatcher.drainMatchingBuyByPrice(targetPrice, priceTolerancePercent);
        }
        return byPrice != null ? byPrice.quantity() : 0;
    }

    /** Reads Hypixel's own stated per-order cap off the amount item's lore and clamps to it - a safety net over our own maths, so a parse miss never blocks the order. */
    private long clampToStatedMax(FlipperCoordinates coords, long requested) {
        try {
            int slotIndex = SlotCoordinate.parse(coords.amountField);
            for (String line : ClientUtils.getOpenSlotLore(slotIndex)) {
                Matcher m = MAX_VOLUME.matcher(line);
                if (m.find()) {
                    return Math.min(requested, Long.parseLong(m.group(1).replace(",", "")));
                }
            }
        } catch (Exception ignored) {
        }
        return requested;
    }
}

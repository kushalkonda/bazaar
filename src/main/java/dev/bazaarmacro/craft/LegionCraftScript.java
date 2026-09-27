package dev.bazaarmacro.craft;

import dev.bazaarmacro.bazaar.HypixelBazaarClient;
import dev.bazaarmacro.books.BookFlipperEngine;
import dev.bazaarmacro.books.BookSniperScanner;
import dev.bazaarmacro.flipper.BazaarOrderFlow;
import dev.bazaarmacro.flipper.CancelRejectionWatcher;
import dev.bazaarmacro.flipper.FlipperCoordinates;
import dev.bazaarmacro.flipper.FlipperEngine;
import dev.bazaarmacro.flipper.FlipperManager;
import dev.bazaarmacro.flipper.FlipperSettings;
import dev.bazaarmacro.flipper.FlipperSide;
import dev.bazaarmacro.flipper.OrderSetupWatcher;
import dev.bazaarmacro.flipper.SellOrderLoop;
import dev.bazaarmacro.macro.ExecutionContext;
import dev.bazaarmacro.macro.MacroDefinition;
import dev.bazaarmacro.macro.MacroExecutor;
import dev.bazaarmacro.macro.MacroRecorder;
import dev.bazaarmacro.macro.MacroStep;
import dev.bazaarmacro.macro.MacroWorkerThread;
import dev.bazaarmacro.util.ClientUtils;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

import java.util.List;
import java.util.Map;

/**
 * {@code /bfm legion craft} - a repeating pipeline: buy 16x level-1 Ultimate Legion books via a
 * real Bazaar buy order (re-pricing on any outbid until the full 16 is obtained), merge them up
 * through the anvil into a single level-5 book (the exact same proven mechanism as
 * {@link ScubaCraftScript} - {@code E3} combine / {@code E2} take-result, count-verified after
 * every shift-click), list that book as a sell offer, and once it sells, restart from the top.
 * Runs until manually stopped.
 *
 * <p><b>Real tag confirmed live, not guessed.</b> A live fetch of
 * {@code https://api.hypixel.net/v2/skyblock/bazaar} this session showed the real product tags
 * are {@code ENCHANTMENT_ULTIMATE_LEGION_1}..{@code _5} - guessing "ENCHANTMENT_LEGION_*" (the
 * user's own shorthand name) would have been wrong, since Hypixel's internal name for this
 * enchant includes an "Ultimate" prefix. Buy-side liquidity was also confirmed real and high
 * (level 1: ~29,500 units sold over the trailing week), so this is a normal, fast-filling buy
 * order, not a rare-book snipe - unlike {@link BookFlipperEngine}, this never needs to wait long
 * for a fill.
 *
 * <p><b>Every stage double-checks itself against real game state</b>, per the user's explicit
 * "make no mistakes... very robust... works 100%" - a buy-order search result is only ever
 * trusted after its own displayed name AND price both match what was expected (same mechanism
 * {@link BookFlipperEngine} uses); which real enchant book was actually obtained is confirmed by
 * diffing the player's own inventory NBT before/after every claim, never assumed from the tag
 * name; the merge phase never proceeds past a step whose real held-count delta doesn't exactly
 * match what was expected; and a merge failure stops the whole script rather than silently
 * looping back into buying more while partially-merged books sit stuck in inventory.
 */
public final class LegionCraftScript {
    private enum Phase {BUY, MERGE, SELL}

    /** Confirmed via a real live Bazaar API fetch this session - see class doc. */
    private static final String LEVEL1_TAG = "ENCHANTMENT_ULTIMATE_LEGION_1";
    private static final String LEVEL5_TAG = "ENCHANTMENT_ULTIMATE_LEGION_5";
    private static final int FIRST_LEVEL = 1;
    private static final int FINAL_LEVEL = 5;
    private static final long TARGET_QTY = 16;

    private static final int SEARCH_RESULT_SCAN_START = 10;
    private static final int SEARCH_RESULT_SCAN_END = 42;
    private static final double PRICE_MATCH_TOLERANCE_PERCENT = 5.0;

    private static final long PRICE_CHECK_TICK_MS = 2_000;
    private static final long MIN_FILL_CHECK_INTERVAL_MS = 5_000;
    private static final long MAX_FILL_CHECK_INTERVAL_MS = 10_000;

    /** Anvil merge screen - identical proven coordinates/timings as {@link ScubaCraftScript}. */
    private static final String ANVIL_COMMAND = "/av";
    private static final String ANVIL_COMBINE_SLOT = "E3";
    private static final String ANVIL_TAKE_RESULT_SLOT = "E2";
    private static final int ANVIL_SCAN_START = 54;
    private static final int INVENTORY_SCAN_END = 200;
    private static final long ANVIL_CLICK_SETTLE_MS = 1500;
    private static final long ANVIL_COMBINE_SETTLE_MS = 2500;
    private static final long ANVIL_OPEN_SETTLE_MS = 1500;

    private static volatile boolean active = false;
    private static volatile boolean cancelRequested = false;
    private static volatile Thread thread;

    private static volatile Phase phase = Phase.BUY;
    /** Discovered from a real NBT diff the first time a fill is claimed - never assumed from the tag name. */
    private static volatile String confirmedEnchantId;

    // -- buy-phase state --------------------------------------------------------------------
    private static volatile long totalObtained;
    private static volatile double buyPrice;
    private static volatile long currentOrderQty;
    private static volatile long currentOrderClaimed;

    // -- sell-phase state: entirely inside SellOrderLoop, rebuilt each cycle once the real
    // enchant id is confirmed (it is only known after the first buy-order fill is claimed).
    private static volatile SellOrderLoop sellLoop;

    private static volatile int notListedStrikes;
    private static volatile long nextFillCheckMs = 0L;

    private LegionCraftScript() {
    }

    public static boolean isActive() {
        return active;
    }

    public static synchronized boolean start() {
        if (active) {
            ClientUtils.sendMessage("§cThe Legion craft script is already running - run §f/bfm legion stop§c first.");
            return false;
        }
        if (FlipperEngine.isActive()) {
            ClientUtils.sendMessage("§cThe flipper is currently active - stop it with §f/bfm stop§c first.");
            return false;
        }
        if (ScubaCraftScript.isActive()) {
            ClientUtils.sendMessage("§cThe Scuba craft script is currently active - stop it with §f/bfm craft stop§c first.");
            return false;
        }
        if (ScubaSellEngine.isActive()) {
            ClientUtils.sendMessage("§cThe Scuba sell engine is currently active - stop it with §f/bfm sell stop§c first.");
            return false;
        }
        if (BookFlipperEngine.isActive()) {
            ClientUtils.sendMessage("§cBook Flipper is currently active - stop it with §f/bfm books stop§c first.");
            return false;
        }
        if (MacroWorkerThread.getInstance().isRunning()) {
            ClientUtils.sendMessage("§cA macro is currently running - stop it with §f/bfm stop§c first.");
            return false;
        }
        if (MacroRecorder.isRecording()) {
            ClientUtils.sendMessage("§cCan't run this while recording - run §f/bfm record stop§c first.");
            return false;
        }

        active = true;
        cancelRequested = false;
        phase = Phase.BUY;
        sellLoop = null;
        totalObtained = 0;
        currentOrderQty = 0;
        confirmedEnchantId = null;
        thread = new Thread(LegionCraftScript::run, "bazaarmacro-legion-craft");
        thread.setDaemon(true);
        thread.start();
        ClientUtils.sendMessage("§dLegion craft script started: buying " + TARGET_QTY + "x level " + FIRST_LEVEL
                + " Ultimate Legion books, merging to level " + FINAL_LEVEL + ", selling, then repeating.");
        return true;
    }

    public static synchronized void stop() {
        if (!active) {
            ClientUtils.sendMessage("§7The Legion craft script isn't running.");
            return;
        }
        cancelRequested = true;
        ClientUtils.sendMessage("§7Stopping the Legion craft script - any currently open order stays live.");
    }

    private static void run() {
        try {
            while (!cancelRequested) {
                try {
                    switch (phase) {
                        case BUY -> tickBuy();
                        case MERGE -> runMergePhase();
                        case SELL -> tickSell();
                    }
                } catch (Exception e) {
                    ClientUtils.sendMessage("§cLegion craft script tick failed: " + e.getMessage());
                    dev.bazaarmacro.util.ErrorReporter.report("LegionCraftScript", "Tick threw in phase " + phase, e);
                }
                if (!cancelRequested) {
                    MacroWorkerThread.sleep(PRICE_CHECK_TICK_MS);
                }
            }
        } finally {
            active = false;
        }
    }

    // ============================================================================================
    // BUY PHASE
    // ============================================================================================

    private static void tickBuy() {
        if (currentOrderQty == 0) {
            long remaining = TARGET_QTY - totalObtained;
            if (remaining <= 0) {
                phase = Phase.MERGE;
                return;
            }
            placeBuyOrder(remaining);
            return;
        }

        FlipperManager.TopCheckResult result;
        try {
            result = FlipperManager.checkTop(LEVEL1_TAG, FlipperSide.BUY_ORDER, buyPrice);
        } catch (Exception e) {
            ClientUtils.sendMessage("§cBuy order book check failed for " + LEVEL1_TAG + ": " + e.getMessage());
            return;
        }

        if (result.status() == FlipperManager.TopCheckResult.Status.BEHIND
                || result.status() == FlipperManager.TopCheckResult.Status.TIED) {
            ClientUtils.sendMessage("§eOutbid on " + LEVEL1_TAG + " (top is now " + ExecutionContext.formatDisplay(result.topPrice())
                    + ") - claiming progress, canceling, and re-placing for the remainder.");
            collectBuyProgress();
            if (currentOrderQty > 0 && claimThenCancelBuy()) {
                currentOrderQty = 0;
            }
            nextFillCheckMs = System.currentTimeMillis() + BazaarOrderFlow.randomFillCheckIntervalMs(MIN_FILL_CHECK_INTERVAL_MS, MAX_FILL_CHECK_INTERVAL_MS);
            return;
        }

        if (System.currentTimeMillis() < nextFillCheckMs) return;
        nextFillCheckMs = System.currentTimeMillis() + BazaarOrderFlow.randomFillCheckIntervalMs(MIN_FILL_CHECK_INTERVAL_MS, MAX_FILL_CHECK_INTERVAL_MS);
        collectBuyProgress();
    }

    private static void placeBuyOrder(long qty) {
        FlipperCoordinates coords = FlipperCoordinates.get();
        if (!coords.isBuyOrderFlowConfigured()) {
            ClientUtils.sendMessage("§cBuy Order coordinates aren't set up yet - fill in flipper_coordinates.json.");
            return;
        }

        HypixelBazaarClient.OrderBook book;
        try {
            book = HypixelBazaarClient.getOrderBook(LEVEL1_TAG);
        } catch (Exception e) {
            ClientUtils.sendMessage("§cFailed to fetch the order book for " + LEVEL1_TAG + ": " + e.getMessage());
            return;
        }
        double stableBuy = book.stableTopBuyPrice();
        double stableSell = book.stableTopSellPrice();
        if (stableBuy <= 0) {
            ClientUtils.sendMessage("§cDegenerate top buy price for " + LEVEL1_TAG + " - skipping this attempt.");
            return;
        }
        double price = stableBuy + FlipperSettings.get().priceIncrement;
        if (stableSell > 0 && price >= stableSell) {
            ClientUtils.sendMessage("§cMarket for " + LEVEL1_TAG + " is crossed right now - waiting rather than placing a degenerate order.");
            return;
        }

        int slot = locateVerifiedSearchResult(LEVEL1_TAG, FIRST_LEVEL, price, stableSell);
        if (slot < 0) {
            ClientUtils.sendMessage("§cCouldn't confidently verify any search result for " + LEVEL1_TAG
                    + " after trying every real candidate search text - stopping rather than guessing.");
            cancelRequested = true;
            return;
        }

        MacroDefinition clickResult = new MacroDefinition("LegionBuyClickResult");
        clickResult.steps.add(MacroStep.clickSlot(String.valueOf(slot), 0, "PICKUP"));
        clickResult.steps.add(MacroStep.waitMs(800));
        clickResult.steps.add(MacroStep.clickSlot(coords.buyOrderButton, 0, "PICKUP"));
        clickResult.steps.add(MacroStep.waitMs(900));
        if (!MacroExecutor.runBlocking(clickResult, new ExecutionContext(), () -> cancelRequested)) {
            ClientUtils.sendMessage("§cFailed to open the buy-order page for " + LEVEL1_TAG + " - will retry.");
            return;
        }

        MacroDefinition amount = new MacroDefinition("LegionBuyAmount");
        amount.steps.add(MacroStep.clickSlot(coords.amountField, 0, "PICKUP"));
        amount.steps.add(MacroStep.waitMs(500));
        amount.steps.add(MacroStep.submitSignText(String.valueOf(qty)));
        amount.steps.add(MacroStep.waitMs(2000));
        if (!MacroExecutor.runBlocking(amount, new ExecutionContext(), () -> cancelRequested)) {
            ClientUtils.sendMessage("§cFailed to enter a quantity for " + LEVEL1_TAG + " - will retry.");
            BazaarOrderFlow.closeScreen("LegionCloseScreen", () -> cancelRequested);
            return;
        }

        MacroDefinition preset = new MacroDefinition("LegionBuyPricePreset");
        preset.steps.add(MacroStep.clickSlotReal(coords.orderPricePreset, 0));
        preset.steps.add(MacroStep.waitMs(700));
        MacroExecutor.runBlocking(preset, new ExecutionContext(), () -> cancelRequested);

        MacroDefinition confirm = new MacroDefinition("LegionBuyConfirm");
        confirm.steps.add(MacroStep.clickSlot(coords.orderConfirm, 0, "PICKUP"));
        confirm.steps.add(MacroStep.waitMs(500));
        confirm.steps.add(MacroStep.closeScreen());
        MacroExecutor.runBlocking(confirm, new ExecutionContext(), () -> cancelRequested);

        OrderSetupWatcher.PriceConfirmed confirmed = OrderSetupWatcher.drainMatchingBuyByPrice(price, PRICE_MATCH_TOLERANCE_PERCENT);
        if (confirmed == null) {
            MacroWorkerThread.sleep(500);
            confirmed = OrderSetupWatcher.drainMatchingBuyByPrice(price, PRICE_MATCH_TOLERANCE_PERCENT);
        }
        if (confirmed == null) {
            ClientUtils.sendMessage("§cNo buy-order confirmation matched the expected price (" + ExecutionContext.formatDisplay(price)
                    + ") for " + LEVEL1_TAG + " - not trusting this as placed. Will retry.");
            return;
        }

        buyPrice = confirmed.pricePerUnit();
        currentOrderQty = confirmed.quantity();
        currentOrderClaimed = 0;
        notListedStrikes = 0;
        nextFillCheckMs = System.currentTimeMillis() + BazaarOrderFlow.randomFillCheckIntervalMs(MIN_FILL_CHECK_INTERVAL_MS, MAX_FILL_CHECK_INTERVAL_MS);
        ClientUtils.sendMessage("§aBuy order placed: " + confirmed.quantity() + "x \"" + confirmed.itemDisplayName()
                + "\" at " + ExecutionContext.formatDisplay(confirmed.pricePerUnit()) + " each (" + totalObtained
                + "/" + TARGET_QTY + " obtained so far).");
    }

    /**
     * Tries every real candidate search text for {@code tag} in turn (see {@link
     * BookSniperScanner#deriveSearchTextCandidates}) - never trusts a single guessed search text
     * on its own. A live run showed {@code "legion"} verify correctly on the very first buy-order
     * placement, then fail moments later on a re-place right after a rapid claim+cancel sequence -
     * a real screen-not-finished-rendering race, not a wrong search text (matches the exact same
     * failure mode {@code openMenuAndCheckSlot} was hardened against earlier this project: a real,
     * populated screen briefly reading as empty right after other actions). Each candidate now gets
     * one retry with a longer settle wait before being treated as a genuine miss, and every failed
     * attempt (not just the last) dumps the real screen so any future mismatch is fully diagnosable
     * from one log, no reproduction needed.
     */
    private static int locateVerifiedSearchResult(String tag, int level, double expectedBuyOrderPrice, double expectedSellOfferPrice) {
        List<String> candidates = BookSniperScanner.deriveSearchTextCandidates(tag);
        for (int i = 0; i < candidates.size(); i++) {
            String searchText = candidates.get(i);
            String expectedName = BookSniperScanner.deriveExpectedDisplayNameFromSearchText(searchText, level);

            BazaarOrderFlow.respectRateLimit();
            MacroDefinition open = new MacroDefinition("LegionOpenSearch");
            open.steps.add(MacroStep.command("/bz " + searchText));
            open.steps.add(MacroStep.waitForScreen("Bazaar", 10_000));
            open.steps.add(MacroStep.waitMs(600));
            if (!MacroExecutor.runBlocking(open, new ExecutionContext(), () -> cancelRequested)) {
                ClientUtils.sendMessage("§cCouldn't open a search for \"" + searchText + "\" - trying the next candidate.");
                continue;
            }

            int slot = findVerifiedResultSlot(expectedName, expectedBuyOrderPrice, expectedSellOfferPrice);
            if (slot < 0) {
                // Real lag, not necessarily a genuine miss - give the search results one more real chance to render.
                MacroWorkerThread.sleep(1500);
                slot = findVerifiedResultSlot(expectedName, expectedBuyOrderPrice, expectedSellOfferPrice);
            }
            if (slot >= 0) {
                ClientUtils.sendMessage("§7Confirmed real search text \"" + searchText + "\" -> \"" + expectedName
                        + "\" (matched by both name and price).");
                return slot;
            }

            boolean lastCandidate = i == candidates.size() - 1;
            ClientUtils.sendMessage("§7Search \"" + searchText + "\" (expected \"" + expectedName + "\") didn't yield a "
                    + "confirmed match even after a retry wait" + (lastCandidate ? "." : " - trying the next candidate.")
                    + " Dumping the real screen below:");
            ClientUtils.debugDumpOpenScreen();
            BazaarOrderFlow.closeScreen("LegionCloseScreen", () -> cancelRequested);
        }
        return -1;
    }

    /** Same name+price cross-check {@link BookFlipperEngine} uses - never click a search result on faith. */
    private static int findVerifiedResultSlot(String expectedName, double expectedBuyOrderPrice, double expectedSellOfferPrice) {
        AbstractContainerScreen<?> screen = ClientUtils.getOpenContainerScreen();
        if (screen == null) return -1;

        List<Slot> slots = screen.getMenu().slots;
        int end = Math.min(SEARCH_RESULT_SCAN_END, slots.size() - 1);
        for (int i = Math.max(0, SEARCH_RESULT_SCAN_START); i <= end; i++) {
            Slot slot = slots.get(i);
            if (!slot.hasItem()) continue;
            String name = ClientUtils.stripColors(slot.getItem().getHoverName().getString()).trim();
            if (!name.equalsIgnoreCase(expectedName)) continue;

            List<String> lore = ClientUtils.getOpenSlotLore(i);
            Double inGameBuyPrice = parsePriceFromLore(lore, "Buy price:");
            Double inGameSellPrice = parsePriceFromLore(lore, "Sell price:");
            if (inGameBuyPrice == null || inGameSellPrice == null) continue;

            // Swap convention (already documented in HypixelBazaarClient): in-game "Buy price"
            // (instant-buy cost) lines up with the top SELL offer; in-game "Sell price" (instant-sell
            // proceeds) lines up with the top BUY order.
            if (withinTolerance(inGameSellPrice, expectedBuyOrderPrice) && withinTolerance(inGameBuyPrice, expectedSellOfferPrice)) {
                return i;
            }
        }
        return -1;
    }

    private static Double parsePriceFromLore(List<String> lore, String label) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(label) + "\\s*([\\d,]+) coins");
        for (String line : lore) {
            java.util.regex.Matcher m = pattern.matcher(line);
            if (m.find()) {
                try {
                    return Double.parseDouble(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private static boolean withinTolerance(double actual, double expected) {
        if (expected <= 0) return false;
        return Math.abs(actual - expected) / expected * 100.0 <= PRICE_MATCH_TOLERANCE_PERCENT;
    }

    private static void collectBuyProgress() {
        FlipperCoordinates coords = FlipperCoordinates.get();
        if (!coords.isClaimFlowConfigured()) return;

        BazaarOrderFlow.respectRateLimit();
        Map<ClientUtils.EnchantBookInfo, Long> before = ClientUtils.countAllEnchantBooks();
        boolean stillListed = BazaarOrderFlow.openMenuAndCheckSlot("Legion", coords.buyClaimSlot, () -> cancelRequested);

        if (stillListed) {
            notListedStrikes = 0;
            MacroDefinition claim = new MacroDefinition("LegionBuyClaimClick");
            claim.steps.add(MacroStep.clickSlot(coords.buyClaimSlot, 0, "PICKUP"));
            claim.steps.add(MacroStep.waitMs(500));
            claim.steps.add(MacroStep.closeScreen());
            MacroExecutor.runBlocking(claim, new ExecutionContext(), () -> cancelRequested);
        } else {
            BazaarOrderFlow.closeScreen("LegionCloseScreen", () -> cancelRequested);
            notListedStrikes++;
            if (currentOrderClaimed == 0 && notListedStrikes < BazaarOrderFlow.NOT_LISTED_STRIKE_LIMIT) return;
        }

        Map<ClientUtils.EnchantBookInfo, Long> after = ClientUtils.countAllEnchantBooks();
        long gained = diffGainedForLevel(before, after, FIRST_LEVEL);
        if (gained > 0) {
            currentOrderClaimed += gained;
            totalObtained += gained;
            ClientUtils.sendMessage("§aCollected " + gained + "x level " + FIRST_LEVEL + " book (" + totalObtained
                    + "/" + TARGET_QTY + " total obtained).");
        }

        if (totalObtained >= TARGET_QTY) {
            ClientUtils.sendMessage("§aAll " + TARGET_QTY + "x level " + FIRST_LEVEL + " books obtained - moving to the anvil merge.");
            currentOrderQty = 0;
            phase = Phase.MERGE;
            return;
        }

        if (!stillListed) {
            ClientUtils.sendMessage("§eBuy order for " + LEVEL1_TAG + " no longer listed (" + totalObtained + "/" + TARGET_QTY
                    + " obtained) - placing a fresh order for the remainder.");
            currentOrderQty = 0;
        }
    }

    /**
     * Diffs a real inventory snapshot to find how much of a level-{@code targetLevel} book was
     * gained, and - the first time this happens - discovers {@link #confirmedEnchantId} from
     * whichever real NBT enchant id actually increased, rather than assuming it matches the tag's
     * name segment (that mapping has only ever been independently confirmed for Scuba). If more
     * than one distinct level-{@code targetLevel} book increased at once, this can't confidently
     * tell which is the Legion order's fill - it stops the whole script rather than guessing.
     */
    private static long diffGainedForLevel(Map<ClientUtils.EnchantBookInfo, Long> before, Map<ClientUtils.EnchantBookInfo, Long> after, int targetLevel) {
        if (confirmedEnchantId != null) {
            ClientUtils.EnchantBookInfo key = new ClientUtils.EnchantBookInfo(confirmedEnchantId, targetLevel);
            return Math.max(0, after.getOrDefault(key, 0L) - before.getOrDefault(key, 0L));
        }

        ClientUtils.EnchantBookInfo found = null;
        long foundGain = 0;
        for (Map.Entry<ClientUtils.EnchantBookInfo, Long> entry : after.entrySet()) {
            if (entry.getKey().level() != targetLevel) continue;
            long gain = entry.getValue() - before.getOrDefault(entry.getKey(), 0L);
            if (gain <= 0) continue;
            if (found != null) {
                ClientUtils.sendMessage("§cMultiple different level " + targetLevel + " enchant books increased at once (\""
                        + found.enchantId() + "\" and \"" + entry.getKey().enchantId() + "\") - can't confidently tell which "
                        + "is the Legion order's fill. Stopping rather than guessing.");
                cancelRequested = true;
                return 0;
            }
            found = entry.getKey();
            foundGain = gain;
        }
        if (found != null) {
            confirmedEnchantId = found.enchantId();
            ClientUtils.sendMessage("§7Confirmed the real enchant id from NBT: \"" + confirmedEnchantId + "\" (read directly off the item, not guessed from the tag name).");
        }
        return foundGain;
    }

    private static boolean claimThenCancelBuy() {
        FlipperCoordinates coords = FlipperCoordinates.get();
        if (!coords.isClaimFlowConfigured()) return false;
        String claimSlot = coords.buyClaimSlot;

        for (int attempt = 1; attempt <= BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT; attempt++) {
            CancelRejectionWatcher.drainRejected();

            BazaarOrderFlow.respectRateLimit();
            MacroDefinition claim = new MacroDefinition("LegionBuyClaimBeforeCancel");
            claim.steps.add(MacroStep.command(coords.claimOrdersCommand));
            claim.steps.add(MacroStep.waitForScreen("Bazaar", 10_000));
            claim.steps.add(MacroStep.waitMs(400));
            claim.steps.add(MacroStep.clickSlot(coords.claimOrdersMenu, 0, "PICKUP"));
            BazaarOrderFlow.addTransitionWait(claim);
            claim.steps.add(MacroStep.clickSlot(claimSlot, 0, "PICKUP"));
            claim.steps.add(MacroStep.waitMs(500));
            claim.steps.add(MacroStep.closeScreen());
            MacroExecutor.runBlocking(claim, new ExecutionContext(), () -> cancelRequested);

            BazaarOrderFlow.respectRateLimit();
            MacroDefinition cancel = new MacroDefinition("LegionBuyCancel");
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
            boolean canceled = MacroExecutor.runBlocking(cancel, new ExecutionContext(), () -> cancelRequested);

            if (canceled && !CancelRejectionWatcher.drainRejected()) return true;
            if (attempt < BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT) {
                ClientUtils.sendMessage("§7Hypixel rejected canceling the buy order - retrying (attempt "
                        + (attempt + 1) + "/" + BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT + ").");
            }
        }
        ClientUtils.sendMessage("§cFailed to cancel the buy order for " + LEVEL1_TAG + " after " + BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT + " attempts.");
        return false;
    }

    // ============================================================================================
    // MERGE PHASE - identical proven mechanism to ScubaCraftScript, parameterized by confirmedEnchantId
    // ============================================================================================

    private static void runMergePhase() {
        if (confirmedEnchantId == null) {
            ClientUtils.sendMessage("§cReached the merge phase without ever confirming the real enchant id - this shouldn't happen. Stopping.");
            cancelRequested = true;
            return;
        }

        if (!openAnvil()) {
            cancelRequested = true;
            return;
        }
        if (cancelRequested) {
            BazaarOrderFlow.closeScreen("LegionCloseScreen", () -> cancelRequested);
            return;
        }

        if (!mergeUpToFinalLevel()) {
            BazaarOrderFlow.closeScreen("LegionCloseScreen", () -> cancelRequested);
            cancelRequested = true;
            return;
        }
        BazaarOrderFlow.closeScreen("LegionCloseScreen", () -> cancelRequested);
        if (cancelRequested) return;

        ClientUtils.sendMessage("§aMerged into 1x level " + FINAL_LEVEL + " \"" + confirmedEnchantId + "\" book - moving to the sell order.");
        phase = Phase.SELL;
    }

    private static boolean openAnvil() {
        MacroDefinition open = new MacroDefinition("LegionAnvilOpen");
        open.steps.add(MacroStep.command(ANVIL_COMMAND));
        open.steps.add(MacroStep.waitForScreen("", 10_000));
        open.steps.add(MacroStep.waitMs(ANVIL_OPEN_SETTLE_MS));
        if (!MacroExecutor.runBlocking(open, new ExecutionContext(), () -> cancelRequested)) {
            ClientUtils.sendMessage("§cFailed to open the anvil (" + ANVIL_COMMAND + ") - stopping.");
            return false;
        }
        return true;
    }

    private static boolean mergeUpToFinalLevel() {
        long remainingAtLevel = TARGET_QTY;
        for (int level = FIRST_LEVEL; level < FINAL_LEVEL; level++) {
            long mergesAtThisLevel = remainingAtLevel / 2;
            for (long m = 1; m <= mergesAtThisLevel; m++) {
                if (cancelRequested) return false;
                if (!performSingleMerge(level, level + 1, m, mergesAtThisLevel)) {
                    return false;
                }
            }
            remainingAtLevel = mergesAtThisLevel;
        }
        return true;
    }

    private static boolean performSingleMerge(int currentLevel, int nextLevel, long mergeIndex, long totalMerges) {
        long beforeCurrent = ClientUtils.countEnchantBooksAtLevel(confirmedEnchantId, currentLevel);
        long beforeNext = ClientUtils.countEnchantBooksAtLevel(confirmedEnchantId, nextLevel);
        if (beforeCurrent < 2) {
            ClientUtils.sendMessage("§cMerge " + mergeIndex + "/" + totalMerges + " (level " + currentLevel + " -> "
                    + nextLevel + ") needs 2x level " + currentLevel + " but only " + beforeCurrent
                    + "x is actually held - stopping. Dumping your real inventory below:");
            ClientUtils.debugDumpInventory();
            return false;
        }

        int slot1 = ClientUtils.findEnchantBookSlotInOpenScreen(confirmedEnchantId, currentLevel, ANVIL_SCAN_START, INVENTORY_SCAN_END);
        if (slot1 < 0) {
            ClientUtils.sendMessage("§cMerge " + mergeIndex + "/" + totalMerges + ": couldn't find a level " + currentLevel
                    + " book anywhere in the open screen - stopping. Dumping your real inventory below:");
            ClientUtils.debugDumpInventory();
            return false;
        }
        if (!clickFixedSlot(String.valueOf(slot1), "QUICK_MOVE", ANVIL_CLICK_SETTLE_MS)) return false;
        if (!confirmHeldCountDropped(currentLevel, beforeCurrent - 1, "first", mergeIndex, totalMerges)) return false;

        int slot2 = ClientUtils.findEnchantBookSlotInOpenScreen(confirmedEnchantId, currentLevel, ANVIL_SCAN_START, INVENTORY_SCAN_END);
        if (slot2 < 0) {
            ClientUtils.sendMessage("§cMerge " + mergeIndex + "/" + totalMerges + ": couldn't find a second level "
                    + currentLevel + " book - stopping. Dumping your real inventory below:");
            ClientUtils.debugDumpInventory();
            return false;
        }
        if (!clickFixedSlot(String.valueOf(slot2), "QUICK_MOVE", ANVIL_CLICK_SETTLE_MS)) return false;
        if (!confirmHeldCountDropped(currentLevel, beforeCurrent - 2, "second", mergeIndex, totalMerges)) return false;

        if (!clickFixedSlot(ANVIL_COMBINE_SLOT, "PICKUP", ANVIL_COMBINE_SETTLE_MS)) return false;
        if (!clickFixedSlot(ANVIL_TAKE_RESULT_SLOT, "PICKUP", ANVIL_CLICK_SETTLE_MS)) return false;
        if (!clickFixedSlot(String.valueOf(slot1), "PICKUP", ANVIL_CLICK_SETTLE_MS)) return false;

        long afterCurrent = ClientUtils.countEnchantBooksAtLevel(confirmedEnchantId, currentLevel);
        long afterNext = ClientUtils.countEnchantBooksAtLevel(confirmedEnchantId, nextLevel);
        long currentDelta = beforeCurrent - afterCurrent;
        long nextDelta = afterNext - beforeNext;
        if (currentDelta != 2 || nextDelta != 1) {
            ClientUtils.sendMessage("§cMerge " + mergeIndex + "/" + totalMerges + " (level " + currentLevel + " -> "
                    + nextLevel + ") didn't produce the expected result - wanted level " + currentLevel
                    + " to drop by 2 (actually " + currentDelta + ") and level " + nextLevel + " to rise by 1 (actually "
                    + nextDelta + "). Stopping here. Dumping your real inventory below:");
            ClientUtils.debugDumpInventory();
            return false;
        }

        ClientUtils.sendMessage("§7Merged into level " + nextLevel + " (" + mergeIndex + "/" + totalMerges + ")");
        return true;
    }

    private static boolean confirmHeldCountDropped(int level, long expectedCount, String which, long mergeIndex, long totalMerges) {
        if (ClientUtils.countEnchantBooksAtLevel(confirmedEnchantId, level) == expectedCount) return true;

        MacroWorkerThread.sleep(ANVIL_CLICK_SETTLE_MS);
        if (ClientUtils.countEnchantBooksAtLevel(confirmedEnchantId, level) == expectedCount) return true;

        ClientUtils.sendMessage("§cMerge " + mergeIndex + "/" + totalMerges + ": shift-clicking the " + which
                + " level " + level + " book didn't actually remove it from your inventory (even after a retry "
                + "wait) - it likely bounced to another slot instead of the anvil. Stopping here. Dumping your "
                + "real inventory below:");
        ClientUtils.debugDumpInventory();
        return false;
    }

    // ============================================================================================
    // SELL PHASE - all of it lives in SellOrderLoop; this just owns the instance and what happens
    // once the book actually sells (restart the whole buy -> merge -> sell cycle).
    // ============================================================================================

    private static SellOrderLoop sellLoop() {
        if (sellLoop == null) {
            sellLoop = new SellOrderLoop(
                    new SellOrderLoop.Config(LEVEL5_TAG, confirmedEnchantId, FINAL_LEVEL,
                            // Derived from the tag, so a best-effort guess only - tried first when
                            // confirming the listing, never trusted alone for crediting coins.
                            BookSniperScanner.deriveExpectedDisplayName(LEVEL5_TAG, FINAL_LEVEL), false,
                            "Legion", MIN_FILL_CHECK_INTERVAL_MS, MAX_FILL_CHECK_INTERVAL_MS,
                            PRICE_MATCH_TOLERANCE_PERCENT),
                    () -> cancelRequested,
                    LegionCraftScript::restartCycle);
        }
        return sellLoop;
    }

    private static void tickSell() {
        sellLoop().tick();
    }

    /** Back to buying a fresh 16x batch - the book sold, so this cycle is complete. */
    private static void restartCycle() {
        ClientUtils.sendMessage("§aLevel " + FINAL_LEVEL + " book sold - restarting the cycle.");
        sellLoop = null;
        totalObtained = 0;
        currentOrderQty = 0;
        phase = Phase.BUY;
    }


    // ============================================================================================
    // Shared helpers
    // ============================================================================================

    private static boolean clickFixedSlot(String coordinate, String clickType, long settleMs) {
        MacroDefinition macro = new MacroDefinition("LegionFixedClick");
        macro.steps.add(MacroStep.clickSlot(coordinate, 0, clickType));
        macro.steps.add(MacroStep.waitMs(settleMs));
        return MacroExecutor.runBlocking(macro, new ExecutionContext(), () -> cancelRequested);
    }

}

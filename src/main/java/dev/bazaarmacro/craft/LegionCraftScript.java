package dev.bazaarmacro.craft;

import dev.bazaarmacro.books.BookSniperScanner;
import dev.bazaarmacro.flipper.BazaarOrderFlow;
import dev.bazaarmacro.flipper.FlipperEngine;
import dev.bazaarmacro.macro.ExecutionContext;
import dev.bazaarmacro.macro.MacroDefinition;
import dev.bazaarmacro.macro.MacroExecutor;
import dev.bazaarmacro.macro.MacroRecorder;
import dev.bazaarmacro.macro.MacroStep;
import dev.bazaarmacro.macro.MacroWorkerThread;
import dev.bazaarmacro.order.BuyOrderEngine;
import dev.bazaarmacro.order.SellOrderEngine;
import dev.bazaarmacro.util.ClientUtils;
import dev.bazaarmacro.util.ErrorReporter;

import java.util.List;
import java.util.Map;

/**
 * {@code /bfm legion craft} - the end-to-end exercise for {@link BuyOrderEngine} and
 * {@link SellOrderEngine}, and the one script kept after the engine rewrite.
 *
 * <p>It's kept specifically because it is the only flow that drives <em>both</em> engines against
 * a real, liquid item in a single cycle: buy 16x level-1 Ultimate Legion books with a real buy
 * order, merge them up the anvil into one level-5 book, list that book, and once it sells, start
 * over. If both engines work, this runs indefinitely unattended; if either has a problem, this
 * surfaces it quickly against a real market rather than in a contrived test.
 *
 * <p>Almost all the logic now lives in the two engines. What remains here is the genuinely
 * Legion-specific part: the anvil merge, and confirming which enchant was actually obtained.
 *
 * <p><b>Real tag confirmed live, not guessed.</b> A live fetch of Hypixel's Bazaar API showed the
 * real product tags are {@code ENCHANTMENT_ULTIMATE_LEGION_1}..{@code _5} - guessing
 * "ENCHANTMENT_LEGION_*" from the user's shorthand would have been wrong. Which enchant a claimed
 * book actually is gets confirmed by diffing the inventory's own NBT across the claim, never
 * assumed from the tag name: that tag-word to NBT-key mapping has only ever been independently
 * verified for Scuba.
 */
public final class LegionCraftScript {
    private enum Phase {BUY, MERGE, SELL}

    /** Confirmed via a real live Bazaar API fetch - see class doc. */
    private static final String LEVEL1_TAG = "ENCHANTMENT_ULTIMATE_LEGION_1";
    private static final String LEVEL5_TAG = "ENCHANTMENT_ULTIMATE_LEGION_5";
    private static final int FIRST_LEVEL = 1;
    private static final int FINAL_LEVEL = 5;
    private static final long TARGET_QTY = 16;

    private static final long PRICE_CHECK_TICK_MS = 2_000;
    private static final long MIN_FILL_CHECK_INTERVAL_MS = 5_000;
    private static final long MAX_FILL_CHECK_INTERVAL_MS = 10_000;
    private static final double PRICE_MATCH_TOLERANCE_PERCENT = 5.0;

    /** Anvil merge screen - real recorded coordinates, count-verified after every click. */
    private static final String ANVIL_COMMAND = "/av";
    private static final String ANVIL_COMBINE_SLOT = "E3";
    private static final String ANVIL_TAKE_RESULT_SLOT = "E2";
    /**
     * Scan floor for the anvil screen. Its own top area holds a combine icon and two decorative
     * input indicators in the same range a naive 0-start scan would cover, and a book shift-clicked
     * into one of those still reads as a real match there - so a follow-up scan could re-select the
     * book just placed instead of a fresh one. No real anvil recording ever touched anything below
     * slot 54 when picking up or placing.
     */
    private static final int ANVIL_SCAN_START = 54;
    private static final int INVENTORY_SCAN_END = 200;
    private static final long ANVIL_CLICK_SETTLE_MS = 1500;
    private static final long ANVIL_COMBINE_SETTLE_MS = 2500;
    private static final long ANVIL_OPEN_SETTLE_MS = 1500;

    private static volatile boolean active = false;
    private static volatile boolean cancelRequested = false;
    private static volatile Phase phase = Phase.BUY;

    /** Discovered from a real NBT diff the first time a fill is claimed - never assumed from the tag name. */
    private static volatile String confirmedEnchantId;
    private static volatile long totalObtained;
    private static volatile Map<ClientUtils.EnchantBookInfo, Long> booksBeforeClaim;

    private static volatile BuyOrderEngine buyEngine;
    private static volatile SellOrderEngine sellEngine;

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
        beginBuyPhase();

        Thread thread = new Thread(LegionCraftScript::run, "bazaarmacro-legion-craft");
        thread.setDaemon(true);
        thread.start();
        ClientUtils.sendMessage("§dLegion craft script started: buying " + TARGET_QTY + "x level " + FIRST_LEVEL
                + ", merging to level " + FINAL_LEVEL + ", selling, then repeating.");
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
                    ErrorReporter.report("LegionCraftScript", "Tick threw in phase " + phase, e);
                }
                if (!cancelRequested) MacroWorkerThread.sleep(PRICE_CHECK_TICK_MS);
            }
        } finally {
            active = false;
        }
    }

    // ============================================================================================
    // BUY PHASE - BuyOrderEngine does the ordering; this only confirms what was actually obtained
    // ============================================================================================

    private static void beginBuyPhase() {
        phase = Phase.BUY;
        totalObtained = 0;
        confirmedEnchantId = null;
        sellEngine = null;
        buyEngine = new BuyOrderEngine("Legion", () -> cancelRequested,
                MIN_FILL_CHECK_INTERVAL_MS, MAX_FILL_CHECK_INTERVAL_MS, PRICE_MATCH_TOLERANCE_PERCENT);

        List<String> candidates = BookSniperScanner.deriveSearchTextCandidates(LEVEL1_TAG);
        List<String> expectedNames = candidates.stream()
                .map(text -> BookSniperScanner.deriveExpectedDisplayNameFromSearchText(text, FIRST_LEVEL))
                .toList();
        buyEngine.want(new BuyOrderEngine.Request(LEVEL1_TAG, candidates, expectedNames, TARGET_QTY),
                LegionCraftScript::onBooksClaimed);
    }

    private static void tickBuy() {
        booksBeforeClaim = ClientUtils.countAllEnchantBooks();
        buyEngine.tick();
        if (totalObtained >= TARGET_QTY) {
            ClientUtils.sendMessage("§aGot all " + TARGET_QTY + "x level " + FIRST_LEVEL + " books - merging.");
            phase = Phase.MERGE;
        }
    }

    /**
     * Confirms which enchant was really obtained by diffing the inventory's own NBT across the
     * claim, rather than trusting that the tag's name segment is the NBT key. Stops rather than
     * guess if more than one kind of book appeared at once.
     */
    private static void onBooksClaimed(long claimed) {
        Map<ClientUtils.EnchantBookInfo, Long> after = ClientUtils.countAllEnchantBooks();
        Map<ClientUtils.EnchantBookInfo, Long> before = booksBeforeClaim == null ? Map.of() : booksBeforeClaim;

        ClientUtils.EnchantBookInfo grew = null;
        int growCount = 0;
        for (Map.Entry<ClientUtils.EnchantBookInfo, Long> entry : after.entrySet()) {
            if (entry.getKey().level() != FIRST_LEVEL) continue;
            if (entry.getValue() > before.getOrDefault(entry.getKey(), 0L)) {
                grew = entry.getKey();
                growCount++;
            }
        }

        if (growCount > 1) {
            ClientUtils.sendMessage("§cMore than one kind of level " + FIRST_LEVEL + " book appeared at once - "
                    + "can't tell which is the Legion fill. Stopping rather than guessing.");
            ClientUtils.debugDumpInventory();
            cancelRequested = true;
            return;
        }
        if (grew == null) return;

        if (confirmedEnchantId == null) {
            confirmedEnchantId = grew.enchantId();
            ClientUtils.sendMessage("§7Confirmed the real enchant id from NBT: \"" + confirmedEnchantId
                    + "\" (read off the item, not guessed from the tag name).");
        }
        totalObtained += claimed;
    }

    // ============================================================================================
    // MERGE PHASE - Legion-specific and unchanged: every click count-verified before the next
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
        // Place the merged book (now on the cursor) back into the slot the first input vacated.
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

    /**
     * Confirms a shift-click actually removed a book from the counted inventory. Checking only
     * "did the source slot go empty" isn't enough: the anvil can silently reject a shift-click and
     * Hypixel relocates the item to another free slot instead, leaving the source slot empty
     * either way. One retry wait first, since the anvil is confirmed to lag heavily.
     */
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
    // SELL PHASE - SellOrderEngine does the work; this only decides what happens once it sells
    // ============================================================================================

    private static void tickSell() {
        if (sellEngine == null) {
            sellEngine = new SellOrderEngine("Legion", () -> cancelRequested,
                    MIN_FILL_CHECK_INTERVAL_MS, MAX_FILL_CHECK_INTERVAL_MS, PRICE_MATCH_TOLERANCE_PERCENT);
            sellEngine.want(SellOrderEngine.Listing.ofBook(LEVEL5_TAG, confirmedEnchantId, FINAL_LEVEL,
                            // Derived from the tag, so a guess: tried first when confirming the
                            // listing, never trusted alone for crediting coins.
                            BookSniperScanner.deriveExpectedDisplayName(LEVEL5_TAG, FINAL_LEVEL), false),
                    LegionCraftScript::onSold);
        }
        sellEngine.tick();
    }

    /** Sold - this cycle is complete, so start a fresh one. */
    private static void onSold() {
        ClientUtils.sendMessage("§aLevel " + FINAL_LEVEL + " book sold - restarting the cycle.");
        beginBuyPhase();
    }

    private static boolean clickFixedSlot(String coordinate, String clickType, long settleMs) {
        MacroDefinition macro = new MacroDefinition("LegionFixedClick");
        macro.steps.add(MacroStep.clickSlot(coordinate, 0, clickType));
        macro.steps.add(MacroStep.waitMs(settleMs));
        return MacroExecutor.runBlocking(macro, new ExecutionContext(), () -> cancelRequested);
    }
}

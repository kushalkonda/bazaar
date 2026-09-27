package dev.bazaarmacro.craft;

import dev.bazaarmacro.flipper.SellOrderLoop;
import dev.bazaarmacro.flipper.FlipperEngine;
import dev.bazaarmacro.macro.ExecutionContext;
import dev.bazaarmacro.macro.MacroDefinition;
import dev.bazaarmacro.macro.MacroExecutor;
import dev.bazaarmacro.macro.MacroRecorder;
import dev.bazaarmacro.macro.MacroStep;
import dev.bazaarmacro.macro.MacroWorkerThread;
import dev.bazaarmacro.util.ClientUtils;

/**
 * V0, hardcoded proof-of-concept for the eventual general Craft Flip Macro system (see the
 * project plan) - a single, purpose-built script for exactly one real recipe: craft 16x level-1
 * Scuba enchantment books via {@code /recipe scuba}, merge them up through an anvil interaction
 * to a single level-5 book (15 total merges: 8+4+2+1), then deposit the result into storage via
 * {@code /st}. Repeats this whole cycle for as long as raw materials hold out, and stops itself
 * cleanly (not as an error) the moment a fresh batch's very first craft click produces nothing -
 * see {@link CraftResult}. Not the general, user-configurable, multi-recipe engine - that's a
 * later phase; this exists to get one real recipe working end-to-end first.
 *
 * <p><b>Rebuilt from a real {@code /bfm record} capture, not guessed.</b> A first version guessed
 * a distinct {@code /av} screen with fixed input-slot coordinates and failed completely (every
 * merge produced a 0/0 delta - the "input slots" it clicked weren't real anvil inputs at all). The
 * user then manually performed the entire craft+merge+store flow once with {@code /bfm record},
 * and the real 174-step capture rewrote every assumption below - except one: the recording never
 * shows an {@code /av} command (the anvil screen was already open when recording started), which
 * a second live run proved was still needed - the {@code /recipe} screen closes itself once
 * crafting finishes, so {@code /av} is issued fresh before merging, confirmed as the correct real
 * command directly by the user.
 * <ul>
 *   <li>Books are never placed at a fixed "input slot" coordinate. Each one is shift-clicked
 *       ({@code QUICK_MOVE}) directly from wherever it's actually sitting - Hypixel auto-routes
 *       it. Since crafted/merged books land in whatever slot happens to be free, that location is
 *       different every single time (confirmed by the user: "obviously the books wont be in the
 *       same spot every time") - so every lookup here is a live NBT scan
 *       ({@link ClientUtils#findEnchantBookSlotInOpenScreen}), never a remembered/fixed slot.</li>
 *   <li>{@code E3} ("click to combine") and {@code E2} ("take result", which lands on the cursor,
 *       not in a container slot) are real fixed coordinates, identical across all 15 recorded
 *       merge cycles.</li>
 *   <li>After taking the result, it's placed back into whichever slot the first input book was
 *       shift-clicked out of (now empty) - matching exactly what the recording shows.</li>
 * </ul>
 *
 * <p><b>The final level-{@link #FINAL_LEVEL} book is listed as a self-resetting Bazaar sell
 * offer, not deposited into storage.</b> An earlier version stored the result via {@code /st}, but
 * the user reported it reliably worked for only the first several cycles before failing on later
 * ones. Rather than debug that further, the user asked for storage to be replaced outright: "I
 * want it to put them for sell order and reset the sell order." This reuses
 * {@link ScubaSellEngine}'s already-proven sell/re-price/collect logic verbatim (same confirmed
 * search term, same NBT-based item lookup, same chat-confirmed proceeds), just wrapped in a phase
 * that blocks until the single book is fully sold before looping back to a fresh craft cycle - see
 * {@link #runSellPhase()}.</p>
 *
 * <p><b>Item identification is NBT-based, not display-name-based.</b> Every unapplied SkyBlock
 * enchant book shares the same generic display name, "Enchanted Book" - the real, level-specific
 * data lives in the item's own NBT as a flat {@code enchantments} compound, e.g.
 * {@code {enchantments:{scuba:1},id:"ENCHANTED_BOOK",...}} - confirmed directly from a real
 * in-game inventory dump. Every count/find in this class reads that field, never a name.
 *
 * <p><b>Nothing here is trusted from click success alone.</b> Every craft click, every shift-click
 * into the anvil, and every full merge cycle is followed by a real NBT-based count check
 * confirming the expected quantity change actually happened, and the script stops immediately the
 * instant one doesn't match - dumping the real inventory contents so any further mismatch is
 * diagnosed from fact, not another guess.
 */
public final class ScubaCraftScript {
    /** Confirmed directly from a real crafted book's own NBT ({@code enchantments:{scuba:1}}) - not a guess. */
    private static final String ENCHANT_ID = "scuba";
    private static final int FIRST_LEVEL = 1;
    private static final int FINAL_LEVEL = 5;
    private static final int INITIAL_QUANTITY = 16;

    // -- /recipe screen: crafting only. It closes itself once crafting is done. ------------------
    private static final String RECIPE_COMMAND = "/recipe scuba";
    private static final String RECIPE_OPTION_SLOT = "B2";
    private static final String RECIPE_CRAFT_SLOT = "F4";

    // -- anvil merge screen, opened separately via /av (confirmed real command - the recording
    // just didn't happen to capture it, since it was already open when that recording started).
    // Every click inside it below (E3/E2/QUICK_MOVE) is exactly what was recorded.
    private static final String ANVIL_COMMAND = "/av";
    /** "Click to combine" - fixed, identical across all 15 real recorded merge cycles. */
    private static final String ANVIL_COMBINE_SLOT = "E3";
    /** "Take result" - result lands on the cursor, not a container slot. Fixed, same 15/15 cycles. */
    private static final String ANVIL_TAKE_RESULT_SLOT = "E2";

    // -- sell phase: lists the finished level-5 book as a Bazaar sell offer instead of storing it,
    // re-pricing on any undercut until fully sold, then looping back to a fresh craft cycle. The
    // selling itself is SellOrderLoop, configured by ScubaSellEngine (same item, same settings) -
    // see the class doc for why storage was replaced.
    private static final long SELL_PRICE_CHECK_TICK_MS = 1_000;

    /** Set by {@link #runSellPhase()}'s sold-out callback so the phase knows to return. */
    private static volatile boolean sellPhaseComplete = false;

    /**
     * Scan floor for the anvil screen specifically. It has its own top area (a combine icon plus
     * two decorative "no entry" input indicators, confirmed from a real screenshot) that occupies
     * the same slot range a naive 0-start scan would cover - a book shift-clicked into one of
     * those input slots still reads as a real level-N match there, so a follow-up scan could
     * re-select the SAME book that was just placed instead of a fresh one, then shift-click
     * something already correctly placed (confirmed live: this caused the second book's move to
     * fail). Neither real anvil recording ever touched anything below slot 54 (row 7) when picking
     * up or placing a book - every single pickup/placement started at A7 or later.
     */
    private static final int ANVIL_SCAN_START = 54;
    private static final int INVENTORY_SCAN_END = 200;

    private static final long CRAFT_CLICK_SETTLE_MS = 600;
    /** The anvil interaction is real-world confirmed to lag heavily - every anvil-side click gets a much longer settle than crafting/storage so a slow server-side update never gets read as a click that didn't register. */
    private static final long ANVIL_CLICK_SETTLE_MS = 1500;
    private static final long ANVIL_COMBINE_SETTLE_MS = 2500;
    private static final long PHASE_TRANSITION_SETTLE_MS = 1500;

    private static volatile boolean active = false;
    private static volatile boolean cancelRequested = false;
    private static volatile Thread thread;

    private ScubaCraftScript() {
    }

    public static boolean isActive() {
        return active;
    }

    public static synchronized boolean start() {
        if (active) {
            ClientUtils.sendMessage("§cThe Scuba craft script is already running - run §f/bfm craft stop§c first.");
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
        if (ScubaSellEngine.isActive()) {
            ClientUtils.sendMessage("§cThe Scuba sell engine is currently active - stop it with §f/bfm sell stop§c first.");
            return false;
        }
        if (dev.bazaarmacro.books.BookFlipperEngine.isActive()) {
            ClientUtils.sendMessage("§cBook Flipper is currently active - stop it with §f/bfm books stop§c first.");
            return false;
        }
        if (LegionCraftScript.isActive()) {
            ClientUtils.sendMessage("§cThe Legion craft script is currently active - stop it with §f/bfm legion stop§c first.");
            return false;
        }

        active = true;
        cancelRequested = false;
        thread = new Thread(ScubaCraftScript::run, "bazaarmacro-scuba-craft");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    public static synchronized void stop() {
        if (!active) {
            ClientUtils.sendMessage("§7The Scuba craft script isn't running.");
            return;
        }
        cancelRequested = true;
        ClientUtils.sendMessage("§7Stopping the Scuba craft script...");
    }

    /** Outcome of one attempt at crafting a fresh batch of {@link #INITIAL_QUANTITY} level-1 books. */
    private enum CraftResult {
        /** All {@link #INITIAL_QUANTITY} crafted successfully. */
        SUCCESS,
        /** The very first craft click of a fresh batch produced nothing - the natural, expected
         *  end condition once raw materials run out, not a failure. */
        OUT_OF_MATERIALS,
        /** Something else went wrong - a click failed to execute, or a LATER click in the batch
         *  (2nd-16th) produced nothing, which isn't the clean "ran out" story and gets treated as
         *  a real problem worth stopping over and investigating rather than silently accepted. */
        ERROR
    }

    private static void run() {
        try {
            int cycle = 0;
            while (!cancelRequested) {
                cycle++;
                ClientUtils.sendMessage("§dScuba craft script - cycle " + cycle + ": crafting " + INITIAL_QUANTITY
                        + "x level " + FIRST_LEVEL + ", merging up to level " + FINAL_LEVEL + ", then selling.");

                CraftResult craftResult = craftInitialBooks();
                if (craftResult == CraftResult.OUT_OF_MATERIALS) {
                    closeScreenQuietly();
                    ClientUtils.sendMessage("§aScuba craft script finished - out of raw materials after "
                            + (cycle - 1) + " completed cycle(s).");
                    return;
                }
                if (craftResult == CraftResult.ERROR) {
                    closeScreenQuietly();
                    return;
                }
                if (cancelRequested) {
                    closeScreenQuietly();
                    return;
                }

                MacroWorkerThread.sleep(PHASE_TRANSITION_SETTLE_MS);

                if (!openAnvil()) return;
                if (cancelRequested) {
                    closeScreenQuietly();
                    return;
                }

                if (!mergeUpToFinalLevel()) {
                    closeScreenQuietly();
                    return;
                }
                closeScreenQuietly();
                if (cancelRequested) return;

                if (!runSellPhase()) return;

                ClientUtils.sendMessage("§aCycle " + cycle + " finished - level " + FINAL_LEVEL + " book sold. "
                        + "Starting the next cycle...");
            }
        } catch (Exception e) {
            ClientUtils.sendMessage("§cScuba craft script stopped due to an error: " + e.getMessage());
            dev.bazaarmacro.util.ErrorReporter.report("ScubaCraftScript", "Main loop threw", e);
        } finally {
            active = false;
        }
    }

    /**
     * {@code /recipe scuba} -> click {@code RECIPE_OPTION_SLOT} -> click {@code RECIPE_CRAFT_SLOT}
     * {@link #INITIAL_QUANTITY} times, verifying the real held count (by NBT enchant level) after
     * every single click. This screen closes itself once crafting is done - confirmed live, it's
     * not something this script closes - so the merge phase opens {@code /av} fresh afterward.
     */
    private static CraftResult craftInitialBooks() {
        MacroDefinition open = new MacroDefinition("ScubaRecipeOpen");
        open.steps.add(MacroStep.command(RECIPE_COMMAND));
        open.steps.add(MacroStep.waitForScreen("", 10_000)); // title unverified - not load-bearing, matches empty-title pattern used elsewhere
        open.steps.add(MacroStep.waitMs(500));
        open.steps.add(MacroStep.clickSlot(RECIPE_OPTION_SLOT, 0, "PICKUP"));
        open.steps.add(MacroStep.waitMs(700));
        if (!MacroExecutor.runBlocking(open, new ExecutionContext(), () -> cancelRequested)) {
            ClientUtils.sendMessage("§cFailed to open the Scuba recipe screen - stopping.");
            return CraftResult.ERROR;
        }

        long expected = ClientUtils.countEnchantBooksAtLevel(ENCHANT_ID, FIRST_LEVEL);
        for (int i = 1; i <= INITIAL_QUANTITY; i++) {
            if (cancelRequested) return CraftResult.ERROR;

            if (!clickFixedSlot(RECIPE_CRAFT_SLOT, "PICKUP", CRAFT_CLICK_SETTLE_MS)) {
                ClientUtils.sendMessage("§cCraft click " + i + "/" + INITIAL_QUANTITY + " failed to execute - stopping.");
                return CraftResult.ERROR;
            }

            expected++;
            long actual = ClientUtils.countEnchantBooksAtLevel(ENCHANT_ID, FIRST_LEVEL);
            if (actual != expected) {
                if (i == 1) {
                    ClientUtils.sendMessage("§7Craft click 1/" + INITIAL_QUANTITY + " produced nothing - out of raw "
                            + "materials for another batch.");
                    return CraftResult.OUT_OF_MATERIALS;
                }
                ClientUtils.sendMessage("§cCraft click " + i + "/" + INITIAL_QUANTITY + " didn't register as expected - "
                        + "wanted " + expected + "x level " + FIRST_LEVEL + " but found " + actual + "x. Stopping here - "
                        + "nothing lost. Dumping your real inventory below:");
                ClientUtils.debugDumpInventory();
                return CraftResult.ERROR;
            }
            ClientUtils.sendMessage("§7Crafted level " + FIRST_LEVEL + " book (" + i + "/" + INITIAL_QUANTITY + ")");
        }

        return CraftResult.SUCCESS;
    }

    /** {@code /av} - opens the anvil merge screen fresh, ready for {@link #performSingleMerge} to run 15 times against it without reopening anything in between. */
    private static boolean openAnvil() {
        MacroDefinition open = new MacroDefinition("ScubaAnvilOpen");
        open.steps.add(MacroStep.command(ANVIL_COMMAND));
        open.steps.add(MacroStep.waitForScreen("", 10_000)); // title unverified - not load-bearing, matches empty-title pattern used elsewhere
        open.steps.add(MacroStep.waitMs(ANVIL_CLICK_SETTLE_MS));
        if (!MacroExecutor.runBlocking(open, new ExecutionContext(), () -> cancelRequested)) {
            ClientUtils.sendMessage("§cFailed to open the anvil (" + ANVIL_COMMAND + ") - stopping.");
            return false;
        }
        return true;
    }

    /** Repeats {@link #performSingleMerge} level by level (1->2, 2->3, ...) until a single level-{@link #FINAL_LEVEL} book remains, all on the still-open anvil screen. */
    private static boolean mergeUpToFinalLevel() {
        long remainingAtLevel = INITIAL_QUANTITY;
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

    /**
     * Confirms a shift-click actually removed a book from the counted 36-slot inventory -
     * {@code expectedCount} is the total held count of {@code level} the caller expects right now.
     * A real failed run showed why checking only "did the source slot go empty" isn't enough: the
     * anvil's input can silently reject a shift-click (e.g. if it's not actually free yet) and
     * Hypixel falls back to relocating the item to some other free slot in the same mirrored
     * inventory - the source slot reads empty either way, but the book never left the inventory at
     * all. Checking the real total count catches that; a relocated book still counts, so a
     * mismatch here is unambiguous. One retry wait is given first, since the anvil is real-world
     * confirmed to lag - a slow update isn't the same as a genuine failure.
     */
    private static boolean confirmHeldCountDropped(int level, long expectedCount, String which, long mergeIndex, long totalMerges) {
        if (ClientUtils.countEnchantBooksAtLevel(ENCHANT_ID, level) == expectedCount) return true;

        MacroWorkerThread.sleep(ANVIL_CLICK_SETTLE_MS);
        if (ClientUtils.countEnchantBooksAtLevel(ENCHANT_ID, level) == expectedCount) return true;

        ClientUtils.sendMessage("§cMerge " + mergeIndex + "/" + totalMerges + ": shift-clicking the " + which
                + " level " + level + " book didn't actually remove it from your inventory (even after a retry "
                + "wait) - it likely bounced to another slot instead of the anvil. Stopping here. Dumping your "
                + "real inventory below:");
        ClientUtils.debugDumpInventory();
        return false;
    }

    /**
     * One full merge cycle, matching the real recorded sequence exactly: shift-click a
     * level-{@code currentLevel} book (found live by NBT, never a fixed slot) into the anvil,
     * confirm it actually left the counted inventory, shift-click a second one the same way, click
     * {@code ANVIL_COMBINE_SLOT}, click {@code ANVIL_TAKE_RESULT_SLOT} to take the merged book
     * onto the cursor, then place it back into the slot the first book vacated. Only ever combines
     * two books of the exact same level, per the user's explicit instruction - {@code
     * currentLevel} is identical for both lookups, so this can never accidentally cross-combine
     * two different levels.
     */
    private static boolean performSingleMerge(int currentLevel, int nextLevel, long mergeIndex, long totalMerges) {
        long beforeCurrent = ClientUtils.countEnchantBooksAtLevel(ENCHANT_ID, currentLevel);
        long beforeNext = ClientUtils.countEnchantBooksAtLevel(ENCHANT_ID, nextLevel);
        if (beforeCurrent < 2) {
            ClientUtils.sendMessage("§cMerge " + mergeIndex + "/" + totalMerges + " (level " + currentLevel + " -> "
                    + nextLevel + ") needs 2x level " + currentLevel + " but only " + beforeCurrent
                    + "x is actually held - stopping. Dumping your real inventory below:");
            ClientUtils.debugDumpInventory();
            return false;
        }

        int slot1 = ClientUtils.findEnchantBookSlotInOpenScreen(ENCHANT_ID, currentLevel, ANVIL_SCAN_START, INVENTORY_SCAN_END);
        if (slot1 < 0) {
            ClientUtils.sendMessage("§cMerge " + mergeIndex + "/" + totalMerges + ": couldn't find a level " + currentLevel
                    + " book anywhere in the open screen - stopping. Dumping your real inventory below:");
            ClientUtils.debugDumpInventory();
            return false;
        }
        if (!clickFixedSlot(String.valueOf(slot1), "QUICK_MOVE", ANVIL_CLICK_SETTLE_MS)) return false;
        if (!confirmHeldCountDropped(currentLevel, beforeCurrent - 1, "first", mergeIndex, totalMerges)) return false;

        int slot2 = ClientUtils.findEnchantBookSlotInOpenScreen(ENCHANT_ID, currentLevel, ANVIL_SCAN_START, INVENTORY_SCAN_END);
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
        // Places the merged book (now on the cursor) back into slot1, which the first shift-click emptied.
        if (!clickFixedSlot(String.valueOf(slot1), "PICKUP", ANVIL_CLICK_SETTLE_MS)) return false;

        long afterCurrent = ClientUtils.countEnchantBooksAtLevel(ENCHANT_ID, currentLevel);
        long afterNext = ClientUtils.countEnchantBooksAtLevel(ENCHANT_ID, nextLevel);
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
     * Blocks until the single level-{@link #FINAL_LEVEL} book this cycle produced is fully sold,
     * re-pricing on every undercut, then returns so {@link #run()} can start a fresh craft cycle.
     * The selling itself is {@link SellOrderLoop} - the same code {@link ScubaSellEngine} runs, on
     * the same item, so there is no second copy to keep in sync.
     *
     * @return true once sold (or cancelled cleanly), false if the loop can't proceed.
     */
    private static boolean runSellPhase() {
        sellPhaseComplete = false;
        SellOrderLoop loop = new SellOrderLoop(ScubaSellEngine.sellConfig(),
                () -> cancelRequested, () -> sellPhaseComplete = true);

        while (!cancelRequested) {
            if (!loop.hasActiveOrder() && ClientUtils.countEnchantBooksAtLevel(ENCHANT_ID, FINAL_LEVEL) < 1) {
                return true; // nothing left to sell - this cycle is done
            }
            loop.tick();
            if (sellPhaseComplete) return true;
            MacroWorkerThread.sleep(SELL_PRICE_CHECK_TICK_MS);
        }
        return false;
    }

    /** Clicks a coordinate (a fixed "B2"-style spreadsheet slot, or a bare integer raw slot index - both accepted by {@link dev.bazaarmacro.macro.SlotCoordinate#parse}) via the normal macro click pipeline. */
    private static boolean clickFixedSlot(String coordinate, String clickType, long settleMs) {
        MacroDefinition macro = new MacroDefinition("ScubaFixedClick");
        macro.steps.add(MacroStep.clickSlot(coordinate, 0, clickType));
        macro.steps.add(MacroStep.waitMs(settleMs));
        return MacroExecutor.runBlocking(macro, new ExecutionContext(), () -> cancelRequested);
    }

    /** Best-effort cleanup - closes whatever screen is open without failing the script over it. */
    private static void closeScreenQuietly() {
        MacroDefinition close = new MacroDefinition("ScubaCloseQuietly");
        close.steps.add(MacroStep.closeScreen());
        MacroExecutor.runBlocking(close, new ExecutionContext(), () -> false);
    }
}

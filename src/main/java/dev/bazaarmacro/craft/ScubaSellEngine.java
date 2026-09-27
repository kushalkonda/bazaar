package dev.bazaarmacro.craft;

import dev.bazaarmacro.flipper.FlipperEngine;
import dev.bazaarmacro.flipper.SellOrderLoop;
import dev.bazaarmacro.macro.MacroRecorder;
import dev.bazaarmacro.macro.MacroWorkerThread;
import dev.bazaarmacro.util.ClientUtils;
import dev.bazaarmacro.util.ErrorReporter;

/**
 * Standalone daemon that keeps every level-5 Scuba book currently held listed as a Bazaar Sell
 * Offer at the top of the book, re-pricing on any undercut, until manually stopped - independent
 * of {@link ScubaCraftScript}, so it works whether the books came from that script or were already
 * sitting in inventory.
 *
 * <p>All of the actual selling lives in {@link SellOrderLoop}; this class is just the daemon shell
 * around it (start/stop guards, a thread, a poll loop). When one offer sells out it simply clears
 * the loop, so any further books still held get listed on the next poll - that "keep going forever"
 * behaviour is the only thing separating this from the sell phase inside {@link ScubaCraftScript}
 * and {@link LegionCraftScript}, which each move on to their next phase instead.
 *
 * <p>Stopping this only stops it watching/re-pricing - it deliberately does NOT cancel whatever's
 * currently listed, so the offer keeps selling on its own afterwards.
 */
public final class ScubaSellEngine {
    /** Confirmed directly from a real crafted book's own NBT ({@code enchantments:{scuba:5}}). */
    private static final String ENCHANT_ID = "scuba";
    private static final int LEVEL = 5;
    /** Confirmed real Bazaar product tag (see {@code BookSniperScanner}'s class doc). */
    private static final String ITEM_TAG = "ENCHANTMENT_SCUBA_5";
    /** What Hypixel actually calls this item in Bazaar chat lines - read off a real
     *  "[Bazaar] Sell Offer Setup! 6x Scuba V for..." message, so it's trusted for claim matching. */
    private static final String DISPLAY_NAME = "Scuba V";

    private static final long PRICE_CHECK_TICK_MS = 1_000;
    private static final long MIN_FILL_CHECK_INTERVAL_MS = 5_000;
    private static final long MAX_FILL_CHECK_INTERVAL_MS = 10_000;
    private static final double PRICE_MATCH_TOLERANCE_PERCENT = 5.0;

    private static volatile boolean active = false;
    private static volatile boolean cancelRequested = false;
    private static volatile SellOrderLoop sellLoop;

    private ScubaSellEngine() {
    }

    public static boolean isActive() {
        return active;
    }

    /** Shared with {@link ScubaCraftScript}, which sells this exact item at the end of every craft cycle. */
    static SellOrderLoop.Config sellConfig() {
        return new SellOrderLoop.Config(ITEM_TAG, ENCHANT_ID, LEVEL, DISPLAY_NAME, true,
                "ScubaSell", MIN_FILL_CHECK_INTERVAL_MS, MAX_FILL_CHECK_INTERVAL_MS, PRICE_MATCH_TOLERANCE_PERCENT);
    }

    public static synchronized boolean start() {
        if (active) {
            ClientUtils.sendMessage("§cThe Scuba sell engine is already running - run §f/bfm sell stop§c first.");
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
        if (dev.bazaarmacro.books.BookFlipperEngine.isActive()) {
            ClientUtils.sendMessage("§cBook Flipper is currently active - stop it with §f/bfm books stop§c first.");
            return false;
        }
        if (LegionCraftScript.isActive()) {
            ClientUtils.sendMessage("§cThe Legion craft script is currently active - stop it with §f/bfm legion stop§c first.");
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
        // On sell-out, just clear tracking - the next tick re-lists whatever else is still held.
        sellLoop = new SellOrderLoop(sellConfig(), () -> cancelRequested, () -> sellLoop.reset());

        Thread thread = new Thread(ScubaSellEngine::run, "bazaarmacro-scuba-sell");
        thread.setDaemon(true);
        thread.start();
        ClientUtils.sendMessage("§dScuba sell engine started - keeping every level " + LEVEL + " book listed at the "
                + "top of the Bazaar sell offers until stopped. Stopping later won't cancel a live listing.");
        return true;
    }

    public static synchronized void stop() {
        if (!active) {
            ClientUtils.sendMessage("§7The Scuba sell engine isn't running.");
            return;
        }
        cancelRequested = true;
        ClientUtils.sendMessage("§7Stopping the Scuba sell engine - any currently listed sell offer stays live.");
    }

    private static void run() {
        try {
            while (!cancelRequested) {
                sellLoop.tick();
                MacroWorkerThread.sleep(PRICE_CHECK_TICK_MS);
            }
        } catch (Exception e) {
            ClientUtils.sendMessage("§cScuba sell engine stopped due to an error: " + e.getMessage());
            ErrorReporter.report("ScubaSellEngine", "Main loop threw", e);
        } finally {
            active = false;
        }
    }
}

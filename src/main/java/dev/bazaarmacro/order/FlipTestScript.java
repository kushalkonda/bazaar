package dev.bazaarmacro.order;

import dev.bazaarmacro.flipper.FlipperEngine;
import dev.bazaarmacro.macro.MacroRecorder;
import dev.bazaarmacro.macro.MacroWorkerThread;
import dev.bazaarmacro.util.ClientUtils;
import dev.bazaarmacro.util.ErrorReporter;

import java.util.List;

/**
 * {@code /bfm test start [qty]} - the reference exercise for {@link BuyOrderEngine} and
 * {@link SellOrderEngine}: buy N Summoning Eyes with a real buy order, list them straight back as
 * a sell offer, and repeat once they sell.
 *
 * <p>Deliberately the simplest thing that drives both engines end to end. The craft scripts this
 * replaced dragged in anvil merging, enchant-book NBT identification and multi-candidate search
 * resolution - all real complexity, none of it the engines' own, and all of it in the way when the
 * question is "does a buy order get placed, tracked, claimed and re-listed correctly."
 *
 * <p>Summoning Eye was picked for being a plain item rather than an enchant book, so it needs no
 * NBT lookup and its search text is simply its display name; and for being liquid enough to
 * actually fill while you watch (~105k bought and ~72k sold per week on a 30-deep book, per a live
 * API check), with a real spread to flip across.
 *
 * <p>It trades real coins - roughly 1.5M per eye at the time of writing - so the default batch is
 * one, and the quantity is an explicit argument rather than something to leave at a large default.
 */
public final class FlipTestScript {
    /** Confirmed present on Hypixel's live Bazaar API, along with the volume figures in the class doc. */
    private static final String ITEM_TAG = "SUMMONING_EYE";
    /** Plain items search by, and are found in the inventory by, their display name. */
    private static final String DISPLAY_NAME = "Summoning Eye";

    private static final long PRICE_CHECK_TICK_MS = 2_000;
    private static final long MIN_FILL_CHECK_INTERVAL_MS = 5_000;
    private static final long MAX_FILL_CHECK_INTERVAL_MS = 10_000;
    private static final double PRICE_MATCH_TOLERANCE_PERCENT = 5.0;

    private enum Phase {BUY, SELL}

    private static volatile boolean active = false;
    private static volatile boolean cancelRequested = false;
    private static volatile Phase phase = Phase.BUY;
    private static volatile long batchQty = 1;
    private static volatile long obtained;

    private static volatile BuyOrderEngine buyEngine;
    private static volatile SellOrderEngine sellEngine;

    private FlipTestScript() {
    }

    public static boolean isActive() {
        return active;
    }

    public static synchronized boolean start(long qty) {
        if (active) {
            ClientUtils.sendMessage("§cThe flip test is already running - run §f/bfm test stop§c first.");
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
        batchQty = Math.max(1, qty);
        beginBuyPhase();

        Thread thread = new Thread(FlipTestScript::run, "bazaarmacro-flip-test");
        thread.setDaemon(true);
        thread.start();
        ClientUtils.sendMessage("§dFlip test started: buying " + batchQty + "x " + DISPLAY_NAME
                + ", listing them back, then repeating. Run §f/bfm test stop§d to end it.");
        return true;
    }

    public static synchronized void stop() {
        if (!active) {
            ClientUtils.sendMessage("§7The flip test isn't running.");
            return;
        }
        cancelRequested = true;
        ClientUtils.sendMessage("§7Stopping the flip test - any currently open order stays live.");
    }

    private static void run() {
        try {
            while (!cancelRequested) {
                try {
                    switch (phase) {
                        case BUY -> tickBuy();
                        case SELL -> sellEngine.tick();
                    }
                } catch (Exception e) {
                    ClientUtils.sendMessage("§cFlip test tick failed: " + e.getMessage());
                    ErrorReporter.report("FlipTestScript", "Tick threw in phase " + phase, e);
                }
                if (!cancelRequested) MacroWorkerThread.sleep(PRICE_CHECK_TICK_MS);
            }
        } finally {
            active = false;
        }
    }

    private static void beginBuyPhase() {
        phase = Phase.BUY;
        obtained = 0;
        sellEngine = null;
        buyEngine = new BuyOrderEngine("FlipTest", () -> cancelRequested,
                MIN_FILL_CHECK_INTERVAL_MS, MAX_FILL_CHECK_INTERVAL_MS, PRICE_MATCH_TOLERANCE_PERCENT);
        buyEngine.want(new BuyOrderEngine.Request(ITEM_TAG, List.of(DISPLAY_NAME), List.of(DISPLAY_NAME), batchQty),
                claimed -> obtained += claimed);
    }

    private static void tickBuy() {
        buyEngine.tick();
        if (obtained < batchQty) return;

        ClientUtils.sendMessage("§aBought " + obtained + "x " + DISPLAY_NAME + " - listing them for sale.");
        phase = Phase.SELL;
        sellEngine = new SellOrderEngine("FlipTest", () -> cancelRequested,
                MIN_FILL_CHECK_INTERVAL_MS, MAX_FILL_CHECK_INTERVAL_MS, PRICE_MATCH_TOLERANCE_PERCENT);
        // Note: the sell flow lists the whole held stack, so any Summoning Eyes already in the
        // inventory before this started go out with the batch.
        sellEngine.want(SellOrderEngine.Listing.ofItem(ITEM_TAG, DISPLAY_NAME), FlipTestScript::onSold);
    }

    private static void onSold() {
        ClientUtils.sendMessage("§aFlip test cycle complete - starting the next one.");
        beginBuyPhase();
    }
}

package dev.bazaarmacro.flipper;

import dev.bazaarmacro.bazaar.BazaarPriceClient;
import dev.bazaarmacro.bazaar.HypixelBazaarClient;
import dev.bazaarmacro.macro.ExecutionContext;
import dev.bazaarmacro.macro.MacroDefinition;
import dev.bazaarmacro.macro.MacroExecutor;
import dev.bazaarmacro.macro.MacroExpression;
import dev.bazaarmacro.macro.MacroRecorder;
import dev.bazaarmacro.macro.MacroStep;
import dev.bazaarmacro.macro.MacroWorkerThread;
import dev.bazaarmacro.macro.SlotCoordinate;
import dev.bazaarmacro.util.ClientUtils;
import dev.bazaarmacro.util.ErrorReporter;

import java.util.HashMap;
import java.util.Map;

/**
 * The single-item autonomous flip loop: keeps a buy order and a sell offer working at the same
 * time - collecting fills into a shared pool of sellable stock, listing that pool once it clears
 * a threshold, and re-pricing either side the moment it's outbid/undercut - rather than a strict
 * buy-then-sell sequence. Runs entirely on its own background thread (never the render thread;
 * game-state touches still go through {@code client.execute} inside {@link MacroExecutor}).
 * Mutually exclusive with {@link MacroExecutor#run} and {@link MacroRecorder} - all three drive
 * the same click/command pipeline.
 *
 * <p>The Buy Order click flow is user-verified: {@code /bz} search -> click the matching search
 * result -> click {@code buyOrderButton} -> click {@code amountField} -> sign pops up -> type
 * quantity -> Done -> click {@code orderPricePreset} -> click {@code orderConfirm} to place.
 * {@code orderPricePreset} ("D2") is a price preset confirmed to apply a fixed +0.1 offset
 * automatically, which happens to match this engine's own {@code priceIncrement} default (used
 * only to size the quantity up front - it does not control the real placed price). That specific
 * click is dispatched via {@link ClientUtils#performRealMouseClick} rather than the usual
 * slot-click shortcut - a real, reproduced failure showed the ordinary click having zero effect
 * on that one screen (confirmed via diagnostic dumps: byte-identical before/after, regardless of
 * how long it waited first) despite working everywhere else, and routing through the actual
 * mouse-click pipeline fixed it. The Sell Offer flow is genuinely different, not just a different
 * slot: going through {@code /bz} search the same way leads to a manual price-entry prompt with
 * no preset at all. The correct path (user-verified) is to click the item directly in the
 * player's own inventory instead - see {@link #placeSellOrder} - which reaches the same D2
 * preset for the full held quantity, no amount-entry step at all, and works fine with the
 * ordinary slot-click shortcut. Both flows are now confirmed end-to-end: a real session produced
 * real profit.
 *
 * <p>The active-orders (F6) menu's layout is confirmed via a real {@code /bfm debug orders}
 * dump: sell offers and buy orders sit in separate rows (sell at B2, buy at B3) - see
 * {@link FlipperCoordinates}. That's what makes running both sides concurrently safe: claiming
 * or canceling a buy order always targets {@code buyClaimSlot}, a sell offer always
 * {@code sellClaimSlot}, so the two never interfere with each other's slot in the manage-orders
 * menu.
 *
 * <p>Whether an order is actually <i>done</i> is decided by a direct signal, not an inferred one:
 * does its slot in the manage-orders (F6) menu still show an item at all. Two earlier approaches
 * were tried and real gameplay broke both: a delta bracketed tightly around each claim click got
 * permanently stuck whenever something else (another mod's own auto-claim, most plausibly)
 * claimed a fill before this engine's own poll saw it - the bracket then measured zero gain
 * forever. An absolute delta from a baseline captured at order-placement time fixed that, but
 * broke under real concurrent operation instead: a buy order spends from the same purse a sell
 * offer's proceeds are measured against, and a sell offer removes from the same inventory count a
 * buy order's fill is measured against, so each side's "baseline" was silently corrupted by the
 * other side's own legitimate activity (confirmed via a real session: the engine measured
 * 9,104/10,427 coins on a sell offer despite Hypixel's own chat log confirming the full 10,427 was
 * paid out - the shortfall exactly tracked concurrent buy-order spending in the same window).
 * Checking the menu slot directly sidesteps both failure modes - it doesn't care what claimed it,
 * or what else touched the shared purse/inventory counters in between. Sell proceeds specifically
 * are credited only from Hypixel's own claim-confirmation chat line (see {@link SellClaimWatcher})
 * - never inferred from any purse delta at all, not even one bracketed tightly around this
 * engine's own claim click, since a purse increase during that window could just as easily be
 * unrelated income (a talisman, a booster, a mob drop) with no way to tell the difference; wrongly
 * crediting that could push the tracked total over the completion threshold and abandon a
 * still-genuinely-open sell offer. Inventory deltas are still taken for buy-side progress
 * messages, bracketed tightly around this engine's own single claim click within one method call,
 * where nothing else can interleave.
 * Being outbid or undercut is detected the same way {@code /bfm flip check} always has (comparing
 * against the live order book) and reacted to by canceling the stale order and re-pricing fresh.
 * A price tie (not just a strictly worse price) triggers the same cancel-and-reprice: Hypixel
 * breaks a tie by order recency, not price, so a same-priced order placed after ours would fill
 * first - "matched" is functionally no better than "behind" for whether ours actually fills soon.
 */
public final class FlipperEngine {
    private static final int MAX_ORDER_SLOTS = 7;
    /**
     * How often the main loop wakes up to check order-book prices via Hypixel's public HTTP API -
     * not a {@code /bz} command or anything else visible to the Minecraft server, so there's no
     * "looks like a bot" concern here the way there is for real in-game actions, and it costs
     * nothing extra in practice: {@link dev.bazaarmacro.bazaar.HypixelBazaarClient} caches the
     * order book for 5s regardless of how often this asks, so ticking faster than that just means
     * reacting within ~1s of the cache actually refreshing instead of waiting out a much longer
     * fixed poll gap for no reason. This is what makes an outbid/undercut get reacted to promptly
     * rather than sitting stale for up to several seconds after the market already moved.
     */
    private static final long PRICE_CHECK_TICK_MS = 1_000;
    /** How often each side's real, in-game claim-slot is opportunistically checked for fills when nothing urgent (an outbid/undercut) is forcing an earlier look - deliberately randomized within a plausible human range rather than fixed, since unlike the price check above, this involves a real {@code /bz} command. */
    private static final long MIN_FILL_CHECK_INTERVAL_MS = 5_000;
    private static final long MAX_FILL_CHECK_INTERVAL_MS = 10_000;
    /** How long with nothing held, no orders open, and no budget before giving up - a wall-clock duration (not a tick count) since {@link #PRICE_CHECK_TICK_MS} ticks much faster than any single action would ever need to happen. */
    private static final long STALL_GRACE_MS = 20_000;
    /** How long to wait for a reconnect attempt to land back in a world before giving up on that attempt and retrying. */
    private static final long RECONNECT_TIMEOUT_MS = 30_000;
    /** Delay between reconnect attempts if one fails outright, so a prolonged real outage doesn't turn into a tight retry loop. */
    private static final long RECONNECT_RETRY_DELAY_MS = 5_000;
    /**
     * How many consecutive empty menu-slot reads (with no corroborating progress) are required
     * before accepting "empty" as "genuinely done" for a buy/sell order. Real gameplay showed a
     * false-empty read persist across at least two consecutive re-opens of the manage-orders menu
     * (a render-lag race, not a one-off blip), which defeated an earlier, lower threshold - a
     * still-open, real order got abandoned anyway. Set with real margin above that observed case
     * rather than the bare minimum that would have "just barely" fixed it.
     */

    private static double priceIncrement() {
        return FlipperSettings.get().priceIncrement;
    }

    private static volatile boolean active = false;
    private static volatile boolean cancelRequested = false;
    private static volatile Thread engineThread;

    private static volatile String itemTag;
    /** What actually gets typed into {@code /bz} - the display name, not itemTag (Hypixel's own search matches display names, not API tags like "ENCHANTED_MYCELIUM"). */
    private static volatile String searchTerm;
    private static volatile String capExpression;
    private static volatile boolean shuttingDown = false;
    private static volatile int activeOrderCount;
    private static volatile boolean warnedClaimFlow = false;
    /** 0 = not currently idle; otherwise the epoch-ms timestamp idling began - see {@link #checkStall}. */
    private static volatile long idleSinceMs = 0L;
    /** 0 = no time limit; otherwise the epoch-ms deadline after which the engine shuts itself down. */
    private static volatile long sessionEndTimeMs = 0L;
    /** When the current session actually started - distinct from sessionEndTimeMs (a deadline, only set if a duration was configured), used to record how long a session ran for in {@link SessionHistoryStorage}. */
    private static volatile long sessionStartTimeMs = 0L;

    // -- Buy track: at most one buy order open at a time --------------------------------
    private static volatile boolean buyActive = false;
    private static volatile double buyPrice;
    private static volatile long buyQty;
    private static volatile long buyClaimedQty;
    private static volatile FlipperManager.TopCheckResult.Status buyCheckStatus;
    /** Real inventory count of {@link #itemTag} right when the current buy order was placed - used only to size progress messages ("collected X/Y"); completion itself comes from the menu-slot check in {@link #collectBuyFills}. */
    private static volatile long buyHeldBaseline;
    /** Consecutive polls in a row where the buy claim slot read as empty with zero confirmed progress - see {@link #collectBuyFills}. */
    private static volatile int buyNotListedStrikes = 0;
    /** Epoch-ms of the next opportunistic real fill-check for the buy side - see {@link #PRICE_CHECK_TICK_MS}'s doc for why this is decoupled from the (much faster) price-check cadence. */
    private static volatile long buyNextFillCheckMs = 0L;

    // -- Sell track: at most one sell offer open at a time, independent of the buy track --
    private static volatile boolean sellActive = false;
    private static volatile double sellPrice;
    private static volatile long sellQty;
    private static volatile long sellCollectedProceeds;
    private static volatile long sellProceedsExpected;
    private static volatile FlipperManager.TopCheckResult.Status sellCheckStatus;
    /** Consecutive polls in a row where the sell claim slot read as empty with zero confirmed progress - see {@link #collectSellProceeds}. */
    private static volatile int sellNotListedStrikes = 0;
    /** Epoch-ms of the next opportunistic real fill-check for the sell side - see {@link #buyNextFillCheckMs}. */
    private static volatile long sellNextFillCheckMs = 0L;

    /** Physically held but not yet listed in a sell order - fed by buy-order claims, drained when a sell order is placed, refunded on a canceled sell offer's unsold remainder. */
    private static volatile long sellableStock;

    private FlipperEngine() {
    }

    public record Snapshot(String itemTag,
                            boolean buyActive, double buyPrice, long buyQty, long buyClaimedQty,
                            FlipperManager.TopCheckResult.Status buyCheckStatus,
                            boolean sellActive, double sellPrice, long sellQty, long sellCollectedProceeds,
                            long sellProceedsExpected, FlipperManager.TopCheckResult.Status sellCheckStatus,
                            long sellableStock, long heldQty, int activeOrderCount, long sessionEndTimeMs) {
    }

    // snapshot() is called from renderNVG() every frame (by both BazaarMacroScreen and the always-on
    // FlipperHud) but currentHeldQty() does a full 36-slot NBT-scanning inventory pass - at 240fps
    // that's ~1000 scans/sec for a number that only meaningfully changes once per poll cycle.
    // Throttling here (not in the scan itself) keeps the engine's own decision logic reading live,
    // uncached data while the UI reads a cheap recent snapshot.
    private static volatile Snapshot cachedSnapshot;
    private static volatile long lastSnapshotMs = 0L;
    private static final long SNAPSHOT_CACHE_MS = 300L;

    public static Snapshot snapshot() {
        long now = System.currentTimeMillis();
        Snapshot cached = cachedSnapshot;
        if (cached != null && now - lastSnapshotMs < SNAPSHOT_CACHE_MS) {
            return cached;
        }
        Snapshot fresh = new Snapshot(itemTag,
                buyActive, buyPrice, buyQty, buyClaimedQty, buyCheckStatus,
                sellActive, sellPrice, sellQty, sellCollectedProceeds, sellProceedsExpected, sellCheckStatus,
                sellableStock, currentHeldQty(), activeOrderCount, sessionEndTimeMs);
        cachedSnapshot = fresh;
        lastSnapshotMs = now;
        return fresh;
    }

    /** Live count of {@link #itemTag} sitting in the inventory right now - the ground truth for "how much have we actually collected", not a running tally that can drift. */
    private static long currentHeldQty() {
        return itemTag == null ? 0 : ClientUtils.countItem(itemTag);
    }

    public static boolean isActive() {
        return active;
    }

    /**
     * @param capText          blank (use full purse), a flat number, or a {@link MacroExpression} like "50%"
     * @param durationMinutes  0 or negative = run until manually stopped; otherwise the engine cancels any
     *                         pending orders and sells everything held once this many minutes have elapsed
     */
    public static synchronized boolean start(String itemInput, String capText, double durationMinutes) {
        if (active) {
            ClientUtils.sendMessage("§cThe flipper is already running - run §f/bfm stop§c first.");
            return false;
        }
        if (MacroWorkerThread.getInstance().isRunning()) {
            ClientUtils.sendMessage("§cA macro is currently running - stop it with §f/bfm stop§c first.");
            return false;
        }
        if (MacroRecorder.isRecording()) {
            ClientUtils.sendMessage("§cCan't start the flipper while recording - run §f/bfm record stop§c first.");
            return false;
        }
        if (dev.bazaarmacro.craft.LegionCraftScript.isActive()) {
            ClientUtils.sendMessage("§cThe Legion craft script is running - stop it with §f/bfm legion stop§c first.");
            return false;
        }

        active = true;
        cancelRequested = false;
        capExpression = capText;
        shuttingDown = false;
        warnedClaimFlow = false;
        idleSinceMs = 0L;
        activeOrderCount = 0;
        buyActive = false;
        buyQty = 0;
        buyClaimedQty = 0;
        buyCheckStatus = null;
        buyNotListedStrikes = 0;
        buyNextFillCheckMs = 0L;
        sellActive = false;
        sellQty = 0;
        sellCollectedProceeds = 0;
        sellProceedsExpected = 0;
        sellCheckStatus = null;
        sellNotListedStrikes = 0;
        sellNextFillCheckMs = 0L;
        sellableStock = 0;
        sessionEndTimeMs = durationMinutes > 0 ? System.currentTimeMillis() + (long) (durationMinutes * 60_000.0) : 0L;
        sessionStartTimeMs = System.currentTimeMillis();
        FlipperProfitTracker.startSession();

        engineThread = new Thread(() -> runLoop(itemInput), "bazaarmacro-flipper-engine");
        engineThread.setDaemon(true);
        engineThread.start();
        return true;
    }

    /**
     * First call winds down gracefully rather than cutting off mid-trade: cancels any open buy
     * order (no more buying) but leaves the sell side running completely normally - watching,
     * repricing on undercuts, listing new stock as it frees up - until everything held is sold and
     * genuinely nothing is left open. Only then does the session actually end. A second call while
     * already winding down forces an immediate stop instead, for when the user really does want to
     * abandon whatever's still open (checkable/fixable manually via {@code /bz}).
     */
    public static synchronized void stop() {
        if (!active) {
            ClientUtils.sendMessage("§7The flipper isn't running.");
            return;
        }
        if (shuttingDown) {
            cancelRequested = true;
            ClientUtils.sendMessage("§cForcing an immediate stop - any order still open is left as-is; check §f/bz§c manually.");
            return;
        }
        shuttingDown = true;
        ClientUtils.sendMessage("§7Winding down: canceling any open buy order and selling out at the real market "
                + "price (not instant-selling) before actually stopping. Run §f/bfm stop§7 again to force an "
                + "immediate stop instead.");
    }

    private static void runLoop(String itemInput) {
        try {
            resolveItem(itemInput);
            ClientUtils.sendMessage("§dFlipper started for §f" + itemTag + "§d (searching §f\"" + searchTerm + "\"§d in-game).");
            sellableStock = currentHeldQty(); // whatever's already held becomes immediately sellable
            ClientUtils.rememberCurrentServerIfConnected();

            while (!cancelRequested) {
                if (!ClientUtils.isConnectedToServer()) {
                    handleDisconnected();
                    continue; // re-check connection state fresh at the top instead of assuming it worked
                }
                ClientUtils.rememberCurrentServerIfConnected();

                if (LobbyKickWatcher.drainKicked()) {
                    handleLobbyKick();
                    continue;
                }

                if (sessionEndTimeMs > 0 && System.currentTimeMillis() >= sessionEndTimeMs && !shuttingDown) {
                    ClientUtils.sendMessage("§dSession duration reached - winding down: canceling any open buy "
                            + "order and selling out at the real market price before stopping.");
                    shuttingDown = true;
                }

                if (shuttingDown) {
                    if (windDownTick()) {
                        ClientUtils.sendMessage("§aWind-down complete - everything sold, all proceeds in the purse.");
                        break;
                    }
                    if (ClientUtils.isInventoryScreenOpen()) {
                        BazaarOrderFlow.closeScreen("FlipperCloseScreen", () -> cancelRequested);
                    }
                    MacroWorkerThread.sleep(PRICE_CHECK_TICK_MS);
                    continue;
                }

                tickBuy();
                if (cancelRequested) break;
                tickSell();
                if (cancelRequested) break;

                // Safety net for the buy/sell screen-reuse optimization above: collectBuyFills()
                // can deliberately leave the F6 menu open for collectSellProceeds() to reuse
                // rather than closing and reopening it a moment later, but an early return on the
                // sell side (e.g. claim-flow not configured) could in principle skip that close.
                // Cheap to double-check here every poll rather than risk a screen left open
                // bleeding into the next tick's item-search commands.
                if (ClientUtils.isInventoryScreenOpen()) {
                    BazaarOrderFlow.closeScreen("FlipperCloseScreen", () -> cancelRequested);
                }

                checkStall();

                MacroWorkerThread.sleep(PRICE_CHECK_TICK_MS);
            }
        } catch (Exception e) {
            ClientUtils.sendMessage("§cFlipper stopped due to an error: " + e.getMessage());
            ErrorReporter.report("FlipperEngine", "Main loop threw for item " + itemTag, e,
                    "buyActive=" + buyActive + " sellActive=" + sellActive + " sellableStock=" + sellableStock);
        } finally {
            if (FlipperProfitTracker.hasSession()) {
                long profit = FlipperProfitTracker.trueProfit();
                long startingPurse = FlipperProfitTracker.startingPurse();
                SessionHistoryStorage.append(new SessionRecord(itemTag, startingPurse,
                        profit, sessionStartTimeMs, System.currentTimeMillis()));
            }
            active = false;
            ClientUtils.sendMessage("§7Flipper stopped.");
            FlipperProfitTracker.endSession();
        }
    }

    /**
     * Fires when the main loop notices it's no longer in a world at all - reconnects to whichever
     * server {@link ClientUtils#rememberCurrentServerIfConnected} last captured and sends
     * {@code /warp is} to get back to the island the flipper needs to be on for any Bazaar screen
     * to work. Blocks the engine thread (not the render thread - this all happens off the main
     * loop's own thread) until either reconnected or {@link #RECONNECT_TIMEOUT_MS} elapses; either
     * way, control returns to the caller, which just re-checks connection state on its next loop
     * iteration rather than assuming this succeeded.
     *
     * <p>{@code /warp is} landing correctly is a best-effort assumption, not independently
     * confirmed - if a reconnect actually lands somewhere {@code /warp is} doesn't work from (the
     * hub instead of directly back in-game, say), Hypixel will just show its own error in chat; no
     * separate handling for that case since there's no verified evidence yet of exactly where a
     * reconnect actually lands.
     */
    private static void handleDisconnected() {
        System.out.println("[BazaarMacro] Lost connection to the server - attempting to reconnect...");
        ClientUtils.reconnectToLastServer();

        long deadline = System.currentTimeMillis() + RECONNECT_TIMEOUT_MS;
        while (!ClientUtils.isConnectedToServer() && System.currentTimeMillis() < deadline && !cancelRequested) {
            MacroWorkerThread.sleep(1_000);
        }
        if (cancelRequested) {
            return;
        }

        if (!ClientUtils.isConnectedToServer()) {
            System.out.println("[BazaarMacro] Reconnect attempt timed out after " + (RECONNECT_TIMEOUT_MS / 1000)
                    + "s - will keep retrying.");
            MacroWorkerThread.sleep(RECONNECT_RETRY_DELAY_MS);
            return;
        }

        // Reconnected - give the world a moment to actually finish loading before sending
        // anything, then head back to the island (Bazaar screens only make sense from there).
        MacroWorkerThread.sleep(3_000);
        ClientUtils.sendCommand("/warp is");
        System.out.println("[BazaarMacro] Reconnected - sent /warp is.");
        MacroWorkerThread.sleep(5_000); // let the warp land before the tick loop resumes touching Bazaar screens
        ClientUtils.sendMessage("§dReconnected after a dropped connection and warped back to the island - resuming.");
    }

    /**
     * Fires when {@link LobbyKickWatcher} catches Hypixel's real "you were put in the SkyBlock
     * lobby" kick - a genuinely different case from {@link #handleDisconnected}: the client never
     * actually left the Minecraft server connection here, it's just sitting in Hypixel's own lobby
     * world, so there's nothing to reconnect - just warp back. Real gameplay showed this follow a
     * real "Sending packets too fast!" rate-limit kick and cascade into every subsequent macro
     * failing (screen timeouts, an unreadable purse) until the flipper gave up entirely, since
     * nothing was watching for this specific, non-disconnect failure mode before.
     */
    private static void handleLobbyKick() {
        ClientUtils.sendMessage("§cGot kicked back to the SkyBlock lobby (a real Hypixel rate-limit kick, not a "
                + "dropped connection) - warping back to the island...");
        MacroWorkerThread.sleep(2_000); // let the lobby world finish loading before sending anything
        ClientUtils.sendCommand("/warp is");
        MacroWorkerThread.sleep(5_000); // let the warp land before the tick loop resumes touching Bazaar screens
        ClientUtils.sendMessage("§dWarped back to the island - resuming. Consider raising the minimum-action-interval "
                + "setting if this keeps happening.");
    }

    // -- Buy track ----------------------------------------------------------------------

    private static void tickBuy() {
        if (!buyActive) {
            maybePlaceBuy();
            return;
        }

        FlipperManager.TopCheckResult result;
        try {
            result = FlipperManager.checkTop(itemTag, FlipperSide.BUY_ORDER, buyPrice);
        } catch (Exception e) {
            ClientUtils.sendMessage("§cBuy order book check failed: " + e.getMessage());
            return;
        }
        buyCheckStatus = result.status();

        // TIED isn't safe to just sit on: Hypixel breaks a price tie by order recency, not just
        // price, so a same-priced order placed after ours fills first - meaning "matched" is
        // functionally the same as "behind" for whether ours actually fills anytime soon. Only a
        // genuinely fresh AHEAD (or a fresh re-price that becomes the sole top) actually queues
        // ahead of a newer competitor. Checked every PRICE_CHECK_TICK_MS (a cheap, real-time-only
        // HTTP read - see that constant's doc) so a real outbid gets reacted to within about a
        // second of the cached order book actually refreshing, not stuck behind a much longer
        // fixed poll gap the way the real, in-game fill-check below still deliberately is.
        if (result.status() == FlipperManager.TopCheckResult.Status.BEHIND
                || result.status() == FlipperManager.TopCheckResult.Status.TIED) {
            String reason = result.status() == FlipperManager.TopCheckResult.Status.TIED
                    ? "Matched (not sole top) on the " + itemTag + " buy order - a same-priced order placed after "
                            + "ours would fill first"
                    : "Outbid on the " + itemTag + " buy order (top is now " + ExecutionContext.formatDisplay(result.topPrice()) + ")";
            ClientUtils.sendMessage("§e" + reason + " - collecting, canceling, and re-pricing.");
            collectBuyFills(); // grab whatever's filled before walking away from the rest
            if (buyActive && claimThenCancel(FlipperSide.BUY_ORDER)) {
                ClientUtils.sendMessage("§7Buy order canceled - re-pricing.");
                buyActive = false;
            }
            buyNextFillCheckMs = System.currentTimeMillis() + BazaarOrderFlow.randomFillCheckIntervalMs(MIN_FILL_CHECK_INTERVAL_MS, MAX_FILL_CHECK_INTERVAL_MS);
            return;
        }

        if (System.currentTimeMillis() < buyNextFillCheckMs) {
            return; // no urgent reason to touch F6 yet - not due for its own opportunistic check
        }
        buyNextFillCheckMs = System.currentTimeMillis() + BazaarOrderFlow.randomFillCheckIntervalMs(MIN_FILL_CHECK_INTERVAL_MS, MAX_FILL_CHECK_INTERVAL_MS);

        collectBuyFills();
    }

    /**
     * Opportunistically claims whatever's filled so far, without touching the order itself.
     * Completion is decided by whether {@code buyClaimSlot} still shows an item in the
     * manage-orders menu at all - see the class doc for why that replaced both an earlier
     * claim-bracketed delta and an absolute-baseline delta, each of which broke under a different
     * real scenario. {@link #buyHeldBaseline}/{@link #buyClaimedQty} are still used, but only to
     * size the "collected X/Yx" progress message - a concurrent sell listing can make that number
     * lag behind reality (it also removes from the same inventory count), which just means a
     * slightly-stale progress line, not an incorrect completion decision.
     *
     * <p>A single empty read isn't trusted on its own if this order has never shown any confirmed
     * progress yet ({@code buyClaimedQty == 0}) - real gameplay showed a just-filled order read as
     * empty on the very first check, indistinguishable from a genuinely-open order caught by a
     * transient screen-render lag right after opening the menu (or, less happily, the claim slot
     * coordinate being wrong for this specific order layout - not yet confirmed either way). Either
     * way, abandoning a real filled order after one blank read is a worse failure than waiting one
     * more poll to confirm it, so this requires {@link #buyNotListedStrikes} consecutive empty
     * reads before accepting "empty" as "done" when there's zero other evidence to go on.
     */
    private static void collectBuyFills() {
        FlipperCoordinates coords = FlipperCoordinates.get();
        if (!coords.isClaimFlowConfigured()) {
            if (!warnedClaimFlow) {
                warnedClaimFlow = true;
                ClientUtils.sendMessage("§cClaim-orders flow isn't set up yet - run §f/bfm record§c once claiming a filled "
                        + "order, then fill in flipper_coordinates.json. Will keep waiting quietly until then.");
            }
            return;
        }

        BazaarOrderFlow.respectRateLimit();
        // Always opens fresh (never reuses) - tickBuy() always runs before tickSell() each poll,
        // so there's nothing to reuse yet on the buy side. Leaves the screen open afterward
        // instead of closing it when sellActive, so the sell-side check that runs moments later
        // this same poll can reuse this same F6 screen instead of sending its own /bz - see
        // openMenuAndCheckSlot's doc.
        MenuSlotState slotState = openMenuAndCheckSlot(coords, coords.buyClaimSlot, false);
        if (slotState == null) {
            return; // couldn't even check this tick (screen-transition hiccup) - try again next poll
        }
        boolean stillListed = slotState.populated();
        if (slotState.price() > 0) {
            // Ground truth from Hypixel's own order display, not an inferred estimate - see the
            // class doc for why the old "fresh top price ± increment" heuristic could be wrong by
            // a full increment forever, permanently masking small real outbids.
            buyPrice = slotState.price();
        }

        if (stillListed) {
            buyNotListedStrikes = 0;
            MacroDefinition claimClick = new MacroDefinition("FlipperClaimClick");
            claimClick.steps.add(MacroStep.clickSlot(coords.buyClaimSlot, 0, "PICKUP"));
            claimClick.steps.add(MacroStep.waitMs(500));
            if (!sellActive) {
                claimClick.steps.add(MacroStep.closeScreen());
            }
            MacroExecutor.runBlocking(claimClick, new ExecutionContext(), () -> cancelRequested);
        } else {
            if (buyClaimedQty == 0 && buyNotListedStrikes == 0) {
                // First-ever ambiguous read on this order: dump the real menu layout to the log
                // before closing, in case this is actually a wrong-slot bug rather than a
                // transient render lag - captures the evidence automatically instead of needing
                // to catch it live in-game.
                ClientUtils.debugDumpOpenScreen();
            }
            if (!sellActive) {
                BazaarOrderFlow.closeScreen("FlipperCloseScreen", () -> cancelRequested);
            }
            buyNotListedStrikes++;
            if (buyClaimedQty == 0 && buyNotListedStrikes < BazaarOrderFlow.NOT_LISTED_STRIKE_LIMIT) {
                return; // ambiguous miss with no corroborating progress yet - recheck next poll
            }
        }

        long totalGained = Math.max(0, currentHeldQty() - buyHeldBaseline);
        long newThisTick = Math.max(0, totalGained - buyClaimedQty);
        if (newThisTick > 0) {
            sellableStock += newThisTick;
            buyClaimedQty = totalGained;
            ClientUtils.sendMessage("§aCollected " + newThisTick + "x " + itemTag + " from the buy order ("
                    + buyClaimedQty + "/" + buyQty + ").");
        }

        if (!stillListed) {
            ClientUtils.sendMessage(buyClaimedQty >= buyQty
                    ? "§aBuy order fully filled."
                    : "§aBuy order no longer listed in the manage-orders menu - treating as fully filled "
                            + "(only observed " + buyClaimedQty + "/" + buyQty + "x via our own claims - the rest "
                            + "was likely already claimed by something else).");
            buyActive = false;
            activeOrderCount = Math.max(0, activeOrderCount - 1);
        } else if (buyClaimedQty >= buyQty) {
            ClientUtils.sendMessage("§aBuy order fully filled.");
            buyActive = false;
            activeOrderCount = Math.max(0, activeOrderCount - 1);
        }
    }

    private static void maybePlaceBuy() {
        if (activeOrderCount >= MAX_ORDER_SLOTS) {
            return; // no room right now - the sell side may free one up
        }

        // Real remaining room for this item, not an assumed-empty-inventory guess - the earlier
        // "36 slots * 64" assumption silently overcounted room the moment the player was carrying
        // anything else (tools, armor, junk - the normal case, not an edge case), which could size
        // a buy order larger than what could actually be claimed into a full inventory.
        long heldNow = currentHeldQty();
        long remainingCapacity = ClientUtils.freeInventoryCapacity(itemTag);
        if (remainingCapacity < 1) {
            if (heldNow < 1) {
                // None of the occupied space is this flip's own stock (it hasn't bought anything
                // yet), so this can't be the normal, self-resolving case of successful buying
                // temporarily filling up while waiting on a sell order - other items already fill
                // the whole inventory and that isn't going to free up on its own. Continuing would
                // just spin here forever every poll, so stop outright with a clear reason instead.
                ClientUtils.sendMessage("§cNo room in your inventory for " + itemTag + " - other items already "
                        + "fill all 36 slots. Free up space and restart the flipper.");
                requestStop();
            }
            return; // otherwise: this flip's own stock filled it up - wait for the sell side to clear some
        }

        double budget;
        try {
            budget = resolveBudget();
        } catch (Exception e) {
            ClientUtils.sendMessage("§cCouldn't resolve purse/cap: " + e.getMessage());
            requestStop();
            return;
        }
        if (budget < 1) {
            return; // nothing to buy with this tick - the sell side keeps working independently
        }

        HypixelBazaarClient.OrderBook book;
        try {
            book = HypixelBazaarClient.getOrderBook(itemTag);
        } catch (Exception e) {
            ClientUtils.sendMessage("§cFailed to fetch the order book: " + e.getMessage());
            return;
        }

        double topBuy = book.topBuyPrice();
        double topSell = book.topSellPrice();
        if (topBuy <= 0) {
            ClientUtils.sendMessage("§cOrder book returned a degenerate top buy price (" + ExecutionContext.formatDisplay(topBuy)
                    + ") for " + itemTag + " - skipping this cycle rather than pricing off it.");
            return;
        }

        // Price off the outlier-resistant top, not the literal one: a single thin order far from
        // where the book's real weight sits would mean chasing a price nobody's actually competing at.
        double price = book.stableTopBuyPrice() + priceIncrement();
        if (topSell > 0 && price >= topSell) {
            ClientUtils.sendMessage("§cComputed buy price (" + ExecutionContext.formatDisplay(price)
                    + ") would meet or cross the top sell offer (" + ExecutionContext.formatDisplay(topSell)
                    + ") - this item's market is currently crossed (instant-buying is cheaper than any "
                    + "sensible limit order), so it isn't flippable via buy orders right now. Wrapping up "
                    + "rather than retrying forever - try a different item, or instant-buy that stock manually.");
            shuttingDown = true;
            return;
        }

        long qty = Math.min((long) Math.floor(budget / price), remainingCapacity);
        if (qty < 1) {
            return; // budget too small this tick - try again once the purse grows (e.g. from a sell)
        }

        FlipperCoordinates coords = FlipperCoordinates.get();
        if (!coords.isBuyOrderFlowConfigured()) {
            ClientUtils.sendMessage("§cBuy Order coordinates aren't set up yet - run §f/bfm record§c once "
                    + "placing a real buy order, then fill in flipper_coordinates.json.");
            requestStop();
            return;
        }

        BazaarOrderFlow.respectRateLimit();
        ClientUtils.sendMessage("§dPlacing BUY order: §f" + qty + "x " + itemTag
                + " §d(targeting ~§f" + ExecutionContext.formatDisplay(price) + "§d each via the Top Order preset).");

        long placedQty = placeOrder(coords.buyOrderButton, coords.orderPricePreset, coords, qty);
        if (placedQty > 0) {
            if (placedQty != qty) {
                ClientUtils.sendMessage("§eHypixel's real order-volume cap was lower than expected - placed "
                        + placedQty + "x instead of " + qty + "x.");
            }
            buyActive = true;
            buyQty = placedQty;
            buyClaimedQty = 0;
            buyHeldBaseline = currentHeldQty();
            buyCheckStatus = null;
            buyNotListedStrikes = 0;
            activeOrderCount++;
            buyPrice = refreshPlacedPrice(FlipperSide.BUY_ORDER, price);
        } else {
            ClientUtils.sendMessage("§cFailed to place the buy order - will retry.");
            ErrorReporter.report("FlipperEngine", "placeOrder returned 0 for a " + itemTag + " buy order (requested qty "
                    + qty + " at ~" + ExecutionContext.formatDisplay(price) + ")");
        }
    }

    // -- Sell track -----------------------------------------------------------------------

    private static void tickSell() {
        if (!sellActive) {
            maybePlaceSell();
            return;
        }

        FlipperManager.TopCheckResult result;
        try {
            result = FlipperManager.checkTop(itemTag, FlipperSide.SELL_OFFER, sellPrice);
        } catch (Exception e) {
            ClientUtils.sendMessage("§cSell order book check failed: " + e.getMessage());
            return;
        }
        sellCheckStatus = result.status();

        // See tickBuy() for why TIED gets treated the same as BEHIND, and why this checkTop() runs
        // on the fast PRICE_CHECK_TICK_MS cadence while the real, in-game fill-check below stays
        // on the slower, randomized one.
        if (result.status() == FlipperManager.TopCheckResult.Status.BEHIND
                || result.status() == FlipperManager.TopCheckResult.Status.TIED) {
            String reason = result.status() == FlipperManager.TopCheckResult.Status.TIED
                    ? "Matched (not sole top) on the " + itemTag + " sell offer - a same-priced offer listed after "
                            + "ours would sell first"
                    : "Undercut on the " + itemTag + " sell offer (top is now " + ExecutionContext.formatDisplay(result.topPrice()) + ")";
            ClientUtils.sendMessage("§e" + reason + " - collecting, canceling, and re-pricing.");
            collectSellProceeds(); // grab whatever's sold before pulling the rest back
            if (sellActive) {
                long beforeHeld = currentHeldQty();
                if (claimThenCancel(FlipperSide.SELL_OFFER)) {
                    long returned = Math.max(0, currentHeldQty() - beforeHeld);
                    sellableStock += returned;
                    ClientUtils.sendMessage("§7Sell offer canceled - " + returned + "x returned to inventory - re-pricing.");
                    sellActive = false;
                }
            }
            sellNextFillCheckMs = System.currentTimeMillis() + BazaarOrderFlow.randomFillCheckIntervalMs(MIN_FILL_CHECK_INTERVAL_MS, MAX_FILL_CHECK_INTERVAL_MS);
            return;
        }

        if (System.currentTimeMillis() < sellNextFillCheckMs) {
            return; // no urgent reason to touch F6 yet - not due for its own opportunistic check
        }
        sellNextFillCheckMs = System.currentTimeMillis() + BazaarOrderFlow.randomFillCheckIntervalMs(MIN_FILL_CHECK_INTERVAL_MS, MAX_FILL_CHECK_INTERVAL_MS);

        collectSellProceeds();
    }

    /**
     * Opportunistically claims proceeds already earned, without touching the order itself.
     * Completion is decided by whether {@code sellClaimSlot} still shows an item in the
     * manage-orders menu at all - see the class doc for why that replaced a purse-baseline delta,
     * which broke under real concurrent operation (a buy order spending from the same purse this
     * method was reading as pure sell income).
     *
     * <p>{@link #sellCollectedProceeds} is accumulated <em>only</em> from
     * {@link SellClaimWatcher}'s exact reading of Hypixel's own claim-confirmation chat line -
     * never inferred from a purse delta, even one bracketed tightly around this method's own
     * claim click. Two real, separate failures ruled that out: first, a real session showed a
     * claim Hypixel itself confirmed paid 11,543 coins get measured by a bracketed delta as only
     * 9,082, a shortfall that lined up almost exactly with a separate buy-order attempt (rejected
     * for insufficient funds moments earlier) whose escrow refund landed inside this method's own
     * before/after purse reads - proof that "nothing else runs between these two reads" doesn't
     * hold once an *asynchronous* server-side event is in play, even though the engine's own ticks
     * are strictly sequential. Second, and more fundamentally: a purse increase during this window
     * could just as easily be unrelated income entirely - a talisman's passive coin generation, a
     * Coins Allowance booster, a mob drop, anything - with no way to distinguish it from a real
     * Bazaar payout. Crediting that to {@link #sellCollectedProceeds} wouldn't just mis-track
     * profit; if it happened to push the total over the completion threshold, this would falsely
     * declare a still-genuinely-open sell offer "fully filled" and abandon tracking it entirely -
     * the exact same orphaned-order failure mode fixed elsewhere in this class, just reached via a
     * different mechanism. If Hypixel's own confirmation doesn't arrive, this simply doesn't credit
     * anything that tick rather than guessing - the completion decision below never depends on it
     * anyway, since that's driven purely by whether the order still shows up in the menu at all.
     *
     * <p>A single empty menu-slot read isn't trusted on its own if this offer has never shown any
     * confirmed proceeds yet ({@code sellCollectedProceeds == 0}) - see {@link #collectBuyFills}
     * for why: the same ambiguity (transient render lag vs. a genuinely finished order vs. a
     * possibly-wrong slot coordinate) applies here too, and abandoning a real paid-out offer after
     * one blank read is worse than waiting to confirm it.
     */
    private static void collectSellProceeds() {
        // Checked first and unconditionally, before anything else below: Hypixel's own claim
        // confirmation can arrive independently of - and more reliably than - this engine's own
        // claim-click timing (e.g. a message queued moments after a previous tick's click, only
        // now delivered). See the method doc for why this is the *only* source ever trusted for
        // sell proceeds - no purse-delta fallback, direct confirmation or nothing.
        long confirmedFromChat = SellClaimWatcher.drainMatching(searchTerm);
        if (confirmedFromChat > 0) {
            sellCollectedProceeds += confirmedFromChat;
            ClientUtils.sendMessage("§aSell offer paid out " + ExecutionContext.formatDisplay(confirmedFromChat) + " coins ("
                    + ExecutionContext.formatDisplay(sellCollectedProceeds) + "/" + ExecutionContext.formatDisplay(sellProceedsExpected) + ").");
        }

        FlipperCoordinates coords = FlipperCoordinates.get();
        if (!coords.isClaimFlowConfigured()) {
            return; // already warned once by the buy side; no need to duplicate
        }

        BazaarOrderFlow.respectRateLimit();
        // Reuses the buy-side check's already-open F6 screen when there is one (tickSell() always
        // runs after tickBuy() each poll - see runLoop()), instead of closing and reopening the
        // identical screen a moment later purely to look at the sell row instead of the buy row.
        MenuSlotState slotState = openMenuAndCheckSlot(coords, coords.sellClaimSlot, true);
        if (slotState == null) {
            return; // couldn't even check this tick (screen-transition hiccup) - try again next poll
        }
        boolean stillListed = slotState.populated();
        if (slotState.price() > 0) {
            // Ground truth from Hypixel's own order display, not an inferred estimate - see the
            // class doc for why the old "fresh top price ± increment" heuristic could be wrong by
            // a full increment forever, permanently masking small real undercuts.
            sellPrice = slotState.price();
        }

        if (stillListed) {
            sellNotListedStrikes = 0;
            long beforeHeld = currentHeldQty();

            MacroDefinition claimClick = new MacroDefinition("FlipperClaimClick");
            claimClick.steps.add(MacroStep.clickSlot(coords.sellClaimSlot, 0, "PICKUP"));
            claimClick.steps.add(MacroStep.waitMs(700));
            claimClick.steps.add(MacroStep.closeScreen());
            MacroExecutor.runBlocking(claimClick, new ExecutionContext(), () -> cancelRequested);

            // Shouldn't normally happen from a plain claim (that's what canceling is for), but if
            // Hypixel ever hands items back here, don't silently lose track of them.
            long unexpectedReturn = Math.max(0, currentHeldQty() - beforeHeld);
            if (unexpectedReturn > 0) {
                sellableStock += unexpectedReturn;
            }

            // Give the confirmation message for THIS click one more short chance to arrive. If it
            // still doesn't, this tick just credits nothing - see the method doc for why there's no
            // purse-delta fallback here at all.
            long confirmedForThisClick = SellClaimWatcher.drainMatching(searchTerm);
            if (confirmedForThisClick == 0) {
                MacroWorkerThread.sleep(500);
                confirmedForThisClick = SellClaimWatcher.drainMatching(searchTerm);
            }

            if (confirmedForThisClick > 0) {
                sellCollectedProceeds += confirmedForThisClick;
                ClientUtils.sendMessage("§aSell offer paid out " + ExecutionContext.formatDisplay(confirmedForThisClick) + " coins ("
                        + ExecutionContext.formatDisplay(sellCollectedProceeds) + "/" + ExecutionContext.formatDisplay(sellProceedsExpected) + ").");
            }
        } else {
            if (sellCollectedProceeds == 0 && sellNotListedStrikes == 0) {
                // First-ever ambiguous read on this offer: dump the real menu layout to the log
                // before closing, in case this is a wrong-slot bug rather than a transient render
                // lag - captures the evidence automatically instead of needing to catch it live.
                ClientUtils.debugDumpOpenScreen();
            }
            BazaarOrderFlow.closeScreen("FlipperCloseScreen", () -> cancelRequested);

            sellNotListedStrikes++;
            if (sellCollectedProceeds == 0 && sellNotListedStrikes < BazaarOrderFlow.NOT_LISTED_STRIKE_LIMIT) {
                return; // ambiguous miss with no corroborating evidence yet - recheck next poll
            }
        }

        if (!stillListed) {
            ClientUtils.sendMessage(sellCollectedProceeds >= sellProceedsExpected
                    ? "§aSell offer fully filled - " + ExecutionContext.formatDisplay(sellCollectedProceeds) + " coins collected."
                    : "§aSell offer no longer listed in the manage-orders menu - treating as fully filled (only "
                            + "observed " + ExecutionContext.formatDisplay(sellCollectedProceeds) + " of the expected "
                            + ExecutionContext.formatDisplay(sellProceedsExpected) + " coins via our own claims - the rest "
                            + "was likely already claimed by something else).");
            sellActive = false;
            activeOrderCount = Math.max(0, activeOrderCount - 1);
        } else if (sellCollectedProceeds >= sellProceedsExpected * 0.98) {
            ClientUtils.sendMessage("§aSell offer fully filled - " + ExecutionContext.formatDisplay(sellCollectedProceeds) + " coins collected.");
            sellActive = false;
            activeOrderCount = Math.max(0, activeOrderCount - 1);
        }
    }

    private static void maybePlaceSell() {
        // Defensive re-sync: sellableStock is a Java-tracked running tally (credited from buy-fill
        // deltas, debited when a sell order lists), not a direct read, so it can drift from what's
        // actually held in either direction - a real session showed it under-count by exactly 1
        // (tracked 5x, but 6x was really held), and since placeSellOrder always lists the item's
        // *entire* current stack (there's no way to sell only part of it via that click flow), the
        // engine ended up listing more than its own tracked count expected, producing a confusing
        // "listed 6x, -1x remains unlisted" message. Correcting in both directions here - not just
        // clamping downward like before - means the qty used below always matches what will
        // actually get listed, rather than a running tally that quietly went stale.
        long actuallyHeld = currentHeldQty();
        if (sellableStock != actuallyHeld) {
            ClientUtils.sendMessage("§eSellable-stock tracking said " + sellableStock + "x but " + actuallyHeld
                    + "x is actually held - correcting. (If you see this, something about fill tracking is off - please report it.)");
            sellableStock = actuallyHeld;
        }

        if (sellableStock < 1) {
            return;
        }
        // The minimum-batch threshold only makes sense while the buy side is still actively
        // adding to this pile - waiting for it to grow further is pointless once nothing more is
        // coming, and a real session showed exactly that: 3x sitting unsold indefinitely because
        // the (already-finished) buy order's threshold was never revisited once buyActive went
        // false. With no buy order open, list whatever's held right now instead.
        long threshold = minCollectThreshold();
        if (buyActive && sellableStock < threshold) {
            ClientUtils.sendMessage("§7Have " + sellableStock + "x sellable, need " + threshold + "x ("
                    + Math.round(FlipperSettings.get().minCollectFraction * 100) + "% of the last " + buyQty
                    + "x buy order) - waiting to accumulate more before listing a sell offer.");
            return;
        }
        if (activeOrderCount >= MAX_ORDER_SLOTS) {
            ClientUtils.sendMessage("§eHave " + sellableStock + "x ready to sell, but all " + MAX_ORDER_SLOTS
                    + " order slots are in use - waiting for one to free up.");
            return;
        }

        FlipperCoordinates coords = FlipperCoordinates.get();
        if (!coords.isSellOrderFlowConfigured()) {
            ClientUtils.sendMessage("§cSell Offer coordinates aren't set up yet - fill in flipper_coordinates.json.");
            return; // don't stop the whole engine - the buy side can keep working
        }

        HypixelBazaarClient.OrderBook book;
        try {
            book = HypixelBazaarClient.getOrderBook(itemTag);
        } catch (Exception e) {
            ClientUtils.sendMessage("§cHave " + sellableStock + "x ready to sell, but failed to fetch the order book: " + e.getMessage());
            return;
        }

        double topBuy = book.topBuyPrice();
        double topSell = book.topSellPrice();
        if (topSell <= 0) {
            ClientUtils.sendMessage("§cHave " + sellableStock + "x ready to sell, but the order book returned a degenerate "
                    + "top sell price (" + ExecutionContext.formatDisplay(topSell) + ") - skipping this cycle rather than pricing off it.");
            return;
        }

        double price = Math.max(0.1, book.stableTopSellPrice() - priceIncrement());
        if (topBuy > 0 && price <= topBuy) {
            ClientUtils.sendMessage("§cComputed sell price (" + ExecutionContext.formatDisplay(price)
                    + ") would meet or cross the top buy order (" + ExecutionContext.formatDisplay(topBuy)
                    + ") - this item's market is currently crossed, so a limit sell offer here would "
                    + "undersell an instant-sell. Wrapping up rather than retrying forever - "
                    + "instant-sell that stock manually if you want that price.");
            shuttingDown = true;
            return;
        }

        long qty = sellableStock;

        BazaarOrderFlow.respectRateLimit();
        ClientUtils.sendMessage("§dPlacing SELL offer: §f" + qty + "x " + itemTag
                + " §d(targeting ~§f" + ExecutionContext.formatDisplay(price) + "§d each via the Top Order preset).");

        long placedQty = placeSellOrder(coords);
        if (placedQty > 0) {
            sellableStock = Math.max(0, sellableStock - placedQty);
            if (placedQty < qty) {
                // The unlisted remainder just stays in sellableStock - it'll go out in a future
                // sell order rather than being silently lost or double-counted. Genuinely means
                // Hypixel's real per-order volume cap was lower than expected.
                ClientUtils.sendMessage("§eHypixel's real order-volume cap was lower than expected - listed "
                        + placedQty + "x, " + (qty - placedQty) + "x remains unlisted.");
            } else if (placedQty > qty) {
                // placeSellOrder lists whatever's currently held in full (there's no way to sell
                // only part of a stack via that click flow) - if more landed in inventory between
                // the qty decision above and the actual click (e.g. a concurrent buy fill), more
                // than planned gets listed. Not an error - just means the tracked count was
                // briefly behind reality; sellableStock is already corrected to 0 above either way.
                ClientUtils.sendMessage("§eMore was actually held than tracked at listing time (probably a buy fill "
                        + "landing in between) - listed " + placedQty + "x instead of the planned " + qty + "x.");
            }
            sellActive = true;
            sellQty = placedQty;
            sellCollectedProceeds = 0;
            sellCheckStatus = null;
            sellNotListedStrikes = 0;
            activeOrderCount++;
            sellPrice = refreshPlacedPrice(FlipperSide.SELL_OFFER, price);
            double afterTax = 1.0 - FlipperSettings.get().taxRatePercent / 100.0;
            sellProceedsExpected = Math.max(1, Math.round(placedQty * sellPrice * afterTax));
        } else {
            ClientUtils.sendMessage("§cFailed to place the sell order - will retry.");
            ErrorReporter.report("FlipperEngine", "placeSellOrder returned 0 for " + qty + "x " + itemTag
                    + " at ~" + ExecutionContext.formatDisplay(price));
        }
    }

    /**
     * Stops the session if there's genuinely nothing left to do - no budget to buy with, nothing
     * held to sell, no orders open. A grace period (not an instant stop) avoids bailing out on a
     * single transient purse-read hiccup - tracked as wall-clock time via {@link #idleSinceMs}
     * rather than a tick count, since {@link #PRICE_CHECK_TICK_MS} ticks far faster than any
     * single stall decision should actually need to fire.
     */
    private static void checkStall() {
        if (buyActive || sellActive || sellableStock > 0) {
            idleSinceMs = 0L;
            return;
        }
        double budget;
        try {
            budget = resolveBudget();
        } catch (Exception e) {
            budget = -1;
        }
        if (budget < 1) {
            long now = System.currentTimeMillis();
            if (idleSinceMs == 0L) {
                idleSinceMs = now;
            } else if (now - idleSinceMs >= STALL_GRACE_MS) {
                ClientUtils.sendMessage("§cNothing to buy (budget " + ExecutionContext.formatDisplay(budget)
                        + "), nothing held, no orders open - stopping.");
                requestStop();
            }
        } else {
            idleSinceMs = 0L;
        }
    }

    /**
     * One tick of graceful wind-down (triggered by the session duration elapsing, a crossed
     * market making the item unflippable, or a second {@code /bfm stop} - see the class's
     * shutdown handling). Cancels the buy order once and never re-places it, but deliberately
     * leaves the sell side running exactly as normal - watching, repricing on undercuts, listing
     * newly-freed stock as soon as it's available - rather than dumping everything via an instant
     * sell, which would realize a meaningfully worse price than a real sell order filling at the
     * top of the book. Called every {@link #PRICE_CHECK_TICK_MS} tick until it reports done.
     *
     * @return true once genuinely nothing is left open or held - safe to end the session for real.
     */
    private static boolean windDownTick() {
        if (buyActive) {
            collectBuyFills();
            if (buyActive && claimThenCancel(FlipperSide.BUY_ORDER)) {
                ClientUtils.sendMessage("§7Buy order canceled - winding down.");
                buyActive = false;
            }
        }

        tickSell();

        return !buyActive && !sellActive && sellableStock < 1 && currentHeldQty() < 1;
    }

    // -- Helpers ----------------------------------------------------------------------

    private static void requestStop() {
        cancelRequested = true;
    }

    /**
     * A rough, best-effort estimate of the order's real placed price, used only until the first
     * watch-tick's menu check corrects it to the exact figure read from the order's own lore (see
     * {@link #openMenuAndCheckSlot}/{@link MenuSlotState}). This heuristic - re-fetching an
     * uncached top price right after placing and adding/subtracting one price increment - turned
     * out to have a real, confirmed bug: if the public API's own aggregate snapshot happens to
     * already reflect this just-placed order by the time of the re-fetch, that increment gets
     * applied a *second* time on top of one already baked into the real price, permanently biasing
     * the tracked price in the "we're still ahead" direction by a full increment. On a market that
     * only moves in increments that size, this meant {@link FlipperManager#checkTop} could never
     * detect a real outbid/undercut at all - confirmed by a real session with zero outbid/undercut
     * detections across dozens of real, observed cancel-and-relist cycles. Kept only as the
     * starting value before the first accurate lore-based reading arrives, and as a fallback if
     * that reading is ever unavailable.
     */
    private static double refreshPlacedPrice(FlipperSide side, double preClickEstimate) {
        HypixelBazaarClient.invalidateCache();
        try {
            FlipperManager.TopCheckResult fresh = FlipperManager.checkTop(itemTag, side, 0);
            return side == FlipperSide.BUY_ORDER
                    ? fresh.topPrice() + priceIncrement()
                    : Math.max(0.1, fresh.topPrice() - priceIncrement());
        } catch (Exception e) {
            ClientUtils.sendMessage("§eCouldn't refresh the real placed price after ordering (keeping the pre-order estimate): "
                    + e.getMessage());
            return preClickEstimate;
        }
    }

    private static String orderLabel(FlipperSide side) {
        return side == FlipperSide.BUY_ORDER ? "buy order" : "sell offer";
    }

    /**
     * Claims whatever's ready on the currently active order, then cancels whatever's left of it.
     * Uses {@code side} to pick the right claim slot (buy orders and sell offers sit in different
     * rows of the manage-orders menu - see {@link FlipperCoordinates}), which is exactly what
     * makes it safe to call this independently for either side even while the other side also has
     * an order open. Decrements {@link #activeOrderCount} on success. Returns false (leaving the
     * order untouched) if the claim/cancel flow isn't configured yet or every attempt below fails,
     * so the caller falls back to just continuing to watch it rather than treating it as gone.
     *
     * <p>Real gameplay showed the click sequence "succeed" (every click lands, the macro runner
     * reports success) while Hypixel's own server silently refused the cancel and replied
     * {@code "[Bazaar] You have goods to claim on this order!"} instead - it won't cancel an order
     * that still has an unclaimed fill sitting on it, which can happen if more filled in the gap
     * between this method's own claim pass and its cancel click. {@link CancelRejectionWatcher}
     * catches that exact message, so this retries with a fresh claim pass when it fires instead of
     * either wrongly declaring success or giving up on just one attempt.
     */
    private static boolean claimThenCancel(FlipperSide side) {
        FlipperCoordinates coords = FlipperCoordinates.get();
        String label = orderLabel(side);
        if (!coords.isClaimFlowConfigured()) {
            ClientUtils.sendMessage("§eCan't cancel the " + itemTag + " " + label + " yet - the claim/cancel flow isn't configured.");
            return false;
        }
        String claimSlot = side == FlipperSide.BUY_ORDER ? coords.buyClaimSlot : coords.sellClaimSlot;

        for (int attempt = 1; attempt <= BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT; attempt++) {
            CancelRejectionWatcher.drainRejected(); // clear any stale rejection from before this attempt

            BazaarOrderFlow.respectRateLimit();
            MacroExecutor.runBlocking(buildClaimMacro(coords, claimSlot), new ExecutionContext(), () -> cancelRequested);

            BazaarOrderFlow.respectRateLimit();
            boolean canceled = MacroExecutor.runBlocking(buildCancelMacro(coords, claimSlot), new ExecutionContext(), () -> cancelRequested);

            if (canceled && !CancelRejectionWatcher.drainRejected()) {
                activeOrderCount = Math.max(0, activeOrderCount - 1);
                return true;
            }

            if (attempt < BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT) {
                ClientUtils.sendMessage("§7Hypixel rejected canceling the " + label + " - it still had unclaimed goods on it. "
                        + "Claiming again and retrying (attempt " + (attempt + 1) + "/" + BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT + ").");
            }
        }

        ClientUtils.sendMessage("§cFailed to cancel the " + label + " after " + BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT + " attempts - will keep watching it.");
        ErrorReporter.report("FlipperEngine", "Failed to cancel the " + itemTag + " " + label + " after "
                + BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT + " attempts");
        return false;
    }

    /** Won't list a sell order for less than this fraction of the current/last buy order's quantity - a slot is too valuable to spend on a handful of items. Falls back to "sell whatever's held" if no buy order has run yet this session. */
    private static long minCollectThreshold() {
        if (buyQty <= 0) return 1;
        return Math.max(1, (long) (buyQty * FlipperSettings.get().minCollectFraction));
    }

    private static double resolveBudget() throws Exception {
        long purse = ClientUtils.getPurse();
        if (purse < 0) {
            throw new IllegalStateException("couldn't read purse from the scoreboard sidebar");
        }
        if (capExpression == null || capExpression.isBlank()) {
            return purse;
        }
        Map<String, Double> variables = new HashMap<>();
        variables.put("purse", (double) purse);
        double cap = MacroExpression.evaluate(capExpression, variables);
        return Math.min(purse, cap);
    }

    /**
     * Resolves the user's typed input to {@link #itemTag} (for API price lookups) and
     * {@link #searchTerm} (for the actual in-game {@code /bz} search). These come from the
     * *same* confirmed match, not raw user input, on purpose: Coflnet's search is forgiving of
     * minor typos and will still resolve a correct tag, but Hypixel's own in-game search may
     * not be - echoing the user's possibly-slightly-wrong text back into {@code /bz} can find
     * nothing even when the tag resolved fine. Always search for the name Coflnet actually
     * confirmed, not what was typed.
     */
    private static void resolveItem(String input) throws Exception {
        String trimmed = input.trim();
        if (trimmed.equals(trimmed.toUpperCase()) && trimmed.matches("[A-Z0-9_:]+")) {
            // Already looks like an exact tag (e.g. via /bfm start) - no display name to recover,
            // so /bz gets the tag as-is. This is the one case that can still hit the same failure
            // mode if Hypixel's search doesn't accept the raw tag text.
            itemTag = trimmed;
            searchTerm = trimmed;
            return;
        }
        BazaarPriceClient.ItemMatch match = BazaarPriceClient.searchItem(trimmed);
        itemTag = match.tag();
        searchTerm = match.displayName();
    }

    /** Bazaar search results occupy slots 10-42 (rows 2-6 of a 9-wide grid) - matches Aether's own scan range. */
    private static final int SEARCH_RESULT_SCAN_START = 10;
    private static final int SEARCH_RESULT_SCAN_END = 42;

    /**
     * How far into a screen to scan for the player's own inventory when selling (see
     * {@link #placeSellOrder}). Deliberately wide rather than a precise offset: a real
     * {@code /bfm debug orders} dump confirmed the player's inventory mirrors at slots 36-71 in
     * the F6 manage-orders sub-screen specifically, but the plain root {@code /bz} screen - which
     * is what selling actually opens - has a different total row count. Rather than chase the
     * exact right offset per screen variant, this scans essentially the whole screen; the
     * find-item step already clamps the end bound to the real slot count, and matching is an
     * exact (not substring) item-name match, so a real item name has no realistic chance of
     * colliding with an unrelated menu button.
     */
    private static final int PLAYER_INVENTORY_IN_SCREEN_START = 0;
    private static final int PLAYER_INVENTORY_IN_SCREEN_END = 200;

    private static final java.util.regex.Pattern MAX_VOLUME_PATTERN =
            java.util.regex.Pattern.compile("(?i)(?:buy|sell) up to ([\\d,]+)");
    private static volatile boolean warnedMaxVolumeParse = false;

    /**
     * A single {@code waitMs(500)} after a click that's expected to open a new sub-screen (an
     * item's page, an amount prompt, an order's detail view, etc.) was previously just a flat
     * delay, no re-check - fine as long as nothing else ever touches the screen in that window.
     * Real gameplay showed a claim/cancel macro reaching its next click with no screen open at
     * all mid-sequence, most plausibly another Bazaar-hooking mod (BtrBz/SkyHanni are both
     * installed alongside this one) transiently interacting with the same screen. This
     * re-confirms *some* container screen is present (waiting up to 5s for one to reappear if it
     * isn't) before the settle wait, deliberately title-agnostic (an empty {@code titleContains}
     * matches any screen) since different screens in this flow have different titles.
     */
    /**
     * Item page -> find and click the search result matching {@code searchTerm} exactly (not a
     * fixed slot - which result lands where depends on how many matches the search returns) ->
     * click {@code actionButton} (Buy Order) -> [read Hypixel's real per-order volume cap off the
     * amount item's lore and clamp {@code requestedQty} against it] -> click {@code amountField}
     * -> type quantity on the sign -> click {@code orderPricePreset} (via a real simulated mouse
     * click - see the class doc) -> click {@code orderConfirm} -> close. User-verified sequence,
     * confirmed end-to-end by a real completed session.
     *
     * @return the quantity actually submitted, or 0 if any phase of the click flow failed.
     */
    private static long placeOrder(String actionButton, String pricePresetSlot, FlipperCoordinates coords, long requestedQty) {
        MacroDefinition phaseA = new MacroDefinition("FlipperOrderPhaseA");
        phaseA.steps.add(MacroStep.command("/bz " + searchTerm)); // display name, not itemTag - see field doc
        phaseA.steps.add(MacroStep.waitForScreen("Bazaar", 10_000));
        phaseA.steps.add(MacroStep.waitMs(400));
        phaseA.steps.add(MacroStep.findItemSlot(searchTerm, SEARCH_RESULT_SCAN_START, SEARCH_RESULT_SCAN_END, 0, "PICKUP"));
        phaseA.steps.add(MacroStep.waitMs(800));
        phaseA.steps.add(MacroStep.clickSlot(actionButton, 0, "PICKUP"));
        phaseA.steps.add(MacroStep.waitMs(900));
        if (!MacroExecutor.runBlocking(phaseA, new ExecutionContext(), () -> cancelRequested)) {
            return 0;
        }

        long qty = clampToRealMaxVolume(coords, requestedQty);

        MacroDefinition phaseB = new MacroDefinition("FlipperOrderPhaseB");
        phaseB.steps.add(MacroStep.clickSlot(coords.amountField, 0, "PICKUP"));
        phaseB.steps.add(MacroStep.waitMs(500));
        phaseB.steps.add(MacroStep.submitSignText(String.valueOf(qty)));
        phaseB.steps.add(MacroStep.waitMs(2000));
        if (!MacroExecutor.runBlocking(phaseB, new ExecutionContext(), () -> cancelRequested)) {
            return 0;
        }

        MacroDefinition phaseC = new MacroDefinition("FlipperOrderPhaseC");
        phaseC.steps.add(MacroStep.clickSlotReal(pricePresetSlot, 0));
        phaseC.steps.add(MacroStep.waitMs(700));
        if (!MacroExecutor.runBlocking(phaseC, new ExecutionContext(), () -> cancelRequested)) {
            return 0;
        }

        // Purse-based ground truth around the one click that actually commits real money (funds
        // are escrowed the instant this confirm lands - see the real "[Bazaar] Putting goods in
        // escrow..." message it triggers), not trusted from the macro runner's own success/failure
        // verdict alone. See placeSellOrder's doc for why: a real session showed this exact class
        // of click sequence genuinely succeed while runBlocking still reported failure (most
        // likely the window being unfocused/tabbed-out slowing down how fast the UI visibly
        // updates, which this engine's fixed-millisecond waits assume won't happen) - trusting
        // that false negative left a real order completely untracked.
        long purseBeforeConfirm = ClientUtils.getPurse();
        MacroDefinition phaseD = new MacroDefinition("FlipperOrderPhaseD");
        phaseD.steps.add(MacroStep.clickSlot(coords.orderConfirm, 0, "PICKUP"));
        phaseD.steps.add(MacroStep.waitMs(500));
        phaseD.steps.add(MacroStep.closeScreen());
        boolean phaseDOk = MacroExecutor.runBlocking(phaseD, new ExecutionContext(), () -> cancelRequested);

        // Prefer Hypixel's own exact "Buy Order Setup!" confirmation over the purse-drop
        // inference below - see OrderSetupWatcher's doc.
        Long confirmedQty = OrderSetupWatcher.drainMatchingBuy(searchTerm);
        if (confirmedQty == null) {
            MacroWorkerThread.sleep(500);
            confirmedQty = OrderSetupWatcher.drainMatchingBuy(searchTerm);
        }
        if (confirmedQty != null) {
            return confirmedQty;
        }

        if (!phaseDOk) {
            long purseAfterConfirm = ClientUtils.getPurse();
            boolean purseDropped = purseBeforeConfirm >= 0 && purseAfterConfirm >= 0 && purseAfterConfirm < purseBeforeConfirm;
            if (!purseDropped) {
                return 0;
            }
            ClientUtils.sendMessage("§eThe buy-order click sequence reported a failure, but the purse actually dropped by "
                    + ExecutionContext.formatDisplay(purseBeforeConfirm - purseAfterConfirm) + " coins - a real order was "
                    + "placed despite that; trusting the real purse change instead of the reported failure.");
        }

        return qty;
    }

    /**
     * Sell offers use a genuinely different navigation path than buy orders - user-verified: the
     * correct flow is to click the item directly in the player's own inventory instead of
     * searching for it - visible mirrored at the bottom of any open Bazaar screen, works from any
     * single stack even if the item is split across several - which jumps straight to
     * {@code sellPricePreset} for the player's *total* held quantity, with no amount-entry step at
     * all. Sequence: {@code /bz} (just needs any Bazaar screen open, no search needed) -> find the
     * item among {@link #PLAYER_INVENTORY_IN_SCREEN_START}-{@link #PLAYER_INVENTORY_IN_SCREEN_END}
     * -> click {@code sellOfferButton} -> click {@code sellPricePreset} -> click
     * {@code orderConfirm} -> close. Since there's no typed quantity to clamp against Hypixel's
     * lore-stated cap beforehand, the actually-listed amount is measured the same way every other
     * fill in this engine is: a real inventory count taken right before and right after.
     *
     * @return the quantity actually listed, measured from the real inventory count regardless of
     *         whether the macro runner itself reported success - see the class doc for why: a
     *         real, confirmed session showed the click sequence genuinely succeed (Hypixel's own
     *         chat confirmed a real "Sell Offer Setup!") while {@link MacroExecutor#runBlocking}
     *         still reported failure, and every call site blindly trusting that false negative
     *         left a real, fully-valid sell offer (275,947 coins, later manually recovered)
     *         completely untracked - this engine had zero idea it existed. 0 if nothing was held
     *         to begin with, or if the inventory genuinely didn't change (a real failure).
     */
    private static long placeSellOrder(FlipperCoordinates coords) {
        long before = currentHeldQty();
        if (before < 1) {
            return 0;
        }

        MacroDefinition macro = new MacroDefinition("FlipperSellOrder");
        macro.steps.add(MacroStep.command(coords.claimOrdersCommand)); // "/bz" - any Bazaar screen shows the player's own inventory
        macro.steps.add(MacroStep.waitForScreen("Bazaar", 10_000));
        macro.steps.add(MacroStep.waitMs(400));
        macro.steps.add(MacroStep.findItemSlot(searchTerm, PLAYER_INVENTORY_IN_SCREEN_START, PLAYER_INVENTORY_IN_SCREEN_END, 0, "PICKUP"));
        macro.steps.add(MacroStep.waitMs(500));
        macro.steps.add(MacroStep.clickSlot(coords.sellOfferButton, 0, "PICKUP"));
        macro.steps.add(MacroStep.waitMs(500));
        macro.steps.add(MacroStep.clickSlot(coords.sellPricePreset, 0, "PICKUP"));
        macro.steps.add(MacroStep.waitMs(500));
        macro.steps.add(MacroStep.clickSlot(coords.orderConfirm, 0, "PICKUP"));
        macro.steps.add(MacroStep.waitMs(500));
        macro.steps.add(MacroStep.closeScreen());

        boolean macroOk = MacroExecutor.runBlocking(macro, new ExecutionContext(), () -> cancelRequested);

        // Prefer Hypixel's own exact "Sell Offer Setup!" confirmation over any inventory-delta
        // inference - see OrderSetupWatcher's doc for why: that inference measured a genuinely
        // successful 33x sell as zero listed once, orphaning a real 33x/3.98M-coin order, because
        // the post-macro inventory read can still be momentarily stale right after closeScreen().
        Long confirmedQty = OrderSetupWatcher.drainMatchingSell(searchTerm);
        if (confirmedQty == null) {
            MacroWorkerThread.sleep(500);
            confirmedQty = OrderSetupWatcher.drainMatchingSell(searchTerm);
        }
        if (confirmedQty != null) {
            return confirmedQty;
        }

        // No direct confirmation arrived - fall back to the inventory delta, with a settle wait
        // before trusting it (the same render-lag race noted above).
        MacroWorkerThread.sleep(700);
        long actuallyListed = Math.max(0, before - currentHeldQty());
        if (!macroOk && actuallyListed > 0) {
            ClientUtils.sendMessage("§eThe sell-order click sequence reported a failure, but " + actuallyListed
                    + "x actually left the inventory - a real offer was listed despite that; trusting the real "
                    + "inventory count instead of the reported failure.");
        }
        return actuallyListed;
    }

    /**
     * Buy-order only - the sell flow has no amount-entry sign to clamp before submitting (see
     * {@link #placeSellOrder}, which measures the real listed quantity from inventory afterward
     * instead). Reads Hypixel's own stated per-order volume cap (a "Buy up to X" lore line on the
     * amount-entry item) and clamps against it. Falls back to the requested quantity untouched, with a one-time warning,
     * if the lore is missing or doesn't match - this is a safety net on top of our own math, not
     * a replacement for it, so a parse miss should never block placing the order.
     */
    private static long clampToRealMaxVolume(FlipperCoordinates coords, long requestedQty) {
        try {
            int slotIndex = SlotCoordinate.parse(coords.amountField);
            for (String line : ClientUtils.getOpenSlotLore(slotIndex)) {
                java.util.regex.Matcher m = MAX_VOLUME_PATTERN.matcher(line);
                if (m.find()) {
                    long realMax = Long.parseLong(m.group(1).replace(",", ""));
                    return Math.min(requestedQty, realMax);
                }
            }
        } catch (Exception e) {
            if (!warnedMaxVolumeParse) {
                warnedMaxVolumeParse = true;
                ClientUtils.sendMessage("§7Couldn't read Hypixel's real order-volume cap (" + e.getMessage()
                        + ") - using the computed quantity as-is from now on.");
            }
        }
        return requestedQty;
    }

    /**
     * Opens the manage-orders (F6) menu and reports whether {@code claimSlot} currently has an
     * item in it - the definitive "is this order still open" signal used by both
     * {@link #collectBuyFills} and {@link #collectSellProceeds} (see the class doc for why that
     * replaced inferring completion from a shared purse/inventory counter). Deliberately leaves
     * the screen open on a successful check - the caller either clicks the slot immediately or
     * calls {@link #closeCurrentScreen} - since reopening the menu a second time just to click it
     * would double the wait for no benefit. Returns {@code null} if the menu couldn't even be
     * opened this tick (a screen-transition hiccup); the caller should just retry next poll rather
     * than guessing which way to treat that.
     */
    private static final java.util.regex.Pattern PRICE_PER_UNIT_PATTERN =
            java.util.regex.Pattern.compile("(?i)Price per unit: ([\\d,.]+) coins");

    /**
     * @param populated whether the slot currently shows an item at all.
     * @param price the real per-unit price parsed from the order's own lore ("Price per unit: X
     *              coins" - confirmed exact wording from a real {@code /bfm debug orders} dump of
     *              a filled sell offer; not independently confirmed for an unfilled order or for
     *              the buy side specifically, so this is a best-effort correction, not load-bearing
     *              if it ever comes back {@code -1}), or -1 if not populated or not parseable.
     */
    private record MenuSlotState(boolean populated, double price) {
    }

    /**
     * @param reuseIfAlreadyOpen if true and a container screen is already open, skips the
     *                           {@code /bz} + F6-navigation steps entirely and reads the slot
     *                           directly - used by the sell-side check, since the buy-side check
     *                           (always run first each poll - see {@code runLoop}) leaves the F6
     *                           manage-orders screen open when it's about to be reused rather than
     *                           closing it only to have this reopen the identical screen a moment
     *                           later. One real /bz command sent to the server instead of two,
     *                           every poll where both sides are active - the common case for this
     *                           engine's whole concurrent-buy+sell design.
     */
    private static MenuSlotState openMenuAndCheckSlot(FlipperCoordinates coords, String claimSlot, boolean reuseIfAlreadyOpen) {
        if (reuseIfAlreadyOpen && ClientUtils.isInventoryScreenOpen()) {
            return readSlotState(claimSlot);
        }

        MacroDefinition macro = new MacroDefinition("FlipperOpenClaimMenu");
        macro.steps.add(MacroStep.command(coords.claimOrdersCommand));
        macro.steps.add(MacroStep.waitForScreen("Bazaar", 10_000));
        macro.steps.add(MacroStep.waitMs(400));
        macro.steps.add(MacroStep.clickSlot(coords.claimOrdersMenu, 0, "PICKUP"));
        BazaarOrderFlow.addTransitionWait(macro);
        // Extra settle time specific to this check, beyond addTransitionWait's usual 500ms: real
        // gameplay showed a genuinely-populated order slot read as empty here, and that false read
        // repeated across multiple consecutive re-opens of this same menu - not a one-off blip,
        // more consistent with the order list's dynamic contents (fill status, claimable amount)
        // taking longer to arrive/render than the screen itself does. Cheap to wait a bit longer
        // given this only runs once per poll (every 5-10s already).
        macro.steps.add(MacroStep.waitMs(900));
        if (!MacroExecutor.runBlocking(macro, new ExecutionContext(), () -> cancelRequested)) {
            return null;
        }
        return readSlotState(claimSlot);
    }

    private static MenuSlotState readSlotState(String claimSlot) {
        try {
            int slotIndex = SlotCoordinate.parse(claimSlot);
            boolean populated = ClientUtils.isSlotPopulated(slotIndex);
            double price = -1;
            if (populated) {
                for (String line : ClientUtils.getOpenSlotLore(slotIndex)) {
                    java.util.regex.Matcher m = PRICE_PER_UNIT_PATTERN.matcher(line);
                    if (m.find()) {
                        price = Double.parseDouble(m.group(1).replace(",", ""));
                        break;
                    }
                }
            }
            return new MenuSlotState(populated, price);
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code /bz} -> click {@code claimOrdersMenu} (active orders) -> click {@code claimSlot} (buyClaimSlot or sellClaimSlot, whichever this order is) -> close. */
    private static MacroDefinition buildClaimMacro(FlipperCoordinates coords, String claimSlot) {
        MacroDefinition macro = new MacroDefinition("FlipperClaim");
        macro.steps.add(MacroStep.command(coords.claimOrdersCommand));
        macro.steps.add(MacroStep.waitForScreen("Bazaar", 10_000));
        macro.steps.add(MacroStep.waitMs(400));
        macro.steps.add(MacroStep.clickSlot(coords.claimOrdersMenu, 0, "PICKUP"));
        BazaarOrderFlow.addTransitionWait(macro);
        macro.steps.add(MacroStep.clickSlot(claimSlot, 0, "PICKUP"));
        macro.steps.add(MacroStep.waitMs(500));
        macro.steps.add(MacroStep.closeScreen());
        return macro;
    }

    /**
     * {@code /bz} -> {@code claimOrdersMenu} -> click {@code claimSlot} (the order isn't filled,
     * so this opens a management view instead of instantly claiming) -> click
     * {@code cancelButton} (a fixed "C2" on that new screen, confirmed directly by the user - an
     * earlier "same column as the claim slot" guess was wrong) -> click {@code orderConfirm} to
     * finalize -> close.
     *
     * <p>The {@code cancelButton} click is dispatched via {@link ClientUtils#performRealMouseClick}
     * rather than the usual slot-click shortcut - real gameplay showed this specific action
     * sometimes needing 2-3 attempts (via the outer retry in {@code tickBuy}/{@code tickSell})
     * before actually canceling, the same symptom that turned out to be a silent click-registration
     * failure for the buy-order price-preset click (see the class doc) rather than anything wrong
     * with the coordinate or timing. Routing through the real mouse-input pipeline fixed that
     * case, so it's a reasonable, low-risk first thing to try here too.
     */
    private static MacroDefinition buildCancelMacro(FlipperCoordinates coords, String claimSlot) {
        MacroDefinition macro = new MacroDefinition("FlipperCancel");
        macro.steps.add(MacroStep.command(coords.claimOrdersCommand));
        macro.steps.add(MacroStep.waitForScreen("Bazaar", 10_000));
        macro.steps.add(MacroStep.waitMs(400));
        macro.steps.add(MacroStep.clickSlot(coords.claimOrdersMenu, 0, "PICKUP"));
        BazaarOrderFlow.addTransitionWait(macro);
        macro.steps.add(MacroStep.clickSlot(claimSlot, 0, "PICKUP"));
        BazaarOrderFlow.addTransitionWait(macro);
        macro.steps.add(MacroStep.clickSlotReal(coords.cancelButton, 0));
        macro.steps.add(MacroStep.waitMs(700));
        macro.steps.add(MacroStep.clickSlot(coords.orderConfirm, 0, "PICKUP"));
        macro.steps.add(MacroStep.waitMs(500));
        macro.steps.add(MacroStep.closeScreen());
        return macro;
    }

}

package dev.bazaarmacro.flipper;

import dev.bazaarmacro.bazaar.HypixelBazaarClient;
import dev.bazaarmacro.macro.ExecutionContext;
import dev.bazaarmacro.macro.MacroDefinition;
import dev.bazaarmacro.macro.MacroExecutor;
import dev.bazaarmacro.macro.MacroStep;
import dev.bazaarmacro.macro.MacroWorkerThread;
import dev.bazaarmacro.macro.SlotCoordinate;
import dev.bazaarmacro.util.ClientUtils;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/**
 * The low-level Bazaar plumbing every autonomous engine needs, in one place: action rate limiting,
 * screen-transition waits, closing a screen, fill-check pacing, checking whether an order is still
 * listed in the manage-orders menu, and re-reading a just-placed order's real price.
 *
 * <p>Each of these previously existed as a byte-identical private copy inside
 * {@link FlipperEngine}, {@link dev.bazaarmacro.craft.ScubaSellEngine},
 * {@link dev.bazaarmacro.craft.ScubaCraftScript}, {@link dev.bazaarmacro.craft.LegionCraftScript}
 * and {@link dev.bazaarmacro.books.BookFlipperEngine} - five copies that had to be kept in sync by
 * hand, which is exactly how a fix landing in one engine and not the others became a recurring
 * pattern in this project. Consolidating them is safe precisely because they were identical: the
 * bodies here are those same bodies, not a rewrite.
 *
 * <p>What is <em>not</em> pulled in here is anything that genuinely differs between engines - how
 * an item is located (exact display name vs. an NBT enchant read), how a chat confirmation is
 * matched (by name vs. by implied price), or how fast a given market should be polled. Those stay
 * with their own engine, since flattening a real difference into a shared default is how a
 * deliberate choice silently becomes a bug.
 */
public final class BazaarOrderFlow {
    /**
     * How many consecutive empty menu-slot reads are needed before "empty" is accepted as
     * "genuinely done". Real gameplay showed a false-empty read persist across two consecutive
     * re-opens of the manage-orders menu (a render-lag race, not a one-off blip), so this sits
     * with real margin above that observed case - see {@link FlipperEngine#collectBuyFills}.
     */
    public static final int NOT_LISTED_STRIKE_LIMIT = 4;

    /**
     * How many claim-then-cancel attempts to make before giving up and letting the caller retry on
     * a later poll. Hypixel refuses to cancel an order that still has an unclaimed fill sitting on
     * it ({@code "[Bazaar] You have goods to claim on this order!"}), which
     * {@link CancelRejectionWatcher} catches - so a retry with a fresh claim pass is the fix, not
     * treating the first refusal as failure.
     */
    public static final int CANCEL_REJECTION_RETRY_LIMIT = 3;

    /**
     * Shared across every engine rather than per-engine. The engines are mutually exclusive (each
     * one's {@code start()} refuses to run while another is active), so at most one is ever
     * spending this budget - and sharing it means a hand-off from one engine to the next can't
     * burst two actions back-to-back the way two independent clocks would.
     */
    private static volatile long lastActionTime = 0L;

    private BazaarOrderFlow() {
    }

    /** Blocks until at least {@code minActionIntervalSeconds} has passed since the last real in-game action. */
    public static void respectRateLimit() {
        long remaining = (long) (FlipperSettings.get().minActionIntervalSeconds * 1000.0)
                - (System.currentTimeMillis() - lastActionTime);
        if (remaining > 0) {
            MacroWorkerThread.sleep(remaining);
        }
        lastActionTime = System.currentTimeMillis();
    }

    /**
     * Randomized pacing for the opportunistic, real in-game fill check. Deliberately takes its
     * bounds from the caller instead of assuming one pair: the book snipers poll far slower than
     * the main flipper on purpose, because their markets genuinely trade a couple of times an hour.
     */
    public static long randomFillCheckIntervalMs(long minMs, long maxMs) {
        return ThreadLocalRandom.current().nextLong(minMs, maxMs + 1);
    }

    /**
     * Re-confirms <em>some</em> container screen is present (waiting up to 5s for one to reappear)
     * before a settle wait, rather than assuming a click that opens a sub-screen landed. Real
     * gameplay showed a claim/cancel macro reach its next click with no screen open at all
     * mid-sequence, most plausibly another Bazaar-hooking mod transiently interacting with the same
     * screen. Deliberately title-agnostic - different screens in these flows have different titles.
     */
    public static void addTransitionWait(MacroDefinition macro) {
        macro.steps.add(MacroStep.waitForScreen("", 5_000));
        macro.steps.add(MacroStep.waitMs(500));
    }

    /** Closes whatever container screen is open, via the normal macro pipeline. */
    public static void closeScreen(String macroName, BooleanSupplier cancelled) {
        MacroDefinition macro = new MacroDefinition(macroName);
        macro.steps.add(MacroStep.closeScreen());
        MacroExecutor.runBlocking(macro, new ExecutionContext(), cancelled);
    }

    /**
     * Opens the manage-orders (F6) menu and reports whether {@code claimSlot} currently holds an
     * item - the definitive "is this order still open" signal, used by every engine's buy and sell
     * sides alike. Returns true (assume still listed) if the menu couldn't be opened at all this
     * tick, rather than falsely declaring a live order gone.
     */
    public static boolean openMenuAndCheckSlot(String macroPrefix, String claimSlot, BooleanSupplier cancelled) {
        FlipperCoordinates coords = FlipperCoordinates.get();
        MacroDefinition macro = new MacroDefinition(macroPrefix + "OpenClaimMenu");
        macro.steps.add(MacroStep.command(coords.claimOrdersCommand));
        macro.steps.add(MacroStep.waitForScreen("Bazaar", 10_000));
        macro.steps.add(MacroStep.waitMs(400));
        macro.steps.add(MacroStep.clickSlot(coords.claimOrdersMenu, 0, "PICKUP"));
        addTransitionWait(macro);
        // Extra settle beyond the usual transition wait: the order list's dynamic contents (fill
        // status, claimable amount) render later than the screen itself, and reading too early
        // produced a false "empty" on a genuinely-open order.
        macro.steps.add(MacroStep.waitMs(900));
        if (!MacroExecutor.runBlocking(macro, new ExecutionContext(), cancelled)) {
            return true;
        }
        try {
            return ClientUtils.isSlotPopulated(SlotCoordinate.parse(claimSlot));
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * A rough, best-effort estimate of a just-placed sell offer's real price, used only until the
     * first watch-tick reads the exact figure off the order's own lore. Note this re-fetch can
     * itself already reflect the order that was just placed, in which case the increment gets
     * applied a second time - see {@link FlipperEngine#refreshPlacedPrice} for the real, confirmed
     * incident that caused, which is why callers correct this from the order's lore as soon as they
     * can rather than trusting it.
     */
    public static double refreshPlacedSellPrice(String itemTag, double preClickEstimate) {
        HypixelBazaarClient.invalidateCache();
        try {
            FlipperManager.TopCheckResult fresh = FlipperManager.checkTop(itemTag, FlipperSide.SELL_OFFER, 0);
            return Math.max(0.1, fresh.topPrice() - FlipperSettings.get().priceIncrement);
        } catch (Exception e) {
            return preClickEstimate;
        }
    }
}

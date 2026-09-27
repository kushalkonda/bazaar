package dev.bazaarmacro.flipper;

import dev.bazaarmacro.bazaar.HypixelBazaarClient;
import dev.bazaarmacro.util.ClientUtils;

/**
 * Purse-based profit tracking for the current flip session. "True" profit is just the actual
 * coin delta since session start - no estimation needed, purse doesn't lie. "Estimated" profit
 * projects that forward by valuing whatever's currently held (collected but not yet sold) at
 * today's market sell price, i.e. "what my profit would be if I cashed out everything right now".
 */
public final class FlipperProfitTracker {
    private static volatile long startingPurse = -1;

    private FlipperProfitTracker() {
    }

    static void startSession() {
        startingPurse = ClientUtils.getPurse();
    }

    static void endSession() {
        startingPurse = -1;
    }

    public static boolean hasSession() {
        return startingPurse >= 0;
    }

    public static long startingPurse() {
        return startingPurse;
    }

    /** Actual coin change since session start. Returns 0 if there's no active session or purse can't be read. */
    public static long trueProfit() {
        if (startingPurse < 0) return 0;
        long current = ClientUtils.getPurse();
        return current < 0 ? 0 : current - startingPurse;
    }

    /**
     * {@link #trueProfit()} plus the value of everything not yet realized as coins - regardless
     * of which of three states that value currently happens to be sitting in, so the number
     * doesn't swing around (or look like a temporary loss) just because stock moved from
     * inventory into an open sell offer or back:
     *
     * <ul>
     *   <li>inventory held, plus an open buy order's still-uncollected fill - valued at the
     *       item's current top sell price, net of Hypixel's Bazaar sell tax
     *       ({@link FlipperSettings#taxRatePercent}), since that tax is deducted server-side
     *       before the coins ever hit the purse;</li>
     *   <li>an open sell offer's still-uncollected proceeds - valued at that offer's own
     *       already-locked-in expected payout ({@code sellProceedsExpected}, itself already
     *       after-tax), not a freshly re-fetched market price, since the offer's real price was
     *       fixed the moment it was listed and won't move with the market afterward.</li>
     * </ul>
     *
     * <p>A real session showed the old version - which only valued inventory/pending-buy stock
     * and ignored anything already listed in a sell offer - understate profit (or show what
     * looked like a loss) purely because stock happened to be tied up in an open sell offer at
     * that instant, even though its cost was already reflected in {@link #trueProfit()} and its
     * expected payout was already known.
     */
    public static double estimatedProfit(FlipperEngine.Snapshot snap) {
        long realized = trueProfit();
        double unrealized = 0;

        long heldOrPendingBuy = snap.heldQty() + (snap.buyActive() ? Math.max(0, snap.buyQty() - snap.buyClaimedQty()) : 0);
        if (heldOrPendingBuy > 0 && snap.itemTag() != null) {
            try {
                double sellPrice = HypixelBazaarClient.getOrderBook(snap.itemTag()).topSellPrice();
                double afterTax = sellPrice * (1.0 - FlipperSettings.get().taxRatePercent / 100.0);
                unrealized += heldOrPendingBuy * afterTax;
            } catch (Exception ignored) {
                // Best-effort - a failed price lookup just means this component is skipped for
                // this one call, not that the whole estimate should be discarded.
            }
        }

        if (snap.sellActive()) {
            unrealized += Math.max(0, snap.sellProceedsExpected() - snap.sellCollectedProceeds());
        }

        return realized + unrealized;
    }
}

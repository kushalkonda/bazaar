package dev.bazaarmacro.flipper;

import dev.bazaarmacro.bazaar.HypixelBazaarClient;

/**
 * Pure price-comparison utility: is a given price the top order for an item/side right now?
 * Used both by the standalone {@code /bfm flip check} command and internally by
 * {@link FlipperEngine} when it needs to decide a price. The autonomous watch/alert loop
 * this class used to own has been folded into {@link FlipperEngine}'s
 * {@code WATCHING_BUY_ORDER}/{@code WATCHING_SELL_ORDER} states, which act on the result
 * instead of just alerting.
 */
public final class FlipperManager {
    private FlipperManager() {
    }

    public record TopCheckResult(double myPrice, double topPrice, Status status) {
        public enum Status {AHEAD, TIED, BEHIND}
    }

    /** Fetches the live order book and compares {@code myPrice} against the current top. */
    public static TopCheckResult checkTop(String itemTag, FlipperSide side, double myPrice) throws Exception {
        HypixelBazaarClient.OrderBook book = HypixelBazaarClient.getOrderBook(itemTag);
        double topPrice = side == FlipperSide.BUY_ORDER ? book.topBuyPrice() : book.topSellPrice();

        TopCheckResult.Status status;
        boolean higherIsBetter = side == FlipperSide.BUY_ORDER;
        if (myPrice == topPrice) {
            status = TopCheckResult.Status.TIED;
        } else if ((higherIsBetter && myPrice > topPrice) || (!higherIsBetter && myPrice < topPrice)) {
            status = TopCheckResult.Status.AHEAD;
        } else {
            status = TopCheckResult.Status.BEHIND;
        }
        return new TopCheckResult(myPrice, topPrice, status);
    }
}

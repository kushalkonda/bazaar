package dev.bazaarmacro.flipper;

import dev.bazaarmacro.bazaar.HypixelBazaarClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Scans every Bazaar product for the "buy order then sell offer" margin {@link FlipperEngine}
 * actually captures, ranking by percent margin so the flipper doesn't have to be pointed at an
 * item by guesswork. Reuses {@link HypixelBazaarClient.OrderBook#stableTopBuyPrice()}/{@code
 * stableTopSellPrice()} (the same outlier-resistant prices the engine prices real orders off of)
 * rather than the literal top, and Hypixel's real Bazaar tax rate - so a ranked margin here is
 * the same number the engine would actually realize, not an optimistic best case.
 */
public final class BazaarScanner {

    /**
     * Minimum coins of depth required at the top of *both* sides before a product is considered -
     * filters out items where the "top" is a handful of units nobody could actually transact at
     * scale (a real margin on paper that isn't really flippable for meaningful volume).
     */
    private static final double MIN_LIQUIDITY_COINS = 50_000.0;

    private BazaarScanner() {
    }

    public record Opportunity(String itemTag, double buyOrderPrice, double sellOfferPrice,
                               double marginPerUnit, double marginPercent, long liquidityUnits) {
    }

    /** Fetches every product's order book and returns the top {@code limit} by margin percent, most profitable first. */
    public static List<Opportunity> findTopFlips(int limit) throws Exception {
        Map<String, HypixelBazaarClient.OrderBook> books = HypixelBazaarClient.getAllOrderBooks();
        double priceIncrement = FlipperSettings.get().priceIncrement;
        double taxMultiplier = 1.0 - FlipperSettings.get().taxRatePercent / 100.0;

        List<Opportunity> opportunities = new ArrayList<>();
        for (Map.Entry<String, HypixelBazaarClient.OrderBook> entry : books.entrySet()) {
            HypixelBazaarClient.OrderBook book = entry.getValue();
            double stableBuy = book.stableTopBuyPrice();
            double stableSell = book.stableTopSellPrice();
            if (stableBuy <= 0 || stableSell <= 0) continue;

            double buyOrderPrice = stableBuy + priceIncrement;
            double sellOfferPrice = Math.max(0.1, stableSell - priceIncrement);
            if (sellOfferPrice <= buyOrderPrice) continue; // crossed or no room for a limit-order flip at all

            long liquidityUnits = Math.min(book.topBuyAmount(), book.topSellAmount());
            if (liquidityUnits * buyOrderPrice < MIN_LIQUIDITY_COINS) continue;

            double marginPerUnit = sellOfferPrice * taxMultiplier - buyOrderPrice;
            if (marginPerUnit <= 0) continue;
            double marginPercent = marginPerUnit / buyOrderPrice * 100.0;

            opportunities.add(new Opportunity(entry.getKey(), buyOrderPrice, sellOfferPrice,
                    marginPerUnit, marginPercent, liquidityUnits));
        }

        opportunities.sort(Comparator.comparingDouble(Opportunity::marginPercent).reversed());
        return opportunities.subList(0, Math.min(limit, opportunities.size()));
    }
}

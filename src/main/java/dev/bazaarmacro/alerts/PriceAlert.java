package dev.bazaarmacro.alerts;

import dev.bazaarmacro.flipper.FlipperSide;

/**
 * A one-shot price alert: fires once when the live order-book price for {@code watchSide}
 * crosses {@code targetPrice} in the direction implied by the side - buy orders alert on a
 * fall to/below the target (matching "I want to buy once it's cheap enough"), sell offers on a
 * rise to/above it ("I want to sell once it's worth enough") - then removes itself.
 *
 * <p>{@code targetPrice} is resolved from the user's expression once at creation time (which
 * may reference the live order/instant price then via {@code order}/{@code insta}), not
 * re-evaluated on every poll - a "10% above the current price" alert should mean a fixed target,
 * not a moving one that chases the market forever.
 */
public record PriceAlert(String itemTag, String searchTerm, FlipperSide watchSide, double targetPrice) {

    public boolean isCrossed(double currentOrderPrice) {
        return watchSide == FlipperSide.BUY_ORDER
                ? currentOrderPrice <= targetPrice
                : currentOrderPrice >= targetPrice;
    }
}

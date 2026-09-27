package dev.bazaarmacro.flipper;

public enum FlipperSide {
    BUY_ORDER, SELL_OFFER;

    public static FlipperSide parse(String text) {
        String normalized = text.trim().toLowerCase();
        return switch (normalized) {
            case "buy", "buyorder", "buy_order", "buy-order" -> BUY_ORDER;
            case "sell", "selloffer", "sell_offer", "sell-offer" -> SELL_OFFER;
            default -> throw new IllegalArgumentException("Expected \"buy-order\" or \"sell-offer\", got \"" + text + "\"");
        };
    }
}

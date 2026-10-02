package dev.bazaarmacro.core;

/** Which side of the book an order sits on. */
public enum Side {
    /** A standing offer to buy at or below a price - fills with items. */
    BUY,
    /** A standing offer to sell at or above a price - fills with coins. */
    SELL
}

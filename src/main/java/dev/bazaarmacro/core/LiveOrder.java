package dev.bazaarmacro.core;

/**
 * One order as Hypixel's manage-orders menu actually shows it.
 *
 * @param slotIndex where it sits right now. Valid only until the menu is reopened - orders move, so
 *                  this is for clicking during the current observation and must never be remembered
 *                  across polls.
 * @param side      read from the item's name prefix ("BUY "/"SELL ").
 * @param itemName  the display name, prefix stripped. This is the name Hypixel also uses in chat
 *                  confirmations - never the product tag.
 * @param pricePerUnit the order's own stated price. Ground truth: the "Top Order" preset sets the
 *                  real price server-side at click time, so any price computed before placing is
 *                  only an estimate until this is read back.
 * @param amount    quantity on the order, or -1 if the line was absent.
 */
public record LiveOrder(int slotIndex, Side side, String itemName, double pricePerUnit, long amount) {
}

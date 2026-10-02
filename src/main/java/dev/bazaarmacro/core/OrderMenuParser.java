package dev.bazaarmacro.core;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one slot of Hypixel's manage-orders menu into a {@link LiveOrder}, or decides it isn't an
 * order at all.
 *
 * <p>Pure: takes a name and lore lines, returns data. That is what lets it be tested against the
 * exact strings captured from the real game (see {@code OrderMenuParserTest}) instead of only ever
 * being exercised by running the mod with real coins at stake.
 *
 * <p>Two confirmed facts shape this, both from real captured menu dumps:
 * <ul>
 *   <li>An order names itself {@code "SELL Jolly Pink Rock"} / {@code "BUY Legion I"} and states
 *       its own price and quantity in lore. Nothing needs to be inferred from slot position, which
 *       is what makes any number of concurrent orders tractable.</li>
 *   <li>The menu's decorative filler panes are <em>non-empty items with blank names</em>. "Is this
 *       slot populated" is therefore not a valid test for "is there an order here" - an entry has
 *       to actually look like an order, which is why a parseable price line is required and not
 *       just the name prefix.</li>
 * </ul>
 */
public final class OrderMenuParser {
    private static final String BUY_PREFIX = "BUY ";
    private static final String SELL_PREFIX = "SELL ";

    private static final Pattern PRICE_PER_UNIT = Pattern.compile("(?i)Price per unit:\\s*([\\d,.]+)\\s*coins");
    /**
     * Sell offers say "Offer amount", buy orders say "Order amount". Only the sell wording is
     * confirmed from a real dump; accepting either means a wrong guess about the buy wording costs
     * nothing, since quantity is reporting-only and the order still parses without it.
     */
    private static final Pattern AMOUNT = Pattern.compile("(?i)(?:Offer|Order) amount:\\s*([\\d,]+)x");

    private OrderMenuParser() {
    }

    /**
     * @param slotIndex the slot these contents came from, carried through onto the result.
     * @param name      the slot item's display name, already colour-stripped.
     * @param lore      the slot item's lore lines, already colour-stripped.
     * @return the order, or empty if this slot is a filler pane, a control button, or anything else.
     */
    public static Optional<LiveOrder> parse(int slotIndex, String name, List<String> lore) {
        if (name == null || name.isBlank()) return Optional.empty();
        String trimmed = name.trim();

        Side side;
        String itemName;
        if (trimmed.startsWith(BUY_PREFIX)) {
            side = Side.BUY;
            itemName = trimmed.substring(BUY_PREFIX.length()).trim();
        } else if (trimmed.startsWith(SELL_PREFIX)) {
            side = Side.SELL;
            itemName = trimmed.substring(SELL_PREFIX.length()).trim();
        } else {
            return Optional.empty();
        }
        if (itemName.isEmpty()) return Optional.empty();

        double price = -1;
        long amount = -1;
        if (lore != null) {
            for (String line : lore) {
                if (line == null) continue;
                if (price < 0) {
                    Matcher m = PRICE_PER_UNIT.matcher(line);
                    if (m.find()) price = parseNumber(m.group(1));
                }
                if (amount < 0) {
                    Matcher m = AMOUNT.matcher(line);
                    if (m.find()) amount = (long) parseNumber(m.group(1));
                }
            }
        }
        // No price means this isn't a real order however much the name looks like one.
        if (price <= 0) return Optional.empty();

        return Optional.of(new LiveOrder(slotIndex, side, itemName, price, amount));
    }

    private static double parseNumber(String raw) {
        try {
            return Double.parseDouble(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

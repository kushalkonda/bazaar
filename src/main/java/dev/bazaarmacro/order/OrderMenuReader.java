package dev.bazaarmacro.order;

import dev.bazaarmacro.flipper.BazaarOrderFlow;
import dev.bazaarmacro.flipper.FlipperCoordinates;
import dev.bazaarmacro.flipper.FlipperSide;
import dev.bazaarmacro.macro.ExecutionContext;
import dev.bazaarmacro.macro.MacroDefinition;
import dev.bazaarmacro.macro.MacroExecutor;
import dev.bazaarmacro.macro.MacroStep;
import dev.bazaarmacro.util.ClientUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Hypixel's manage-orders (F6) menu and reports every order actually open right now.
 *
 * <p><b>Why this exists, and why it reads instead of assuming.</b> Everything else in this mod used
 * to track orders by <em>slot position</em> - "my sell offer is the item at B2". That works for
 * exactly one order per side and nothing more. {@code FlipperCoordinates} carried a comment
 * claiming a second concurrent order would sit one column over at C2, a third at D2, and so on -
 * but that was an inference, never observed: across 154 archived game logs there is exactly one
 * captured order-menu dump and it contained a single order. Building multi-order support on that
 * guess would be building the whole feature on something nobody ever checked.
 *
 * <p>So this doesn't use position at all. It scans every slot in the open menu and decides what
 * each one is from its own contents, which are confirmed from that real dump:
 * <pre>
 *   SELL Jolly Pink Rock          &lt;- name is "&lt;SIDE&gt; &lt;item&gt;", giving side and item
 *   Worth 120.8k coins
 *   Offer amount: 1x              &lt;- quantity
 *   Price per unit: 122,354.1 coins   &lt;- price, the unique fingerprint
 *   By: [VIP] white_toes_yum
 *   Click to view options!
 * </pre>
 *
 * <p>That makes the result correct no matter how Hypixel arranges orders, how many there are, or
 * whether cancelling one shifts the rest along - none of which this has to know. It also fixes a
 * real latent bug in the old position-based check: that asked only "is this slot non-empty", and
 * the same dump shows the menu's decorative filler panes are non-empty items, so a border pane in
 * the wrong place would have read as a live order. An entry here has to actually look like an
 * order to count as one.
 */
public final class OrderMenuReader {
    private static final Pattern PRICE_PER_UNIT = Pattern.compile("(?i)Price per unit:\\s*([\\d,.]+) coins");
    /** Sell offers say "Offer amount", buy orders say "Order amount" - accepting either (and requiring only one of them) avoids hinging on wording only confirmed for the sell side. */
    private static final Pattern AMOUNT = Pattern.compile("(?i)(?:Offer|Order) amount:\\s*([\\d,]+)x");

    private OrderMenuReader() {
    }

    /**
     * One order as it actually appears in the menu right now.
     *
     * @param slotIndex  where it currently sits - valid only until the menu is reopened, so use it
     *                   to click <em>this</em> poll and never remember it across polls.
     * @param itemName   the display name with the BUY/SELL prefix stripped.
     * @param pricePerUnit the order's own price - the field used to match it to a tracked order.
     * @param amount     quantity on the order, or -1 if the line wasn't present.
     */
    public record MenuOrder(int slotIndex, FlipperSide side, String itemName, double pricePerUnit, long amount) {
    }

    /** Opens the F6 menu. Returns false if it couldn't be opened this tick; the caller should just retry next poll rather than concluding anything. */
    public static boolean openMenu(BooleanSupplier cancelled) {
        FlipperCoordinates coords = FlipperCoordinates.get();
        MacroDefinition macro = new MacroDefinition("OrderMenuOpen");
        macro.steps.add(MacroStep.command(coords.claimOrdersCommand));
        macro.steps.add(MacroStep.waitForScreen("Bazaar", 10_000));
        macro.steps.add(MacroStep.waitMs(400));
        macro.steps.add(MacroStep.clickSlot(coords.claimOrdersMenu, 0, "PICKUP"));
        BazaarOrderFlow.addTransitionWait(macro);
        // Extra settle: the order list's dynamic contents (fill status, claimable amount) render
        // later than the screen frame does, and reading too early produced a false "no orders" on a
        // genuinely-open order.
        macro.steps.add(MacroStep.waitMs(900));
        return MacroExecutor.runBlocking(macro, new ExecutionContext(), cancelled);
    }

    /**
     * Every order visible in the currently open menu. Call {@link #openMenu} first (or have the
     * menu already open). An empty list means "the menu showed no orders" - which is only
     * meaningful if the menu was genuinely open, so callers treat a failed open as "unknown"
     * rather than "none".
     */
    public static List<MenuOrder> readOpenOrders() {
        List<MenuOrder> found = new ArrayList<>();
        int slots = ClientUtils.openScreenSlotCount();
        for (int i = 0; i < slots; i++) {
            MenuOrder order = parseSlot(i);
            if (order != null) found.add(order);
        }
        return found;
    }

    /** @return the order at this slot, or null if the slot isn't one (empty, a filler pane, a menu button). */
    private static MenuOrder parseSlot(int slotIndex) {
        String name = ClientUtils.getOpenSlotName(slotIndex);
        if (name.isBlank()) return null;

        FlipperSide side;
        String itemName;
        if (name.startsWith("BUY ")) {
            side = FlipperSide.BUY_ORDER;
            itemName = name.substring(4).trim();
        } else if (name.startsWith("SELL ")) {
            side = FlipperSide.SELL_OFFER;
            itemName = name.substring(5).trim();
        } else {
            return null; // a control button, a filler pane, or anything else that isn't an order
        }

        // Require a real price line too: the name prefix alone could in principle appear on
        // something else, and the price is what every caller actually identifies the order by.
        double price = -1;
        long amount = -1;
        for (String line : ClientUtils.getOpenSlotLore(slotIndex)) {
            Matcher priceMatch = PRICE_PER_UNIT.matcher(line);
            if (price < 0 && priceMatch.find()) {
                price = parseNumber(priceMatch.group(1));
            }
            Matcher amountMatch = AMOUNT.matcher(line);
            if (amount < 0 && amountMatch.find()) {
                amount = (long) parseNumber(amountMatch.group(1));
            }
        }
        if (price <= 0) return null;

        return new MenuOrder(slotIndex, side, itemName, price, amount);
    }

    private static double parseNumber(String raw) {
        try {
            return Double.parseDouble(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Picks the menu order that best matches a tracked order, by price within {@code tolerancePercent}.
     *
     * <p>Returns null when nothing matches <em>and</em> when more than one candidate matches: two
     * orders on the same side at indistinguishable prices genuinely cannot be told apart from the
     * menu alone, and guessing between them risks claiming or cancelling the wrong one. Both
     * engines treat that as "can't tell this tick" and leave the orders alone rather than act.
     */
    public static MenuOrder match(List<MenuOrder> orders, FlipperSide side, String itemName,
                                   double price, double tolerancePercent) {
        MenuOrder best = null;
        for (MenuOrder order : orders) {
            if (order.side() != side) continue;
            if (itemName != null && !itemName.equalsIgnoreCase(order.itemName())) continue;
            if (!withinTolerance(order.pricePerUnit(), price, tolerancePercent)) continue;
            if (best != null) return null; // ambiguous - refuse rather than pick one
            best = order;
        }
        return best;
    }

    public static boolean withinTolerance(double actual, double expected, double tolerancePercent) {
        if (expected <= 0) return false;
        return Math.abs(actual - expected) / expected * 100.0 <= tolerancePercent;
    }
}

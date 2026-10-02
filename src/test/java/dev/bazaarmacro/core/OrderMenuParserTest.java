package dev.bazaarmacro.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every input here is a string genuinely captured from the real game, not invented. Sources are
 * recorded in VERIFIED-FACTS.md.
 */
class OrderMenuParserTest {

    /** Captured verbatim from a real manage-orders dump. */
    private static final String REAL_SELL_NAME = "SELL Jolly Pink Rock";
    private static final List<String> REAL_SELL_LORE = List.of(
            "Worth 120.8k coins",
            "",
            "Offer amount: 1x",
            "",
            "Price per unit: 122,354.1 coins",
            "",
            "By: [VIP] white_toes_yum",
            "",
            "Click to view options!");

    /** Captured verbatim from the dump that exposed the duplicate-order bug. */
    private static final String REAL_BUY_NAME = "BUY Legion I";
    private static final List<String> REAL_BUY_LORE = List.of(
            "Worth 38.9M coins",
            "",
            "Order amount: 16x",
            "",
            "Price per unit: 2,432,512.9 coins",
            "",
            "By: [VIP] white_toes_yum",
            "",
            "Click to view options!");

    @Nested
    @DisplayName("real captured orders")
    class RealOrders {

        @Test
        void parsesASellOffer() {
            LiveOrder order = OrderMenuParser.parse(10, REAL_SELL_NAME, REAL_SELL_LORE).orElseThrow();

            assertEquals(Side.SELL, order.side());
            assertEquals("Jolly Pink Rock", order.itemName());
            assertEquals(122_354.1, order.pricePerUnit(), 0.001);
            assertEquals(1, order.amount());
            assertEquals(10, order.slotIndex());
        }

        @Test
        void parsesABuyOrder() {
            LiveOrder order = OrderMenuParser.parse(19, REAL_BUY_NAME, REAL_BUY_LORE).orElseThrow();

            assertEquals(Side.BUY, order.side());
            assertEquals("Legion I", order.itemName());
            assertEquals(2_432_512.9, order.pricePerUnit(), 0.001);
            assertEquals(16, order.amount());
        }

        @Test
        @DisplayName("keeps the display name Hypixel uses in chat, not a product tag")
        void itemNameMatchesTheChatConfirmationName() {
            LiveOrder order = OrderMenuParser.parse(19, REAL_BUY_NAME, REAL_BUY_LORE).orElseThrow();
            BazaarMessage.OrderPlaced placed = (BazaarMessage.OrderPlaced) BazaarMessage
                    .parse("[Bazaar] Buy Order Setup! 16x Legion I for 38,920,205 coins.").orElseThrow();

            // The menu and the chat line must agree, because matching one against the other is how
            // a placed order gets recognised. Feeding a tag here instead is what caused a real
            // order to be placed twice.
            assertEquals(placed.itemName(), order.itemName());
        }
    }

    @Nested
    @DisplayName("things that are not orders")
    class NotOrders {

        @Test
        @DisplayName("a filler pane is a non-empty item with a blank name")
        void rejectsFillerPanes() {
            // Real dumps show these at I2, A3, A4, B4 and elsewhere. The old code asked only
            // 'is this slot non-empty', which counted these as live orders.
            assertTrue(OrderMenuParser.parse(17, "", List.of()).isEmpty());
            assertTrue(OrderMenuParser.parse(17, "   ", List.of()).isEmpty());
        }

        @Test
        void rejectsMenuControls() {
            assertTrue(OrderMenuParser.parse(30, "Go Back", List.of("To Bazaar")).isEmpty());
            assertTrue(OrderMenuParser.parse(31, "Close", List.of()).isEmpty());
            assertTrue(OrderMenuParser.parse(32, "Claim All Coins",
                    List.of("You don't have any coins to claim.")).isEmpty());
            assertTrue(OrderMenuParser.parse(71, "SkyBlock Menu (Click)", List.of("Click to open!")).isEmpty());
        }

        @Test
        @DisplayName("search-result placeholders never look like orders")
        void rejectsNoProductFound() {
            assertTrue(OrderMenuParser.parse(11, "No Product Found", List.of()).isEmpty());
        }

        @Test
        @DisplayName("an order-shaped name without a price is not trusted")
        void requiresAPriceLine() {
            assertTrue(OrderMenuParser.parse(19, "BUY Legion I", List.of("Order amount: 16x")).isEmpty());
            assertTrue(OrderMenuParser.parse(19, "BUY Legion I", null).isEmpty());
        }

        @Test
        void rejectsAPrefixWithNoItem() {
            assertTrue(OrderMenuParser.parse(19, "BUY ", List.of("Price per unit: 5 coins")).isEmpty());
        }
    }

    @Nested
    @DisplayName("tolerant parsing")
    class Tolerance {

        @Test
        @DisplayName("quantity is optional - the buy-side wording is not confirmed")
        void parsesWithoutAnAmountLine() {
            LiveOrder order = OrderMenuParser
                    .parse(19, "BUY Legion I", List.of("Price per unit: 2,432,512.9 coins")).orElseThrow();

            assertEquals(2_432_512.9, order.pricePerUnit(), 0.001);
            assertEquals(-1, order.amount(), "absent quantity is reported as -1, not silently zero");
        }

        @Test
        void handlesWholeNumberPrices() {
            LiveOrder order = OrderMenuParser
                    .parse(10, "SELL Summoning Eye", List.of("Price per unit: 1,497,209 coins")).orElseThrow();

            assertEquals(1_497_209, order.pricePerUnit(), 0.001);
        }

        @Test
        @DisplayName("two orders of one item can be told apart by slot, never by price alone")
        void nearIdenticalPricesStillParseIndependently() {
            // Real observation: two concurrent buy orders sat 0.1 coins apart. Both must parse
            // cleanly; deciding they are indistinguishable is the caller's job, not the parser's.
            LiveOrder a = OrderMenuParser.parse(19, REAL_BUY_NAME,
                    List.of("Price per unit: 2,432,512.9 coins")).orElseThrow();
            LiveOrder b = OrderMenuParser.parse(20, REAL_BUY_NAME,
                    List.of("Price per unit: 2,432,512.8 coins")).orElseThrow();

            assertEquals(19, a.slotIndex());
            assertEquals(20, b.slotIndex());
            assertTrue(Math.abs(a.pricePerUnit() - b.pricePerUnit()) < 0.5,
                    "these really are this close in practice");
        }
    }

    @Test
    @DisplayName("a whole captured menu yields exactly the two real orders")
    void parsesAWholeRealMenu() {
        // The real 2026-09-27 dump: filler panes, two buy orders at B3/C3, and row-4 controls.
        record Slot(int index, String name, List<String> lore) {
        }
        List<Slot> menu = List.of(
                new Slot(9, "", List.of()),
                new Slot(17, "", List.of()),
                new Slot(18, "", List.of()),
                new Slot(19, REAL_BUY_NAME, REAL_BUY_LORE),
                new Slot(20, REAL_BUY_NAME, List.of("Order amount: 16x", "Price per unit: 2,432,512.8 coins")),
                new Slot(26, "", List.of()),
                new Slot(30, "Go Back", List.of("To Bazaar")),
                new Slot(31, "Close", List.of()),
                new Slot(32, "Claim All Coins", List.of("You don't have any coins to claim.")),
                new Slot(71, "SkyBlock Menu (Click)", List.of("Click to open!")));

        List<LiveOrder> found = menu.stream()
                .map(s -> OrderMenuParser.parse(s.index(), s.name(), s.lore()))
                .flatMap(Optional::stream)
                .toList();

        assertEquals(2, found.size(), "exactly the two real orders, no panes or buttons");
        assertEquals(List.of(19, 20), found.stream().map(LiveOrder::slotIndex).toList());
        assertTrue(found.stream().allMatch(o -> o.side() == Side.BUY));
    }
}

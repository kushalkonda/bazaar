package dev.bazaarmacro.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every line here was captured verbatim from a real game log - see VERIFIED-FACTS.md. */
class BazaarMessageTest {

    @Test
    void parsesARealBuyOrderConfirmation() {
        var msg = assertInstanceOf(BazaarMessage.OrderPlaced.class,
                BazaarMessage.parse("[Bazaar] Buy Order Setup! 16x Legion I for 38,920,205 coins.").orElseThrow());

        assertEquals(Side.BUY, msg.side());
        assertEquals(16, msg.quantity());
        assertEquals("Legion I", msg.itemName());
        assertEquals(38_920_205L, msg.totalCoins());
        assertEquals(2_432_512.8, msg.pricePerUnit(), 0.1);
    }

    @Test
    void parsesARealSellOfferConfirmation() {
        var msg = assertInstanceOf(BazaarMessage.OrderPlaced.class,
                BazaarMessage.parse("[Bazaar] Sell Offer Setup! 6x Scuba V for 1,234,567 coins.").orElseThrow());

        assertEquals(Side.SELL, msg.side());
        assertEquals(6, msg.quantity());
        assertEquals("Scuba V", msg.itemName());
    }

    @Test
    @DisplayName("the implied per-unit price matches what the menu shows for the same order")
    void impliedPriceAgreesWithTheMenu() {
        var placed = assertInstanceOf(BazaarMessage.OrderPlaced.class,
                BazaarMessage.parse("[Bazaar] Buy Order Setup! 16x Legion I for 38,920,205 coins.").orElseThrow());

        // The menu showed this same order at 2,432,512.9. Chat implies 2,432,512.8125 - they agree
        // to well within any sane tolerance, which is what makes price usable to tie a confirmation
        // to an order when the display name isn't known in advance.
        assertEquals(2_432_512.9, placed.pricePerUnit(), 1.0);
    }

    @Test
    @DisplayName("confirmations carry a display name, never a product tag")
    void itemNameIsNeverATag() {
        var placed = assertInstanceOf(BazaarMessage.OrderPlaced.class,
                BazaarMessage.parse("[Bazaar] Buy Order Setup! 16x Legion I for 38,920,205 coins.").orElseThrow());

        // Matching this against ENCHANTMENT_ULTIMATE_LEGION_1 can never succeed. Doing exactly
        // that made a successful order read as failed and get placed a second time.
        assertNotEquals("ENCHANTMENT_ULTIMATE_LEGION_1", placed.itemName());
        assertEquals("Legion I", placed.itemName());
    }

    @Test
    void parsesARealCancellation() {
        var msg = assertInstanceOf(BazaarMessage.OrderCancelled.class, BazaarMessage.parse(
                "[Bazaar] Cancelled! Refunded 38,920,206 coins from cancelling Buy Order!").orElseThrow());

        assertEquals(Side.BUY, msg.side());
        assertEquals(38_920_206L, msg.refundedCoins());
    }

    @Test
    void parsesACancelRejection() {
        assertInstanceOf(BazaarMessage.CancelRejected.class,
                BazaarMessage.parse("[Bazaar] You have goods to claim on this order!").orElseThrow());
    }

    @Test
    void parsesALobbyKick() {
        assertInstanceOf(BazaarMessage.KickedToLobby.class, BazaarMessage.parse(
                "A kick occurred in your connection, so you were put in the SkyBlock lobby!").orElseThrow());
    }

    @Test
    @DisplayName("ignores the Bazaar's other chatter")
    void ignoresUnrelatedLines() {
        // All real lines from the same session - none should produce an event.
        assertTrue(BazaarMessage.parse("[Bazaar] Putting goods in escrow...").isEmpty());
        assertTrue(BazaarMessage.parse("[Bazaar] Submitting buy order...").isEmpty());
        assertTrue(BazaarMessage.parse("[Bazaar] Cancelling order...").isEmpty());
        assertTrue(BazaarMessage.parse("[Bazaar] Executing instant buy...").isEmpty());
        assertTrue(BazaarMessage.parse("[Bazaar] Bought 1x Booster Cookie for 12,556,549 coins!").isEmpty());
        assertTrue(BazaarMessage.parse("").isEmpty());
        assertTrue(BazaarMessage.parse(null).isEmpty());
    }

    @Test
    @DisplayName("an instant buy is not mistaken for an order being placed")
    void instantBuyIsNotAnOrder() {
        // "Bought 1x ... for ... coins!" is shaped like a confirmation but means something else
        // entirely - treating it as a placed order would credit an order that doesn't exist.
        assertTrue(BazaarMessage.parse("[Bazaar] Bought 1x Booster Cookie for 12,556,549 coins!").isEmpty());
    }

    @Test
    void handlesItemNamesContainingSpacesAndNumerals() {
        var msg = assertInstanceOf(BazaarMessage.OrderPlaced.class, BazaarMessage.parse(
                "[Bazaar] Sell Offer Setup! 1x Jolly Pink Rock for 122,354 coins.").orElseThrow());

        assertEquals("Jolly Pink Rock", msg.itemName());
        assertEquals(1, msg.quantity());
    }
}

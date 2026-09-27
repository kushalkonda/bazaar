package dev.bazaarmacro.flipper;

import dev.bazaarmacro.util.ClientUtils;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Listens for Hypixel's own real-time order confirmations - {@code "[Bazaar] Buy Order Setup! Xx
 * <item> for Y coins."} and {@code "[Bazaar] Sell Offer Setup! Xx <item> for Y coins."} - and
 * queues the exact confirmed quantity for {@link FlipperEngine} to use directly, instead of
 * inferring what got placed from an inventory or purse delta.
 *
 * <p>Added after a real, confirmed failure: {@code placeSellOrder}'s inventory-delta verification
 * (itself added to catch a *different* false-failure case) measured a genuinely successful 33x
 * sell offer - Hypixel's own chat confirmed "Sell Offer Setup! 33x...for 3,987,198 coins." - as
 * zero listed, because the post-macro inventory read can still be momentarily stale immediately
 * after the screen closes. Orphaning that order left the engine with nothing it believed was
 * sellable, going silent on the sell side for the rest of the session. Hypixel's own setup message
 * is exact and requires no inference or settle-wait guessing at all, so it's preferred whenever it
 * arrives; the inventory/purse-delta checks in {@link FlipperEngine} remain only as a fallback for
 * if this message's wording ever changes.
 */
public final class OrderSetupWatcher {
    private static final Pattern BUY_SETUP_PATTERN =
            Pattern.compile("(?i)\\[Bazaar] Buy Order Setup! ([\\d,]+)x (.+?) for ([\\d,]+) coins\\.");
    private static final Pattern SELL_SETUP_PATTERN =
            Pattern.compile("(?i)\\[Bazaar] Sell Offer Setup! ([\\d,]+)x (.+?) for ([\\d,]+) coins\\.");

    private record SetupEvent(long quantity, String itemDisplayName, double totalCoins) {
    }

    /**
     * Result of a {@link #drainMatchingBuyByPrice}/{@link #drainMatchingSellByPrice} call - unlike
     * the name-matching methods (which only ever confirm quantity, since {@link FlipperEngine}
     * already knows the exact name it searched for), this exists for callers like
     * {@code BookFlipperEngine} that can't reliably know a book's real Hypixel display name in
     * advance (see the Book Flipper plan) and instead confirm identity by cross-checking this
     * message's own implied per-unit price against what they expected to place.
     */
    public record PriceConfirmed(long quantity, double pricePerUnit, String itemDisplayName) {
    }

    private static final ConcurrentLinkedQueue<SetupEvent> PENDING_BUY = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<SetupEvent> PENDING_SELL = new ConcurrentLinkedQueue<>();

    private OrderSetupWatcher() {
    }

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String text = ClientUtils.stripColors(message.getString());

            Matcher buyMatch = BUY_SETUP_PATTERN.matcher(text);
            if (buyMatch.find()) {
                queue(PENDING_BUY, buyMatch);
                return;
            }
            Matcher sellMatch = SELL_SETUP_PATTERN.matcher(text);
            if (sellMatch.find()) {
                queue(PENDING_SELL, sellMatch);
            }
        });
    }

    private static void queue(ConcurrentLinkedQueue<SetupEvent> target, Matcher matcher) {
        try {
            long qty = Long.parseLong(matcher.group(1).replace(",", ""));
            double totalCoins = Double.parseDouble(matcher.group(3).replace(",", ""));
            target.add(new SetupEvent(qty, matcher.group(2).trim(), totalCoins));
        } catch (NumberFormatException ignored) {
        }
    }

    /** Drains queued buy-order confirmations, returning the last one matching {@code itemDisplayName}, or {@code null} if none matched. */
    public static Long drainMatchingBuy(String itemDisplayName) {
        return drain(PENDING_BUY, itemDisplayName);
    }

    /** Drains queued sell-offer confirmations, returning the last one matching {@code itemDisplayName}, or {@code null} if none matched. */
    public static Long drainMatchingSell(String itemDisplayName) {
        return drain(PENDING_SELL, itemDisplayName);
    }

    private static Long drain(ConcurrentLinkedQueue<SetupEvent> queue, String itemDisplayName) {
        Long result = null;
        SetupEvent event;
        while ((event = queue.poll()) != null) {
            if (itemDisplayName != null && event.itemDisplayName().equalsIgnoreCase(itemDisplayName)) {
                result = event.quantity();
            }
        }
        return result;
    }

    /** Drains queued buy-order confirmations, returning the last one whose implied per-unit price (total/qty) is within {@code tolerancePercent} of {@code expectedPricePerUnit}, or {@code null} if none matched. */
    public static PriceConfirmed drainMatchingBuyByPrice(double expectedPricePerUnit, double tolerancePercent) {
        return drainByPrice(PENDING_BUY, expectedPricePerUnit, tolerancePercent);
    }

    /** Sell-offer counterpart of {@link #drainMatchingBuyByPrice}. */
    public static PriceConfirmed drainMatchingSellByPrice(double expectedPricePerUnit, double tolerancePercent) {
        return drainByPrice(PENDING_SELL, expectedPricePerUnit, tolerancePercent);
    }

    private static PriceConfirmed drainByPrice(ConcurrentLinkedQueue<SetupEvent> queue, double expectedPricePerUnit, double tolerancePercent) {
        PriceConfirmed result = null;
        SetupEvent event;
        while ((event = queue.poll()) != null) {
            if (event.quantity() <= 0) continue;
            double pricePerUnit = event.totalCoins() / event.quantity();
            double diffPercent = Math.abs(pricePerUnit - expectedPricePerUnit) / expectedPricePerUnit * 100.0;
            if (diffPercent <= tolerancePercent) {
                result = new PriceConfirmed(event.quantity(), pricePerUnit, event.itemDisplayName());
            }
        }
        return result;
    }
}

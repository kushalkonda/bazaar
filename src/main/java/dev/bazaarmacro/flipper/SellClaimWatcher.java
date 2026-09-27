package dev.bazaarmacro.flipper;

import dev.bazaarmacro.util.ClientUtils;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Listens for Hypixel's own real-time claim confirmation - {@code "[Bazaar] Claimed X coins from
 * selling Yx <item> at Z each!"} - and queues the exact claimed amount for {@link FlipperEngine}
 * to attribute to its current sell offer, instead of inferring the amount from a purse delta.
 *
 * <p>Added after a real, confirmed failure: even a purse delta bracketed tightly around this
 * engine's own claim click (immune, in theory, to *concurrent* buy-side spending, since ticks run
 * sequentially on one thread) still isn't immune to an *asynchronous* server-side event landing
 * inside that same window - a real session showed a claim that Hypixel itself confirmed paid out
 * 11,543 coins get measured by the purse-delta bracket as only 9,082, a shortfall that lined up
 * almost exactly with a separate buy-order attempt (2x, rejected for insufficient funds moments
 * earlier) whose escrow refund landed inside the claim's own before/after purse reads. Hypixel's
 * chat line requires no inference at all, so it's preferred whenever available; the purse delta
 * stays in {@link FlipperEngine} only as a fallback for if this message's wording ever changes.
 */
public final class SellClaimWatcher {
    private static final Pattern CLAIM_PATTERN =
            Pattern.compile("(?i)\\[Bazaar] Claimed ([\\d,]+) coins from selling (\\d+)x (.+?) at ([\\d.,]+) each!");

    private record ClaimedProceeds(long coins, String itemDisplayName, long quantity, double pricePerUnit) {
    }

    private static final ConcurrentLinkedQueue<ClaimedProceeds> PENDING = new ConcurrentLinkedQueue<>();

    private SellClaimWatcher() {
    }

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String text = ClientUtils.stripColors(message.getString());
            Matcher m = CLAIM_PATTERN.matcher(text);
            if (m.find()) {
                try {
                    long coins = Long.parseLong(m.group(1).replace(",", ""));
                    long qty = Long.parseLong(m.group(2));
                    double pricePerUnit = Double.parseDouble(m.group(4).replace(",", ""));
                    PENDING.add(new ClaimedProceeds(coins, m.group(3).trim(), qty, pricePerUnit));
                } catch (NumberFormatException ignored) {
                }
            }
        });
    }

    /**
     * Drains every claim confirmation queued since the last call, returning the total that
     * matches {@code itemDisplayName} (case-insensitive). A non-matching entry (a different item
     * - e.g. the user manually selling something else mid-session) is discarded, not attributed;
     * it's never re-queued, so a mismatched name shouldn't be treated as evidence of anything.
     */
    public static long drainMatching(String itemDisplayName) {
        long total = 0;
        ClaimedProceeds claim;
        while ((claim = PENDING.poll()) != null) {
            if (itemDisplayName != null && claim.itemDisplayName().equalsIgnoreCase(itemDisplayName)) {
                total += claim.coins();
            }
        }
        return total;
    }

    /**
     * Price-aware counterpart of {@link #drainMatching}, for callers (e.g. {@code
     * BookFlipperEngine}) that can't reliably know an item's real Hypixel display name in advance
     * - see the Book Flipper plan. Sums every claim whose own "at Z each" price is within
     * {@code tolerancePercent} of {@code expectedPricePerUnit} instead of matching by name.
     */
    public static long drainMatchingByPrice(double expectedPricePerUnit, double tolerancePercent) {
        long total = 0;
        ClaimedProceeds claim;
        while ((claim = PENDING.poll()) != null) {
            double diffPercent = Math.abs(claim.pricePerUnit() - expectedPricePerUnit) / expectedPricePerUnit * 100.0;
            if (diffPercent <= tolerancePercent) {
                total += claim.coins();
            }
        }
        return total;
    }
}

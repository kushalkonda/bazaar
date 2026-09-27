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

    /** How long an unmatched claim stays available to a later drain before being dropped as stale - see {@link #drainMatching}. */
    private static final long EVENT_TTL_MS = 60_000;

    private record ClaimedProceeds(long coins, String itemDisplayName, long quantity, double pricePerUnit,
                                    long receivedAtMs) {
        boolean isExpired(long now) {
            return now - receivedAtMs > EVENT_TTL_MS;
        }
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
                    PENDING.add(new ClaimedProceeds(coins, m.group(3).trim(), qty, pricePerUnit,
                            System.currentTimeMillis()));
                } catch (NumberFormatException ignored) {
                }
            }
        });
    }

    /**
     * Returns the total of every queued claim confirmation matching {@code itemDisplayName}
     * (case-insensitive), consuming only those.
     *
     * <p>Anything that doesn't match is left queued rather than discarded. An earlier version
     * polled the queue empty regardless, which meant whichever matcher ran first destroyed the
     * evidence any later one needed - the same defect that, in {@code OrderSetupWatcher}, made the
     * buy engine believe a successful order had failed and place it a second time. Only genuinely
     * stale entries ({@link #EVENT_TTL_MS}) are dropped, so nothing accumulates forever.
     */
    public static long drainMatching(String itemDisplayName) {
        long now = System.currentTimeMillis();
        long total = 0;
        for (java.util.Iterator<ClaimedProceeds> it = PENDING.iterator(); it.hasNext(); ) {
            ClaimedProceeds claim = it.next();
            if (claim.isExpired(now)) {
                it.remove();
                continue;
            }
            if (itemDisplayName != null && claim.itemDisplayName().equalsIgnoreCase(itemDisplayName)) {
                total += claim.coins();
                it.remove();
            }
        }
        return total;
    }

    /**
     * Price-aware counterpart of {@link #drainMatching}, for callers (e.g. {@code
     * the order engines) that can't reliably know an item's real Hypixel display name in advance
     * - see the Book Flipper plan. Sums every claim whose own "at Z each" price is within
     * {@code tolerancePercent} of {@code expectedPricePerUnit} instead of matching by name.
     */
    public static long drainMatchingByPrice(double expectedPricePerUnit, double tolerancePercent) {
        long now = System.currentTimeMillis();
        long total = 0;
        for (java.util.Iterator<ClaimedProceeds> it = PENDING.iterator(); it.hasNext(); ) {
            ClaimedProceeds claim = it.next();
            if (claim.isExpired(now)) {
                it.remove();
                continue;
            }
            double diffPercent = Math.abs(claim.pricePerUnit() - expectedPricePerUnit) / expectedPricePerUnit * 100.0;
            if (diffPercent <= tolerancePercent) {
                total += claim.coins();
                it.remove();
            }
        }
        return total;
    }
}

package dev.bazaarmacro.core;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hypixel's own Bazaar chat lines, parsed into typed events.
 *
 * <p>These are the <b>only</b> trustworthy source for quantities and coins. A purse delta is not
 * attributable - a talisman, a booster or a mob drop is indistinguishable from Bazaar income, and
 * crediting one as proceeds can push an order past a completion threshold and abandon it while it
 * is still genuinely open. An inventory delta is better but reads stale immediately after a screen
 * closes.
 *
 * <p>Pure, so every pattern here is tested against the exact lines captured from real game logs
 * (see {@code BazaarMessageTest}). Each one below is a line that actually occurred, not a guess at
 * Hypixel's wording.
 *
 * <p><b>These lines carry an item's display name, never its product tag.</b> "Buy Order Setup! 16x
 * Legion I" - not {@code ENCHANTMENT_ULTIMATE_LEGION_1}. Matching a confirmation against a tag can
 * never succeed; doing exactly that made a successful order look failed and placed it a second
 * time, escrowing 38.9M coins twice.
 */
public sealed interface BazaarMessage {

    /** {@code [Bazaar] Buy Order Setup! 16x Legion I for 38,920,205 coins.} */
    record OrderPlaced(Side side, long quantity, String itemName, long totalCoins) implements BazaarMessage {
        /** Per-unit price implied by the confirmation - the only identifying number available when an item's real display name isn't known in advance. */
        public double pricePerUnit() {
            return quantity <= 0 ? 0 : (double) totalCoins / quantity;
        }
    }

    /** {@code [Bazaar] Cancelled! Refunded 38,920,206 coins from cancelling Buy Order!} */
    record OrderCancelled(Side side, long refundedCoins) implements BazaarMessage {
    }

    /** Hypixel refusing to cancel an order that still has an unclaimed fill on it. Claim, then retry. */
    record CancelRejected() implements BazaarMessage {
    }

    /**
     * Being moved to the SkyBlock lobby, typically after a "Sending packets too fast!" rate-limit
     * kick. The client never leaves the server, so a disconnect check won't catch it - but every
     * Bazaar action fails until warped back.
     */
    record KickedToLobby() implements BazaarMessage {
    }

    Pattern BUY_PLACED = Pattern.compile(
            "(?i)\\[Bazaar]\\s*Buy Order Setup!\\s*([\\d,]+)x\\s+(.+?)\\s+for\\s+([\\d,]+)\\s*coins");
    Pattern SELL_PLACED = Pattern.compile(
            "(?i)\\[Bazaar]\\s*Sell Offer Setup!\\s*([\\d,]+)x\\s+(.+?)\\s+for\\s+([\\d,]+)\\s*coins");
    Pattern CANCELLED = Pattern.compile(
            "(?i)\\[Bazaar]\\s*Cancelled!\\s*Refunded\\s+([\\d,]+)\\s*coins from cancelling\\s+(Buy Order|Sell Offer)");
    Pattern CANCEL_REJECTED = Pattern.compile("(?i)\\[Bazaar].*goods to claim on this order");
    Pattern LOBBY_KICK = Pattern.compile("(?i)you were put in the SkyBlock lobby");

    /**
     * @param line a chat line with colour codes already stripped.
     * @return the typed event, or empty if this line isn't one this mod acts on.
     */
    static Optional<BazaarMessage> parse(String line) {
        if (line == null || line.isBlank()) return Optional.empty();

        Matcher m = BUY_PLACED.matcher(line);
        if (m.find()) return placed(Side.BUY, m);

        m = SELL_PLACED.matcher(line);
        if (m.find()) return placed(Side.SELL, m);

        m = CANCELLED.matcher(line);
        if (m.find()) {
            Side side = m.group(2).toLowerCase().startsWith("buy") ? Side.BUY : Side.SELL;
            return number(m.group(1)).map(coins -> new OrderCancelled(side, coins));
        }

        if (CANCEL_REJECTED.matcher(line).find()) return Optional.of(new CancelRejected());
        if (LOBBY_KICK.matcher(line).find()) return Optional.of(new KickedToLobby());

        return Optional.empty();
    }

    private static Optional<BazaarMessage> placed(Side side, Matcher m) {
        Optional<Long> qty = number(m.group(1));
        Optional<Long> total = number(m.group(3));
        if (qty.isEmpty() || total.isEmpty()) return Optional.empty();
        return Optional.of(new OrderPlaced(side, qty.get(), m.group(2).trim(), total.get()));
    }

    private static Optional<Long> number(String raw) {
        try {
            return Optional.of(Long.parseLong(raw.replace(",", "")));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}

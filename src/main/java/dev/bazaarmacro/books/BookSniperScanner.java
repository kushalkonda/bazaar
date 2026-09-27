package dev.bazaarmacro.books;

import dev.bazaarmacro.bazaar.HypixelBazaarClient;
import dev.bazaarmacro.flipper.FlipperSettings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans every Bazaar-tradeable enchant book for the "snipe the top buy order, wait for a rare
 * sale, relist at the top sell offer" opportunity {@link dev.bazaarmacro.books.BookFlipperEngine}
 * actually executes - deliberately separate from {@link dev.bazaarmacro.flipper.BazaarScanner},
 * whose margin math this reuses but whose liquidity-depth filter would exclude exactly the kind
 * of thin, rarely-traded book this scanner exists to find.
 *
 * <p>Every enchant-book product on the Bazaar carries the tag prefix {@code ENCHANTMENT_}
 * (confirmed via a real Bazaar API fetch: 773 of 2123 total products, e.g.
 * {@code ENCHANTMENT_SCUBA_5}) - that prefix is the sole filter for "is this a book."
 *
 * <p>Trade frequency comes from {@link HypixelBazaarClient.OrderBook#sellMovingWeek()} (real units
 * sold over the trailing 7 days, per Hypixel's own {@code quick_status} - confirmed present on a
 * real enchant-book product via a live fetch), divided into an hourly rate. Margin uses the same
 * outlier-resistant prices and real tax rate {@code BazaarScanner} does, so a ranked score here
 * reflects what {@code BookFlipperEngine} would actually realize, not an optimistic best case.
 */
public final class BookSniperScanner {
    private static final String TAG_PREFIX = "ENCHANTMENT_";
    private static final Pattern LEVEL_SUFFIX = Pattern.compile("_(\\d+)$");

    /** User-confirmed threshold: "100%+ margins only." */
    private static final double MIN_MARGIN_PERCENT = 100.0;
    /** User-confirmed threshold: "there should be a minimum of a 2 million coin margin" - a percentage floor alone would still admit a 100%+ margin on a cheap book not worth the order-slot. */
    private static final double MIN_MARGIN_COINS = 2_000_000.0;
    /** User-confirmed threshold: "0.5 unit/hour is fine" as the floor - rarer than this risks an order sitting dead indefinitely. */
    private static final double MIN_TRADES_PER_HOUR = 0.5;

    private BookSniperScanner() {
    }

    public record Candidate(String tag, int level, double buyOrderPrice, double sellOfferPrice,
                             double marginPercent, double marginPerUnit, double tradesPerHour, double score) {
    }

    /**
     * Fetches every product's order book and returns qualifying enchant books ranked by score,
     * most promising first. Ranking weights real measured trade volume directly (not a guess about
     * which specific levels tend to be liquid - user-confirmed: "it doesn't HAVE to be 1 or 5, just
     * needs good insta sell volume, which is more common in lvl 1 or 5 books" - so volume itself,
     * which is already measured factually via {@link HypixelBazaarClient.OrderBook#sellMovingWeek()},
     * is what should drive the ranking, not an assumption about which level number correlates with it).
     */
    public static List<Candidate> findCandidates() throws Exception {
        Map<String, HypixelBazaarClient.OrderBook> books = HypixelBazaarClient.getAllOrderBooks();
        double priceIncrement = FlipperSettings.get().priceIncrement;
        double taxMultiplier = 1.0 - FlipperSettings.get().taxRatePercent / 100.0;

        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, HypixelBazaarClient.OrderBook> entry : books.entrySet()) {
            String tag = entry.getKey();
            if (!tag.startsWith(TAG_PREFIX)) continue;

            Matcher levelMatch = LEVEL_SUFFIX.matcher(tag);
            if (!levelMatch.find()) continue; // no parseable level suffix - skip rather than guess one
            int level = Integer.parseInt(levelMatch.group(1));

            HypixelBazaarClient.OrderBook book = entry.getValue();
            double stableBuy = book.stableTopBuyPrice();
            double stableSell = book.stableTopSellPrice();
            if (stableBuy <= 0 || stableSell <= 0) continue;

            double buyOrderPrice = stableBuy + priceIncrement;
            double sellOfferPrice = Math.max(0.1, stableSell - priceIncrement);
            if (sellOfferPrice <= buyOrderPrice) continue; // crossed or no room for a limit-order flip at all

            double marginPerUnit = sellOfferPrice * taxMultiplier - buyOrderPrice;
            if (marginPerUnit < MIN_MARGIN_COINS) continue;
            double marginPercent = marginPerUnit / buyOrderPrice * 100.0;
            if (marginPercent < MIN_MARGIN_PERCENT) continue;

            double tradesPerHour = book.sellMovingWeek() / (7.0 * 24.0);
            if (tradesPerHour < MIN_TRADES_PER_HOUR) continue;

            double score = marginPercent * tradesPerHour;

            candidates.add(new Candidate(tag, level, buyOrderPrice, sellOfferPrice, marginPercent, marginPerUnit, tradesPerHour, score));
        }

        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
        return candidates;
    }

    /** {@code ENCHANTMENT_ULTIMATE_CROP_FEVER_5} -> {@code "ultimate crop fever"} - a loose search aid only, never trusted as correct on its own (see the Book Flipper plan). */
    public static String deriveLooseSearchText(String tag) {
        String withoutPrefix = tag.replaceFirst("^" + TAG_PREFIX, "");
        String withoutLevel = withoutPrefix.replaceFirst("_\\d+$", "");
        return withoutLevel.toLowerCase().replace('_', ' ');
    }

    /**
     * Multiple real candidate search texts to try in order, never just one - a live test this
     * session showed {@code deriveLooseSearchText}'s full text ("ultimate legion") returned "No
     * Product Found" for every result on a real {@code ENCHANTMENT_ULTIMATE_LEGION_1} search, so a
     * single derived guess isn't reliable enough to commit to blindly (per the user's explicit
     * instruction after that: "you guessed... do better... be resourceful and find out"). Since
     * "ULTIMATE_" is a whole qualifier segment shared across many enchant tags (also seen on
     * {@code ULTIMATE_CROP_FEVER}, {@code ULTIMATE_WISE}), it's plausible Hypixel's search - or the
     * item's real display name - doesn't include that word at all. Rather than swap one guess for
     * another, a caller tries each candidate here in turn and only ever commits to whichever one a
     * real search result actually confirms, by both name and price - which is exactly the shape
     * {@code BuyOrderEngine.Request} takes its search candidates in, and what
     * {@code BuyOrderEngine.locateVerifiedResult} does with them.
     *
     * <p>Nothing calls this right now: the only caller was the Legion craft script, and the test
     * harness that replaced it buys a plain item whose search text is simply its display name.
     * Kept because it is the correct input for the engine's candidate API the moment any
     * book-trading strategy comes back.
     */
    public static List<String> deriveSearchTextCandidates(String tag) {
        String full = deriveLooseSearchText(tag);
        List<String> candidates = new ArrayList<>();
        if (full.startsWith("ultimate ")) {
            candidates.add(full.substring("ultimate ".length()));
        }
        candidates.add(full);
        return candidates;
    }

    /**
     * Best-effort expected in-game display name for a specific search-text candidate (Title Case +
     * Roman numeral level, e.g. {@code "legion"} + level 1 -> {@code "Legion I"}). Confirmed
     * against two independent real families via a live {@code /bfm books locate} dump - Hypixel's
     * own search results showed exactly "Small Brain III"/"IV"/"V" for
     * {@code ENCHANTMENT_SMALL_BRAIN_*}, matching the same pattern already confirmed for Scuba via
     * a real "Supercrafted Scuba I (Book)!" chat line - not a guess invented from nothing, but
     * still not verified for every one of the 773 enchant-book products, so callers must still
     * cross-check this against the real selected search result (both its displayed name AND its
     * price) before trusting it, per the user's explicit instruction: "just check if its the
     * correct book, we cant be guessing like that."
     */
    public static String deriveExpectedDisplayNameFromSearchText(String searchText, int level) {
        return toTitleCase(searchText) + " " + toRomanNumeral(level);
    }

    /** Convenience form of {@link #deriveExpectedDisplayNameFromSearchText} using the full (non-stripped) search text - kept for callers that only need one best-effort name, e.g. chat-confirmation matching where a price-based fallback already covers a wrong guess. */
    public static String deriveExpectedDisplayName(String tag, int level) {
        return deriveExpectedDisplayNameFromSearchText(deriveLooseSearchText(tag), level);
    }

    private static String toTitleCase(String words) {
        StringBuilder result = new StringBuilder();
        for (String word : words.split(" ")) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private static final int[] ROMAN_VALUES = {10, 9, 5, 4, 1};
    private static final String[] ROMAN_SYMBOLS = {"X", "IX", "V", "IV", "I"};

    /** Handles 1-10 - every real enchant level seen in this project so far (Scuba 1-5, Small Brain 1-5, Execute up to 6) fits well within that range. */
    private static String toRomanNumeral(int number) {
        StringBuilder result = new StringBuilder();
        int remaining = number;
        for (int i = 0; i < ROMAN_VALUES.length; i++) {
            while (remaining >= ROMAN_VALUES[i]) {
                result.append(ROMAN_SYMBOLS[i]);
                remaining -= ROMAN_VALUES[i];
            }
        }
        return result.toString();
    }
}

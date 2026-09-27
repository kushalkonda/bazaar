package dev.bazaarmacro.bazaar;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Client for Hypixel's official public Bazaar API (no key required):
 * {@code https://api.hypixel.net/v2/skyblock/bazaar}. There is no per-item endpoint -
 * the response is every product's order book in one payload (a few MB) - so this fetches
 * and caches the whole map with a TTL rather than hitting the network per lookup.
 *
 * <p><b>The raw JSON field names are swapped relative to their obvious meaning</b> - see
 * {@link #parseOrderBook} for the full reasoning and the live cross-check that proved it
 * (an earlier version of this class took the names at face value, which silently made every
 * price computed here backwards - buy-side logic was pricing off real sell-offer data and
 * vice versa, which is also what made an entirely ordinary, non-crossed market look crossed).
 * Past that swap, {@code quick_status.buyPrice}/{@code sellPrice} are still a weighted average
 * of recent trades, not the top order price - unusable for "is this the top order".
 * {@link OrderBook#topBuyPrice()} and {@link OrderBook#topSellPrice()} compute top price via
 * max/min over the raw arrays instead, so they don't depend on that (or any) assumed sort
 * order holding forever.
 */
public final class HypixelBazaarClient {
    private static final String ENDPOINT = "https://api.hypixel.net/v2/skyblock/bazaar";
    /**
     * Was 30s - too long relative to {@code FlipperEngine}'s 5-10s poll cadence: a real outbid
     * could go undetected for up to 30 seconds after it happened, since most polls were just
     * re-checking the same stale cached snapshot rather than actually seeing the new top price.
     * Matched down to the engine's own fastest poll interval so this is never the bottleneck on
     * detecting a real outbid/undercut.
     */
    private static final long CACHE_TTL_MS = TimeUnit.SECONDS.toMillis(5);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson GSON = new Gson();
    private static final ReentrantLock REFRESH_LOCK = new ReentrantLock();

    private static volatile Map<String, JsonObject> productsCache = Map.of();
    private static volatile long lastFetchTime = 0L;

    private HypixelBazaarClient() {
    }

    /** One price level of a product's order book. Gson populates these by field name from the raw JSON; the raw {@code orders} count is present in the response but deliberately not modelled, since nothing here prices off it. */
    public record PriceLevel(double pricePerUnit, long amount) {
    }

    /** A thin outlier level is ignored for pricing purposes if it's more than this far from the book's median price. Verified live on ENCHANTED_SEA_LUMIES: a real 64-unit buy order sat at 95,000 while ~19 levels/thousands of units clustered at ~85,000 - chasing the literal extreme there would mean paying ~12% over the real market. */
    private static final double OUTLIER_TOLERANCE = 0.10;

    /**
     * @param sellMovingWeek units sold (via any mechanism) over the trailing 7 days - real Hypixel
     *                       trade-frequency data read from {@code quick_status}, used by
     *                       {@link dev.bazaarmacro.books.BookSniperScanner} to rank how often a
     *                       thin book actually trades. Confirmed present on a real enchant-book
     *                       product via a live API fetch; 0 if the field is missing.
     *                       The matching {@code buyMovingWeek} is deliberately not modelled -
     *                       nothing reads it, and carrying an unused field invites treating it as
     *                       meaningful later without re-confirming what it measures.
     */
    public record OrderBook(List<PriceLevel> buySummary, List<PriceLevel> sellSummary, long sellMovingWeek) {
        /** The literal best buy order price, however thin - use for "is my order still genuinely top" comparisons, where reality (not representativeness) is what matters. */
        public double topBuyPrice() {
            return buySummary.stream().mapToDouble(PriceLevel::pricePerUnit).max().orElse(0.0);
        }

        /** The literal best sell offer price, however thin - see {@link #topBuyPrice()}. */
        public double topSellPrice() {
            return sellSummary.stream().mapToDouble(PriceLevel::pricePerUnit).min().orElse(0.0);
        }

        /** How much is actually available at {@link #topBuyPrice()} - a rough liquidity signal for whether that price is really tradeable at scale. */
        public long topBuyAmount() {
            return buySummary.stream()
                    .max(Comparator.comparingDouble(PriceLevel::pricePerUnit))
                    .map(PriceLevel::amount).orElse(0L);
        }

        /** How much is actually available at {@link #topSellPrice()} - see {@link #topBuyAmount()}. */
        public long topSellAmount() {
            return sellSummary.stream()
                    .min(Comparator.comparingDouble(PriceLevel::pricePerUnit))
                    .map(PriceLevel::amount).orElse(0L);
        }

        /**
         * The highest buy price worth actually pricing a new order against - the literal top,
         * unless it is a thin outlier far from the median of the book’s price levels (see
         * {@link #OUTLIER_TOLERANCE}), in which case the next-best price within tolerance of the
         * median is used instead. Use this (not {@link #topBuyPrice()}) when deciding what price
         * to place a brand new order at, so a single manipulated or mistaken order doesn't drag
         * our own price along with it.
         */
        public double stableTopBuyPrice() {
            return stableExtreme(buySummary, true);
        }

        /** Sell-side counterpart of {@link #stableTopBuyPrice()}. */
        public double stableTopSellPrice() {
            return stableExtreme(sellSummary, false);
        }

        private static double stableExtreme(List<PriceLevel> levels, boolean highest) {
            if (levels.isEmpty()) return 0.0;
            List<Double> prices = levels.stream().map(PriceLevel::pricePerUnit).sorted().toList();
            double median = median(prices);
            double lowerBound = median * (1.0 - OUTLIER_TOLERANCE);
            double upperBound = median * (1.0 + OUTLIER_TOLERANCE);

            java.util.stream.DoubleStream filtered = prices.stream()
                    .mapToDouble(Double::doubleValue)
                    .filter(p -> p >= lowerBound && p <= upperBound);
            // Fallback below only fires if every level in a book got filtered out (degenerate
            // case, e.g. a single level far from itself - never happens - or empty), not a real
            // path in practice; falls back to the literal extreme rather than 0 either way.
            double defaultValue = highest ? prices.get(prices.size() - 1) : prices.get(0);
            return highest
                    ? filtered.max().orElse(defaultValue)
                    : filtered.min().orElse(defaultValue);
        }

        private static double median(List<Double> sortedPrices) {
            int n = sortedPrices.size();
            return n % 2 == 1
                    ? sortedPrices.get(n / 2)
                    : (sortedPrices.get(n / 2 - 1) + sortedPrices.get(n / 2)) / 2.0;
        }
    }

    /** Refreshes the cached product map if stale, then returns one product's order book. */
    public static OrderBook getOrderBook(String itemTag) throws Exception {
        refreshIfNeeded();
        JsonObject product = productsCache.get(itemTag);
        if (product == null) {
            throw new IllegalArgumentException("No Bazaar product found for tag \"" + itemTag + "\"");
        }
        return parseOrderBook(product);
    }

    /** Refreshes the cached product map if stale, then returns every product's order book keyed by tag - for scanning the whole Bazaar (e.g. {@code BazaarScanner}) rather than checking one known item. */
    public static Map<String, OrderBook> getAllOrderBooks() throws Exception {
        refreshIfNeeded();
        Map<String, OrderBook> books = new java.util.HashMap<>();
        for (Map.Entry<String, JsonObject> entry : productsCache.entrySet()) {
            books.put(entry.getKey(), parseOrderBook(entry.getValue()));
        }
        return books;
    }

    /**
     * Forces the next {@link #getOrderBook} call to hit the network instead of serving cached
     * data. Needed right after placing an order: Hypixel's "Top Order" price preset sets the
     * real price live, server-side, at the moment of the click - if it doesn't match whatever
     * (possibly up to {@link #CACHE_TTL_MS} stale) top price we used beforehand just to size the
     * quantity, tracking that stale estimate as the order's real price would be wrong for the
     * rest of the order's life.
     */
    public static void invalidateCache() {
        lastFetchTime = 0L;
    }

    private static void refreshIfNeeded() throws Exception {
        if (System.currentTimeMillis() - lastFetchTime < CACHE_TTL_MS && !productsCache.isEmpty()) {
            return;
        }
        REFRESH_LOCK.lock();
        try {
            if (System.currentTimeMillis() - lastFetchTime < CACHE_TTL_MS && !productsCache.isEmpty()) {
                return;
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Hypixel Bazaar API returned status " + response.statusCode());
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.get("success").getAsBoolean()) {
                throw new IllegalStateException("Hypixel Bazaar API reported success=false");
            }

            java.lang.reflect.Type mapType = new TypeToken<Map<String, JsonObject>>() {
            }.getType();
            productsCache = GSON.fromJson(root.getAsJsonObject("products"), mapType);
            lastFetchTime = System.currentTimeMillis();
        } finally {
            REFRESH_LOCK.unlock();
        }
    }

    /**
     * Hypixel's raw JSON field names are the OPPOSITE of what they sound like: {@code
     * buy_summary} is actually the sell-offer book (the price levels a player would BUY from),
     * and {@code sell_summary} is actually the buy-order book (the price levels a player would
     * SELL into) - apparently named from the action a player takes, not the type of order
     * sitting there. Confirmed by cross-referencing quick_status on two of the most liquid
     * products in the Bazaar: for both WHEAT and COBBLESTONE, quick_status.buyPrice (what it
     * costs to instant-buy) falls inside the raw {@code buy_summary} range, and
     * quick_status.sellPrice (what instant-selling nets you) falls at the top of the raw {@code
     * sell_summary} range - the naive (non-swapped) reading would mean sell offers sitting
     * entirely below buy orders on some of the highest-volume products in the game, which is
     * economically impossible on a real matching exchange (that gap would be arbitraged away
     * instantly). This swap is applied here, once, so every other method on {@link OrderBook}
     * can treat {@code buySummary}/{@code sellSummary} at face value.
     */
    private static OrderBook parseOrderBook(JsonObject product) {
        List<PriceLevel> realBuyOrders = parseLevels(product, "sell_summary");
        List<PriceLevel> realSellOffers = parseLevels(product, "buy_summary");

        long sellMovingWeek = 0L;
        if (product.has("quick_status") && product.get("quick_status").isJsonObject()) {
            sellMovingWeek = getLongOrZero(product.getAsJsonObject("quick_status"), "sellMovingWeek");
        }

        return new OrderBook(realBuyOrders, realSellOffers, sellMovingWeek);
    }

    private static long getLongOrZero(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return 0L;
        try {
            return object.get(key).getAsLong();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static List<PriceLevel> parseLevels(JsonObject product, String key) {
        if (!product.has(key) || !product.get(key).isJsonArray()) {
            return List.of();
        }
        java.lang.reflect.Type listType = new TypeToken<List<PriceLevel>>() {
        }.getType();
        List<PriceLevel> levels = GSON.fromJson(product.getAsJsonArray(key), listType);
        return levels != null ? levels : List.of();
    }
}

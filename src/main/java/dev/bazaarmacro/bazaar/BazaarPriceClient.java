package dev.bazaarmacro.bazaar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Minimal client for the public Coflnet Hypixel Skyblock price API
 * (https://sky.coflnet.com), used to resolve a display name to a Bazaar
 * item tag and to fetch that item's live buy/sell snapshot.
 */
public final class BazaarPriceClient {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final long MATCH_CACHE_TTL_MS = TimeUnit.HOURS.toMillis(24);
    private static final long SNAPSHOT_CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(5);

    private static final ConcurrentHashMap<String, CachedValue<Snapshot>> SNAPSHOT_CACHE = new ConcurrentHashMap<>();

    private BazaarPriceClient() {
    }

    public record Snapshot(double buyPrice, double sellPrice) {
    }

    /** A resolved search hit: the API tag plus the item's real, correctly-spelled display name. */
    public record ItemMatch(String tag, String displayName) {
    }

    private static final ConcurrentHashMap<String, CachedValue<ItemMatch>> MATCH_CACHE = new ConcurrentHashMap<>();

    /**
     * Resolves a (possibly imprecise/misspelled) query to the actual item Coflnet matched -
     * both its API tag (e.g. "ENCHANTED_SEA_LUMIES") and its real display name (e.g.
     * "Enchanted Sea Lumies"). Prefer this over {@link #searchItemTag} whenever the result is
     * going to be typed into Hypixel's own UI (e.g. a {@code /bz} search): Coflnet's search is
     * forgiving of minor typos, but Hypixel's in-game search may not be, so echoing back the
     * user's raw (possibly slightly wrong) input can silently fail there even when Coflnet
     * resolved it fine - always use the confirmed-correct name it actually found instead.
     */
    public static ItemMatch searchItem(String query) throws Exception {
        CachedValue<ItemMatch> cached = MATCH_CACHE.get(query);
        if (cached != null && !cached.isExpired(MATCH_CACHE_TTL_MS)) {
            return cached.value;
        }

        // URLEncoder encodes spaces as '+', which is correct for a query string but not a URL
        // path segment - this endpoint takes the name in the path and treats '+' as a literal
        // (empty match) rather than a space. The multi-arg URI constructor percent-encodes each
        // component per its actual rules (space -> %20 in a path), which is what this needs.
        URI uri = new URI("https", "sky.coflnet.com", "/api/item/search/" + query, null);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Item search failed with status " + response.statusCode());
        }

        JsonArray results = JsonParser.parseString(response.body()).getAsJsonArray();
        if (results.isEmpty()) {
            throw new IllegalStateException("No item found matching \"" + query + "\"");
        }

        JsonObject first = results.get(0).getAsJsonObject();
        String tag = first.get("id").getAsString();
        // Coflnet's "name" field is suffixed like "Enchanted Sea Lumies - bazaar" - strip that part.
        String rawName = first.get("name").getAsString();
        String displayName = rawName.contains(" - ") ? rawName.substring(0, rawName.indexOf(" - ")) : rawName;

        ItemMatch match = new ItemMatch(tag, displayName);
        MATCH_CACHE.put(query, new CachedValue<>(match));
        return match;
    }

    /** Resolves a display name (e.g. "Enchanted Wheat") to a Bazaar item tag (e.g. "ENCHANTED_WHEAT"). */
    private static String searchItemTag(String displayName) throws Exception {
        return searchItem(displayName).tag();
    }

    /** Fetches the live Bazaar buy/sell price for an item tag. */
    private static Snapshot getSnapshot(String itemTag) throws Exception {
        CachedValue<Snapshot> cached = SNAPSHOT_CACHE.get(itemTag);
        if (cached != null && !cached.isExpired(SNAPSHOT_CACHE_TTL_MS)) {
            return cached.value;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://sky.coflnet.com/api/bazaar/" + itemTag + "/snapshot"))
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Bazaar snapshot failed with status " + response.statusCode());
        }

        JsonElement root = JsonParser.parseString(response.body());
        if (!root.isJsonObject()) {
            throw new IllegalStateException("Unexpected snapshot response shape");
        }
        JsonObject data = root.getAsJsonObject();
        Snapshot snapshot = new Snapshot(getDouble(data, "buyPrice"), getDouble(data, "sellPrice"));
        SNAPSHOT_CACHE.put(itemTag, new CachedValue<>(snapshot));
        return snapshot;
    }

    /** Resolves a name to a tag (if it isn't already one) then fetches its snapshot. */
    public static Snapshot getSnapshotByName(String itemNameOrTag) throws Exception {
        String tag = looksLikeTag(itemNameOrTag) ? itemNameOrTag : searchItemTag(itemNameOrTag);
        return getSnapshot(tag);
    }

    private static boolean looksLikeTag(String s) {
        return s != null && s.equals(s.toUpperCase()) && s.matches("[A-Z0-9_:]+");
    }

    private static double getDouble(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) return 0.0;
        try {
            return value.getAsDouble();
        } catch (RuntimeException ignored) {
            return 0.0;
        }
    }

    private static final class CachedValue<T> {
        final T value;
        final long fetchedAt = System.currentTimeMillis();

        CachedValue(T value) {
            this.value = value;
        }

        boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - fetchedAt > ttlMs;
        }
    }
}

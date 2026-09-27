package dev.bazaarmacro.flipper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.bazaarmacro.util.ConfigPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tunable {@link FlipperEngine} behavior, persisted so the sliders on the Flipper screen survive
 * a restart. How often the engine checks are made is deliberately *not* here, and is actually two
 * separate cadences hardcoded in {@link FlipperEngine}: a fast, fixed ~1s price check (pure HTTP,
 * invisible to the Minecraft server, so no "looks like a bot" concern) that reacts to a real
 * outbid/undercut almost immediately, and a slower randomized 5-10s cadence specifically for the
 * real, in-game opportunistic fill-check - a configurable *fixed* cadence there would look like
 * automation in a way a randomized one doesn't.
 */
public class FlipperSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = ConfigPaths.dir();
    private static final Path FILE = CONFIG_DIR.resolve("flipper_settings.json");
    private static final int CURRENT_VERSION = 5;

    public int configVersion = CURRENT_VERSION;

    /** How far above/below the current top price a new order targets, in coins. */
    public double priceIncrement = 0.1;
    /**
     * Minimum time between two order-placing/claiming actions, in seconds. Was 30s, then briefly
     * lowered to 3.0 to reduce downtime once outbid/undercut detection became fast - but that was
     * a real mistake: it was below the settings slider's own established floor of 5s (a boundary
     * that was clearly chosen deliberately and shouldn't have been undercut), and a real session
     * on that value got kicked for "Sending packets too fast!" moments after both the buy and sell
     * sides needed to react close together. Raised to a value with real safety margin above that
     * floor - still a meaningful improvement over the original 30s, but nowhere near what
     * triggered a real kick. NOTE: an existing saved {@code flipper_settings.json} keeps whatever
     * value it already has - if you already have 3.0 saved from a previous version, raise this
     * back up yourself via the Settings slider.
     */
    public double minActionIntervalSeconds = 10.0;
    /** Won't place a sell order for less than this fraction of the last buy order's quantity. */
    public double minCollectFraction = 0.25;
    /** Hypixel's Bazaar sell tax, in percent - 1.25 by default, reducible to 1.125/1.0 via Community Shop "Bazaar Flipper" reputation. Only affects profit projections; Hypixel deducts it server-side regardless. */
    public double taxRatePercent = 1.25;

    private static FlipperSettings instance;

    public static synchronized FlipperSettings get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static synchronized void save(FlipperSettings settings) {
        instance = settings;
        try {
            Files.createDirectories(CONFIG_DIR);
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(settings, writer);
            }
        } catch (IOException e) {
            System.err.println("[BazaarMacro] Failed to save flipper_settings.json: " + e.getMessage());
        }
    }

    /** Persists the current in-memory instance - call after mutating a field directly (e.g. from a slider). */
    public static synchronized void saveCurrent() {
        if (instance != null) {
            save(instance);
        }
    }

    private static FlipperSettings load() {
        if (!Files.isRegularFile(FILE)) {
            FlipperSettings defaults = new FlipperSettings();
            save(defaults);
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            FlipperSettings loaded = GSON.fromJson(root, FlipperSettings.class);
            if (loaded == null) {
                loaded = new FlipperSettings();
            }
            // Gson uses this class's own no-arg constructor, so field initializers already run
            // for any key simply missing from an older file - a genuinely new field gets its
            // fresh default automatically. A removed field (like the old pollIntervalSeconds) is
            // just ignored by Gson. Never blanket-discard the whole file on a version bump - that
            // would silently wipe any value the user tuned that had nothing to do with the change.
            if (loaded.configVersion != CURRENT_VERSION) {
                loaded.configVersion = CURRENT_VERSION;
                save(loaded);
            }
            return loaded;
        } catch (IOException e) {
            System.err.println("[BazaarMacro] Failed to load flipper_settings.json: " + e.getMessage());
            return new FlipperSettings();
        }
    }
}

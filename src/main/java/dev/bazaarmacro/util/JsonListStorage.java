package dev.bazaarmacro.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes a plain {@code List<T>} as one pretty-printed JSON file in the mod's config
 * directory, failing soft (empty list, message to the console) rather than throwing - a missing or
 * corrupt config file should never take down whatever was asking for it.
 *
 * <p>{@code MacroStorage}, {@code PriceAlertStorage} and {@code SessionHistoryStorage} were three
 * byte-identical copies of this, differing only in element type and filename.
 *
 * <p>Deliberately not used by {@code FlipperSettings}, {@code FlipperCoordinates} or {@code Theme}:
 * those persist a single object, not a list, and each carries real per-file logic (a config version
 * with a documented upgrade path, value clamping, enum-name validation) that has nothing to do with
 * serialization and shouldn't be flattened into a shared helper.
 */
public final class JsonListStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = ConfigPaths.dir();

    private JsonListStorage() {
    }

    /**
     * @param listType the element list's {@code TypeToken} type - required because Java erases the
     *                 element type of a {@code List<T>} at runtime, so Gson can't recover it from
     *                 {@code T} alone.
     */
    public static <T> List<T> load(String filename, Type listType) {
        Path file = CONFIG_DIR.resolve(filename);
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<T> loaded = GSON.fromJson(reader, listType);
            return loaded != null ? loaded : new ArrayList<>();
        } catch (IOException | RuntimeException e) {
            System.err.println("[BazaarMacro] Failed to load " + filename + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public static <T> void save(String filename, List<T> values, Type listType) {
        try {
            Files.createDirectories(CONFIG_DIR);
            try (Writer writer = Files.newBufferedWriter(CONFIG_DIR.resolve(filename), StandardCharsets.UTF_8)) {
                GSON.toJson(values, listType, writer);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[BazaarMacro] Failed to save " + filename + ": " + e.getMessage());
        }
    }
}

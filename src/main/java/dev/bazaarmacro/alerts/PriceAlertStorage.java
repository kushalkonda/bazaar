package dev.bazaarmacro.alerts;

import com.google.gson.reflect.TypeToken;
import dev.bazaarmacro.util.JsonListStorage;

import java.lang.reflect.Type;
import java.util.List;

/** Loads/saves all {@link PriceAlert}s as one JSON file, same pattern as {@code MacroStorage}. */
public final class PriceAlertStorage {
    private static final String FILENAME = "flipper_alerts.json";
    private static final Type LIST_TYPE = new TypeToken<List<PriceAlert>>() {
    }.getType();

    private PriceAlertStorage() {
    }

    public static List<PriceAlert> load() {
        return JsonListStorage.load(FILENAME, LIST_TYPE);
    }

    public static void save(List<PriceAlert> alerts) {
        JsonListStorage.save(FILENAME, alerts, LIST_TYPE);
    }
}

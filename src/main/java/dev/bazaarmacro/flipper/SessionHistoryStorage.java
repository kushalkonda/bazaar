package dev.bazaarmacro.flipper;

import com.google.gson.reflect.TypeToken;
import dev.bazaarmacro.util.JsonListStorage;

import java.lang.reflect.Type;
import java.util.List;

/** Loads/saves completed {@link SessionRecord}s as one JSON file, same pattern as {@code MacroStorage}. */
public final class SessionHistoryStorage {
    private static final String FILENAME = "flipper_history.json";
    private static final Type LIST_TYPE = new TypeToken<List<SessionRecord>>() {
    }.getType();

    private SessionHistoryStorage() {
    }

    public static synchronized List<SessionRecord> load() {
        return JsonListStorage.load(FILENAME, LIST_TYPE);
    }

    public static synchronized void append(SessionRecord record) {
        List<SessionRecord> sessions = load();
        sessions.add(record);
        JsonListStorage.save(FILENAME, sessions, LIST_TYPE);
    }

    public static long totalProfit() {
        return load().stream().mapToLong(SessionRecord::profit).sum();
    }
}

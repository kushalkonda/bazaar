package dev.bazaarmacro.macro;

import com.google.gson.reflect.TypeToken;
import dev.bazaarmacro.util.JsonListStorage;

import java.lang.reflect.Type;
import java.util.List;

/** Loads/saves all {@link MacroDefinition}s as one JSON file under the mod's config directory. */
public final class MacroStorage {
    private static final String FILENAME = "macros.json";
    private static final Type LIST_TYPE = new TypeToken<List<MacroDefinition>>() {
    }.getType();

    private MacroStorage() {
    }

    public static List<MacroDefinition> load() {
        return JsonListStorage.load(FILENAME, LIST_TYPE);
    }

    public static void save(List<MacroDefinition> macros) {
        JsonListStorage.save(FILENAME, macros, LIST_TYPE);
    }
}

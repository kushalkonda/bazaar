package dev.bazaarmacro.macro;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** In-memory list of saved macros, lazily loaded from and persisted to {@link MacroStorage}. */
public final class MacroRegistry {
    private static List<MacroDefinition> macros;

    private MacroRegistry() {
    }

    public static synchronized List<MacroDefinition> all() {
        if (macros == null) {
            macros = MacroStorage.load();
        }
        return macros;
    }

    public static synchronized Optional<MacroDefinition> find(String name) {
        return all().stream().filter(m -> m.name.equalsIgnoreCase(name)).findFirst();
    }

    public static synchronized void save(MacroDefinition macro) {
        all().removeIf(m -> m.name.equalsIgnoreCase(macro.name));
        all().add(macro);
        persist();
    }

    public static synchronized void delete(String name) {
        all().removeIf(m -> m.name.equalsIgnoreCase(name));
        persist();
    }

    private static void persist() {
        MacroStorage.save(new ArrayList<>(all()));
    }
}

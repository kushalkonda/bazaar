package dev.bazaarmacro.macro;

import java.util.ArrayList;
import java.util.List;

/** A named, ordered sequence of steps the user built in the macro editor. */
public class MacroDefinition {
    public String name;
    public List<MacroStep> steps = new ArrayList<>();

    public MacroDefinition() {
    }

    public MacroDefinition(String name) {
        this.name = name;
    }

    public MacroDefinition copy() {
        MacroDefinition copy = new MacroDefinition(name);
        copy.steps = new ArrayList<>(steps);
        return copy;
    }
}

package dev.bazaarmacro.macro;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-run variable store letting one step's computed result feed a later step, both as
 * a {@link MacroExpression} variable and as a {name} placeholder in command/sign text.
 */
public class ExecutionContext {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");

    private final Map<String, Double> variables = new HashMap<>();

    public void set(String name, double value) {
        variables.put(name.toLowerCase(Locale.ROOT), value);
    }

    public Double get(String name) {
        return variables.get(name.toLowerCase(Locale.ROOT));
    }

    /** Snapshot of every stored variable, keyed lowercase, for {@link MacroExpression#evaluate}. */
    public Map<String, Double> asVariableMap() {
        return new HashMap<>(variables);
    }

    /** Replaces every {name} occurrence in the template with the stored variable's formatted value. */
    public String resolve(String template) {
        if (template == null) return null;
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            Double value = variables.get(matcher.group(1).toLowerCase(Locale.ROOT));
            String replacement = value != null ? format(value) : matcher.group(0);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /** Whole numbers print without a trailing ".0" - important for sign inputs, which expect plain digits. */
    public static String format(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return String.valueOf(value);
        }
        if (value == Math.floor(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private static final java.text.DecimalFormat DISPLAY_FORMAT =
            new java.text.DecimalFormat("#,##0.##", new java.text.DecimalFormatSymbols(Locale.US));

    /**
     * Comma-grouped, rounded-to-2-decimals rendering for chat messages and UI panels - e.g.
     * {@code 10427} -> "10,427", {@code 933.8000000000001} (float drift from a live price
     * calculation) -> "933.8". Deliberately separate from {@link #format}, which stays raw/plain
     * because it also feeds sign-text and command placeholders typed straight into the game -
     * commas there would corrupt a quantity or price the server expects as plain digits.
     */
    public static String formatDisplay(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return String.valueOf(value);
        }
        return DISPLAY_FORMAT.format(value);
    }
}

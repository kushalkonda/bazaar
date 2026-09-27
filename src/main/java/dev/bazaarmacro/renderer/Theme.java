package dev.bazaarmacro.renderer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.bazaarmacro.util.ConfigPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared color palette so every screen and component looks consistent - now swappable between a
 * handful of {@link ThemePreset}s instead of one fixed purple/pink scheme. Every field below is a
 * plain mutable static (not a constant) so every existing call site (they all just read
 * {@code Theme.FIELD_NAME}) keeps working unchanged; {@link #applyPreset} overwrites them in
 * place and the next render frame simply reads the new values - no component rebuild needed.
 */
public final class Theme {
    private Theme() {
    }

    /**
     * One preset supplies a small "seed" palette (background family, button/field shades, two
     * accents, text shades) - {@link #applyPreset} derives the rest (selected-button hover,
     * card background, etc.) from it, the same way the original hand-tuned purple/pink values
     * related to each other, rather than requiring every one of the ~20 exposed fields to be
     * hand-authored per preset.
     */
    public enum ThemePreset {
        PURPLE_PINK("Purple/Pink", 0xF11C1229, 0xFF5B3B8C, 0xFF2A1D3F, 0xFF322447,
                0xFF3B2A57, 0xFF52397D, 0xFF261C36, 0xFF201530, 0xFF2B1D40,
                0xFFFF7AE0, 0xFFB57CF2,
                0xFFF3E9FF, 0xFFAF9AC9, 0xFF6C5D82, 0xFF7A6B92,
                0xFF241831, 0xFF6C5D82),
        MIDNIGHT_BLUE("Midnight Blue", 0xF1121A29, 0xFF3B5B8C, 0xFF1D2A3F, 0xFF243247,
                0xFF2A3B57, 0xFF39527D, 0xFF1C2636, 0xFF152030, 0xFF1D2B40,
                0xFF5AC8FA, 0xFF7C9CF2,
                0xFFE9F3FF, 0xFF9AAFC9, 0xFF5D6C82, 0xFF6B7A92,
                0xFF182431, 0xFF5D6C82),
        EMERALD("Emerald", 0xF1122117, 0xFF3B8C5F, 0xFF1D3F2A, 0xFF244732,
                0xFF2A573C, 0xFF397D53, 0xFF1C3624, 0xFF153020, 0xFF1D402B,
                0xFF6BFFB0, 0xFF7CF2C8,
                0xFFE9FFF1, 0xFF9AC9AF, 0xFF5D8268, 0xFF6B926F,
                0xFF182F1F, 0xFF5D8268),
        CRIMSON("Crimson", 0xF1291217, 0xFF8C3B4B, 0xFF3F1D26, 0xFF47242D,
                0xFF572A34, 0xFF7D3947, 0xFF361C22, 0xFF301520, 0xFF401D29,
                0xFFFF9166, 0xFFF27C8E,
                0xFFFFECEA, 0xFFC99AA4, 0xFF826167, 0xFF926B76,
                0xFF2F1820, 0xFF826167),
        SLATE("Slate", 0xF1141416, 0xFF5A5A62, 0xFF212124, 0xFF28282C,
                0xFF303034, 0xFF3F3F45, 0xFF1C1C1E, 0xFF1A1A1C, 0xFF232326,
                0xFFE0E0E6, 0xFF9BA3B0,
                0xFFF5F5F7, 0xFFA3A3AC, 0xFF66666D, 0xFF74747C,
                0xFF1E1E20, 0xFF66666D);

        public final String label;
        public final int accent;
        final int panelBg, panelBorder, rowBg, rowBgAlt;
        final int buttonBg, buttonHover, buttonDisabled, fieldBg, fieldBgFocused;
        final int accent2;
        final int textPrimary, textSecondary, textDisabled, textPlaceholder;
        final int pillTrackOff, pillKnobOff;

        ThemePreset(String label, int panelBg, int panelBorder, int rowBg, int rowBgAlt,
                    int buttonBg, int buttonHover, int buttonDisabled, int fieldBg, int fieldBgFocused,
                    int accent, int accent2,
                    int textPrimary, int textSecondary, int textDisabled, int textPlaceholder,
                    int pillTrackOff, int pillKnobOff) {
            this.label = label;
            this.panelBg = panelBg;
            this.panelBorder = panelBorder;
            this.rowBg = rowBg;
            this.rowBgAlt = rowBgAlt;
            this.buttonBg = buttonBg;
            this.buttonHover = buttonHover;
            this.buttonDisabled = buttonDisabled;
            this.fieldBg = fieldBg;
            this.fieldBgFocused = fieldBgFocused;
            this.accent = accent;
            this.accent2 = accent2;
            this.textPrimary = textPrimary;
            this.textSecondary = textSecondary;
            this.textDisabled = textDisabled;
            this.textPlaceholder = textPlaceholder;
            this.pillTrackOff = pillTrackOff;
            this.pillKnobOff = pillKnobOff;
        }
    }

    // Panel / background
    public static int PANEL_BG;
    public static int PANEL_BORDER;
    public static int ROW_BG;
    public static int ROW_BG_ALT;

    // Buttons
    public static int BUTTON_BG;
    public static int BUTTON_HOVER;
    public static int BUTTON_SELECTED;
    public static int BUTTON_SELECTED_HOVER;
    public static int BUTTON_DISABLED;

    // Text fields
    public static int FIELD_BG;
    public static int FIELD_BG_FOCUSED;
    public static int FIELD_BORDER_FOCUSED;

    // Text
    public static int TEXT_PRIMARY;
    public static int TEXT_SECONDARY;
    public static int TEXT_DISABLED;
    public static int TEXT_PLACEHOLDER;

    // Accents
    public static int ACCENT_PINK;
    public static int ACCENT_PURPLE;

    // Profit HUD - intentionally theme-independent: green=good/red=bad is a universal signal,
    // not a stylistic choice, so it stays constant across every preset.
    public static final int PROFIT_POSITIVE = 0xFF6BFF8F;
    public static final int PROFIT_NEGATIVE = 0xFFFF6B6B;

    // Order status badges (Top/Matched/Undercut) - reuses profit colors for Top/Undercut so
    // "green = good, red = bad" stays consistent across the whole UI.
    public static final int STATUS_TOP = PROFIT_POSITIVE;
    public static final int STATUS_MATCHED = 0xFFF2C572;
    public static final int STATUS_UNDERCUT = PROFIT_NEGATIVE;

    // Toggle/slider controls (proportions match Aether's MainGUI pill/slider widgets)
    public static int PILL_TRACK_OFF;
    public static int PILL_KNOB_OFF;
    public static int CARD_BG;
    public static final int CARD_BORDER = 0x1FFFFFFF;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = ConfigPaths.dir();
    private static final Path FILE = CONFIG_DIR.resolve("theme.json");

    private static volatile ThemePreset currentPreset;

    static {
        applyPreset(loadSavedPreset(), false);
    }

    public static ThemePreset getCurrentPreset() {
        return currentPreset;
    }

    /** Swaps every exposed color field to {@code preset}'s values and persists the choice. */
    public static void applyPreset(ThemePreset preset) {
        applyPreset(preset, true);
    }

    private static void applyPreset(ThemePreset p, boolean save) {
        currentPreset = p;

        PANEL_BG = p.panelBg;
        PANEL_BORDER = p.panelBorder;
        ROW_BG = p.rowBg;
        ROW_BG_ALT = p.rowBgAlt;

        BUTTON_BG = p.buttonBg;
        BUTTON_HOVER = p.buttonHover;
        BUTTON_SELECTED = p.accent;
        BUTTON_SELECTED_HOVER = lighten(p.accent, 0.15f);
        BUTTON_DISABLED = p.buttonDisabled;

        FIELD_BG = p.fieldBg;
        FIELD_BG_FOCUSED = p.fieldBgFocused;
        FIELD_BORDER_FOCUSED = p.accent;

        TEXT_PRIMARY = p.textPrimary;
        TEXT_SECONDARY = p.textSecondary;
        TEXT_DISABLED = p.textDisabled;
        TEXT_PLACEHOLDER = p.textPlaceholder;

        ACCENT_PINK = p.accent;
        ACCENT_PURPLE = p.accent2;

        PILL_TRACK_OFF = p.pillTrackOff;
        PILL_KNOB_OFF = p.pillKnobOff;
        CARD_BG = ROW_BG;

        if (save) {
            save(p);
        }
    }

    private static int lighten(int argb, float amount) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        r = Math.min(255, Math.round(r + (255 - r) * amount));
        g = Math.min(255, Math.round(g + (255 - g) * amount));
        b = Math.min(255, Math.round(b + (255 - b) * amount));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Returns {@code argb} with its alpha channel replaced. */
    public static int withAlpha(int argb, float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    private record ThemeData(String preset) {
    }

    private static ThemePreset loadSavedPreset() {
        if (!Files.isRegularFile(FILE)) {
            return ThemePreset.PURPLE_PINK;
        }
        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            ThemeData data = GSON.fromJson(reader, ThemeData.class);
            if (data != null && data.preset() != null) {
                return ThemePreset.valueOf(data.preset());
            }
        } catch (Exception e) {
            System.err.println("[BazaarMacro] Failed to load theme.json (using the default theme): " + e.getMessage());
        }
        return ThemePreset.PURPLE_PINK;
    }

    private static void save(ThemePreset preset) {
        try {
            Files.createDirectories(CONFIG_DIR);
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(new ThemeData(preset.name()), writer);
            }
        } catch (IOException e) {
            System.err.println("[BazaarMacro] Failed to save theme.json: " + e.getMessage());
        }
    }
}

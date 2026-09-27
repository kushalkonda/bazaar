package dev.bazaarmacro.ui.components;

import dev.bazaarmacro.renderer.NVGRenderer;
import dev.bazaarmacro.renderer.Theme;

/** A small clickable color swatch representing one {@link Theme.ThemePreset} - used by the theme picker. */
public class ThemeSwatch implements Component {
    private static final float SIZE = 16f;

    private final float x, y;
    private final Theme.ThemePreset preset;
    private final Runnable onClick;
    private boolean hovered = false;

    public ThemeSwatch(float x, float y, Theme.ThemePreset preset, Runnable onClick) {
        this.x = x;
        this.y = y;
        this.preset = preset;
        this.onClick = onClick;
    }

    public static float size() {
        return SIZE;
    }

    @Override
    public void render(NVGRenderer nvg) {
        nvg.roundedRect(x, y, SIZE, SIZE, 4f, preset.accent);
        boolean active = Theme.getCurrentPreset() == preset;
        if (active) {
            nvg.rectOutline(x - 2f, y - 2f, SIZE + 4f, SIZE + 4f, 5f, 1.5f, 0xFFFFFFFF);
        } else if (hovered) {
            nvg.rectOutline(x - 1f, y - 1f, SIZE + 2f, SIZE + 2f, 4.5f, 1f, Theme.withAlpha(0xFFFFFFFF, 0.5f));
        }
    }

    @Override
    public boolean contains(double mouseX, double mouseY) {
        boolean inside = mouseX >= x - 2 && mouseX <= x + SIZE + 2 && mouseY >= y - 2 && mouseY <= y + SIZE + 2;
        hovered = inside;
        return inside;
    }

    @Override
    public boolean mousePressed(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        if (onClick != null) onClick.run();
        return true;
    }
}

package dev.bazaarmacro.ui.components;

import dev.bazaarmacro.renderer.Fonts;
import dev.bazaarmacro.renderer.NVGRenderer;
import dev.bazaarmacro.renderer.Theme;

public class NvgButton implements Component {
    private final float x, y, w, h;
    private String label;
    private final Runnable onClick;
    private boolean enabled = true;
    private boolean hovered = false;
    private boolean selected = false;
    private boolean visible = true;

    public NvgButton(float x, float y, float w, float h, String label, Runnable onClick) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.label = label;
        this.onClick = onClick;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Marks this button as the active/selected choice in a group (e.g. the current step type). */
    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void render(NVGRenderer nvg) {
        int bg;
        if (!enabled) {
            bg = Theme.BUTTON_DISABLED;
        } else if (selected) {
            bg = hovered ? Theme.BUTTON_SELECTED_HOVER : Theme.BUTTON_SELECTED;
        } else {
            bg = hovered ? Theme.BUTTON_HOVER : Theme.BUTTON_BG;
        }
        nvg.roundedRect(x, y, w, h, 5f, bg);
        if (selected) {
            nvg.rectOutline(x, y, w, h, 5f, 1.2f, Theme.ACCENT_PINK);
        }
        int textColor = enabled ? (selected ? 0xFF1B0E24 : Theme.TEXT_PRIMARY) : Theme.TEXT_DISABLED;
        nvg.textCentered(Fonts.REGULAR, label, x, y, w, h, 13f, textColor);
    }

    @Override
    public boolean contains(double mouseX, double mouseY) {
        boolean inside = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        hovered = inside;
        return inside;
    }

    @Override
    public boolean mousePressed(double mouseX, double mouseY, int button) {
        if (!enabled || button != 0) return false;
        if (onClick != null) onClick.run();
        return true;
    }
}

package dev.bazaarmacro.ui.components;

import dev.bazaarmacro.renderer.NVGRenderer;
import dev.bazaarmacro.renderer.Theme;

import java.util.function.Consumer;

/** A pill-shaped on/off switch - proportions match Aether's MainGUI toggle widget. */
public class NvgToggle implements Component {
    private static final float TRACK_W = 38f;
    private static final float TRACK_H = 21f;

    private final float x, y;
    private boolean value;
    private final Consumer<Boolean> onChange;
    private boolean visible = true;

    public NvgToggle(float x, float y, boolean initial, Consumer<Boolean> onChange) {
        this.x = x;
        this.y = y;
        this.value = initial;
        this.onChange = onChange;
    }

    public boolean getValue() {
        return value;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    public static float width() {
        return TRACK_W;
    }

    @Override
    public void render(NVGRenderer nvg) {
        int trackColor = value ? Theme.withAlpha(Theme.ACCENT_PINK, 0.22f) : Theme.PILL_TRACK_OFF;
        nvg.roundedRect(x, y, TRACK_W, TRACK_H, TRACK_H / 2f, trackColor);
        int borderColor = value ? Theme.withAlpha(Theme.ACCENT_PINK, 0.7f) : Theme.withAlpha(0xFFFFFFFF, 0.15f);
        nvg.rectOutline(x, y, TRACK_W, TRACK_H, TRACK_H / 2f, 1f, borderColor);

        float knobX = value ? x + TRACK_W - 17f : x + 2f;
        nvg.roundedRect(knobX, y + 3f, 15f, 15f, 7.5f, value ? Theme.ACCENT_PINK : Theme.PILL_KNOB_OFF);
    }

    @Override
    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX <= x + TRACK_W && mouseY >= y && mouseY <= y + TRACK_H;
    }

    @Override
    public boolean mousePressed(double mouseX, double mouseY, int button) {
        if (button != 0) return false;
        value = !value;
        if (onChange != null) onChange.accept(value);
        return true;
    }
}

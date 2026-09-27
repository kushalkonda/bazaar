package dev.bazaarmacro.ui.components;

import dev.bazaarmacro.renderer.Fonts;
import dev.bazaarmacro.renderer.NVGRenderer;
import dev.bazaarmacro.renderer.Theme;

import java.util.function.DoubleConsumer;

/**
 * A horizontal drag slider with a live value label at its right edge (no text-entry box -
 * drag only, unlike Aether's typeable version, to keep this simple).
 */
public class NvgSlider implements Component {
    private static final float LABEL_WIDTH = 56f;
    private static final float LABEL_GAP = 10f;

    private final float x, y, w, h;
    private final double min, max;
    private double value;
    private final int decimals;
    private final String suffix;
    private final DoubleConsumer onChange;
    private boolean dragging = false;
    private boolean visible = true;

    public NvgSlider(float x, float y, float w, float h, double min, double max, double initial,
                      int decimals, String suffix, DoubleConsumer onChange) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.min = min;
        this.max = max;
        this.value = clamp(initial);
        this.decimals = decimals;
        this.suffix = suffix == null ? "" : suffix;
        this.onChange = onChange;
    }

    public double getValue() {
        return value;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    private double clamp(double v) {
        return Math.max(min, Math.min(max, v));
    }

    private float trackWidth() {
        return w - LABEL_WIDTH - LABEL_GAP;
    }

    @Override
    public void render(NVGRenderer nvg) {
        float trackW = trackWidth();
        float trackY = y + h / 2f - 2f;
        nvg.roundedRect(x, trackY, trackW, 4f, 2f, Theme.FIELD_BG);

        float t = (float) ((value - min) / (max - min));
        float fillW = trackW * t;
        if (fillW > 0.5f) {
            nvg.roundedRect(x, trackY, fillW, 4f, 2f, Theme.ACCENT_PURPLE);
        }
        nvg.circle(x + fillW, y + h / 2f, 6f, 0xFFFFFFFF);

        String label = formatValue() + suffix;
        nvg.text(Fonts.REGULAR, label, x + trackW + LABEL_GAP, y + (h - 12f) / 2f, 12f, Theme.TEXT_PRIMARY);
    }

    private String formatValue() {
        return decimals <= 0 ? String.valueOf(Math.round(value)) : String.format("%." + decimals + "f", value);
    }

    @Override
    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }

    @Override
    public boolean mousePressed(double mouseX, double mouseY, int button) {
        if (button != 0 || mouseX > x + trackWidth()) return false;
        dragging = true;
        updateFromMouseX(mouseX);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (!dragging) return false;
        updateFromMouseX(mouseX);
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean was = dragging;
        dragging = false;
        return was;
    }

    private void updateFromMouseX(double mouseX) {
        float trackW = trackWidth();
        double t = trackW <= 0 ? 0 : Math.max(0, Math.min(1, (mouseX - x) / trackW));
        value = clamp(min + t * (max - min));
        if (onChange != null) onChange.accept(value);
    }
}

package dev.bazaarmacro.renderer;

import org.lwjgl.nanovg.NVGColor;

import static org.lwjgl.nanovg.NanoVG.*;

/**
 * High-level NanoVG rendering API, trimmed to what the macro editor UI needs:
 * filled/rounded rectangles, lines, left-aligned and centered text, and
 * nested scissor (clip) regions.
 *
 * <p>All drawing methods are only safe to call between
 * {@link NanoVGManager#beginFrame(float, float)} and {@link NanoVGManager#endFrame()}.
 * Obtain the singleton instance via {@link NanoVGManager#getRenderer()}.
 *
 * <p>Colors are ARGB ints: {@code 0xAARRGGBB}.
 */
public class NVGRenderer {

    private final long vg;

    private final NVGColor c1 = NVGColor.malloc();
    private final float[] fontBounds = new float[4];

    private ScissorRegion scissorStack = null;

    NVGRenderer(long vg) {
        this.vg = vg;
    }

    public void rect(float x, float y, float w, float h, int color) {
        nvgBeginPath(vg);
        nvgRect(vg, x, y, w, h);
        color(color, c1);
        nvgFillColor(vg, c1);
        nvgFill(vg);
    }

    public void roundedRect(float x, float y, float w, float h, float radius, int color) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y, w, h, radius);
        color(color, c1);
        nvgFillColor(vg, c1);
        nvgFill(vg);
    }

    public void rectOutline(float x, float y, float w, float h, float radius, float thickness, int color) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x + thickness / 2f, y + thickness / 2f, w - thickness, h - thickness, radius);
        nvgStrokeWidth(vg, thickness);
        color(color, c1);
        nvgStrokeColor(vg, c1);
        nvgStroke(vg);
    }

    public void circle(float cx, float cy, float radius, int color) {
        nvgBeginPath(vg);
        nvgCircle(vg, cx, cy, radius);
        color(color, c1);
        nvgFillColor(vg, c1);
        nvgFill(vg);
    }

    public void line(float x1, float y1, float x2, float y2, float thickness, int color) {
        nvgBeginPath(vg);
        nvgMoveTo(vg, x1, y1);
        nvgLineTo(vg, x2, y2);
        nvgStrokeWidth(vg, thickness);
        color(color, c1);
        nvgStrokeColor(vg, c1);
        nvgStroke(vg);
    }

    public void text(String fontName, String text, float x, float y, float size, int color) {
        int fontId = NanoVGManager.getFontId(fontName);
        if (fontId == -1) return;
        nvgFontFaceId(vg, fontId);
        nvgFontSize(vg, size);
        color(color, c1);
        nvgFillColor(vg, c1);
        nvgText(vg, x, y + 0.5f, text);
    }

    public void textCentered(String fontName, String text, float x, float y, float w, float h,
                              float size, int color) {
        float tw = textWidth(fontName, text, size);
        float tx = x + (w - tw) / 2f;
        float ty = y + (h - size) / 2f;
        text(fontName, text, tx, ty, size, color);
    }

    public float textWidth(String fontName, String text, float size) {
        int fontId = NanoVGManager.getFontId(fontName);
        if (fontId == -1) return 0f;
        nvgFontFaceId(vg, fontId);
        nvgFontSize(vg, size);
        return nvgTextBounds(vg, 0, 0, text, fontBounds);
    }

    public void pushScissor(float x, float y, float w, float h) {
        scissorStack = new ScissorRegion(scissorStack, x, y, x + w, y + h);
        scissorStack.apply(vg);
    }

    public void popScissor() {
        nvgResetScissor(vg);
        scissorStack = scissorStack != null ? scissorStack.parent : null;
        if (scissorStack != null) scissorStack.apply(vg);
    }

    private void color(int argb, NVGColor out) {
        nvgRGBA(
                (byte) ((argb >> 16) & 0xFF),
                (byte) ((argb >> 8) & 0xFF),
                (byte) (argb & 0xFF),
                (byte) ((argb >> 24) & 0xFF),
                out
        );
    }

    private static final class ScissorRegion {
        final ScissorRegion parent;
        final float x1, y1, x2, y2;

        ScissorRegion(ScissorRegion parent, float x1, float y1, float x2, float y2) {
            this.parent = parent;
            if (parent != null) {
                this.x1 = Math.max(x1, parent.x1);
                this.y1 = Math.max(y1, parent.y1);
                this.x2 = Math.min(x2, parent.x2);
                this.y2 = Math.min(y2, parent.y2);
            } else {
                this.x1 = x1;
                this.y1 = y1;
                this.x2 = x2;
                this.y2 = y2;
            }
        }

        void apply(long vg) {
            float w = Math.max(0f, x2 - x1);
            float h = Math.max(0f, y2 - y1);
            nvgScissor(vg, x1, y1, w, h);
        }
    }
}

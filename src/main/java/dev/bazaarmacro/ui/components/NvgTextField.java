package dev.bazaarmacro.ui.components;

import dev.bazaarmacro.renderer.Fonts;
import dev.bazaarmacro.renderer.NVGRenderer;
import dev.bazaarmacro.renderer.Theme;
import org.lwjgl.glfw.GLFW;

/** Single-line editable text field: click to focus, type to append, backspace to delete. */
public class NvgTextField implements Component {
    private final float x, y, w, h;
    private final StringBuilder value = new StringBuilder();
    private String placeholder = "";
    private boolean focused = false;
    private boolean visible = true;
    /** No field in this UI has ever needed a different cap, so this is a plain constant rather than a per-instance setting. */
    private static final int MAX_LENGTH = 256;

    public NvgTextField(float x, float y, float w, float h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public NvgTextField withPlaceholder(String placeholder) {
        this.placeholder = placeholder;
        return this;
    }

    public NvgTextField withText(String text) {
        value.setLength(0);
        if (text != null) value.append(text);
        return this;
    }

    public String getText() {
        return value.toString();
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
        if (!visible) focused = false;
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public void render(NVGRenderer nvg) {
        nvg.roundedRect(x, y, w, h, 4f, focused ? Theme.FIELD_BG_FOCUSED : Theme.FIELD_BG);
        if (focused) {
            nvg.rectOutline(x, y, w, h, 4f, 1.5f, Theme.FIELD_BORDER_FOCUSED);
        }

        boolean hasValue = value.length() > 0;
        String shown = hasValue ? value.toString() : placeholder;
        int color = hasValue ? Theme.TEXT_PRIMARY : Theme.TEXT_PLACEHOLDER;
        String suffix = focused ? "|" : "";

        // Longer input than the box: keep the tail visible (what was just typed) rather
        // than letting it spill past the field's edge into whatever's next to it.
        float maxTextWidth = w - 16f;
        if (hasValue) {
            while (shown.length() > 1 && nvg.textWidth(Fonts.REGULAR, shown + suffix, 13f) > maxTextWidth) {
                shown = shown.substring(1);
            }
        }

        nvg.pushScissor(x, y, w, h);
        nvg.text(Fonts.REGULAR, shown + suffix, x + 8f, y + (h - 13f) / 2f, 13f, color);
        nvg.popScissor();
    }

    @Override
    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
    }

    @Override
    public boolean mousePressed(double mouseX, double mouseY, int button) {
        focused = true;
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (!focused) return false;
        // Never swallow Escape, even while focused - otherwise NVGScreen's own "Escape closes
        // the screen" fallback (checked only after every component declines the key) never runs,
        // and the whole GUI becomes unclosable by keyboard the moment any field has been clicked.
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            return false;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE && value.length() > 0) {
            value.deleteCharAt(value.length() - 1);
            return true;
        }
        return focused;
    }

    @Override
    public boolean charTyped(char ch, int modifiers) {
        if (!focused) return false;
        if (ch >= 32 && value.length() < MAX_LENGTH) {
            value.append(ch);
        }
        return true;
    }
}

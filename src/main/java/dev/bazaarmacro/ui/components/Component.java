package dev.bazaarmacro.ui.components;

import dev.bazaarmacro.renderer.NVGRenderer;

/** Minimal interactive widget contract for {@link dev.bazaarmacro.renderer.NVGScreen}. */
public interface Component {
    void render(NVGRenderer nvg);

    boolean contains(double mouseX, double mouseY);

    default boolean isVisible() {
        return true;
    }

    default boolean isEnabled() {
        return true;
    }

    default boolean mousePressed(double mouseX, double mouseY, int button) {
        return false;
    }

    default boolean mouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }

    default boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        return false;
    }

    default boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return false;
    }

    default boolean keyPressed(int key, int scancode, int modifiers) {
        return false;
    }

    default boolean charTyped(char ch, int modifiers) {
        return false;
    }
}

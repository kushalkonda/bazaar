package dev.bazaarmacro.renderer;

import dev.bazaarmacro.ui.components.Component;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for screens that render their UI with NanoVG instead of vanilla widgets.
 *
 * <p>Subclasses override {@link #renderNVG(NVGRenderer)} to draw custom content and use
 * {@link #addComponent(Component)} to register interactive widgets (buttons, text fields).
 * All drawing is deferred to {@link BmRenderQueue} and flushed by {@code MixinGuiRenderer}
 * after Minecraft's own GUI render pass, matching this Minecraft version's split
 * render-state-extraction pipeline.
 */
public abstract class NVGScreen extends Screen {

    private final List<Component> components = new ArrayList<>();

    protected NVGScreen(String title) {
        super(net.minecraft.network.chat.Component.literal(title));
    }

    @Override
    protected void init() {
        components.clear();
        initNVG();
    }

    /** Called after init/resize. Add components via {@link #addComponent(Component)}. */
    protected void initNVG() {
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        BmRenderQueue.enqueue(this::renderQueued);
    }

    private void renderQueued() {
        if (net.minecraft.client.Minecraft.getInstance().screen != this) {
            return;
        }
        if (!NanoVGManager.isInitialized()) NanoVGManager.init();
        NanoVGManager.beginFrame(width, height);
        NVGRenderer nvg = NanoVGManager.getRenderer();
        try {
            renderNVG(nvg);
            for (Component c : components) {
                if (c.isVisible()) c.render(nvg);
            }
        } finally {
            NanoVGManager.endFrame();
        }
    }

    /** Override to draw custom NanoVG content before components. */
    protected void renderNVG(NVGRenderer nvg) {
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        double mx = click.x(), my = click.y();
        int btn = click.button();
        for (int i = components.size() - 1; i >= 0; i--) {
            Component c = components.get(i);
            if (c.isVisible() && c.isEnabled() && c.contains(mx, my)) {
                if (c.mousePressed(mx, my, btn)) return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        double mx = click.x(), my = click.y();
        int btn = click.button();
        for (int i = components.size() - 1; i >= 0; i--) {
            Component c = components.get(i);
            if (c.isVisible() && c.isEnabled() && c.mouseReleased(mx, my, btn)) return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double deltaX, double deltaY) {
        double mx = click.x(), my = click.y();
        int btn = click.button();
        for (int i = components.size() - 1; i >= 0; i--) {
            Component c = components.get(i);
            if (c.isVisible() && c.isEnabled() && c.mouseDragged(mx, my, btn, deltaX, deltaY)) return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        // Purely for hover-highlight side effects (e.g. NvgButton) - contains() records
        // whether the pointer is over each component even when nothing is clicked/scrolled.
        for (Component c : components) {
            if (c.isVisible()) c.contains(mouseX, mouseY);
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        for (int i = components.size() - 1; i >= 0; i--) {
            Component c = components.get(i);
            if (c.isVisible() && c.isEnabled() && c.contains(mouseX, mouseY)) {
                if (c.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        int key = input.key(), scan = input.scancode(), mods = input.modifiers();
        for (int i = components.size() - 1; i >= 0; i--) {
            Component c = components.get(i);
            if (c.isVisible() && c.isEnabled() && c.keyPressed(key, scan, mods)) return true;
        }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        char ch = (char) input.codepoint();
        for (int i = components.size() - 1; i >= 0; i--) {
            Component c = components.get(i);
            if (c.isVisible() && c.isEnabled() && c.charTyped(ch, 0)) return true;
        }
        return super.charTyped(input);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor g, int mx, int my, float delta) {
    }

    @Override
    public java.util.List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children() {
        return java.util.List.of();
    }

    protected <T extends Component> T addComponent(T component) {
        components.add(component);
        return component;
    }

    protected void removeComponent(Component component) {
        components.remove(component);
    }
}

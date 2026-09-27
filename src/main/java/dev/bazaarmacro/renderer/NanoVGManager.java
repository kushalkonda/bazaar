package dev.bazaarmacro.renderer;

import com.mojang.blaze3d.opengl.DirectStateAccess;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.bazaarmacro.mixin.AccessorGlDevice;
import dev.bazaarmacro.mixin.AccessorGpuDevice;
import net.minecraft.client.Minecraft;
import org.lwjgl.nanovg.NanoVG;
import org.lwjgl.nanovg.NanoVGGL3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33C;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Singleton manager for the NanoVG rendering context.
 *
 * <p>Call {@link #init()} once (lazily, on first use) before any NanoVG rendering,
 * and pair every {@link #beginFrame(float, float)} with an {@link #endFrame()}.
 */
public final class NanoVGManager {

    private static long vg = -1L;
    private static NVGRenderer renderer;
    private static boolean initialized = false;
    private static boolean drawing = false;

    private static int savedSampler = 0;
    private static int savedFbo = 0;
    private static int savedProgram = 0;
    private static int savedVao = 0;

    private static final Map<String, Integer> fontIds = new HashMap<>();
    private static final Map<String, ByteBuffer> fontBuffers = new HashMap<>();

    private NanoVGManager() {
    }

    public static void init() {
        if (initialized) return;

        vg = NanoVGGL3.nvgCreate(NanoVGGL3.NVG_ANTIALIAS | NanoVGGL3.NVG_STENCIL_STROKES);
        if (vg == -1L) {
            throw new RuntimeException("[BazaarMacro] Failed to create NanoVG context");
        }

        renderer = new NVGRenderer(vg);

        loadFont(Fonts.REGULAR, "/assets/bazaarmacro/fonts/Inter-Regular.otf");
        loadFont(Fonts.BOLD, "/assets/bazaarmacro/fonts/Inter-Bold.otf");

        initialized = true;
    }

    public static void beginFrame(float width, float height) {
        if (!initialized) throw new IllegalStateException("[BazaarMacro] NanoVGManager.init() must be called first");
        if (drawing) throw new IllegalStateException("[BazaarMacro] endFrame() was not called before beginFrame()");

        float computedRatio = 1f;
        try {
            var rt = Minecraft.getInstance().getMainRenderTarget();
            savedFbo = GL30.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            int mcFbo = ((GlTexture) rt.getColorTexture()).getFbo(resolveDirectStateAccess(), null);
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, mcFbo);
            GlStateManager._viewport(0, 0, rt.width, rt.height);
            computedRatio = (float) rt.width / width;
        } catch (RuntimeException | LinkageError e) {
            System.err.println("[BazaarMacro] Failed to bind main render target for NanoVG: " + e.getMessage());
            savedFbo = GL30.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        }

        savedProgram = GL11.glGetInteger(0x8B8D /* GL_CURRENT_PROGRAM */);
        savedVao = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

        GlStateManager._activeTexture(GL30.GL_TEXTURE0);
        savedSampler = GL33C.glGetInteger(GL33C.GL_SAMPLER_BINDING);
        GL33C.glBindSampler(0, 0);

        NanoVG.nvgBeginFrame(vg, width, height, computedRatio);
        NanoVG.nvgTextAlign(vg, NanoVG.NVG_ALIGN_LEFT | NanoVG.NVG_ALIGN_TOP);
        drawing = true;
    }

    public static void endFrame() {
        if (!drawing) throw new IllegalStateException("[BazaarMacro] beginFrame() was not called before endFrame()");

        NanoVG.nvgEndFrame(vg);

        GlStateManager._glUseProgram(savedProgram);
        GlStateManager._disableCull();
        GlStateManager._disableDepthTest();
        GlStateManager._enableBlend();
        GlStateManager._blendFuncSeparate(770, 771, 1, 0);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glColorMask(true, true, true, true);

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, savedFbo);
        GlStateManager._activeTexture(GL30.GL_TEXTURE0);
        GlStateManager._bindTexture(0);
        GL33C.glBindSampler(0, savedSampler);
        GL30.glBindVertexArray(savedVao);

        drawing = false;
    }

    public static NVGRenderer getRenderer() {
        return renderer;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    private static DirectStateAccess resolveDirectStateAccess() {
        return ((AccessorGlDevice) ((AccessorGpuDevice) RenderSystem.getDevice()).bm$getBackend())
                .bm$directStateAccess();
    }

    public static int getFontId(String name) {
        return fontIds.getOrDefault(name, -1);
    }

    private static void loadFont(String name, String resourcePath) {
        if (fontIds.containsKey(name)) return;
        try (InputStream in = NanoVGManager.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                System.err.println("[BazaarMacro] Font resource not found: " + resourcePath);
                return;
            }
            byte[] bytes = in.readAllBytes();
            ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length)
                    .order(ByteOrder.nativeOrder())
                    .put(bytes);
            buffer.flip();

            int id = NanoVG.nvgCreateFontMem(vg, name, buffer, false);
            if (id == -1) {
                System.err.println("[BazaarMacro] NanoVG failed to load font: " + name);
                return;
            }
            fontIds.put(name, id);
            fontBuffers.put(name, buffer);
        } catch (IOException e) {
            System.err.println("[BazaarMacro] IOException loading font " + name + ": " + e.getMessage());
        }
    }
}

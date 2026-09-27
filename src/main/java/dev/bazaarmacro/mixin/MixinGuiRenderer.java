package dev.bazaarmacro.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import dev.bazaarmacro.flipper.FlipperHud;
import dev.bazaarmacro.renderer.BmRenderQueue;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flushes queued NanoVG draw calls after Minecraft's own GUI render pass each frame, then draws
 * the profit HUD - both need to run every frame regardless of whether a screen is open, and this
 * injection point already fires unconditionally (it's what makes our own screens render at all).
 */
@Mixin(GuiRenderer.class)
public class MixinGuiRenderer {
    @Inject(method = "render", at = @At("TAIL"))
    private void bm$flushQueuedNvg(GpuBufferSlice fog, CallbackInfo ci) {
        BmRenderQueue.flush();
        FlipperHud.renderIfActive();
    }
}

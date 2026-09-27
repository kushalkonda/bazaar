package dev.bazaarmacro.mixin;

import dev.bazaarmacro.macro.MacroRecorder;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes every real slot click (player-driven, not macro-driven) so {@link MacroRecorder}
 * can capture it. This fires for our own synthetic clicks too (performSlotClick routes
 * through the same method), so recording and macro execution are kept mutually exclusive
 * elsewhere rather than trying to distinguish "real" from "synthetic" clicks here.
 */
@Mixin(AbstractContainerScreen.class)
public class MixinAbstractContainerScreenRecorder {
    @Inject(method = "slotClicked", at = @At("HEAD"))
    private void bm$recordSlotClick(Slot slot, int slotId, int mouseButton, ContainerInput type, CallbackInfo ci) {
        MacroRecorder.recordSlotClick(slotId, mouseButton, type);
    }
}

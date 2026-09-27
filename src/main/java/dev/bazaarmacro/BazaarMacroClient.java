package dev.bazaarmacro;

import dev.bazaarmacro.alerts.PriceAlertManager;
import dev.bazaarmacro.command.BfmCommandRegistrar;
import dev.bazaarmacro.flipper.CancelRejectionWatcher;
import dev.bazaarmacro.flipper.LobbyKickWatcher;
import dev.bazaarmacro.flipper.OrderSetupWatcher;
import dev.bazaarmacro.flipper.SellClaimWatcher;
import dev.bazaarmacro.macro.DefaultMacros;
import dev.bazaarmacro.macro.MacroRecorder;
import dev.bazaarmacro.util.ClientUtils;
import dev.bazaarmacro.util.ErrorReporter;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class BazaarMacroClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BfmCommandRegistrar.register();
        DefaultMacros.seedIfMissing();
        PriceAlertManager.ensureRunning();
        SellClaimWatcher.register();
        CancelRejectionWatcher.register();
        LobbyKickWatcher.register();
        OrderSetupWatcher.register();
        ErrorReporter.register();

        ClientSendMessageEvents.CHAT.register(MacroRecorder::recordChat);
        ClientSendMessageEvents.COMMAND.register(MacroRecorder::recordCommand);

        // Dumps whatever screen is currently open. Unlike /bfm debug screen, this works even
        // while a container screen has keyboard focus and the chat box can't be reached (e.g.
        // Hypixel's anvil-merge screen) - a real gap found live: the user couldn't type the debug
        // command at the exact moment it was needed.
        KeyMapping dumpScreenKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.bazaarmacro.dumpscreen", GLFW.GLFW_KEY_F9, KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (dumpScreenKey.consumeClick()) {
                ClientUtils.debugDumpOpenScreen();
            }
        });
    }
}

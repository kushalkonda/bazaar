package dev.bazaarmacro.macro;

import dev.bazaarmacro.flipper.FlipperEngine;
import dev.bazaarmacro.renderer.NVGScreen;
import dev.bazaarmacro.ui.MacroEditorScreen;
import dev.bazaarmacro.ui.MacroListScreen;
import dev.bazaarmacro.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ContainerInput;

/**
 * Records real player actions into a {@link MacroDefinition} while active: every command
 * or chat message sent, and every slot clicked in any open container screen (captured by
 * {@code MixinAbstractContainerScreenRecorder}). The real elapsed time between actions is
 * inserted as {@code WAIT} steps so playback matches the pace of what was recorded.
 *
 * <p>Mutually exclusive with {@link MacroExecutor} - only one may be active, since both
 * drive the same game-thread click/command pipeline.
 *
 * <p>Sign text isn't captured (there's no generic hook for it here); add
 * {@code SUBMIT_SIGN_TEXT} steps by hand afterward in the editor, where a math expression
 * like {@code {qty}} is usually more useful than whatever literal number was typed anyway.
 */
public final class MacroRecorder {
    private static final long MIN_RECORDED_GAP_MS = 30;

    private static volatile boolean recording = false;
    private static MacroDefinition target;
    private static String originalName;
    private static NVGScreen returnScreen;
    private static long lastActionTime;

    private MacroRecorder() {
    }

    public static boolean isRecording() {
        return recording;
    }

    /** Starts recording into {@code macroToRecordInto}, closing the GUI so real gameplay input works. */
    public static boolean start(MacroDefinition macroToRecordInto, String originalMacroName, NVGScreen screenToReturnTo) {
        if (recording) {
            ClientUtils.sendMessage("§cAlready recording - run §f/bfm record stop§c first.");
            return false;
        }
        if (MacroWorkerThread.getInstance().isRunning()) {
            ClientUtils.sendMessage("§cA macro is currently running - stop it with §f/bfm stop§c first.");
            return false;
        }
        if (FlipperEngine.isActive()) {
            ClientUtils.sendMessage("§cThe flipper is currently active - stop it with §f/bfm stop§c first.");
            return false;
        }
        if (dev.bazaarmacro.order.FlipTestScript.isActive()) {
            ClientUtils.sendMessage("§cThe flip test is currently running - stop it with §f/bfm test stop§c first.");
            return false;
        }

        target = macroToRecordInto;
        originalName = originalMacroName;
        returnScreen = screenToReturnTo;
        lastActionTime = System.currentTimeMillis();
        recording = true;

        Minecraft.getInstance().setScreen(null);
        ClientUtils.sendMessage("§dRecording started for \"" + target.name + "\".");
        ClientUtils.sendMessage("§7Every command/chat message and slot click you make will be captured.");
        ClientUtils.sendMessage("§7Run §f/bfm record stop§7 (or just §f/bfm§7) when done.");
        return true;
    }

    public static void stop() {
        if (!recording) {
            ClientUtils.sendMessage("§cNot currently recording.");
            return;
        }
        recording = false;

        MacroDefinition finished = target;
        String finishedOriginalName = originalName;
        NVGScreen screen = returnScreen;
        target = null;
        originalName = null;
        returnScreen = null;

        ClientUtils.sendMessage("§aRecording stopped - " + finished.steps.size() + " step(s) captured.");
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(
                new MacroEditorScreen(finished, finishedOriginalName, screen != null ? screen : new MacroListScreen())));
    }

    /** Called from the Fabric client-command send event; {@code commandWithoutSlash} has no leading '/'. */
    public static void recordCommand(String commandWithoutSlash) {
        recordSentText("/" + commandWithoutSlash);
    }

    /** Called from the Fabric chat-send event. */
    public static void recordChat(String message) {
        recordSentText(message);
    }

    /** Called from {@code MixinAbstractContainerScreenRecorder} on every real slot click. */
    public static void recordSlotClick(int slotIndex, int button, ContainerInput type) {
        if (!recording) return;
        insertWaitGap();
        String coordinate = SlotCoordinate.toCoordinate(slotIndex, SlotCoordinate.DEFAULT_WIDTH);
        target.steps.add(MacroStep.clickSlot(coordinate, button, type.name()));
    }

    private static void recordSentText(String text) {
        if (!recording) return;
        insertWaitGap();
        target.steps.add(MacroStep.command(text));
    }

    private static void insertWaitGap() {
        long now = System.currentTimeMillis();
        long gap = now - lastActionTime;
        if (gap >= MIN_RECORDED_GAP_MS) {
            target.steps.add(MacroStep.waitMs(gap));
        }
        lastActionTime = now;
    }
}

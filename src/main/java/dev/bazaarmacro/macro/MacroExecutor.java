package dev.bazaarmacro.macro;

import dev.bazaarmacro.bazaar.BazaarPriceClient;
import dev.bazaarmacro.flipper.FlipperEngine;
import dev.bazaarmacro.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Interprets a {@link MacroDefinition} step-by-step. {@link #run} queues onto
 * {@link MacroWorkerThread} for manually-triggered macros (the UI/`/bfm run`); autonomous
 * callers that manage their own background thread and need a true/false result to branch
 * on - namely {@link FlipperEngine} - call {@link #runBlocking} directly instead.
 */
public final class MacroExecutor {
    private static final long SLOT_CLICK_SETTLE_MS = 150;
    private static final long SIGN_SUBMIT_SETTLE_MS = 200;

    private MacroExecutor() {
    }

    public static void run(MacroDefinition macro) {
        if (MacroRecorder.isRecording()) {
            ClientUtils.sendMessage("§cCan't run a macro while recording - run §f/bfm record stop§c first.");
            return;
        }
        if (FlipperEngine.isActive()) {
            ClientUtils.sendMessage("§cCan't run a macro while the flipper is active - run §f/bfm stop§c first.");
            return;
        }
        if (dev.bazaarmacro.order.FlipTestScript.isActive()) {
            ClientUtils.sendMessage("§cCan't run a macro while the Legion craft script is active - run §f/bfm legion stop§c first.");
            return;
        }
        MacroWorkerThread.getInstance().submit("Macro-" + macro.name, () -> {
            ClientUtils.sendMessage("§aRunning macro \"" + macro.name + "\"...");
            boolean ok = runBlocking(macro, new ExecutionContext(), () -> MacroWorkerThread.getInstance().isCancelled());
            if (ok) {
                ClientUtils.sendMessage("§aMacro \"" + macro.name + "\" finished.");
            }
        });
    }

    public static void stop() {
        MacroWorkerThread.getInstance().cancel();
    }

    /**
     * Runs every step of {@code macro} in order on the calling thread - which must not be
     * the render thread, since steps block/poll - sharing {@code context} across steps for
     * {name} placeholders and COMPUTE results. Returns {@code true} if every step completed;
     * {@code false} if a step failed or {@code cancelled} became true mid-run, in which case
     * a chat message explaining why has already been sent.
     */
    public static boolean runBlocking(MacroDefinition macro, ExecutionContext context, BooleanSupplier cancelled) {
        for (int i = 0; i < macro.steps.size(); i++) {
            if (cancelled.getAsBoolean()) {
                ClientUtils.sendMessage("§cMacro \"" + macro.name + "\" stopped.");
                return false;
            }

            MacroStep step = macro.steps.get(i);
            boolean ok = executeStep(step, context, cancelled);
            if (!ok) {
                ClientUtils.sendMessage("§cMacro \"" + macro.name + "\" aborted at step " + (i + 1) + ": " + step.describe());
                return false;
            }
        }
        return true;
    }

    private static boolean executeStep(MacroStep step, ExecutionContext context, BooleanSupplier cancelled) {
        try {
            return switch (step.type) {
                case COMMAND -> {
                    ClientUtils.sendCommand(context.resolve(step.command));
                    yield true;
                }
                case WAIT -> {
                    MacroWorkerThread.sleep(step.ms);
                    yield true;
                }
                case WAIT_FOR_SCREEN -> waitForScreen(step.titleContains, step.timeoutMs, cancelled);
                case CLICK_SLOT -> clickSlot(step, context);
                case CLICK_SLOT_REAL -> clickSlotReal(step, context);
                case FIND_ITEM_SLOT -> findItemSlot(step, context);
                case COMPUTE -> compute(step, context);
                case SUBMIT_SIGN_TEXT -> submitSignText(context.resolve(step.value));
                case CLOSE_SCREEN -> {
                    ClientUtils.closeScreen();
                    yield true;
                }
            };
        } catch (Exception e) {
            ClientUtils.sendMessage("§cStep failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean waitForScreen(String titleContains, long timeoutMs, BooleanSupplier cancelled) {
        String target = ClientUtils.stripColors(titleContains).toLowerCase();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cancelled.getAsBoolean()) return false;
            AbstractContainerScreen<?> screen = ClientUtils.getOpenContainerScreen();
            if (screen != null) {
                String title = ClientUtils.stripColors(screen.getTitle().getString()).toLowerCase();
                if (title.contains(target)) return true;
            }
            MacroWorkerThread.sleep(50);
        }
        ClientUtils.sendMessage("§cTimed out waiting for screen containing \"" + titleContains + "\"");
        return false;
    }

    private static boolean clickSlot(MacroStep step, ExecutionContext context) {
        Minecraft client = Minecraft.getInstance();
        AbstractContainerScreen<?> screen = ClientUtils.getOpenContainerScreen();
        if (screen == null) {
            ClientUtils.sendMessage("§cNo container screen open for click-slot step");
            return false;
        }

        int slotIndex;
        try {
            slotIndex = SlotCoordinate.parse(context.resolve(step.coordinate));
        } catch (IllegalArgumentException e) {
            ClientUtils.sendMessage("§cInvalid slot coordinate \"" + step.coordinate + "\": " + e.getMessage());
            return false;
        }

        ContainerInput type;
        try {
            type = ContainerInput.valueOf(step.clickType);
        } catch (Exception e) {
            ClientUtils.sendMessage("§cInvalid click type \"" + step.clickType + "\"");
            return false;
        }

        client.execute(() -> {
            AbstractContainerScreen<?> current = ClientUtils.getOpenContainerScreen();
            if (current != null) {
                ClientUtils.performSlotClick(current, slotIndex, step.button, type);
            }
        });
        MacroWorkerThread.sleep(SLOT_CLICK_SETTLE_MS);
        return true;
    }

    /** See {@link ClientUtils#performRealMouseClick} - experimental alternative to {@link #clickSlot}. */
    private static boolean clickSlotReal(MacroStep step, ExecutionContext context) {
        Minecraft client = Minecraft.getInstance();
        AbstractContainerScreen<?> screen = ClientUtils.getOpenContainerScreen();
        if (screen == null) {
            ClientUtils.sendMessage("§cNo container screen open for click-slot step");
            return false;
        }

        int slotIndex;
        try {
            slotIndex = SlotCoordinate.parse(context.resolve(step.coordinate));
        } catch (IllegalArgumentException e) {
            ClientUtils.sendMessage("§cInvalid slot coordinate \"" + step.coordinate + "\": " + e.getMessage());
            return false;
        }

        client.execute(() -> {
            AbstractContainerScreen<?> current = ClientUtils.getOpenContainerScreen();
            if (current != null) {
                ClientUtils.performRealMouseClick(current, slotIndex, step.button);
            }
        });
        MacroWorkerThread.sleep(SLOT_CLICK_SETTLE_MS);
        return true;
    }

    /**
     * Scans {@code [scanStart, scanEnd]} for a slot whose colour-stripped hover name exactly
     * matches {@code itemNameContains} (case-insensitive) and clicks the first one found -
     * used for Bazaar search results, whose position isn't fixed once a search returns more
     * than one match.
     */
    private static boolean findItemSlot(MacroStep step, ExecutionContext context) {
        Minecraft client = Minecraft.getInstance();
        AbstractContainerScreen<?> screen = ClientUtils.getOpenContainerScreen();
        if (screen == null) {
            ClientUtils.sendMessage("§cNo container screen open for find-item-slot step");
            return false;
        }

        ContainerInput type;
        try {
            type = ContainerInput.valueOf(step.clickType);
        } catch (Exception e) {
            ClientUtils.sendMessage("§cInvalid click type \"" + step.clickType + "\"");
            return false;
        }

        String target = ClientUtils.stripColors(context.resolve(step.itemNameContains)).toLowerCase().trim();
        java.util.List<Slot> slots = screen.getMenu().slots;
        int end = Math.min(step.scanEnd, slots.size() - 1);

        for (int i = Math.max(0, step.scanStart); i <= end; i++) {
            Slot slot = slots.get(i);
            if (!slot.hasItem()) continue;
            String name = ClientUtils.stripColors(slot.getItem().getHoverName().getString()).toLowerCase().trim();
            if (name.equals(target)) {
                int slotIndex = i;
                client.execute(() -> {
                    AbstractContainerScreen<?> current = ClientUtils.getOpenContainerScreen();
                    if (current != null) {
                        ClientUtils.performSlotClick(current, slotIndex, step.button, type);
                    }
                });
                MacroWorkerThread.sleep(SLOT_CLICK_SETTLE_MS);
                return true;
            }
        }

        ClientUtils.sendMessage("§cCould not find an item matching \"" + step.itemNameContains
                + "\" in slots " + step.scanStart + "-" + end);
        return false;
    }

    /**
     * Evaluates {@code step.expression} against every earlier variable plus {@code purse}
     * and (if {@code itemNameOrTag} is set) the item's live {@code buyPrice}/{@code sellPrice},
     * then stores the result under {@code step.resultVar}.
     */
    private static boolean compute(MacroStep step, ExecutionContext context) {
        Map<String, Double> variables = new HashMap<>(context.asVariableMap());
        variables.put("purse", (double) ClientUtils.getPurse());

        String item = context.resolve(step.itemNameOrTag);
        if (item != null && !item.isBlank()) {
            BazaarPriceClient.Snapshot snapshot;
            try {
                snapshot = BazaarPriceClient.getSnapshotByName(item);
            } catch (Exception e) {
                ClientUtils.sendMessage("§cFailed to fetch bazaar price for \"" + item + "\": " + e.getMessage());
                return false;
            }
            variables.put("buyprice", snapshot.buyPrice());
            variables.put("sellprice", snapshot.sellPrice());
        }

        double result;
        try {
            result = MacroExpression.evaluate(context.resolve(step.expression), variables);
        } catch (Exception e) {
            ClientUtils.sendMessage("§cExpression error in \"" + step.expression + "\": " + e.getMessage());
            return false;
        }

        if (!Double.isFinite(result)) {
            ClientUtils.sendMessage("§cExpression \"" + step.expression + "\" did not produce a valid number");
            return false;
        }

        String varName = (step.resultVar == null || step.resultVar.isBlank()) ? "result" : step.resultVar;
        context.set(varName, result);
        ClientUtils.sendMessage("§7" + varName + " = §e" + ExecutionContext.format(result));
        return true;
    }

    private static boolean submitSignText(String text) {
        Minecraft client = Minecraft.getInstance();
        if (!(client.screen instanceof AbstractSignEditScreen)) {
            ClientUtils.sendMessage("§cNo sign-edit screen open for submit-sign-text step");
            return false;
        }

        client.execute(() -> {
            if (!(client.screen instanceof AbstractSignEditScreen signScreen)) return;
            signScreen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_A, 0, GLFW.GLFW_MOD_CONTROL));
            signScreen.keyPressed(new KeyEvent(GLFW.GLFW_KEY_DELETE, 0, 0));
            for (char c : text.toCharArray()) {
                signScreen.charTyped(new CharacterEvent(c));
            }
        });
        MacroWorkerThread.sleep(SIGN_SUBMIT_SETTLE_MS);

        client.execute(() -> {
            if (client.screen instanceof AbstractSignEditScreen signScreen) {
                signScreen.onClose();
            }
        });
        MacroWorkerThread.sleep(SIGN_SUBMIT_SETTLE_MS);
        return true;
    }
}

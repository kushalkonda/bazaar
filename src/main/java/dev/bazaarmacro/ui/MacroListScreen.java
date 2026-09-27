package dev.bazaarmacro.ui;

import dev.bazaarmacro.macro.MacroDefinition;
import dev.bazaarmacro.macro.MacroExecutor;
import dev.bazaarmacro.macro.MacroRegistry;
import dev.bazaarmacro.renderer.Fonts;
import dev.bazaarmacro.renderer.NVGRenderer;
import dev.bazaarmacro.renderer.NVGScreen;
import dev.bazaarmacro.renderer.Theme;
import dev.bazaarmacro.ui.components.NvgButton;
import net.minecraft.client.Minecraft;

import java.util.List;

/** Lists saved macros with Run/Edit/Delete actions and a button to create a new one. */
public class MacroListScreen extends NVGScreen {
    private static final float PANEL_WIDTH = 460f;
    private static final float PANEL_TOP = 30f;
    private static final float HEADER_HEIGHT = 54f;
    private static final float ROW_HEIGHT = 38f;
    private static final float ROW_GAP = 6f;
    private static final float SIDE_PADDING = 16f;

    // Row action buttons, laid out additively from the row's right edge - no manual
    // offset math to get wrong, so they can never end up overlapping.
    private static final float ACTION_BUTTON_WIDTH = 64f;
    private static final float ACTION_BUTTON_GAP = 8f;
    private static final float ACTION_BUTTON_HEIGHT = 26f;

    public MacroListScreen() {
        super("Bazaar Macro");
    }

    @Override
    protected void initNVG() {
        List<MacroDefinition> macros = MacroRegistry.all();
        float panelX = (width - PANEL_WIDTH) / 2f;
        float rowsTop = PANEL_TOP + HEADER_HEIGHT;

        for (int i = 0; i < macros.size(); i++) {
            MacroDefinition macro = macros.get(i);
            float rowY = rowsTop + i * (ROW_HEIGHT + ROW_GAP);
            float buttonY = rowY + (ROW_HEIGHT - ACTION_BUTTON_HEIGHT) / 2f;
            float rowRight = panelX + PANEL_WIDTH - SIDE_PADDING;

            float deleteX = rowRight - ACTION_BUTTON_WIDTH;
            float editX = deleteX - ACTION_BUTTON_GAP - ACTION_BUTTON_WIDTH;
            float runX = editX - ACTION_BUTTON_GAP - ACTION_BUTTON_WIDTH;

            addComponent(new NvgButton(runX, buttonY, ACTION_BUTTON_WIDTH, ACTION_BUTTON_HEIGHT, "Run",
                    () -> runMacro(macro)));
            addComponent(new NvgButton(editX, buttonY, ACTION_BUTTON_WIDTH, ACTION_BUTTON_HEIGHT, "Edit",
                    () -> Minecraft.getInstance().setScreen(new MacroEditorScreen(macro.copy(), macro.name, this))));
            addComponent(new NvgButton(deleteX, buttonY, ACTION_BUTTON_WIDTH, ACTION_BUTTON_HEIGHT, "Delete",
                    () -> {
                        MacroRegistry.delete(macro.name);
                        Minecraft.getInstance().setScreen(new MacroListScreen());
                    }));
        }

        float newButtonY = rowsTop + macros.size() * (ROW_HEIGHT + ROW_GAP) + 8f;
        addComponent(new NvgButton(panelX + SIDE_PADDING, newButtonY, PANEL_WIDTH - SIDE_PADDING * 2, 30,
                "+ New Macro",
                () -> Minecraft.getInstance().setScreen(new MacroEditorScreen(new MacroDefinition("New Macro"), null, this))));
    }

    private void runMacro(MacroDefinition macro) {
        Minecraft.getInstance().setScreen(null);
        MacroExecutor.run(macro);
    }

    @Override
    protected void renderNVG(NVGRenderer nvg) {
        List<MacroDefinition> macros = MacroRegistry.all();
        float panelX = (width - PANEL_WIDTH) / 2f;
        float rowsTop = PANEL_TOP + HEADER_HEIGHT;
        float panelHeight = HEADER_HEIGHT + macros.size() * (ROW_HEIGHT + ROW_GAP) + 46f;

        nvg.roundedRect(panelX, PANEL_TOP, PANEL_WIDTH, panelHeight, 10f, Theme.PANEL_BG);
        nvg.rectOutline(panelX, PANEL_TOP, PANEL_WIDTH, panelHeight, 10f, 1.2f, Theme.PANEL_BORDER);

        nvg.text(Fonts.BOLD, "Advanced Macros", panelX + SIDE_PADDING, PANEL_TOP + 14f, 17f, Theme.ACCENT_PINK);
        nvg.text(Fonts.REGULAR, "/bfm gui", panelX + PANEL_WIDTH - SIDE_PADDING - nvg.textWidth(Fonts.REGULAR, "/bfm gui", 12f),
                PANEL_TOP + 18f, 12f, Theme.TEXT_SECONDARY);
        nvg.line(panelX + SIDE_PADDING, PANEL_TOP + HEADER_HEIGHT - 8f, panelX + PANEL_WIDTH - SIDE_PADDING,
                PANEL_TOP + HEADER_HEIGHT - 8f, 1f, Theme.PANEL_BORDER);

        if (macros.isEmpty()) {
            nvg.text(Fonts.REGULAR, "No macros yet - click \"+ New Macro\" to build one.",
                    panelX + SIDE_PADDING, rowsTop + 10f, 13f, Theme.TEXT_SECONDARY);
        }

        for (int i = 0; i < macros.size(); i++) {
            MacroDefinition macro = macros.get(i);
            float rowY = rowsTop + i * (ROW_HEIGHT + ROW_GAP);
            nvg.roundedRect(panelX + SIDE_PADDING, rowY, PANEL_WIDTH - SIDE_PADDING * 2, ROW_HEIGHT, 6f,
                    i % 2 == 0 ? Theme.ROW_BG : Theme.ROW_BG_ALT);
            nvg.text(Fonts.REGULAR, macro.name, panelX + SIDE_PADDING + 10f, rowY + 8f, 13f, Theme.TEXT_PRIMARY);
            nvg.text(Fonts.REGULAR, macro.steps.size() + " steps", panelX + SIDE_PADDING + 10f, rowY + 22f, 10f,
                    Theme.TEXT_SECONDARY);
        }
    }
}

package dev.bazaarmacro.ui;

import dev.bazaarmacro.macro.BazaarTemplates;
import dev.bazaarmacro.macro.MacroDefinition;
import dev.bazaarmacro.macro.MacroRecorder;
import dev.bazaarmacro.macro.MacroRegistry;
import dev.bazaarmacro.macro.MacroStep;
import dev.bazaarmacro.renderer.Fonts;
import dev.bazaarmacro.renderer.NVGRenderer;
import dev.bazaarmacro.renderer.NVGScreen;
import dev.bazaarmacro.renderer.Theme;
import dev.bazaarmacro.ui.components.NvgButton;
import dev.bazaarmacro.ui.components.NvgTextField;
import net.minecraft.client.Minecraft;

import java.util.Collections;

/**
 * Editor for a single macro: rename it, record or hand-build its steps, drop in a
 * Bazaar transaction template, and reorder/delete steps afterward.
 *
 * <p>Every group of buttons is laid out additively (each button's position is derived
 * from the previous one plus a fixed gap, anchored to either the left or right edge of
 * the panel) rather than with independent hand-picked offsets - that's what guarantees
 * button rects can never overlap, instead of relying on checking the arithmetic by eye.
 * Section Y-coordinates chain the same way: each one is "end of the previous section
 * plus a gap", so adding a new section never requires re-deriving the ones below it.
 *
 * <p>Any mutation (add/remove/reorder a step, switch the pending add-type) simply calls
 * {@link #init()} again to rebuild the whole component list from {@link #macro} and
 * {@link #pendingType} - simpler and less error-prone than incrementally patching a
 * live widget tree for a screen this size.
 */
public class MacroEditorScreen extends NVGScreen {
    private static final float PANEL_WIDTH = 600f;
    private static final float PANEL_TOP = 30f;
    private static final float SIDE_PADDING = 16f;
    private static final float SECTION_GAP = 16f;
    private static final float LABEL_HEIGHT = 18f;
    private static final float CONTENT_WIDTH = PANEL_WIDTH - SIDE_PADDING * 2;

    private static final float TITLE_Y = PANEL_TOP + 14f;
    private static final float DIVIDER_Y = PANEL_TOP + 40f;

    private static final float NAME_ROW_Y = PANEL_TOP + 50f;
    private static final float NAME_ROW_HEIGHT = 28f;
    private static final float HEADER_BUTTON_WIDTH = 64f;
    private static final float HEADER_BUTTON_GAP = 8f;

    private static final float TEMPLATE_LABEL_Y = NAME_ROW_Y + NAME_ROW_HEIGHT + SECTION_GAP;
    private static final float TEMPLATE_FIELDS_Y = TEMPLATE_LABEL_Y + LABEL_HEIGHT;
    private static final float TEMPLATE_BUTTONS_Y = TEMPLATE_FIELDS_Y + 26f + 8f;
    private static final float TEMPLATE_ROW_HEIGHT = 26f;
    private static final float TEMPLATE_GAP = 10f;

    private static final float STEPS_LABEL_Y = TEMPLATE_BUTTONS_Y + TEMPLATE_ROW_HEIGHT + SECTION_GAP;
    private static final float STEPS_TOP = STEPS_LABEL_Y + LABEL_HEIGHT;
    private static final float STEP_ROW_HEIGHT = 34f;
    private static final float STEP_ROW_GAP = 6f;
    private static final float STEP_BUTTON_WIDTH = 56f;
    private static final float STEP_BUTTON_HEIGHT = 24f;
    private static final float STEP_BUTTON_GAP = 8f;

    private static final float TYPE_BUTTON_HEIGHT = 26f;
    private static final float TYPE_BUTTON_GAP = 8f;

    private static final float FORM_FIELD_HEIGHT = 26f;
    private static final float FORM_FIELD_GAP = 10f;
    private static final float FORM_FIELD1_WIDTH = 230f;
    private static final float FORM_FIELD2_WIDTH = 120f;
    private static final float FORM_FIELD3_WIDTH = 110f;
    private static final float FORM_ADD_BUTTON_WIDTH = 78f;

    /** Step types the "Add step" form can create - every type is creatable. */
    private static final MacroStep.StepType[] ADDABLE_TYPES = MacroStep.StepType.values();

    private final MacroDefinition macro;
    private final String originalName;
    private final NVGScreen parent;

    private String workingName;
    private MacroStep.StepType pendingType = MacroStep.StepType.COMMAND;

    private NvgTextField nameField;
    private NvgTextField templateItemField;
    private NvgTextField templateExprField;
    private NvgTextField field1;
    private NvgTextField field2;
    private NvgTextField field3;

    public MacroEditorScreen(MacroDefinition macro, String originalName, NVGScreen parent) {
        super("Edit Macro");
        this.macro = macro;
        this.originalName = originalName;
        this.parent = parent;
        this.workingName = macro.name;
    }

    @Override
    protected void initNVG() {
        float panelX = (width - PANEL_WIDTH) / 2f;

        buildHeaderRow(panelX);
        buildTemplateSection(panelX);
        buildStepRows(panelX);

        float typeButtonsY = addTypeButtonsY();
        buildTypeButtons(panelX, typeButtonsY);
        buildAddForm(panelX, typeButtonsY + TYPE_BUTTON_HEIGHT + SECTION_GAP);
    }

    private void buildHeaderRow(float panelX) {
        float rightEdge = panelX + PANEL_WIDTH - SIDE_PADDING;
        float cancelX = rightEdge - HEADER_BUTTON_WIDTH;
        float saveX = cancelX - HEADER_BUTTON_GAP - HEADER_BUTTON_WIDTH;
        float recordX = saveX - HEADER_BUTTON_GAP - HEADER_BUTTON_WIDTH;
        float nameFieldWidth = recordX - HEADER_BUTTON_GAP - (panelX + SIDE_PADDING);

        nameField = addComponent(new NvgTextField(panelX + SIDE_PADDING, NAME_ROW_Y, nameFieldWidth, NAME_ROW_HEIGHT)
                .withPlaceholder("Macro name")
                .withText(workingName));
        addComponent(new NvgButton(recordX, NAME_ROW_Y, HEADER_BUTTON_WIDTH, NAME_ROW_HEIGHT, "Record", this::onRecord));
        addComponent(new NvgButton(saveX, NAME_ROW_Y, HEADER_BUTTON_WIDTH, NAME_ROW_HEIGHT, "Save", this::onSave));
        addComponent(new NvgButton(cancelX, NAME_ROW_Y, HEADER_BUTTON_WIDTH, NAME_ROW_HEIGHT, "Cancel", this::onCancel));
    }

    private void buildTemplateSection(float panelX) {
        float x1 = panelX + SIDE_PADDING;
        float fieldWidth = (CONTENT_WIDTH - TEMPLATE_GAP) / 2f;
        float x2 = x1 + fieldWidth + TEMPLATE_GAP;

        templateItemField = addComponent(new NvgTextField(x1, TEMPLATE_FIELDS_Y, fieldWidth, TEMPLATE_ROW_HEIGHT)
                .withPlaceholder("item name, e.g. Cobblestone"));
        templateExprField = addComponent(new NvgTextField(x2, TEMPLATE_FIELDS_Y, fieldWidth, TEMPLATE_ROW_HEIGHT)
                .withPlaceholder("qty or expression, e.g. floor(purse*0.25/buyPrice)"));

        // Only Instant Buy has verified click coordinates - see BazaarTemplates. The other three
        // Bazaar flows used to have buttons here too, but each inserted a macro whose every click
        // was an unverified "?" placeholder that always failed on the first step; recording them
        // with /bfm record is the only way that actually works.
        addComponent(new NvgButton(x1, TEMPLATE_BUTTONS_Y, CONTENT_WIDTH, TEMPLATE_ROW_HEIGHT, "Insta Buy",
                this::insertInstantBuyTemplate));
    }

    private void insertInstantBuyTemplate() {
        String item = templateItemField.getText().trim();
        String expr = templateExprField.getText().trim();
        if (item.isBlank() || expr.isBlank()) {
            dev.bazaarmacro.util.ClientUtils.sendMessage("§cFill in an item and a quantity/expression above first.");
            return;
        }
        BazaarTemplates.insertInstantBuy(macro, item, expr);
        init();
    }

    private void buildStepRows(float panelX) {
        float rowRight = panelX + PANEL_WIDTH - SIDE_PADDING;
        float deleteX = rowRight - STEP_BUTTON_WIDTH;
        float downX = deleteX - STEP_BUTTON_GAP - STEP_BUTTON_WIDTH;
        float upX = downX - STEP_BUTTON_GAP - STEP_BUTTON_WIDTH;

        for (int i = 0; i < macro.steps.size(); i++) {
            int index = i;
            float rowY = STEPS_TOP + i * (STEP_ROW_HEIGHT + STEP_ROW_GAP);
            float buttonY = rowY + (STEP_ROW_HEIGHT - STEP_BUTTON_HEIGHT) / 2f;

            NvgButton up = new NvgButton(upX, buttonY, STEP_BUTTON_WIDTH, STEP_BUTTON_HEIGHT, "Up", () -> moveStep(index, -1));
            up.setEnabled(index > 0);
            addComponent(up);

            NvgButton down = new NvgButton(downX, buttonY, STEP_BUTTON_WIDTH, STEP_BUTTON_HEIGHT, "Down", () -> moveStep(index, 1));
            down.setEnabled(index < macro.steps.size() - 1);
            addComponent(down);

            addComponent(new NvgButton(deleteX, buttonY, STEP_BUTTON_WIDTH, STEP_BUTTON_HEIGHT, "Delete", () -> removeStep(index)));
        }
    }

    /** Bottom Y of the step list (top of the next section starts here). */
    private float stepsBottom() {
        return STEPS_TOP + macro.steps.size() * (STEP_ROW_HEIGHT + STEP_ROW_GAP);
    }

    private float addTypeButtonsY() {
        return stepsBottom() + SECTION_GAP + LABEL_HEIGHT;
    }

    private void buildTypeButtons(float panelX, float y) {
        float available = CONTENT_WIDTH - (ADDABLE_TYPES.length - 1) * TYPE_BUTTON_GAP;
        float buttonWidth = available / ADDABLE_TYPES.length;

        for (int i = 0; i < ADDABLE_TYPES.length; i++) {
            MacroStep.StepType type = ADDABLE_TYPES[i];
            float x = panelX + SIDE_PADDING + i * (buttonWidth + TYPE_BUTTON_GAP);
            NvgButton button = new NvgButton(x, y, buttonWidth, TYPE_BUTTON_HEIGHT, shortLabel(type), () -> {
                pendingType = type;
                init();
            });
            button.setSelected(type == pendingType);
            addComponent(button);
        }
    }

    private void buildAddForm(float panelX, float y) {
        float x1 = panelX + SIDE_PADDING;
        float x2 = x1 + FORM_FIELD1_WIDTH + FORM_FIELD_GAP;
        float x3 = x2 + FORM_FIELD2_WIDTH + FORM_FIELD_GAP;
        float xAdd = x3 + FORM_FIELD3_WIDTH + FORM_FIELD_GAP;

        field1 = addComponent(new NvgTextField(x1, y, FORM_FIELD1_WIDTH, FORM_FIELD_HEIGHT));
        field2 = addComponent(new NvgTextField(x2, y, FORM_FIELD2_WIDTH, FORM_FIELD_HEIGHT));
        field3 = addComponent(new NvgTextField(x3, y, FORM_FIELD3_WIDTH, FORM_FIELD_HEIGHT));

        switch (pendingType) {
            case COMMAND -> field1.withPlaceholder("/bz {itemName} or literal command");
            case WAIT -> field1.withPlaceholder("milliseconds, e.g. 500");
            case WAIT_FOR_SCREEN -> {
                field1.withPlaceholder("title contains, e.g. Bazaar");
                field2.withPlaceholder("timeout ms, e.g. 8000");
            }
            case CLICK_SLOT -> {
                field1.withPlaceholder("coordinate, e.g. B2");
                field2.withPlaceholder("button (0=left)");
                field3.withPlaceholder("PICKUP/QUICK_MOVE/THROW");
            }
            case FIND_ITEM_SLOT -> {
                field1.withPlaceholder("item name to find, e.g. Cobblestone (scans slots 10-42)");
                field2.withPlaceholder("button (0=left)");
                field3.withPlaceholder("PICKUP/QUICK_MOVE/THROW");
            }
            case COMPUTE -> {
                field1.withPlaceholder("expression, e.g. floor(purse*0.25/buyPrice)");
                field2.withPlaceholder("result name (default: result)");
                field3.withPlaceholder("item for buyPrice/sellPrice (optional)");
            }
            case SUBMIT_SIGN_TEXT -> field1.withPlaceholder("value, e.g. {qty}");
            case CLOSE_SCREEN -> {
            }
        }

        field2.setVisible(pendingType == MacroStep.StepType.WAIT_FOR_SCREEN
                || pendingType == MacroStep.StepType.CLICK_SLOT
                || pendingType == MacroStep.StepType.FIND_ITEM_SLOT
                || pendingType == MacroStep.StepType.COMPUTE);
        field3.setVisible(pendingType == MacroStep.StepType.CLICK_SLOT
                || pendingType == MacroStep.StepType.FIND_ITEM_SLOT
                || pendingType == MacroStep.StepType.COMPUTE);
        field1.setVisible(pendingType != MacroStep.StepType.CLOSE_SCREEN);

        addComponent(new NvgButton(xAdd, y, FORM_ADD_BUTTON_WIDTH, FORM_FIELD_HEIGHT, "Add Step", this::addPendingStep));
    }

    private void addPendingStep() {
        String f1 = field1.getText().trim();
        String f2 = field2.getText().trim();
        String f3 = field3.getText().trim();

        MacroStep step = switch (pendingType) {
            case COMMAND -> MacroStep.command(f1);
            case WAIT -> MacroStep.waitMs(parseLong(f1, 500));
            case WAIT_FOR_SCREEN -> MacroStep.waitForScreen(f1, parseLong(f2, 8000));
            case CLICK_SLOT -> MacroStep.clickSlot(f1, (int) parseLong(f2, 0), f3.isBlank() ? "PICKUP" : f3.toUpperCase());
            case CLICK_SLOT_REAL -> MacroStep.clickSlotReal(f1, (int) parseLong(f2, 0));
            case FIND_ITEM_SLOT -> MacroStep.findItemSlot(f1, 10, 42, (int) parseLong(f2, 0), f3.isBlank() ? "PICKUP" : f3.toUpperCase());
            case COMPUTE -> MacroStep.compute(f2.isBlank() ? "result" : f2, f3, f1);
            case SUBMIT_SIGN_TEXT -> MacroStep.submitSignText(f1);
            case CLOSE_SCREEN -> MacroStep.closeScreen();
        };

        macro.steps.add(step);
        init();
    }

    private void moveStep(int index, int delta) {
        int target = index + delta;
        if (target < 0 || target >= macro.steps.size()) return;
        Collections.swap(macro.steps, index, target);
        init();
    }

    private void removeStep(int index) {
        macro.steps.remove(index);
        init();
    }

    private void onRecord() {
        workingName = nameField.getText();
        macro.name = workingName.isBlank() ? "Unnamed Macro" : workingName;
        MacroRecorder.start(macro, originalName, parent);
    }

    private void onSave() {
        workingName = nameField.getText();
        String name = workingName.isBlank() ? "Unnamed Macro" : workingName;
        macro.name = name;

        if (originalName != null && !originalName.equalsIgnoreCase(name)) {
            MacroRegistry.delete(originalName);
        }
        MacroRegistry.save(macro);
        Minecraft.getInstance().setScreen(new MacroListScreen());
    }

    private void onCancel() {
        Minecraft.getInstance().setScreen(parent != null ? parent : new MacroListScreen());
    }

    @Override
    protected void renderNVG(NVGRenderer nvg) {
        float panelX = (width - PANEL_WIDTH) / 2f;
        float addFormY = addTypeButtonsY() + TYPE_BUTTON_HEIGHT + SECTION_GAP;
        float panelHeight = (addFormY + FORM_FIELD_HEIGHT + SECTION_GAP) - PANEL_TOP;

        nvg.roundedRect(panelX, PANEL_TOP, PANEL_WIDTH, panelHeight, 10f, Theme.PANEL_BG);
        nvg.rectOutline(panelX, PANEL_TOP, PANEL_WIDTH, panelHeight, 10f, 1.2f, Theme.PANEL_BORDER);

        nvg.text(Fonts.BOLD, "Edit Macro", panelX + SIDE_PADDING, TITLE_Y, 16f, Theme.ACCENT_PINK);
        nvg.line(panelX + SIDE_PADDING, DIVIDER_Y, panelX + PANEL_WIDTH - SIDE_PADDING, DIVIDER_Y, 1f, Theme.PANEL_BORDER);

        nvg.text(Fonts.REGULAR, "Insert bazaar template:", panelX + SIDE_PADDING, TEMPLATE_LABEL_Y, 12f, Theme.TEXT_SECONDARY);

        nvg.text(Fonts.REGULAR, "Steps:", panelX + SIDE_PADDING, STEPS_LABEL_Y, 12f, Theme.TEXT_SECONDARY);
        if (macro.steps.isEmpty()) {
            nvg.text(Fonts.REGULAR, "No steps yet - add one below, or use Record / a template above.",
                    panelX + SIDE_PADDING, STEPS_TOP + 8f, 13f, Theme.TEXT_SECONDARY);
        }
        for (int i = 0; i < macro.steps.size(); i++) {
            float rowY = STEPS_TOP + i * (STEP_ROW_HEIGHT + STEP_ROW_GAP);
            nvg.roundedRect(panelX + SIDE_PADDING, rowY, CONTENT_WIDTH, STEP_ROW_HEIGHT, 6f,
                    i % 2 == 0 ? Theme.ROW_BG : Theme.ROW_BG_ALT);
            nvg.text(Fonts.REGULAR, (i + 1) + ". " + macro.steps.get(i).describe(),
                    panelX + SIDE_PADDING + 10f, rowY + (STEP_ROW_HEIGHT - 12f) / 2f, 12f, Theme.TEXT_PRIMARY);
        }

        nvg.text(Fonts.REGULAR, "Add step:", panelX + SIDE_PADDING, addTypeButtonsY() - LABEL_HEIGHT, 12f, Theme.TEXT_SECONDARY);
    }

    private static String shortLabel(MacroStep.StepType type) {
        return switch (type) {
            case COMMAND -> "Command";
            case WAIT -> "Wait";
            case WAIT_FOR_SCREEN -> "WaitFor";
            case CLICK_SLOT -> "ClickSlot";
            case CLICK_SLOT_REAL -> "ClickSlotReal";
            case FIND_ITEM_SLOT -> "FindItem";
            case COMPUTE -> "Compute";
            case SUBMIT_SIGN_TEXT -> "Sign";
            case CLOSE_SCREEN -> "Close";
        };
    }

    private static long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(text.replaceAll("[^0-9-]", ""));
        } catch (Exception e) {
            return fallback;
        }
    }
}

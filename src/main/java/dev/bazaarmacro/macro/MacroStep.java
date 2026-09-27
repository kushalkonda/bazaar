package dev.bazaarmacro.macro;

/**
 * A single step in a {@link MacroDefinition}, modeled as a flat tagged union
 * (a {@link StepType} discriminator plus every type's fields, most left null/default)
 * so it serializes to a simple, readable JSON object via Gson without custom adapters.
 */
public class MacroStep {

    public enum StepType {
        /** Sends a chat message or, if prefixed with '/', a client command. */
        COMMAND,
        /** Sleeps for a fixed duration on the macro worker thread. */
        WAIT,
        /** Polls the open container screen's title until it contains a substring, or times out. */
        WAIT_FOR_SCREEN,
        /** Clicks a slot in the open container screen, addressed by a coordinate like "B2" or a raw index. */
        CLICK_SLOT,
        /**
         * Experimental alternative to {@link #CLICK_SLOT} (2026-08-01) - routes through the
         * screen's actual mouse-input methods (computed real pixel position) instead of invoking
         * the slot-click handler directly. See {@link dev.bazaarmacro.util.ClientUtils#performRealMouseClick}.
         */
        CLICK_SLOT_REAL,
        /**
         * Scans a slot range in the open container for an item whose (colour-stripped) hover
         * name exactly matches {@link #itemNameContains}, and clicks the first match. Use this
         * instead of {@link #CLICK_SLOT} for Bazaar search results - which item lands where
         * depends on how many results the search returns, so a fixed coordinate silently
         * clicks the wrong item whenever there's more than one match.
         */
        FIND_ITEM_SLOT,
        /**
         * Evaluates a {@link MacroExpression} and stores the result under a named variable,
         * available to later steps as {resultVar} and to later COMPUTE steps as a bare variable.
         * If {@link #itemNameOrTag} is set, the expression can also reference {@code buyPrice}
         * and {@code sellPrice} for that item; {@code purse} is always available.
         */
        COMPUTE,
        /** Types a value (literal or a {placeholder}) into an open sign-edit screen and submits it. */
        SUBMIT_SIGN_TEXT,
        /** Closes the currently open container screen. */
        CLOSE_SCREEN
    }

    public StepType type;

    // COMMAND
    public String command;

    // WAIT
    public long ms;

    // WAIT_FOR_SCREEN
    public String titleContains;
    public long timeoutMs;

    // CLICK_SLOT
    public String coordinate;
    public int button;
    /** Name of a {@link net.minecraft.world.inventory.ContainerInput} constant, e.g. "PICKUP", "QUICK_MOVE", "THROW". */
    public String clickType;

    // FIND_ITEM_SLOT (button/clickType above are reused for the click once the slot is found)
    public String itemNameContains;
    public int scanStart;
    public int scanEnd;

    // COMPUTE - the priced item whose buyPrice/sellPrice the expression can reference
    public String itemNameOrTag;

    // COMPUTE
    public String resultVar;
    public String expression;

    // SUBMIT_SIGN_TEXT
    public String value;

    public MacroStep() {
    }

    private MacroStep(StepType type) {
        this.type = type;
    }

    public static MacroStep command(String command) {
        MacroStep s = new MacroStep(StepType.COMMAND);
        s.command = command;
        return s;
    }

    public static MacroStep waitMs(long ms) {
        MacroStep s = new MacroStep(StepType.WAIT);
        s.ms = ms;
        return s;
    }

    public static MacroStep waitForScreen(String titleContains, long timeoutMs) {
        MacroStep s = new MacroStep(StepType.WAIT_FOR_SCREEN);
        s.titleContains = titleContains;
        s.timeoutMs = timeoutMs;
        return s;
    }

    public static MacroStep clickSlot(String coordinate, int button, String clickType) {
        MacroStep s = new MacroStep(StepType.CLICK_SLOT);
        s.coordinate = coordinate;
        s.button = button;
        s.clickType = clickType;
        return s;
    }

    public static MacroStep clickSlotReal(String coordinate, int button) {
        MacroStep s = new MacroStep(StepType.CLICK_SLOT_REAL);
        s.coordinate = coordinate;
        s.button = button;
        return s;
    }

    public static MacroStep findItemSlot(String itemNameContains, int scanStart, int scanEnd, int button, String clickType) {
        MacroStep s = new MacroStep(StepType.FIND_ITEM_SLOT);
        s.itemNameContains = itemNameContains;
        s.scanStart = scanStart;
        s.scanEnd = scanEnd;
        s.button = button;
        s.clickType = clickType;
        return s;
    }

    /**
     * @param resultVar     variable name to store the result under (available as {name} later)
     * @param itemNameOrTag optional; if set, exposes {@code buyPrice}/{@code sellPrice} for this item to the expression
     * @param expression    a {@link MacroExpression}, e.g. "floor(purse * 0.25 / buyPrice)"
     */
    public static MacroStep compute(String resultVar, String itemNameOrTag, String expression) {
        MacroStep s = new MacroStep(StepType.COMPUTE);
        s.resultVar = resultVar;
        s.itemNameOrTag = itemNameOrTag;
        s.expression = expression;
        return s;
    }

    public static MacroStep submitSignText(String value) {
        MacroStep s = new MacroStep(StepType.SUBMIT_SIGN_TEXT);
        s.value = value;
        return s;
    }

    public static MacroStep closeScreen() {
        return new MacroStep(StepType.CLOSE_SCREEN);
    }

    /** Short human-readable summary shown in the macro editor's step list. */
    public String describe() {
        return switch (type) {
            case COMMAND -> "Command: " + command;
            case WAIT -> "Wait " + ms + "ms";
            case WAIT_FOR_SCREEN -> "Wait for screen containing \"" + titleContains + "\" (timeout " + timeoutMs + "ms)";
            case CLICK_SLOT -> "Click slot " + coordinate + " (" + clickType + ", button " + button + ")";
            case CLICK_SLOT_REAL -> "Click slot " + coordinate + " (real mouse click, button " + button + ")";
            case FIND_ITEM_SLOT -> "Find and click \"" + itemNameContains + "\" in slots " + scanStart + "-" + scanEnd;
            case COMPUTE -> "Compute " + (resultVar == null || resultVar.isBlank() ? "result" : resultVar)
                    + " = " + expression
                    + (itemNameOrTag == null || itemNameOrTag.isBlank() ? "" : " (item: " + itemNameOrTag + ")");
            case SUBMIT_SIGN_TEXT -> "Type into sign: " + value;
            case CLOSE_SCREEN -> "Close screen";
        };
    }
}

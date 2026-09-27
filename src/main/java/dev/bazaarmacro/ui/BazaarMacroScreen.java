package dev.bazaarmacro.ui;

import dev.bazaarmacro.flipper.BazaarScanner;
import dev.bazaarmacro.flipper.FlipperEngine;
import dev.bazaarmacro.flipper.FlipperManager;
import dev.bazaarmacro.flipper.FlipperSettings;
import dev.bazaarmacro.flipper.SessionHistoryStorage;
import dev.bazaarmacro.macro.ExecutionContext;
import dev.bazaarmacro.renderer.Fonts;
import dev.bazaarmacro.renderer.NVGRenderer;
import dev.bazaarmacro.renderer.NVGScreen;
import dev.bazaarmacro.renderer.Theme;
import dev.bazaarmacro.ui.components.NvgButton;
import dev.bazaarmacro.ui.components.NvgSlider;
import dev.bazaarmacro.ui.components.NvgTextField;
import dev.bazaarmacro.ui.components.NvgToggle;
import dev.bazaarmacro.ui.components.ThemeSwatch;
import dev.bazaarmacro.util.ClientUtils;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.DoubleConsumer;

/**
 * The primary {@code /bfm} screen: one Aether-style panel with a left sidebar (three static
 * pages, no hover-expand or animation - there are only ever three, so nothing is gained by
 * either) and a per-page content area to its right, instead of separate screens per feature.
 * Replaces the old {@code FlipperScreen}/{@code FlipFinderScreen} pair; the general-purpose
 * macro editor ({@link MacroListScreen}) is intentionally not reachable from here anymore -
 * it's still fully functional via {@code /bfm macros}, just no longer a button on this panel.
 */
public class BazaarMacroScreen extends NVGScreen {

    public enum Page {
        BAZAAR_MACRO("Bazaar Macro", "Autonomous single-item buy/sell-order flipping loop."),
        CRAFT_FLIP("Craft Flip Macro", "Craft configured items from configured storage spaces. Coming soon."),
        BAZAAR_SCANNER("Bazaar Scanner", "Ranks every Bazaar product by real, liquid flip margin.");

        final String title;
        final String description;

        Page(String title, String description) {
            this.title = title;
            this.description = description;
        }
    }

    private static final Page[] PAGES = Page.values();
    private static final int SCAN_ROW_COUNT = 8;

    // Overall panel/chrome
    private static final float PANEL_WIDTH = 700f;
    private static final float PANEL_HEIGHT = 520f;
    private static final float PANEL_TOP = 24f;
    private static final float SIDEBAR_WIDTH = 172f;
    private static final float TOPBAR_HEIGHT = 54f;
    private static final float CONTENT_PADDING = 20f;

    // Sidebar nav
    private static final float BRAND_Y_OFFSET = 20f;
    private static final float NAV_TOP_OFFSET = 46f;
    private static final float NAV_ITEM_HEIGHT = 38f;
    private static final float NAV_GAP = 6f;
    private static final float NAV_SIDE_PADDING = 12f;

    // Sidebar theme picker (bottom-anchored, reachable from every page)
    private static final float THEME_BOTTOM_MARGIN = 20f;
    private static final float THEME_LABEL_GAP = 16f;
    private static final float THEME_SWATCH_GAP = 8f;

    // Bazaar Macro page (relative to contentTop)
    private static final float ITEM_ROW_OFFSET = 0f;
    private static final float ROW_HEIGHT = 22f;
    private static final float SECTION_GAP = 11f;
    private static final float BUTTON_HEIGHT = 25f;
    private static final float BUTTON_WIDTH = 130f;
    private static final float STATUS_LINE_HEIGHT = 15f;
    private static final int STATUS_LINES = 8;
    private static final float LABEL_HEIGHT = 15f;
    private static final float SETTINGS_ROW_HEIGHT = 30f;
    private static final float SETTINGS_ROW_GAP = 5f;
    private static final float SLIDER_WIDTH = 160f;
    private static final float SLIDER_HEIGHT = 18f;

    // Bazaar Scanner page
    private static final float SCAN_ROW_HEIGHT = 42f;
    private static final float SCAN_ROW_GAP = 6f;
    private static final float FLIP_BUTTON_WIDTH = 56f;
    private static final float FLIP_BUTTON_HEIGHT = 22f;

    private record SettingRow(String label, String description) {
    }

    private static final SettingRow[] SETTING_ROWS = {
            new SettingRow("Price increment", "For sizing quantity only - Hypixel's own preset sets the real price"),
            new SettingRow("Min action interval", "Minimum time between order/claim actions"),
            new SettingRow("Min collect fraction", "Won't sell until this much of a buy order is in"),
            new SettingRow("Bazaar tax", "Server cut on sell proceeds - lower with Bazaar Flipper reputation"),
    };

    private Page activePage = Page.BAZAAR_MACRO;

    // Layout anchors, recomputed each initNVG() (resize-safe)
    private float panelX, panelY;
    private float contentX, contentInnerX, contentInnerWidth, contentTop;

    // Bazaar Macro page state - survives initNVG() reruns, same reasoning as the old FlipperScreen
    private String workingItem = "";
    private String workingCap = "";
    private String workingDuration = "";
    private boolean advancedExpanded = false;
    private long allTimeProfit;

    private NvgTextField itemField;
    private NvgTextField capField;
    private NvgTextField durationField;
    private NvgButton startStopButton;
    private NvgToggle advancedToggle;
    private final List<NvgSlider> settingsSliders = new ArrayList<>();

    // Bazaar Scanner page state
    private List<BazaarScanner.Opportunity> scanResults = List.of();
    private boolean scanning = false;
    private final List<NvgButton> flipButtons = new ArrayList<>();

    private final List<NvgButton> navButtons = new ArrayList<>();
    private final List<ThemeSwatch> themeSwatches = new ArrayList<>();

    public BazaarMacroScreen() {
        this("");
    }

    /** {@code initialItem} pre-fills the Bazaar Macro page's item field (used by the Scanner page's "Flip" button). */
    public BazaarMacroScreen(String initialItem) {
        super("Bazaar Macro");
        this.workingItem = initialItem == null ? "" : initialItem;
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new BazaarMacroScreen());
    }

    /** Opens straight to a given page - e.g. {@code /bfm scan} jumping right to the Scanner page and kicking off a scan. */
    public static void openOnPage(Page page) {
        BazaarMacroScreen screen = new BazaarMacroScreen();
        screen.activePage = page;
        Minecraft.getInstance().setScreen(screen);
    }

    @Override
    protected void initNVG() {
        panelX = (width - PANEL_WIDTH) / 2f;
        panelY = PANEL_TOP;
        contentX = panelX + SIDEBAR_WIDTH;
        contentInnerX = contentX + CONTENT_PADDING;
        contentInnerWidth = PANEL_WIDTH - SIDEBAR_WIDTH - CONTENT_PADDING * 2;
        contentTop = panelY + TOPBAR_HEIGHT + CONTENT_PADDING;

        allTimeProfit = SessionHistoryStorage.totalProfit();

        buildSidebar();
        buildBazaarMacroPage();
        rebuildScanButtons();
        updateVisibility();

        if (activePage == Page.BAZAAR_SCANNER && scanResults.isEmpty() && !scanning) {
            triggerScan();
        }
    }

    private void buildSidebar() {
        navButtons.clear();
        float navX = panelX + NAV_SIDE_PADDING;
        float navW = SIDEBAR_WIDTH - NAV_SIDE_PADDING * 2;
        float navTop = panelY + NAV_TOP_OFFSET;
        for (int i = 0; i < PAGES.length; i++) {
            Page page = PAGES[i];
            float rowY = navTop + i * (NAV_ITEM_HEIGHT + NAV_GAP);
            NvgButton nav = new NvgButton(navX, rowY, navW, NAV_ITEM_HEIGHT, page.title, () -> switchToPage(page));
            navButtons.add(nav);
            addComponent(nav);
        }

        themeSwatches.clear();
        Theme.ThemePreset[] presets = Theme.ThemePreset.values();
        float swatchY = panelY + PANEL_HEIGHT - THEME_BOTTOM_MARGIN - ThemeSwatch.size();
        for (int i = 0; i < presets.length; i++) {
            Theme.ThemePreset preset = presets[i];
            float swatchX = navX + i * (ThemeSwatch.size() + THEME_SWATCH_GAP);
            ThemeSwatch swatch = new ThemeSwatch(swatchX, swatchY, preset, () -> Theme.applyPreset(preset));
            themeSwatches.add(swatch);
            addComponent(swatch);
        }
    }

    private void buildBazaarMacroPage() {
        float itemRowY = contentTop + ITEM_ROW_OFFSET;
        float capRowY = itemRowY + 27f;
        float durationRowY = capRowY + 27f;
        float buttonRowY = durationRowY + ROW_HEIGHT + SECTION_GAP;
        float statusTop = buttonRowY + BUTTON_HEIGHT + SECTION_GAP;
        float settingsLabelY = statusTop + STATUS_LINES * STATUS_LINE_HEIGHT + SECTION_GAP;
        float settingsTop = settingsLabelY + LABEL_HEIGHT;

        itemField = addComponent(new NvgTextField(contentInnerX, itemRowY, contentInnerWidth, ROW_HEIGHT)
                .withPlaceholder("item name, e.g. Enchanted Mycelium")
                .withText(workingItem));
        capField = addComponent(new NvgTextField(contentInnerX, capRowY, contentInnerWidth, ROW_HEIGHT)
                .withPlaceholder("optional cap, e.g. 50% or 10000000 - blank = full purse")
                .withText(workingCap));
        durationField = addComponent(new NvgTextField(contentInnerX, durationRowY, contentInnerWidth, ROW_HEIGHT)
                .withPlaceholder("optional session length in minutes - blank = run until stopped")
                .withText(workingDuration));

        startStopButton = addComponent(new NvgButton(contentInnerX, buttonRowY, BUTTON_WIDTH, BUTTON_HEIGHT,
                "Start", this::onStartStop));

        float toggleX = contentInnerX + contentInnerWidth - NvgToggle.width();
        advancedToggle = addComponent(new NvgToggle(toggleX, settingsLabelY - 2f, advancedExpanded, val -> {
            advancedExpanded = val;
            for (NvgSlider slider : settingsSliders) {
                slider.setVisible(val && activePage == Page.BAZAAR_MACRO);
            }
        }));

        buildSettingsSliders(settingsTop);
    }

    private void buildSettingsSliders(float settingsTop) {
        settingsSliders.clear();
        FlipperSettings settings = FlipperSettings.get();
        float sliderX = contentInnerX + contentInnerWidth - SLIDER_WIDTH;

        addSlider(sliderX, settingsTop, 0, 0.1, 10.0, settings.priceIncrement, 1, " coins",
                v -> {
                    settings.priceIncrement = v;
                    FlipperSettings.saveCurrent();
                });
        addSlider(sliderX, settingsTop, 1, 15, 120, settings.minActionIntervalSeconds, 0, "s",
                v -> {
                    settings.minActionIntervalSeconds = v;
                    FlipperSettings.saveCurrent();
                });
        addSlider(sliderX, settingsTop, 2, 5, 100, settings.minCollectFraction * 100.0, 0, "%",
                v -> {
                    settings.minCollectFraction = v / 100.0;
                    FlipperSettings.saveCurrent();
                });
        addSlider(sliderX, settingsTop, 3, 1.0, 1.25, settings.taxRatePercent, 3, "%",
                v -> {
                    settings.taxRatePercent = v;
                    FlipperSettings.saveCurrent();
                });
    }

    private void addSlider(float sliderX, float settingsTop, int rowIndex, double min, double max, double initial,
                            int decimals, String suffix, DoubleConsumer onChange) {
        float rowY = settingsTop + rowIndex * (SETTINGS_ROW_HEIGHT + SETTINGS_ROW_GAP);
        float sliderY = rowY + (SETTINGS_ROW_HEIGHT - SLIDER_HEIGHT) / 2f;
        NvgSlider slider = new NvgSlider(sliderX, sliderY, SLIDER_WIDTH, SLIDER_HEIGHT, min, max, initial, decimals, suffix, onChange);
        slider.setVisible(advancedExpanded && activePage == Page.BAZAAR_MACRO);
        settingsSliders.add(slider);
        addComponent(slider);
    }

    private static float scanRowY(float contentTop, int index) {
        return contentTop + index * (SCAN_ROW_HEIGHT + SCAN_ROW_GAP);
    }

    private void rebuildScanButtons() {
        for (NvgButton button : flipButtons) {
            removeComponent(button);
        }
        flipButtons.clear();
        for (int i = 0; i < scanResults.size(); i++) {
            BazaarScanner.Opportunity opp = scanResults.get(i);
            float rowY = scanRowY(contentTop, i);
            float buttonX = contentInnerX + contentInnerWidth - FLIP_BUTTON_WIDTH;
            float buttonY = rowY + (SCAN_ROW_HEIGHT - FLIP_BUTTON_HEIGHT) / 2f;
            NvgButton flipButton = new NvgButton(buttonX, buttonY, FLIP_BUTTON_WIDTH, FLIP_BUTTON_HEIGHT, "Flip",
                    () -> useOpportunity(opp));
            flipButton.setVisible(activePage == Page.BAZAAR_SCANNER);
            flipButtons.add(flipButton);
            addComponent(flipButton);
        }
    }

    private void useOpportunity(BazaarScanner.Opportunity opp) {
        workingItem = opp.itemTag();
        itemField.withText(workingItem);
        switchToPage(Page.BAZAAR_MACRO);
    }

    private void triggerScan() {
        if (scanning) return;
        scanning = true;
        ClientUtils.sendMessage("§dScanning the Bazaar for flip opportunities...");
        CompletableFuture.supplyAsync(() -> {
            try {
                return BazaarScanner.findTopFlips(SCAN_ROW_COUNT);
            } catch (Exception e) {
                ClientUtils.sendMessage("§cScan failed: " + e.getMessage());
                return null;
            }
        }).thenAccept(results -> Minecraft.getInstance().execute(() -> {
            scanning = false;
            if (results == null) return;
            scanResults = results;
            rebuildScanButtons();
        }));
    }

    private void switchToPage(Page page) {
        if (activePage == page) return;
        activePage = page;
        updateVisibility();
        if (page == Page.BAZAAR_SCANNER && scanResults.isEmpty() && !scanning) {
            triggerScan();
        }
    }

    private void updateVisibility() {
        boolean macro = activePage == Page.BAZAAR_MACRO;
        boolean scanner = activePage == Page.BAZAAR_SCANNER;

        itemField.setVisible(macro);
        capField.setVisible(macro);
        durationField.setVisible(macro);
        startStopButton.setVisible(macro);
        advancedToggle.setVisible(macro);
        for (NvgSlider slider : settingsSliders) {
            slider.setVisible(macro && advancedExpanded);
        }
        for (NvgButton flipButton : flipButtons) {
            flipButton.setVisible(scanner);
        }
    }

    private void onStartStop() {
        workingItem = itemField.getText();
        workingCap = capField.getText();
        workingDuration = durationField.getText();

        if (FlipperEngine.isActive()) {
            FlipperEngine.stop();
        } else {
            String item = workingItem.trim();
            String cap = workingCap.trim();
            if (item.isBlank()) {
                ClientUtils.sendMessage("§cEnter an item first.");
                return;
            }
            double durationMinutes = 0;
            String durationText = workingDuration.trim();
            if (!durationText.isBlank()) {
                try {
                    durationMinutes = Double.parseDouble(durationText);
                } catch (NumberFormatException e) {
                    ClientUtils.sendMessage("§cSession length must be a number of minutes (or leave it blank).");
                    return;
                }
            }
            FlipperEngine.start(item, cap, durationMinutes);
        }
        // No init() here - the button's label/selected state is synced live every frame in
        // renderNVG() instead, so it stays correct even when the engine stops itself in the
        // background (e.g. after an error), not just right after a click.
    }

    @Override
    protected void renderNVG(NVGRenderer nvg) {
        nvg.roundedRect(panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, 10f, Theme.withAlpha(Theme.PANEL_BG, 0.92f));
        nvg.rectOutline(panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, 10f, 1f, Theme.PANEL_BORDER);

        renderSidebar(nvg);
        renderTopBar(nvg);

        switch (activePage) {
            case BAZAAR_MACRO -> renderBazaarMacroPage(nvg);
            case CRAFT_FLIP -> renderCraftFlipPage(nvg);
            case BAZAAR_SCANNER -> renderBazaarScannerPage(nvg);
        }
    }

    private void renderSidebar(NVGRenderer nvg) {
        nvg.roundedRect(panelX, panelY, SIDEBAR_WIDTH, PANEL_HEIGHT, 10f, Theme.ROW_BG);
        // Square off the sidebar's right edge (roundedRect above rounds all 4 corners) so it
        // reads as one continuous panel rather than a rounded card floating inside another.
        nvg.rect(panelX + SIDEBAR_WIDTH - 10f, panelY, 10f, PANEL_HEIGHT, Theme.ROW_BG);
        nvg.line(panelX + SIDEBAR_WIDTH, panelY, panelX + SIDEBAR_WIDTH, panelY + PANEL_HEIGHT, 1f, Theme.PANEL_BORDER);

        nvg.text(Fonts.BOLD, "Bazaar Macro", panelX + NAV_SIDE_PADDING, panelY + BRAND_Y_OFFSET, 12.5f, Theme.ACCENT_PINK);

        for (int i = 0; i < PAGES.length; i++) {
            navButtons.get(i).setSelected(activePage == PAGES[i]);
        }

        float swatchY = panelY + PANEL_HEIGHT - THEME_BOTTOM_MARGIN - ThemeSwatch.size();
        nvg.text(Fonts.REGULAR, "Theme", panelX + NAV_SIDE_PADDING, swatchY - THEME_LABEL_GAP, 9.5f, Theme.TEXT_SECONDARY);
    }

    private void renderTopBar(NVGRenderer nvg) {
        float titleY = panelY + 14f;
        float descY = panelY + 32f;
        nvg.text(Fonts.BOLD, activePage.title, contentX + CONTENT_PADDING, titleY, 14f, Theme.TEXT_PRIMARY);
        nvg.text(Fonts.REGULAR, activePage.description, contentX + CONTENT_PADDING, descY, 10.5f, Theme.TEXT_SECONDARY);

        if (activePage == Page.BAZAAR_MACRO) {
            String allTimeText = "All-time: " + (allTimeProfit >= 0 ? "+" : "") + ExecutionContext.formatDisplay(allTimeProfit);
            nvg.text(Fonts.REGULAR, allTimeText,
                    contentX + PANEL_WIDTH - SIDEBAR_WIDTH - CONTENT_PADDING - nvg.textWidth(Fonts.REGULAR, allTimeText, 10.5f),
                    titleY + 1f, 10.5f, allTimeProfit >= 0 ? Theme.PROFIT_POSITIVE : Theme.PROFIT_NEGATIVE);
        } else if (activePage == Page.BAZAAR_SCANNER) {
            String hint = scanning ? "Scanning..." : "Esc to close";
            nvg.text(Fonts.REGULAR, hint,
                    contentX + PANEL_WIDTH - SIDEBAR_WIDTH - CONTENT_PADDING - nvg.textWidth(Fonts.REGULAR, hint, 10.5f),
                    titleY + 1f, 10.5f, Theme.TEXT_SECONDARY);
        }

        nvg.line(contentX + CONTENT_PADDING, panelY + TOPBAR_HEIGHT, panelX + PANEL_WIDTH - CONTENT_PADDING,
                panelY + TOPBAR_HEIGHT, 1f, Theme.PANEL_BORDER);
    }

    private void renderBazaarMacroPage(NVGRenderer nvg) {
        boolean active = FlipperEngine.isActive();
        startStopButton.setLabel(active ? "Stop" : "Start");
        startStopButton.setSelected(active);

        float statusTop = statusTopY();
        renderStatus(nvg, active, statusTop);
        renderSettings(nvg, statusTop);
    }

    private float statusTopY() {
        float itemRowY = contentTop + ITEM_ROW_OFFSET;
        float capRowY = itemRowY + 27f;
        float durationRowY = capRowY + 27f;
        float buttonRowY = durationRowY + ROW_HEIGHT + SECTION_GAP;
        return buttonRowY + BUTTON_HEIGHT + SECTION_GAP;
    }

    private void renderStatus(NVGRenderer nvg, boolean active, float statusTop) {
        float x = contentInnerX;
        float y = statusTop;

        if (!active) {
            nvg.text(Fonts.REGULAR, "Not running.", x, y, 11.5f, Theme.TEXT_SECONDARY);
            return;
        }

        FlipperEngine.Snapshot snap = FlipperEngine.snapshot();
        nvg.text(Fonts.REGULAR, "Item: " + snap.itemTag(), x, y, 11.5f, Theme.TEXT_PRIMARY);
        y += STATUS_LINE_HEIGHT;

        // Buy track
        nvg.circle(x + 3.5f, y + 4.5f, 3.5f, snap.buyActive() ? Theme.ACCENT_PINK : Theme.TEXT_DISABLED);
        String buyLine = "Buy: " + (snap.buyActive() ? "watching order" : "idle");
        nvg.text(Fonts.REGULAR, buyLine, x + 12f, y, 11.5f, Theme.TEXT_PRIMARY);
        String buyBadge = statusLabel(snap.buyCheckStatus());
        if (buyBadge != null) {
            float badgeX = x + 12f + nvg.textWidth(Fonts.REGULAR, buyLine, 11.5f) + 8f;
            nvg.text(Fonts.BOLD, buyBadge, badgeX, y, 11.5f, statusColor(snap.buyCheckStatus()));
        }
        y += STATUS_LINE_HEIGHT;
        String buyDetail = snap.buyActive()
                ? snap.buyQty() + "x @ " + ExecutionContext.formatDisplay(snap.buyPrice()) + " (" + snap.buyClaimedQty() + "/" + snap.buyQty() + " collected)"
                : "no buy order open";
        nvg.text(Fonts.REGULAR, buyDetail, x, y, 10.5f, Theme.TEXT_SECONDARY);
        y += STATUS_LINE_HEIGHT;

        // Sell track - runs independently of the buy track above, not sequentially after it
        nvg.circle(x + 3.5f, y + 4.5f, 3.5f, snap.sellActive() ? Theme.ACCENT_PURPLE : Theme.TEXT_DISABLED);
        String sellLine = "Sell: " + (snap.sellActive() ? "watching offer" : "idle");
        nvg.text(Fonts.REGULAR, sellLine, x + 12f, y, 11.5f, Theme.TEXT_PRIMARY);
        String sellBadge = statusLabel(snap.sellCheckStatus());
        if (sellBadge != null) {
            float badgeX = x + 12f + nvg.textWidth(Fonts.REGULAR, sellLine, 11.5f) + 8f;
            nvg.text(Fonts.BOLD, sellBadge, badgeX, y, 11.5f, statusColor(snap.sellCheckStatus()));
        }
        y += STATUS_LINE_HEIGHT;
        String sellDetail = snap.sellActive()
                ? snap.sellQty() + "x @ " + ExecutionContext.formatDisplay(snap.sellPrice()) + " ("
                        + ExecutionContext.formatDisplay(snap.sellCollectedProceeds()) + "/" + ExecutionContext.formatDisplay(snap.sellProceedsExpected()) + " coins)"
                : "no sell offer open";
        nvg.text(Fonts.REGULAR, sellDetail, x, y, 10.5f, Theme.TEXT_SECONDARY);
        y += STATUS_LINE_HEIGHT;

        nvg.text(Fonts.REGULAR, "Sellable stock: " + snap.sellableStock() + "x (held: " + snap.heldQty() + "x)", x, y, 11.5f, Theme.TEXT_SECONDARY);
        y += STATUS_LINE_HEIGHT;
        nvg.text(Fonts.REGULAR, "Order slots used: " + snap.activeOrderCount() + "/7", x, y, 11.5f, Theme.TEXT_SECONDARY);
        y += STATUS_LINE_HEIGHT;
        nvg.text(Fonts.REGULAR, "Session: " + formatSessionRemaining(snap.sessionEndTimeMs()), x, y, 11.5f, Theme.TEXT_SECONDARY);
    }

    private static String statusLabel(FlipperManager.TopCheckResult.Status status) {
        if (status == null) return null;
        return switch (status) {
            case AHEAD -> "Top";
            case TIED -> "Matched";
            case BEHIND -> "Undercut";
        };
    }

    private static int statusColor(FlipperManager.TopCheckResult.Status status) {
        return switch (status) {
            case AHEAD -> Theme.STATUS_TOP;
            case TIED -> Theme.STATUS_MATCHED;
            case BEHIND -> Theme.STATUS_UNDERCUT;
        };
    }

    private static String formatSessionRemaining(long sessionEndTimeMs) {
        if (sessionEndTimeMs <= 0) return "unlimited";
        long remainingMs = sessionEndTimeMs - System.currentTimeMillis();
        if (remainingMs <= 0) return "wrapping up";
        long minutes = remainingMs / 60_000;
        long seconds = (remainingMs / 1000) % 60;
        return minutes + "m " + seconds + "s remaining";
    }

    private void renderSettings(NVGRenderer nvg, float statusTop) {
        float settingsLabelY = statusTop + STATUS_LINES * STATUS_LINE_HEIGHT + SECTION_GAP;
        float settingsTop = settingsLabelY + LABEL_HEIGHT;

        nvg.text(Fonts.REGULAR, "Settings", contentInnerX, settingsLabelY, 10.5f, Theme.TEXT_SECONDARY);

        String advancedLabel = "Advanced";
        float toggleX = contentInnerX + contentInnerWidth - NvgToggle.width();
        float labelWidth = nvg.textWidth(Fonts.REGULAR, advancedLabel, 10.5f);
        nvg.text(Fonts.REGULAR, advancedLabel, toggleX - 8f - labelWidth, settingsLabelY, 10.5f, Theme.TEXT_SECONDARY);

        if (!advancedExpanded) {
            return;
        }

        for (int i = 0; i < SETTING_ROWS.length; i++) {
            float rowY = settingsTop + i * (SETTINGS_ROW_HEIGHT + SETTINGS_ROW_GAP);
            nvg.roundedRect(contentInnerX, rowY, contentInnerWidth, SETTINGS_ROW_HEIGHT, 6f, Theme.CARD_BG);
            nvg.rectOutline(contentInnerX, rowY, contentInnerWidth, SETTINGS_ROW_HEIGHT, 6f, 1f, Theme.CARD_BORDER);

            SettingRow row = SETTING_ROWS[i];
            float textX = contentInnerX + 10f;
            float labelAreaWidth = contentInnerWidth - SLIDER_WIDTH - 20f;
            nvg.pushScissor(textX, rowY, labelAreaWidth, SETTINGS_ROW_HEIGHT);
            nvg.text(Fonts.REGULAR, row.label(), textX, rowY + 5f, 11f, Theme.TEXT_PRIMARY);
            nvg.text(Fonts.REGULAR, row.description(), textX, rowY + 17f, 9f, Theme.TEXT_SECONDARY);
            nvg.popScissor();
        }
    }

    private void renderCraftFlipPage(NVGRenderer nvg) {
        nvg.roundedRect(contentInnerX, contentTop, contentInnerWidth, 90f, 8f, Theme.CARD_BG);
        nvg.rectOutline(contentInnerX, contentTop, contentInnerWidth, 90f, 8f, 1f, Theme.CARD_BORDER);
        nvg.text(Fonts.BOLD, "Not built yet", contentInnerX + 14f, contentTop + 16f, 12f, Theme.TEXT_PRIMARY);
        nvg.text(Fonts.REGULAR, "This page will craft a configured list of items using configured storage",
                contentInnerX + 14f, contentTop + 38f, 10.5f, Theme.TEXT_SECONDARY);
        nvg.text(Fonts.REGULAR, "spaces, then flip the results on the Bazaar. Infrastructure only for now.",
                contentInnerX + 14f, contentTop + 54f, 10.5f, Theme.TEXT_SECONDARY);
    }

    private void renderBazaarScannerPage(NVGRenderer nvg) {
        if (scanResults.isEmpty()) {
            String message = scanning
                    ? "Scanning..."
                    : "No profitable, liquid opportunities right now - try again shortly.";
            nvg.text(Fonts.REGULAR, message, contentInnerX, contentTop, 11.5f, Theme.TEXT_SECONDARY);
            return;
        }

        for (int i = 0; i < scanResults.size(); i++) {
            renderScanRow(nvg, scanRowY(contentTop, i), scanResults.get(i));
        }
    }

    private void renderScanRow(NVGRenderer nvg, float rowY, BazaarScanner.Opportunity opp) {
        nvg.roundedRect(contentInnerX, rowY, contentInnerWidth, SCAN_ROW_HEIGHT, 6f, Theme.CARD_BG);
        nvg.rectOutline(contentInnerX, rowY, contentInnerWidth, SCAN_ROW_HEIGHT, 6f, 1f, Theme.CARD_BORDER);

        float textX = contentInnerX + 10f;
        nvg.text(Fonts.BOLD, opp.itemTag(), textX, rowY + 5f, 11.5f, Theme.TEXT_PRIMARY);

        String marginText = "+" + ExecutionContext.formatDisplay(opp.marginPercent()) + "% margin ("
                + opp.liquidityUnits() + "x available)";
        nvg.text(Fonts.REGULAR, marginText, textX, rowY + 18f, 10f, Theme.PROFIT_POSITIVE);

        String priceText = "Buy " + ExecutionContext.formatDisplay(opp.buyOrderPrice()) + " -> Sell "
                + ExecutionContext.formatDisplay(opp.sellOfferPrice());
        nvg.text(Fonts.REGULAR, priceText, textX, rowY + 30f, 10f, Theme.TEXT_SECONDARY);
    }
}

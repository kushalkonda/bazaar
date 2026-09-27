package dev.bazaarmacro.flipper;

import dev.bazaarmacro.macro.ExecutionContext;
import dev.bazaarmacro.renderer.Fonts;
import dev.bazaarmacro.renderer.NVGRenderer;
import dev.bazaarmacro.renderer.NanoVGManager;
import dev.bazaarmacro.renderer.Theme;
import net.minecraft.client.Minecraft;

/**
 * Persistent top-left profit overlay, drawn every frame whenever the flipper is running -
 * independent of whether the {@code /bfm} screen (or any screen) is open, since this renders
 * from the same tail-of-GuiRenderer hook {@code MixinGuiRenderer} already flushes screen
 * content from, not from a {@code Screen}'s own render lifecycle.
 */
public final class FlipperHud {
    private static final float X = 5f;
    private static final float Y = 5f;
    private static final float PADDING = 6f;
    private static final float LINE_HEIGHT = 11.5f;
    private static final float FONT_SIZE = 10f;

    private FlipperHud() {
    }

    public static void renderIfActive() {
        if (!FlipperEngine.isActive() || !FlipperProfitTracker.hasSession()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.getWindow() == null) {
            return;
        }

        if (!NanoVGManager.isInitialized()) {
            NanoVGManager.init();
        }
        float width = client.getWindow().getGuiScaledWidth();
        float height = client.getWindow().getGuiScaledHeight();

        NanoVGManager.beginFrame(width, height);
        try {
            draw(NanoVGManager.getRenderer());
        } finally {
            NanoVGManager.endFrame();
        }
    }

    private static void draw(NVGRenderer nvg) {
        FlipperEngine.Snapshot snap = FlipperEngine.snapshot();
        long trueProfit = FlipperProfitTracker.trueProfit();
        // Projects "if everything not yet realized as coins - inventory, an open buy order's
        // pending fill, and an open sell offer's uncollected proceeds - settled right now": see
        // FlipperProfitTracker.estimatedProfit's doc for why all three are valued consistently
        // instead of only inventory/pending-buy stock (that gap made the number swing around or
        // look like a loss purely from stock temporarily sitting in an open sell offer).
        double estimatedProfit = FlipperProfitTracker.estimatedProfit(snap);

        // itemTag is null for a brief window at session start - it's resolved asynchronously on
        // the engine thread (a network lookup) while this HUD starts rendering on the very next
        // frame, well before that resolves. NanoVG's native text-measuring call crashes outright
        // on a null CharSequence (not just misrenders), so this can never be allowed through bare.
        String line1 = snap.itemTag() != null ? snap.itemTag() : "...";
        String line2 = "Purse: " + ExecutionContext.formatDisplay(FlipperProfitTracker.startingPurse());
        String line3 = "True: " + formatSigned(trueProfit);
        String line4 = "Est: " + formatSigned(estimatedProfit);

        // Status line sits last, anchored under the profit numbers rather than above them, so it
        // reads as a footer ("what's it doing right now") under the profit tracker rather than a
        // header competing with it for attention.
        String buyBadge = snap.buyActive() ? statusLabel(snap.buyCheckStatus()) : null;
        String sellBadge = snap.sellActive() ? statusLabel(snap.sellCheckStatus()) : null;
        String buySegment = "Buy: " + (buyBadge == null ? (snap.buyActive() ? "-" : "idle") : buyBadge);
        String sellSegment = "Sell: " + (sellBadge == null ? (snap.sellActive() ? "-" : "idle") : sellBadge);
        String line5 = buySegment + "   " + sellSegment;

        float textWidth = Math.max(nvg.textWidth(Fonts.BOLD, line1, FONT_SIZE),
                Math.max(nvg.textWidth(Fonts.REGULAR, line2, FONT_SIZE),
                        Math.max(nvg.textWidth(Fonts.REGULAR, line3, FONT_SIZE),
                                Math.max(nvg.textWidth(Fonts.REGULAR, line4, FONT_SIZE), nvg.textWidth(Fonts.REGULAR, line5, FONT_SIZE)))));
        float boxWidth = textWidth + PADDING * 2f;
        float boxHeight = LINE_HEIGHT * 5f + PADDING * 2f;

        // Low-profile: this sits on screen during actual gameplay the whole time the flipper
        // runs, so it stays small and translucent rather than reading as a solid UI panel.
        nvg.roundedRect(X, Y, boxWidth, boxHeight, 5f, Theme.withAlpha(Theme.PANEL_BG, 0.72f));
        nvg.rectOutline(X, Y, boxWidth, boxHeight, 5f, 1f, Theme.withAlpha(Theme.PANEL_BORDER, 0.6f));

        float textX = X + PADDING;
        float textY = Y + PADDING;
        nvg.text(Fonts.BOLD, line1, textX, textY, FONT_SIZE, Theme.ACCENT_PINK);
        textY += LINE_HEIGHT;
        nvg.text(Fonts.REGULAR, line2, textX, textY, FONT_SIZE, Theme.TEXT_SECONDARY);
        textY += LINE_HEIGHT;
        nvg.text(Fonts.REGULAR, line3, textX, textY, FONT_SIZE, trueProfit >= 0 ? Theme.PROFIT_POSITIVE : Theme.PROFIT_NEGATIVE);
        textY += LINE_HEIGHT;
        nvg.text(Fonts.REGULAR, line4, textX, textY, FONT_SIZE, estimatedProfit >= 0 ? Theme.PROFIT_POSITIVE : Theme.PROFIT_NEGATIVE);
        textY += LINE_HEIGHT;
        nvg.text(Fonts.REGULAR, buySegment, textX, textY, FONT_SIZE,
                buyBadge != null ? statusColor(snap.buyCheckStatus()) : Theme.TEXT_SECONDARY);
        float sellSegmentX = textX + nvg.textWidth(Fonts.REGULAR, buySegment, FONT_SIZE) + nvg.textWidth(Fonts.REGULAR, "   ", FONT_SIZE);
        nvg.text(Fonts.REGULAR, sellSegment, sellSegmentX, textY, FONT_SIZE,
                sellBadge != null ? statusColor(snap.sellCheckStatus()) : Theme.TEXT_SECONDARY);
    }

    private static String formatSigned(double value) {
        return (value >= 0 ? "+" : "") + ExecutionContext.formatDisplay(value);
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
}

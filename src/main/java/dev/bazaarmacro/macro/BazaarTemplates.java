package dev.bazaarmacro.macro;

import dev.bazaarmacro.flipper.FlipperCoordinates;

/**
 * One-click starting point for the single Bazaar transaction flow whose click coordinates are
 * actually verified: Instant Buy - "Buy Instantly" (B2), "Custom Amount" (H2) and "Confirm" (E2),
 * checked against Aether's own hardcoded Bazaar constants (see {@link SlotCoordinate}).
 *
 * <p>Templates for Instant Sell, Buy Order and Sell Offer used to live here too, but every one of
 * their clicks was an unverified {@code "?"} placeholder that {@link SlotCoordinate#parse} rejects
 * at runtime - so those three buttons could only ever insert a macro that failed on its first
 * click. They were removed rather than left as broken starting points: the real way to build any
 * of those flows is one {@code /bfm record start} session, which captures the true coordinates
 * directly instead of seeding a skeleton full of blanks.
 *
 * <p>(The engines themselves don't use this class at all - {@link dev.bazaarmacro.flipper.FlipperEngine}
 * places real buy orders and sell offers through its own separately-verified click sequences.)
 */
public final class BazaarTemplates {
    private BazaarTemplates() {
    }

    /** {@code /bz <item>} -> click the matching search result -> Buy Instantly -> Custom Amount -> computed qty -> Confirm -> close. */
    public static void insertInstantBuy(MacroDefinition macro, String item, String quantityExpression) {
        FlipperCoordinates coords = FlipperCoordinates.get();
        macro.steps.add(MacroStep.command("/bz " + item));
        macro.steps.add(MacroStep.waitForScreen("Bazaar", 10_000));
        macro.steps.add(MacroStep.waitMs(400));
        // Find by exact name match rather than a fixed slot - which result lands where depends
        // on how many matches the search returns, so a fixed coordinate silently clicks the
        // wrong item whenever there's more than one match (confirmed in practice).
        macro.steps.add(MacroStep.findItemSlot(item, 10, 42, 0, "PICKUP"));
        macro.steps.add(MacroStep.waitMs(500));
        macro.steps.add(MacroStep.clickSlot(coords.buyInstantly, 0, "PICKUP")); // "Buy Instantly"
        macro.steps.add(MacroStep.waitMs(500));
        macro.steps.add(MacroStep.clickSlot(coords.amountField, 0, "PICKUP")); // "Custom Amount"
        macro.steps.add(MacroStep.waitMs(500));
        macro.steps.add(MacroStep.compute("qty", item, quantityExpression));
        macro.steps.add(MacroStep.submitSignText("{qty}"));
        macro.steps.add(MacroStep.waitMs(500));
        macro.steps.add(MacroStep.clickSlot(coords.confirmInstant, 0, "PICKUP")); // "Confirm"
        macro.steps.add(MacroStep.waitMs(500));
        macro.steps.add(MacroStep.closeScreen());
    }
}

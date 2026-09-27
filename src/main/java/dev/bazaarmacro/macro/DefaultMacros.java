package dev.bazaarmacro.macro;

/**
 * Seeds a single proof-of-concept macro on first launch so {@code /bfm} has something to
 * show and run immediately: buy Cobblestone from the Bazaar with a quantity computed as
 * 5% of your current purse divided by the live buy price - built via
 * {@link BazaarTemplates#insertInstantBuy}, the same one-click path the "Insta Buy" button in
 * the editor uses, so it also doubles as a demo of the template system.
 *
 * <p>One real Hypixel UI quirk shaped the underlying step sequence, worth knowing before
 * editing it: the Bazaar container's title stays "Bazaar" across the search-results, item, and
 * quantity pages, so {@code WAIT_FOR_SCREEN} can only confirm the *first* transition; later ones
 * use a fixed {@code WAIT} instead. The item itself is located by an exact name scan
 * ({@code FIND_ITEM_SLOT}), not a fixed result slot, so a multi-result search can't misfire.
 */
public final class DefaultMacros {
    private static final String EXAMPLE_NAME = "Buy Cobblestone (5% of purse)";

    private DefaultMacros() {
    }

    public static void seedIfMissing() {
        if (MacroRegistry.find(EXAMPLE_NAME).isPresent()) {
            return;
        }
        MacroDefinition macro = new MacroDefinition(EXAMPLE_NAME);
        BazaarTemplates.insertInstantBuy(macro, "Cobblestone", "floor(purse * 0.05 / buyPrice)");
        MacroRegistry.save(macro);
    }
}

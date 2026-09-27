package dev.bazaarmacro.flipper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.bazaarmacro.util.ConfigPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The item-page click layout {@link FlipperEngine} builds its macros from. Persisted as
 * editable JSON (not hardcoded) so a coordinate can be corrected without a rebuild rather than
 * guessed at - every field below is one this codebase actually clicks, and every one has a real
 * source recorded in the comments (Aether's own constants, a {@code /bfm record} capture, a
 * {@code /bfm debug orders} dump, or direct user confirmation).
 *
 * <p>A {@code sellInstantly} field used to sit here too, defaulted to "C2" under the "verified"
 * heading, but nothing ever clicked it and the Instant Sell flow was never actually verified
 * anywhere in this project - so it was removed rather than left looking confirmed.
 */
public class FlipperCoordinates {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR = ConfigPaths.dir();
    private static final Path FILE = CONFIG_DIR.resolve("flipper_coordinates.json");

    /** Placeholder marking a coordinate/command as not yet filled in - fails loud instead of guessing. */
    public static final String UNVERIFIED = "?";

    /**
     * Bumped whenever the *defaults* below change (a coordinate goes from unverified to
     * verified, a field is renamed, etc.). A file on disk from an older version is discarded
     * and regenerated from the current defaults instead of silently keeping stale values
     * (e.g. a persisted "?" for a field that's since been verified) - see {@link #load()}.
     * Bump this by hand whenever you change a default field value in this class.
     */
    private static final int CURRENT_VERSION = 8;
    public int configVersion = CURRENT_VERSION;

    // -- Verified (matches Aether's own hardcoded Bazaar constants where they overlap).
    // buyInstantly/amountField/confirmInstant drive BazaarTemplates' Instant Buy template, which
    // previously hardcoded these same three values as literals - meaning correcting one here had
    // no effect, defeating the whole point of this file being editable.
    public String buyInstantly = "B2";
    public String buyOrderButton = "G2";
    public String sellOfferButton = "H2";
    public String amountField = "H2";
    public String confirmInstant = "E2";

    // -- Verified from the user directly (2026-07-31): after clicking buyOrderButton, click
    // amountField (H2) -> a sign pops up, type the quantity, click Done -> click orderPricePreset
    // (D2, a "Top Order" price preset - no separate price-typing step) -> click orderConfirm (E2)
    // to place.
    public String orderPricePreset = "D2";
    public String orderConfirm = "E2";

    // -- Verified from the user directly (2026-08-01): the manual-price-entry behavior seen
    // earlier wasn't a different preset slot after all - it was the wrong navigation path
    // entirely. Reaching a Sell Offer via /bz search + clicking a search result's sell button
    // leads to manual price entry with no preset available; the real flow is to click the item
    // directly in the player's own inventory instead (visible at the bottom of any open Bazaar
    // screen - works from any single stack even if the item is split across several), which
    // jumps straight to this same D2 "Top Order" preset for the player's TOTAL held quantity, no
    // amount-entry step at all. See FlipperEngine.placeSellOrder. Kept as its own field (not just
    // reusing orderPricePreset) in case the two screens ever do diverge, but both are D2 today.
    public String sellPricePreset = "D2";

    // -- Verified from a real /bfm debug orders dump (2026-08-01): /bz -> claimOrdersMenu (F6)
    // opens "manage orders", which lays out sell offers and buy orders in SEPARATE rows, not
    // interleaved left-to-right in one row as an earlier, never-independently-checked assumption
    // held (that assumption was only ever exercised with a buy order active, so it happened to
    // look right by luck). Confirmed real layout: sell offers start at B2 (row 2), buy orders
    // start at B3 (row 3); a 2nd concurrent order of the same type would sit one column over
    // (C2/C3), a 3rd at D2/D3, etc. Row 4 holds the menu's own controls (Go Back D4, Close E4,
    // Claim All Coins F4). FlipperEngine only ever runs one order of each type at a time, so the
    // first column (buyClaimSlot/sellClaimSlot) is always the right one to claim in practice.
    public String claimOrdersCommand = "/bz";
    public String claimOrdersMenu = "F6";
    public String buyClaimSlot = "B3";
    public String sellClaimSlot = "B2";

    // -- Verified from the user directly (2026-08-02, corrects an earlier, wrong guess): canceling
    // an unfilled order reuses its claim slot (same click, but since there's nothing to claim yet
    // it opens a management view instead of instantly collecting) -> click cancelButton on THAT NEW
    // screen -> click orderConfirm ("E2") to finalize. The earlier assumption ("same column as the
    // claim slot, row 2" - so "B3" -> "B2" for buy) was never actually right; the real button is a
    // fixed "C2" regardless of which order/column this is, confirmed directly by the user for both
    // sides. See FlipperEngine.buildCancelMacro.
    public String cancelButton = "C2";

    private static FlipperCoordinates instance;

    public static synchronized FlipperCoordinates get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static synchronized void save(FlipperCoordinates coordinates) {
        instance = coordinates;
        try {
            Files.createDirectories(CONFIG_DIR);
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(coordinates, writer);
            }
        } catch (IOException e) {
            System.err.println("[BazaarMacro] Failed to save flipper_coordinates.json: " + e.getMessage());
        }
    }

    private static FlipperCoordinates load() {
        if (!Files.isRegularFile(FILE)) {
            FlipperCoordinates defaults = new FlipperCoordinates();
            save(defaults);
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            FlipperCoordinates loaded = GSON.fromJson(root, FlipperCoordinates.class);
            if (loaded == null) {
                loaded = new FlipperCoordinates();
            }

            // Previously this discarded and regenerated the *entire* object from scratch on any
            // version mismatch - simple, but it meant any hand-edited coordinate got silently
            // wiped back to a hardcoded default on every version bump, even ones that had nothing
            // to do with that specific field. Gson already handles the actual thing this needs to
            // guard against: it uses this class's own no-arg constructor, so field initializers run
            // for any key simply missing from an older file - a genuinely new field still gets its
            // fresh default. Everything else - anything the file already had, hand-edited or not -
            // is preserved as-is. All that's left to do on a version bump is record it.
            if (loaded.configVersion != CURRENT_VERSION) {
                System.out.println("[BazaarMacro] flipper_coordinates.json upgraded from version "
                        + loaded.configVersion + " to " + CURRENT_VERSION + " - existing values kept, "
                        + "only brand-new fields use their defaults.");
                loaded.configVersion = CURRENT_VERSION;
                save(loaded);
            }
            return loaded;
        } catch (IOException e) {
            System.err.println("[BazaarMacro] Failed to load flipper_coordinates.json: " + e.getMessage());
            return new FlipperCoordinates();
        }
    }

    public boolean isBuyOrderFlowConfigured() {
        return !UNVERIFIED.equals(orderPricePreset) && !UNVERIFIED.equals(orderConfirm);
    }

    public boolean isSellOrderFlowConfigured() {
        return !UNVERIFIED.equals(sellPricePreset) && !UNVERIFIED.equals(orderConfirm);
    }

    public boolean isClaimFlowConfigured() {
        return !claimOrdersCommand.isBlank() && !UNVERIFIED.equals(claimOrdersMenu)
                && !UNVERIFIED.equals(buyClaimSlot) && !UNVERIFIED.equals(sellClaimSlot);
    }
}

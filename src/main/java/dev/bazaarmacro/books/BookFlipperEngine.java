package dev.bazaarmacro.books;

import dev.bazaarmacro.bazaar.HypixelBazaarClient;
import dev.bazaarmacro.craft.ScubaCraftScript;
import dev.bazaarmacro.craft.ScubaSellEngine;
import dev.bazaarmacro.flipper.BazaarOrderFlow;
import dev.bazaarmacro.flipper.CancelRejectionWatcher;
import dev.bazaarmacro.flipper.FlipperCoordinates;
import dev.bazaarmacro.flipper.FlipperEngine;
import dev.bazaarmacro.flipper.FlipperManager;
import dev.bazaarmacro.flipper.FlipperSide;
import dev.bazaarmacro.flipper.OrderSetupWatcher;
import dev.bazaarmacro.flipper.SellOrderLoop;
import dev.bazaarmacro.macro.ExecutionContext;
import dev.bazaarmacro.macro.MacroDefinition;
import dev.bazaarmacro.macro.MacroExecutor;
import dev.bazaarmacro.macro.MacroRecorder;
import dev.bazaarmacro.macro.MacroStep;
import dev.bazaarmacro.macro.MacroWorkerThread;
import dev.bazaarmacro.util.ClientUtils;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V0, single-slot proof-of-concept for Book Flipper (see the project plan) - auto-discovers the
 * single best rare, high-margin enchant book via {@link BookSniperScanner}, holds the top buy
 * order for it, and the moment it fills, lists it as a sell offer (mirroring {@link
 * ScubaSellEngine}'s already-proven single-item sell loop, generalized to an arbitrary
 * enchant/level instead of a hardcoded one). Multi-slot concurrency is a later phase - see the
 * plan for why (Hypixel's F6 menu has no name-based way to tell concurrent enchant-book orders
 * apart, since they all share the generic "Enchanted Book" display name; that needs its own
 * verified column-identification mechanism before running more than one at once).
 *
 * <p><b>Verify by price, never by name</b> - the central design decision of this whole feature
 * (see the plan's "Confirmed facts" section). Two things this engine does NOT have reliably in
 * advance: the real Hypixel display name for a book (only ever independently confirmed for Scuba
 * and, live this session, Small Brain - not assumed to generalize to the other ~770 products), and
 * a way to tell which F6 column is which book by name (identical display names). Both are worked
 * around using data this engine already knows with certainty - the real price fetched moments
 * earlier from Hypixel's own public API for the exact tag being handled - confirmed live: a real
 * search-result dump for "Small Brain" showed clear "Buy price: X coins"/"Sell price: Y coins"
 * lore lines matching (after accounting for {@link HypixelBazaarClient}'s already-documented
 * buy/sell field swap) this engine's own computed prices almost exactly.
 */
public final class BookFlipperEngine {
    private enum SlotState {EMPTY, BUY_PENDING, SELL_PENDING}

    private static final long PRICE_CHECK_TICK_MS = 2_000;
    private static final long MIN_FILL_CHECK_INTERVAL_MS = 30_000;
    private static final long MAX_FILL_CHECK_INTERVAL_MS = 60_000;
    /** Rare books don't need FlipperEngine's aggressive re-scan cadence - re-checking the whole market every few seconds buys nothing while a snipe is already in place. */
    private static final long DISCOVERY_INTERVAL_MS = 5 * 60_000;
    /** How many books to snipe per order - kept at 1 for this single-slot proof-of-concept; these are rare, expensive items, not a bulk flip. */
    private static final long BUY_QTY = 1;
    /** Generous tolerance for cross-checking a live in-game price against the API price fetched moments earlier - accounts for real-time drift plus this engine's own price-increment offset. */
    private static final double PRICE_MATCH_TOLERANCE_PERCENT = 5.0;

    /** Same real range {@code FlipperEngine} scans for Bazaar search results on this same screen type. */
    private static final int SEARCH_RESULT_SCAN_START = 10;
    private static final int SEARCH_RESULT_SCAN_END = 42;

    private static final Pattern BUY_PRICE_LORE = Pattern.compile("(?i)Buy price:\\s*([\\d,]+) coins");
    private static final Pattern SELL_PRICE_LORE = Pattern.compile("(?i)Sell price:\\s*([\\d,]+) coins");

    private static volatile boolean active = false;
    private static volatile boolean cancelRequested = false;
    private static volatile Thread thread;

    private static volatile SlotState state = SlotState.EMPTY;
    private static volatile String tag;
    private static volatile int level;

    private static volatile double buyPrice;
    private static volatile long buyQty;

    private static volatile String confirmedEnchantId;
    private static volatile int confirmedLevel;
    // All sell-side state lives in this loop, created once the book's real identity is confirmed.
    private static volatile SellOrderLoop sellLoop;
    private static volatile int notListedStrikes;

    private static volatile long nextFillCheckMs = 0L;
    private static volatile long lastDiscoveryMs = 0L;

    private BookFlipperEngine() {
    }

    public static boolean isActive() {
        return active;
    }

    public static synchronized boolean start() {
        if (active) {
            ClientUtils.sendMessage("§cBook Flipper is already running - run §f/bfm books stop§c first.");
            return false;
        }
        if (FlipperEngine.isActive()) {
            ClientUtils.sendMessage("§cThe flipper is currently active - stop it with §f/bfm stop§c first.");
            return false;
        }
        if (ScubaCraftScript.isActive()) {
            ClientUtils.sendMessage("§cThe Scuba craft script is currently active - stop it with §f/bfm craft stop§c first.");
            return false;
        }
        if (ScubaSellEngine.isActive()) {
            ClientUtils.sendMessage("§cThe Scuba sell engine is currently active - stop it with §f/bfm sell stop§c first.");
            return false;
        }
        if (dev.bazaarmacro.craft.LegionCraftScript.isActive()) {
            ClientUtils.sendMessage("§cThe Legion craft script is currently active - stop it with §f/bfm legion stop§c first.");
            return false;
        }
        if (MacroWorkerThread.getInstance().isRunning()) {
            ClientUtils.sendMessage("§cA macro is currently running - stop it with §f/bfm stop§c first.");
            return false;
        }
        if (MacroRecorder.isRecording()) {
            ClientUtils.sendMessage("§cCan't run this while recording - run §f/bfm record stop§c first.");
            return false;
        }

        active = true;
        cancelRequested = false;
        state = SlotState.EMPTY;
        lastDiscoveryMs = 0L;
        thread = new Thread(BookFlipperEngine::run, "bazaarmacro-book-flipper");
        thread.setDaemon(true);
        thread.start();
        ClientUtils.sendMessage("§dBook Flipper started - scanning for a rare, high-margin book to snipe.");
        return true;
    }

    public static synchronized void stop() {
        if (!active) {
            ClientUtils.sendMessage("§7Book Flipper isn't running.");
            return;
        }
        cancelRequested = true;
        ClientUtils.sendMessage("§7Stopping Book Flipper - any currently open order stays live.");
    }

    private static void run() {
        try {
            while (!cancelRequested) {
                try {
                    tick();
                } catch (Exception e) {
                    ClientUtils.sendMessage("§cBook Flipper tick failed: " + e.getMessage());
                    dev.bazaarmacro.util.ErrorReporter.report("BookFlipperEngine", "Tick threw", e);
                }
                MacroWorkerThread.sleep(PRICE_CHECK_TICK_MS);
            }
        } finally {
            active = false;
        }
    }

    private static void tick() throws Exception {
        switch (state) {
            case EMPTY -> tryDiscoverAndSnipe();
            case BUY_PENDING -> tickBuy();
            case SELL_PENDING -> tickSell();
        }
    }

    // -- Discovery + buy-order placement --------------------------------------------------------

    private static void tryDiscoverAndSnipe() throws Exception {
        long now = System.currentTimeMillis();
        if (now - lastDiscoveryMs < DISCOVERY_INTERVAL_MS) return;
        lastDiscoveryMs = now;

        List<BookSniperScanner.Candidate> candidates = BookSniperScanner.findCandidates();
        if (candidates.isEmpty()) {
            ClientUtils.sendMessage("§7No book candidates clear the thresholds right now - will re-check in "
                    + (DISCOVERY_INTERVAL_MS / 60_000) + " minutes.");
            return;
        }

        BookSniperScanner.Candidate best = candidates.get(0);
        ClientUtils.sendMessage("§dSniping candidate: §f" + best.tag() + " §d(margin "
                + ExecutionContext.formatDisplay(best.marginPerUnit()) + ", " + String.format("%.2f", best.tradesPerHour()) + "/hr).");
        placeBuyOrderForCandidate(best);
    }

    private static void placeBuyOrderForCandidate(BookSniperScanner.Candidate c) {
        FlipperCoordinates coords = FlipperCoordinates.get();
        if (!coords.isBuyOrderFlowConfigured()) {
            ClientUtils.sendMessage("§cBuy Order coordinates aren't set up yet - fill in flipper_coordinates.json.");
            return;
        }

        int slot = locateVerifiedSearchResult(c);
        if (slot < 0) {
            ClientUtils.sendMessage("§cCouldn't confidently verify any search result for " + c.tag()
                    + " after trying every real candidate search text - skipping this candidate rather than guessing.");
            return;
        }

        MacroDefinition clickResult = new MacroDefinition("BookClickResult");
        clickResult.steps.add(MacroStep.clickSlot(String.valueOf(slot), 0, "PICKUP"));
        clickResult.steps.add(MacroStep.waitMs(800));
        clickResult.steps.add(MacroStep.clickSlot(coords.buyOrderButton, 0, "PICKUP"));
        clickResult.steps.add(MacroStep.waitMs(900));
        if (!MacroExecutor.runBlocking(clickResult, new ExecutionContext(), () -> cancelRequested)) {
            ClientUtils.sendMessage("§cFailed to open the buy-order page for " + c.tag() + " - skipping this candidate.");
            return;
        }

        MacroDefinition amount = new MacroDefinition("BookAmount");
        amount.steps.add(MacroStep.clickSlot(coords.amountField, 0, "PICKUP"));
        amount.steps.add(MacroStep.waitMs(500));
        amount.steps.add(MacroStep.submitSignText(String.valueOf(BUY_QTY)));
        amount.steps.add(MacroStep.waitMs(2000));
        if (!MacroExecutor.runBlocking(amount, new ExecutionContext(), () -> cancelRequested)) {
            ClientUtils.sendMessage("§cFailed to enter a quantity for " + c.tag() + " - skipping this candidate.");
            BazaarOrderFlow.closeScreen("BookCloseScreen", () -> cancelRequested);
            return;
        }

        MacroDefinition preset = new MacroDefinition("BookPricePreset");
        preset.steps.add(MacroStep.clickSlotReal(coords.orderPricePreset, 0));
        preset.steps.add(MacroStep.waitMs(700));
        MacroExecutor.runBlocking(preset, new ExecutionContext(), () -> cancelRequested);

        MacroDefinition confirm = new MacroDefinition("BookConfirm");
        confirm.steps.add(MacroStep.clickSlot(coords.orderConfirm, 0, "PICKUP"));
        confirm.steps.add(MacroStep.waitMs(500));
        confirm.steps.add(MacroStep.closeScreen());
        MacroExecutor.runBlocking(confirm, new ExecutionContext(), () -> cancelRequested);

        OrderSetupWatcher.PriceConfirmed confirmed = OrderSetupWatcher.drainMatchingBuyByPrice(c.buyOrderPrice(), PRICE_MATCH_TOLERANCE_PERCENT);
        if (confirmed == null) {
            MacroWorkerThread.sleep(500);
            confirmed = OrderSetupWatcher.drainMatchingBuyByPrice(c.buyOrderPrice(), PRICE_MATCH_TOLERANCE_PERCENT);
        }
        if (confirmed == null) {
            ClientUtils.sendMessage("§cNo buy-order confirmation matched the expected price (" + ExecutionContext.formatDisplay(c.buyOrderPrice())
                    + ") for " + c.tag() + " - not trusting this as placed. Skipping.");
            return;
        }

        tag = c.tag();
        level = c.level();
        buyPrice = confirmed.pricePerUnit();
        buyQty = confirmed.quantity();
        notListedStrikes = 0;
        nextFillCheckMs = System.currentTimeMillis() + BazaarOrderFlow.randomFillCheckIntervalMs(MIN_FILL_CHECK_INTERVAL_MS, MAX_FILL_CHECK_INTERVAL_MS);
        state = SlotState.BUY_PENDING;
        ClientUtils.sendMessage("§aBuy order placed: " + confirmed.quantity() + "x \"" + confirmed.itemDisplayName()
                + "\" at " + ExecutionContext.formatDisplay(confirmed.pricePerUnit()) + " each.");
    }

    /**
     * Scans the real search-result slots for one whose displayed name matches {@code
     * expectedName} AND whose own lore price matches {@code c}'s already-known prices - both must
     * agree before this is trusted, per the user's explicit instruction not to guess. Confirmed
     * live: a real search result's lore reads "Buy price: X coins"/"Sell price: Y coins" - after
     * {@link HypixelBazaarClient}'s already-documented swap, in-game "Buy price" (cost to instant
     * buy) lines up with this candidate's {@code sellOfferPrice}, and in-game "Sell price" (instant
     * sell proceeds) lines up with {@code buyOrderPrice}.
     */
    /**
     * Tries every real candidate search text for {@code c.tag()} in turn (see {@link
     * BookSniperScanner#deriveSearchTextCandidates}) - never trusts a single guessed search text
     * on its own. A live test showed the full derived text ("ultimate legion") return "No Product
     * Found" for every result on a real Ultimate-family enchant, so a lone guess isn't reliable
     * enough to commit to blindly. Only ever returns a slot whose real displayed name AND price
     * both match one of the real candidates, dumping the screen for diagnosis if none of them do.
     */
    private static int locateVerifiedSearchResult(BookSniperScanner.Candidate c) {
        List<String> candidates = BookSniperScanner.deriveSearchTextCandidates(c.tag());
        for (int i = 0; i < candidates.size(); i++) {
            String searchText = candidates.get(i);
            String expectedName = BookSniperScanner.deriveExpectedDisplayNameFromSearchText(searchText, c.level());

            BazaarOrderFlow.respectRateLimit();
            MacroDefinition open = new MacroDefinition("BookOpenSearch");
            open.steps.add(MacroStep.command("/bz " + searchText));
            open.steps.add(MacroStep.waitForScreen("Bazaar", 10_000));
            open.steps.add(MacroStep.waitMs(600));
            if (!MacroExecutor.runBlocking(open, new ExecutionContext(), () -> cancelRequested)) {
                ClientUtils.sendMessage("§cCouldn't open a search for \"" + searchText + "\" - trying the next candidate.");
                continue;
            }

            int slot = findVerifiedResultSlot(c, expectedName);
            if (slot < 0) {
                // Real lag, not necessarily a genuine miss - give the search results one more real chance to render
                // (confirmed live: the same search text can correctly match once, then transiently fail moments
                // later right after other real actions - see LegionCraftScript's class doc for the exact case).
                MacroWorkerThread.sleep(1500);
                slot = findVerifiedResultSlot(c, expectedName);
            }
            if (slot >= 0) {
                ClientUtils.sendMessage("§7Confirmed real search text \"" + searchText + "\" -> \"" + expectedName
                        + "\" (matched by both name and price).");
                return slot;
            }

            boolean lastCandidate = i == candidates.size() - 1;
            ClientUtils.sendMessage("§7Search \"" + searchText + "\" (expected \"" + expectedName + "\") didn't yield a "
                    + "confirmed match even after a retry wait" + (lastCandidate ? "." : " - trying the next candidate.")
                    + " Dumping the real screen below:");
            ClientUtils.debugDumpOpenScreen();
            BazaarOrderFlow.closeScreen("BookCloseScreen", () -> cancelRequested);
        }
        return -1;
    }

    private static int findVerifiedResultSlot(BookSniperScanner.Candidate c, String expectedName) {
        AbstractContainerScreen<?> screen = ClientUtils.getOpenContainerScreen();
        if (screen == null) return -1;

        List<Slot> slots = screen.getMenu().slots;
        int end = Math.min(SEARCH_RESULT_SCAN_END, slots.size() - 1);
        for (int i = Math.max(0, SEARCH_RESULT_SCAN_START); i <= end; i++) {
            Slot slot = slots.get(i);
            if (!slot.hasItem()) continue;
            String name = ClientUtils.stripColors(slot.getItem().getHoverName().getString()).trim();
            if (!name.equalsIgnoreCase(expectedName)) continue;

            List<String> lore = ClientUtils.getOpenSlotLore(i);
            Double inGameBuyPrice = parsePriceFromLore(lore, BUY_PRICE_LORE);
            Double inGameSellPrice = parsePriceFromLore(lore, SELL_PRICE_LORE);
            if (inGameBuyPrice == null || inGameSellPrice == null) continue;

            if (withinTolerance(inGameBuyPrice, c.sellOfferPrice()) && withinTolerance(inGameSellPrice, c.buyOrderPrice())) {
                return i;
            }
        }
        return -1;
    }

    private static Double parsePriceFromLore(List<String> lore, Pattern pattern) {
        for (String line : lore) {
            Matcher m = pattern.matcher(line);
            if (m.find()) {
                try {
                    return Double.parseDouble(m.group(1).replace(",", ""));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private static boolean withinTolerance(double actual, double expected) {
        if (expected <= 0) return false;
        return Math.abs(actual - expected) / expected * 100.0 <= PRICE_MATCH_TOLERANCE_PERCENT;
    }

    // -- Watching the open buy order -------------------------------------------------------------

    private static void tickBuy() throws Exception {
        FlipperManager.TopCheckResult result;
        try {
            result = FlipperManager.checkTop(tag, FlipperSide.BUY_ORDER, buyPrice);
        } catch (Exception e) {
            ClientUtils.sendMessage("§cBuy order book check failed for " + tag + ": " + e.getMessage());
            return;
        }

        if (result.status() == FlipperManager.TopCheckResult.Status.BEHIND
                || result.status() == FlipperManager.TopCheckResult.Status.TIED) {
            ClientUtils.sendMessage("§eOutbid on " + tag + " (top is now " + ExecutionContext.formatDisplay(result.topPrice())
                    + ") - canceling and re-sniping fresh.");
            claimThenCancelBuy();
            state = SlotState.EMPTY;
            lastDiscoveryMs = 0L; // force an immediate fresh discovery pass next tick, not a stale 5-minute wait
            return;
        }

        if (System.currentTimeMillis() < nextFillCheckMs) return;
        nextFillCheckMs = System.currentTimeMillis() + BazaarOrderFlow.randomFillCheckIntervalMs(MIN_FILL_CHECK_INTERVAL_MS, MAX_FILL_CHECK_INTERVAL_MS);
        checkForBuyFill();
    }

    private static void checkForBuyFill() {
        FlipperCoordinates coords = FlipperCoordinates.get();
        if (!coords.isClaimFlowConfigured()) return;

        BazaarOrderFlow.respectRateLimit();
        Map<ClientUtils.EnchantBookInfo, Long> beforeClaim = ClientUtils.countAllEnchantBooks();
        boolean stillListed = BazaarOrderFlow.openMenuAndCheckSlot("Book", coords.buyClaimSlot, () -> cancelRequested);

        if (stillListed) {
            notListedStrikes = 0;
            MacroDefinition claim = new MacroDefinition("BookClaimClick");
            claim.steps.add(MacroStep.clickSlot(coords.buyClaimSlot, 0, "PICKUP"));
            claim.steps.add(MacroStep.waitMs(700));
            claim.steps.add(MacroStep.closeScreen());
            MacroExecutor.runBlocking(claim, new ExecutionContext(), () -> cancelRequested);
        } else {
            BazaarOrderFlow.closeScreen("BookCloseScreen", () -> cancelRequested);
            notListedStrikes++;
            if (notListedStrikes < BazaarOrderFlow.NOT_LISTED_STRIKE_LIMIT) return; // ambiguous miss - recheck next poll
        }

        Map<ClientUtils.EnchantBookInfo, Long> afterClaim = ClientUtils.countAllEnchantBooks();
        ClientUtils.EnchantBookInfo newBook = findIncreasedBook(beforeClaim, afterClaim);
        if (newBook == null) {
            if (!stillListed) {
                ClientUtils.sendMessage("§eBuy order for " + tag + " is no longer listed, but no new enchant book "
                        + "appeared in inventory - treating this slot as lost. Going back to discovery.");
                state = SlotState.EMPTY;
            }
            return; // still listed and nothing claimed yet - keep waiting
        }

        ClientUtils.sendMessage("§aBuy order filled: got a real level " + newBook.level() + " \"" + newBook.enchantId()
                + "\" book - confirmed directly from its own NBT, not guessed from the search text.");
        confirmedEnchantId = newBook.enchantId();
        confirmedLevel = newBook.level();
        sellLoop = null; // rebuilt for this specific book by sellLoop()
        notListedStrikes = 0;
        state = SlotState.SELL_PENDING;
    }

    private static ClientUtils.EnchantBookInfo findIncreasedBook(Map<ClientUtils.EnchantBookInfo, Long> before, Map<ClientUtils.EnchantBookInfo, Long> after) {
        for (Map.Entry<ClientUtils.EnchantBookInfo, Long> entry : after.entrySet()) {
            long beforeCount = before.getOrDefault(entry.getKey(), 0L);
            if (entry.getValue() > beforeCount) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static void claimThenCancelBuy() {
        FlipperCoordinates coords = FlipperCoordinates.get();
        if (!coords.isClaimFlowConfigured()) return;
        String claimSlot = coords.buyClaimSlot;

        for (int attempt = 1; attempt <= BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT; attempt++) {
            CancelRejectionWatcher.drainRejected();

            BazaarOrderFlow.respectRateLimit();
            MacroDefinition claim = new MacroDefinition("BookClaimBeforeCancel");
            claim.steps.add(MacroStep.command(coords.claimOrdersCommand));
            claim.steps.add(MacroStep.waitForScreen("Bazaar", 10_000));
            claim.steps.add(MacroStep.waitMs(400));
            claim.steps.add(MacroStep.clickSlot(coords.claimOrdersMenu, 0, "PICKUP"));
            BazaarOrderFlow.addTransitionWait(claim);
            claim.steps.add(MacroStep.clickSlot(claimSlot, 0, "PICKUP"));
            claim.steps.add(MacroStep.waitMs(500));
            claim.steps.add(MacroStep.closeScreen());
            MacroExecutor.runBlocking(claim, new ExecutionContext(), () -> cancelRequested);

            BazaarOrderFlow.respectRateLimit();
            MacroDefinition cancel = new MacroDefinition("BookCancel");
            cancel.steps.add(MacroStep.command(coords.claimOrdersCommand));
            cancel.steps.add(MacroStep.waitForScreen("Bazaar", 10_000));
            cancel.steps.add(MacroStep.waitMs(400));
            cancel.steps.add(MacroStep.clickSlot(coords.claimOrdersMenu, 0, "PICKUP"));
            BazaarOrderFlow.addTransitionWait(cancel);
            cancel.steps.add(MacroStep.clickSlot(claimSlot, 0, "PICKUP"));
            BazaarOrderFlow.addTransitionWait(cancel);
            cancel.steps.add(MacroStep.clickSlotReal(coords.cancelButton, 0));
            cancel.steps.add(MacroStep.waitMs(700));
            cancel.steps.add(MacroStep.clickSlot(coords.orderConfirm, 0, "PICKUP"));
            cancel.steps.add(MacroStep.waitMs(500));
            cancel.steps.add(MacroStep.closeScreen());
            boolean canceled = MacroExecutor.runBlocking(cancel, new ExecutionContext(), () -> cancelRequested);

            if (canceled && !CancelRejectionWatcher.drainRejected()) return;
            if (attempt < BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT) {
                ClientUtils.sendMessage("§7Hypixel rejected canceling the buy order - retrying (attempt "
                        + (attempt + 1) + "/" + BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT + ").");
            }
        }
        ClientUtils.sendMessage("§cFailed to cancel the buy order for " + tag + " after " + BazaarOrderFlow.CANCEL_REJECTION_RETRY_LIMIT + " attempts.");
    }

    // -- Selling the confirmed book (mirrors ScubaSellEngine, generalized to any enchant/level) --

    // -- Selling the confirmed book: all of it lives in SellOrderLoop; this owns the instance and
    // decides what happens once it sells (free the slot and go hunt the next candidate).

    private static SellOrderLoop sellLoop() {
        if (sellLoop == null) {
            sellLoop = new SellOrderLoop(
                    new SellOrderLoop.Config(tag, confirmedEnchantId, confirmedLevel,
                            // Derived from the tag, so a best-effort guess only - tried first when
                            // confirming the listing, never trusted alone for crediting coins. See
                            // the class doc: these books can't be told apart by display name.
                            BookSniperScanner.deriveExpectedDisplayName(tag, confirmedLevel), false,
                            "Book", MIN_FILL_CHECK_INTERVAL_MS, MAX_FILL_CHECK_INTERVAL_MS,
                            PRICE_MATCH_TOLERANCE_PERCENT),
                    () -> cancelRequested,
                    BookFlipperEngine::onSoldOut);
        }
        return sellLoop;
    }

    private static void tickSell() {
        sellLoop().tick();
    }

    /** Sold - free the slot and let the next discovery tick pick a fresh candidate immediately. */
    private static void onSoldOut() {
        sellLoop = null;
        state = SlotState.EMPTY;
        lastDiscoveryMs = 0L;
    }

}

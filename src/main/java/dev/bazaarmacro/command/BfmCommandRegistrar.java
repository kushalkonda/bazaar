package dev.bazaarmacro.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.bazaarmacro.alerts.PriceAlert;
import dev.bazaarmacro.alerts.PriceAlertManager;
import dev.bazaarmacro.bazaar.BazaarPriceClient;
import dev.bazaarmacro.bazaar.HypixelBazaarClient;
import dev.bazaarmacro.books.BookSniperScanner;
import dev.bazaarmacro.order.FlipTestScript;
import dev.bazaarmacro.flipper.FlipperCoordinates;
import dev.bazaarmacro.flipper.FlipperEngine;
import dev.bazaarmacro.flipper.FlipperManager;
import dev.bazaarmacro.flipper.FlipperSide;
import dev.bazaarmacro.flipper.SessionHistoryStorage;
import dev.bazaarmacro.macro.ExecutionContext;
import dev.bazaarmacro.macro.MacroDefinition;
import dev.bazaarmacro.macro.MacroExecutor;
import dev.bazaarmacro.macro.MacroExpression;
import dev.bazaarmacro.macro.MacroRecorder;
import dev.bazaarmacro.macro.MacroRegistry;
import dev.bazaarmacro.macro.MacroStep;
import dev.bazaarmacro.macro.MacroWorkerThread;
import dev.bazaarmacro.ui.BazaarMacroScreen;
import dev.bazaarmacro.ui.MacroListScreen;
import dev.bazaarmacro.util.ClientUtils;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Registers {@code /bfm} (not {@code /bm} - that collides with an existing Hypixel client
 * command). Opens the {@link BazaarMacroScreen} by default; {@code /bfm macros} reaches the
 * general-purpose macro editor (no longer linked from the main screen, but still fully usable
 * from chat).
 */
public final class BfmCommandRegistrar {
    private BfmCommandRegistrar() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("bfm")
                        .executes(ctx -> {
                            openFlipperGui();
                            return 1;
                        })
                        .then(ClientCommands.literal("gui").executes(ctx -> {
                            openFlipperGui();
                            return 1;
                        }))
                        .then(ClientCommands.literal("macros").executes(ctx -> {
                            Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new MacroListScreen()));
                            return 1;
                        }))
                        .then(ClientCommands.literal("list").executes(ctx -> {
                            listMacros();
                            return 1;
                        }))
                        .then(ClientCommands.literal("stop").executes(ctx -> {
                            stopWhateverIsActive();
                            return 1;
                        }))
                        .then(ClientCommands.literal("run")
                                .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                        .suggests((ctx, builder) -> suggestMacroNames(builder))
                                        .executes(ctx -> {
                                            runMacro(StringArgumentType.getString(ctx, "name"));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("record")
                                .then(ClientCommands.literal("start")
                                        .executes(ctx -> {
                                            startRecording(null);
                                            return 1;
                                        })
                                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                                .suggests((ctx, builder) -> suggestMacroNames(builder))
                                                .executes(ctx -> {
                                                    startRecording(StringArgumentType.getString(ctx, "name"));
                                                    return 1;
                                                })))
                                .then(ClientCommands.literal("stop").executes(ctx -> {
                                    MacroRecorder.stop();
                                    return 1;
                                })))
                        // Exact Hypixel product tag only (no spaces) - use the GUI for display names like "Enchanted Mycelium".
                        .then(ClientCommands.literal("start")
                                .then(ClientCommands.argument("item", StringArgumentType.word())
                                        .executes(ctx -> {
                                            FlipperEngine.start(StringArgumentType.getString(ctx, "item"), "", 0);
                                            return 1;
                                        })
                                        .then(ClientCommands.argument("cap", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    FlipperEngine.start(StringArgumentType.getString(ctx, "item"),
                                                            StringArgumentType.getString(ctx, "cap"), 0);
                                                    return 1;
                                                })
                                                .then(ClientCommands.argument("durationMinutes", DoubleArgumentType.doubleArg(0))
                                                        .executes(ctx -> {
                                                            FlipperEngine.start(StringArgumentType.getString(ctx, "item"),
                                                                    StringArgumentType.getString(ctx, "cap"),
                                                                    DoubleArgumentType.getDouble(ctx, "durationMinutes"));
                                                            return 1;
                                                        })))))
                        .then(flipCommand())
                        .then(alertCommand())
                        .then(testCommand())
                        .then(booksCommand())
                        .then(errorsCommand())
                        .then(ClientCommands.literal("scan").executes(ctx -> {
                            Minecraft.getInstance().execute(() -> BazaarMacroScreen.openOnPage(BazaarMacroScreen.Page.BAZAAR_SCANNER));
                            return 1;
                        }))
                        .then(ClientCommands.literal("history").executes(ctx -> {
                            listHistory();
                            return 1;
                        }))
                        .then(ClientCommands.literal("debug")
                                .then(ClientCommands.literal("inventory").executes(ctx -> {
                                    ClientUtils.debugDumpInventory();
                                    return 1;
                                }))
                                .then(ClientCommands.literal("orders").executes(ctx -> {
                                    debugDumpOrders();
                                    return 1;
                                }))
                                .then(ClientCommands.literal("root").executes(ctx -> {
                                    debugDumpRootScreen();
                                    return 1;
                                }))
                                .then(ClientCommands.literal("screen").executes(ctx -> {
                                    ClientUtils.debugDumpOpenScreen();
                                    return 1;
                                })))));
    }

    /**
     * {@code /bfm errors} - lists the most recent crash reports {@link dev.bazaarmacro.util.ErrorReporter}
     * has written (newest first, with full paths so they're easy to open/paste). These accumulate
     * automatically whenever any autonomous engine hits an error or a hard operational failure -
     * this command just surfaces where they landed, nothing needs to be manually configured first.
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> errorsCommand() {
        return ClientCommands.literal("errors").executes(ctx -> {
            listErrorReports();
            return 1;
        });
    }

    private static void listErrorReports() {
        java.nio.file.Path dir = dev.bazaarmacro.util.ConfigPaths.crashLogDir();
        if (!java.nio.file.Files.isDirectory(dir)) {
            ClientUtils.sendMessage("§7No crash reports yet - nothing has failed since this was added.");
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(dir)) {
            List<java.nio.file.Path> sorted = files.filter(java.nio.file.Files::isRegularFile)
                    .sorted(java.util.Comparator.comparingLong((java.nio.file.Path p) -> {
                        try {
                            return java.nio.file.Files.getLastModifiedTime(p).toMillis();
                        } catch (java.io.IOException e) {
                            return 0L;
                        }
                    }).reversed())
                    .limit(10)
                    .toList();
            if (sorted.isEmpty()) {
                ClientUtils.sendMessage("§7No crash reports yet - nothing has failed since this was added.");
                return;
            }
            ClientUtils.sendMessage("§7--- Most recent crash reports (newest first) ---");
            for (java.nio.file.Path file : sorted) {
                ClientUtils.sendMessage("§7" + file.toAbsolutePath());
            }
            ClientUtils.sendMessage("§7--- end list (" + dir.toAbsolutePath() + ") ---");
        } catch (java.io.IOException e) {
            ClientUtils.sendMessage("§cCouldn't list crash reports: " + e.getMessage());
        }
    }

    /** {@code /bfm flip check <item> <buy|sell> <price>} - standalone one-shot top-price check, unrelated to the autonomous engine. */
    private static LiteralArgumentBuilder<FabricClientCommandSource> flipCommand() {
        return ClientCommands.literal("flip")
                .then(ClientCommands.literal("check")
                        .then(ClientCommands.argument("item", StringArgumentType.word())
                                .then(ClientCommands.argument("side", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestSides(builder))
                                        .then(ClientCommands.argument("price", DoubleArgumentType.doubleArg(0))
                                                .executes(ctx -> {
                                                    checkTop(StringArgumentType.getString(ctx, "item"),
                                                            StringArgumentType.getString(ctx, "side"),
                                                            DoubleArgumentType.getDouble(ctx, "price"));
                                                    return 1;
                                                })))));
    }

    /**
     * {@code /bfm alert add <item> <buy-order|sell-offer> <expression>} - fires once when the
     * live order-book price for that side crosses the resolved target, then removes itself.
     * Standalone from {@link FlipperEngine}: alerts poll independently via
     * {@link PriceAlertManager}, so they work whether or not the flipper is running.
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> alertCommand() {
        return ClientCommands.literal("alert")
                .then(ClientCommands.literal("add")
                        .then(ClientCommands.argument("item", StringArgumentType.word())
                                .then(ClientCommands.argument("side", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestAlertSides(builder))
                                        .then(ClientCommands.argument("expression", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    addAlert(StringArgumentType.getString(ctx, "item"),
                                                            StringArgumentType.getString(ctx, "side"),
                                                            StringArgumentType.getString(ctx, "expression"));
                                                    return 1;
                                                })))))
                .then(ClientCommands.literal("list").executes(ctx -> {
                    listAlerts();
                    return 1;
                }))
                .then(ClientCommands.literal("remove")
                        .then(ClientCommands.argument("index", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    removeAlert(IntegerArgumentType.getInteger(ctx, "index"));
                                    return 1;
                                })));
    }

    /**
     * {@code /bfm test start [qty]} - buys N Summoning Eyes and lists them straight back, on
     * repeat: the reference exercise for both order engines, see {@link FlipTestScript}. Trades
     * real coins, so the quantity defaults to 1 and is raised explicitly.
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> testCommand() {
        return ClientCommands.literal("test")
                .then(ClientCommands.literal("start")
                        .executes(ctx -> {
                            FlipTestScript.start(1);
                            return 1;
                        })
                        .then(ClientCommands.argument("qty", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    FlipTestScript.start(IntegerArgumentType.getInteger(ctx, "qty"));
                                    return 1;
                                })))
                .then(ClientCommands.literal("stop").executes(ctx -> {
                    FlipTestScript.stop();
                    return 1;
                }));
    }

    /**
     * {@code /bfm books scan} / {@code locate} - read-only analysis over the real Bazaar via
     * {@link BookSniperScanner}: which rare books currently clear the margin and volume floors, and
     * what a given candidate's real search result looks like in-game. Places no orders; the engine
     * that traded off this was removed in the engine rewrite, leaving the scanner as the research
     * tool it always was.
     */
    private static LiteralArgumentBuilder<FabricClientCommandSource> booksCommand() {
        return ClientCommands.literal("books")
                .then(ClientCommands.literal("scan").executes(ctx -> {
                    scanBooks();
                    return 1;
                }))
                .then(ClientCommands.literal("locate")
                        .then(ClientCommands.argument("rank", IntegerArgumentType.integer(1))
                                .executes(ctx -> {
                                    locateBookCandidate(IntegerArgumentType.getInteger(ctx, "rank"));
                                    return 1;
                                })));
    }

    /**
     * {@code /bfm books locate <rank>} - temporary diagnostic (see the Book Flipper plan). Takes
     * the Nth-ranked candidate from a fresh {@link BookSniperScanner} run, searches {@code /bz}
     * with a loose best-effort text derived from its tag, and dumps the resulting screen's real
     * slot layout/lore. This exists specifically because nothing in this codebase has ever
     * confirmed what a Bazaar *search result* actually shows in its lore (only an
     * *already-placed order*'s lore, via {@code /bfm debug orders}, has been confirmed) - real
     * data from this is needed before any buy-order placement logic gets built on an assumption.
     */
    private static void locateBookCandidate(int rank) {
        CompletableFuture.runAsync(() -> {
            try {
                List<BookSniperScanner.Candidate> candidates = BookSniperScanner.findCandidates();
                if (rank > candidates.size()) {
                    ClientUtils.sendMessage("§cOnly " + candidates.size() + " candidate(s) available - asked for #" + rank + ".");
                    return;
                }
                BookSniperScanner.Candidate c = candidates.get(rank - 1);
                String searchText = BookSniperScanner.deriveLooseSearchText(c.tag());
                String expectedName = BookSniperScanner.deriveExpectedDisplayName(c.tag(), c.level());
                ClientUtils.sendMessage("§7Candidate #" + rank + ": §f" + c.tag() + " §7- expected buy "
                        + ExecutionContext.formatDisplay(c.buyOrderPrice()) + ", sell "
                        + ExecutionContext.formatDisplay(c.sellOfferPrice()) + " §7- expected name §f\"" + expectedName
                        + "\" §7- searching §f/bz " + searchText);

                MacroDefinition macro = new MacroDefinition("BooksLocate");
                macro.steps.add(MacroStep.command("/bz " + searchText));
                macro.steps.add(MacroStep.waitForScreen("Bazaar", 10_000));
                macro.steps.add(MacroStep.waitMs(600));
                if (!MacroExecutor.runBlocking(macro, new ExecutionContext(), () -> false)) {
                    ClientUtils.sendMessage("§cSearch didn't open a Bazaar screen - see the error above.");
                    return;
                }
                ClientUtils.debugDumpOpenScreen();
            } catch (Exception e) {
                ClientUtils.sendMessage("§cLocate failed: " + e.getMessage());
            }
        });
    }

    private static void scanBooks() {
        CompletableFuture.runAsync(() -> {
            try {
                List<BookSniperScanner.Candidate> candidates = BookSniperScanner.findCandidates();
                if (candidates.isEmpty()) {
                    ClientUtils.sendMessage("§7No book candidates cleared the margin/volume thresholds right now.");
                    return;
                }
                ClientUtils.sendMessage("§7Top book candidates (" + candidates.size() + " total, showing up to 15):");
                int shown = Math.min(15, candidates.size());
                for (int i = 0; i < shown; i++) {
                    BookSniperScanner.Candidate c = candidates.get(i);
                    ClientUtils.sendMessage("§7 " + (i + 1) + ". §f" + c.tag() + " §7(level " + c.level() + ") - "
                            + "buy " + ExecutionContext.formatDisplay(c.buyOrderPrice()) + ", sell "
                            + ExecutionContext.formatDisplay(c.sellOfferPrice()) + " §7- margin "
                            + ExecutionContext.formatDisplay(c.marginPerUnit()) + " ("
                            + String.format("%.0f", c.marginPercent()) + "%), "
                            + String.format("%.2f", c.tradesPerHour()) + "/hr, score "
                            + String.format("%.0f", c.score()));
                }
            } catch (Exception e) {
                ClientUtils.sendMessage("§cBook scan failed: " + e.getMessage());
            }
        });
    }

    private static CompletableFuture<Suggestions> suggestMacroNames(SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        for (MacroDefinition macro : MacroRegistry.all()) {
            if (macro.name.toLowerCase().startsWith(remaining)) {
                builder.suggest(macro.name);
            }
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestSides(SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        for (String option : new String[]{"buy", "sell"}) {
            if (option.startsWith(remaining)) {
                builder.suggest(option);
            }
        }
        return builder.buildFuture();
    }

    private static void openFlipperGui() {
        if (MacroRecorder.isRecording()) {
            MacroRecorder.stop();
            return;
        }
        Minecraft.getInstance().execute(BazaarMacroScreen::open);
    }

    /**
     * {@code /bfm debug orders} - opens the manage-orders (F6) menu and dumps its real slot layout.
     * This is what originally established that layout (sell offers on row 2, buy orders on row 3 -
     * see {@link FlipperCoordinates}), replacing an earlier wrong "interleaved in one row" guess.
     * That question is settled; the command stays as the standing way to re-check the real layout
     * whenever a claim slot reads unexpectedly, rather than inferring what the menu contained.
     */
    private static void debugDumpOrders() {
        if (FlipperEngine.isActive()) {
            ClientUtils.sendMessage("§cStop the flipper first with §f/bfm stop§c before running this diagnostic.");
            return;
        }
        if (MacroWorkerThread.getInstance().isRunning()) {
            ClientUtils.sendMessage("§cA macro is currently running - stop it with §f/bfm stop§c first.");
            return;
        }
        if (MacroRecorder.isRecording()) {
            ClientUtils.sendMessage("§cCan't run this while recording - run §f/bfm record stop§c first.");
            return;
        }

        FlipperCoordinates coords = FlipperCoordinates.get();
        Thread thread = new Thread(() -> {
            MacroDefinition macro = new MacroDefinition("DebugManageOrders");
            macro.steps.add(MacroStep.command(coords.claimOrdersCommand));
            macro.steps.add(MacroStep.waitForScreen("Bazaar", 10_000));
            macro.steps.add(MacroStep.waitMs(400));
            macro.steps.add(MacroStep.clickSlot(coords.claimOrdersMenu, 0, "PICKUP"));
            macro.steps.add(MacroStep.waitMs(500));
            boolean ok = MacroExecutor.runBlocking(macro, new ExecutionContext(), () -> false);
            if (!ok) {
                ClientUtils.sendMessage("§cCouldn't open the manage-orders menu - see the error above.");
                return;
            }
            ClientUtils.debugDumpOpenScreen();
            ClientUtils.closeScreen();
        }, "bazaarmacro-debug-orders");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * {@code /bfm debug root} - opens the plain {@code /bz} root screen (no search, no F6) and
     * dumps its real slot layout. Originally added because the sell-offer flow needed to know where
     * the player's own inventory mirrors on this specific screen; the answer was to stop depending
     * on a fixed offset at all - the sell flow now scans a deliberately wide range and matches by
     * item rather than position (see {@code FlipperEngine.placeSellOrder}). Kept as the standing way
     * to read this screen's real contents when something there doesn't behave as expected.
     */
    private static void debugDumpRootScreen() {
        if (FlipperEngine.isActive()) {
            ClientUtils.sendMessage("§cStop the flipper first with §f/bfm stop§c before running this diagnostic.");
            return;
        }
        if (MacroWorkerThread.getInstance().isRunning()) {
            ClientUtils.sendMessage("§cA macro is currently running - stop it with §f/bfm stop§c first.");
            return;
        }
        if (MacroRecorder.isRecording()) {
            ClientUtils.sendMessage("§cCan't run this while recording - run §f/bfm record stop§c first.");
            return;
        }

        FlipperCoordinates coords = FlipperCoordinates.get();
        Thread thread = new Thread(() -> {
            MacroDefinition macro = new MacroDefinition("DebugRootScreen");
            macro.steps.add(MacroStep.command(coords.claimOrdersCommand));
            macro.steps.add(MacroStep.waitForScreen("Bazaar", 10_000));
            macro.steps.add(MacroStep.waitMs(400));
            boolean ok = MacroExecutor.runBlocking(macro, new ExecutionContext(), () -> false);
            if (!ok) {
                ClientUtils.sendMessage("§cCouldn't open the Bazaar root screen - see the error above.");
                return;
            }
            ClientUtils.debugDumpOpenScreen();
            ClientUtils.closeScreen();
        }, "bazaarmacro-debug-root");
        thread.setDaemon(true);
        thread.start();
    }

    private static void listHistory() {
        var sessions = SessionHistoryStorage.load();
        if (sessions.isEmpty()) {
            ClientUtils.sendMessage("§7No completed flip sessions yet.");
            return;
        }
        long total = 0;
        ClientUtils.sendMessage("§7Recent flip sessions:");
        int start = Math.max(0, sessions.size() - 10);
        for (int i = start; i < sessions.size(); i++) {
            var session = sessions.get(i);
            total += session.profit();
            long minutes = Math.max(0, (session.endTimeMs() - session.startTimeMs()) / 60_000);
            String color = session.profit() >= 0 ? "§a" : "§c";
            ClientUtils.sendMessage("§7 - §f" + session.itemTag() + " §7(" + minutes + "m): " + color
                    + (session.profit() >= 0 ? "+" : "") + ExecutionContext.formatDisplay(session.profit()));
        }
        long allTimeTotal = SessionHistoryStorage.totalProfit();
        String color = allTimeTotal >= 0 ? "§a" : "§c";
        ClientUtils.sendMessage("§7All-time total (" + sessions.size() + " sessions): " + color
                + (allTimeTotal >= 0 ? "+" : "") + ExecutionContext.formatDisplay(allTimeTotal));
    }

    private static void listMacros() {
        var macros = MacroRegistry.all();
        if (macros.isEmpty()) {
            ClientUtils.sendMessage("§7No macros saved yet. Run §f/bfm macros§7 to open the editor.");
            return;
        }
        ClientUtils.sendMessage("§7Saved macros:");
        for (MacroDefinition macro : macros) {
            ClientUtils.sendMessage("§7 - §f" + macro.name + " §7(" + macro.steps.size() + " steps)");
        }
    }

    private static void runMacro(String name) {
        MacroRegistry.find(name).ifPresentOrElse(
                MacroExecutor::run,
                () -> ClientUtils.sendMessage("§cNo macro named \"" + name + "\"")
        );
    }

    /** With no name, appends to a fresh "Recording HH:mm:ss" macro; with a name, appends to that macro (creating it if new). */
    private static void startRecording(String name) {
        String macroName = (name == null || name.isBlank())
                ? "Recording " + LocalTime.now().withNano(0)
                : name;
        var existing = MacroRegistry.find(macroName);
        MacroDefinition target = existing.map(MacroDefinition::copy).orElseGet(() -> new MacroDefinition(macroName));
        String originalName = existing.map(m -> m.name).orElse(null);
        MacroRecorder.start(target, originalName, null);
    }

    private static void stopWhateverIsActive() {
        if (FlipperEngine.isActive()) {
            FlipperEngine.stop();
            return;
        }
        if (FlipTestScript.isActive()) {
            FlipTestScript.stop();
            return;
        }
        MacroExecutor.stop();
        ClientUtils.sendMessage("§cStopping the running macro...");
    }

    /** {@code item} must be the exact Hypixel product tag (e.g. COBBLESTONE, ENCHANTED_WHEAT), not a display name. */
    private static void checkTop(String item, String sideText, double price) {
        CompletableFuture.runAsync(() -> {
            try {
                FlipperSide side = FlipperSide.parse(sideText);
                String tag = item.toUpperCase();
                FlipperManager.TopCheckResult result = FlipperManager.checkTop(tag, side, price);
                ClientUtils.sendMessage(formatCheckResult(tag, result));
            } catch (Exception e) {
                ClientUtils.sendMessage("§cFlip check failed: " + e.getMessage());
            }
        });
    }

    private static String formatCheckResult(String tag, FlipperManager.TopCheckResult result) {
        String topStr = ExecutionContext.formatDisplay(result.topPrice());
        String priceStr = ExecutionContext.formatDisplay(result.myPrice());
        return switch (result.status()) {
            case AHEAD -> "§a" + tag + ": " + priceStr + " IS the top order (current top " + topStr + ").";
            case TIED -> "§e" + tag + ": " + priceStr + " ties the top price (" + topStr + ") but may not be first in queue at it.";
            case BEHIND -> "§c" + tag + ": " + priceStr + " is NOT top - current top is " + topStr + ".";
        };
    }

    private static CompletableFuture<Suggestions> suggestAlertSides(SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase();
        for (String option : new String[]{"buy-order", "sell-offer"}) {
            if (option.startsWith(remaining)) {
                builder.suggest(option);
            }
        }
        return builder.buildFuture();
    }

    /** Same resolution rule as {@code FlipperEngine.resolveItem}: an exact tag is used as-is, anything else goes through Coflnet's search. */
    private static BazaarPriceClient.ItemMatch resolveAlertItem(String input) throws Exception {
        String trimmed = input.trim();
        if (trimmed.equals(trimmed.toUpperCase()) && trimmed.matches("[A-Z0-9_:]+")) {
            return new BazaarPriceClient.ItemMatch(trimmed, trimmed);
        }
        return BazaarPriceClient.searchItem(trimmed);
    }

    /**
     * {@code expression} is resolved once, now, against the live order book - it may reference
     * {@code order} (this side's own order price) and {@code insta} (the opposing instant-trade
     * price) alongside plain {@link MacroExpression} arithmetic, e.g. {@code order - 5000000} or
     * {@code order * 1.1}. The resulting number becomes a fixed target, not re-evaluated later.
     */
    private static void addAlert(String item, String sideText, String expression) {
        CompletableFuture.runAsync(() -> {
            try {
                FlipperSide side = FlipperSide.parse(sideText);
                BazaarPriceClient.ItemMatch match = resolveAlertItem(item);
                HypixelBazaarClient.OrderBook book = HypixelBazaarClient.getOrderBook(match.tag());
                double orderPrice = side == FlipperSide.BUY_ORDER ? book.topBuyPrice() : book.topSellPrice();
                double instaPrice = side == FlipperSide.BUY_ORDER ? book.topSellPrice() : book.topBuyPrice();

                Map<String, Double> variables = new HashMap<>();
                variables.put("order", orderPrice);
                variables.put("insta", instaPrice);
                double target = MacroExpression.evaluate(expression, variables);

                PriceAlertManager.add(new PriceAlert(match.tag(), match.displayName(), side, target));
                String sideLabel = side == FlipperSide.BUY_ORDER ? "buy order" : "sell offer";
                String direction = side == FlipperSide.BUY_ORDER ? "falls to/below" : "rises to/above";
                ClientUtils.sendMessage("§aAlert added: §f" + match.displayName() + " §a" + sideLabel + " " + direction
                        + " §f" + ExecutionContext.formatDisplay(target) + "§a.");
            } catch (Exception e) {
                ClientUtils.sendMessage("§cCouldn't add alert: " + e.getMessage());
            }
        });
    }

    private static void listAlerts() {
        List<PriceAlert> alerts = PriceAlertManager.list();
        if (alerts.isEmpty()) {
            ClientUtils.sendMessage("§7No active alerts. Add one with §f/bfm alert add <item> <buy-order|sell-offer> <price>§7.");
            return;
        }
        ClientUtils.sendMessage("§7Active alerts:");
        for (int i = 0; i < alerts.size(); i++) {
            PriceAlert alert = alerts.get(i);
            String sideLabel = alert.watchSide() == FlipperSide.BUY_ORDER ? "buy order" : "sell offer";
            ClientUtils.sendMessage("§7 " + (i + 1) + ". §f" + alert.searchTerm() + " §7" + sideLabel + " @ §f"
                    + ExecutionContext.formatDisplay(alert.targetPrice()) + " §7(remove with §f/bfm alert remove " + (i + 1) + "§7)");
        }
    }

    private static void removeAlert(int oneBasedIndex) {
        if (PriceAlertManager.removeAt(oneBasedIndex - 1)) {
            ClientUtils.sendMessage("§7Alert " + oneBasedIndex + " removed.");
        } else {
            ClientUtils.sendMessage("§cNo alert at index " + oneBasedIndex + ".");
        }
    }
}

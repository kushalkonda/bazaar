package dev.bazaarmacro.util;

import dev.bazaarmacro.macro.SlotCoordinate;
import dev.bazaarmacro.mixin.AccessorAbstractContainerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Client interaction helpers shared by the macro executor: chat, commands, and slot clicks. */
public final class ClientUtils {

    private static final Pattern STRIP_FORMATTING = Pattern.compile("(?i)§[0-9A-FK-ORZ]");
    private static final String MESSAGE_PREFIX = "§b§lBazaarMacro >> §7";

    private static final long COMMAND_COOLDOWN_MS = 250;
    private static final Object COMMAND_QUEUE_LOCK = new Object();
    private static long nextCommandTime = 0;
    private static final ScheduledExecutorService COMMAND_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "bazaarmacro-command-dispatch");
        thread.setDaemon(true);
        return thread;
    });

    private ClientUtils() {
    }

    public static String stripColors(String s) {
        return s == null ? "" : STRIP_FORMATTING.matcher(s).replaceAll("");
    }

    public static void sendMessage(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal(MESSAGE_PREFIX + message));
            }
        });
    }

    /**
     * Sends a chat message or client command, rate-limited to one per
     * {@link #COMMAND_COOLDOWN_MS} to avoid tripping server-side spam limits.
     */
    public static void sendCommand(String cmd) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || cmd == null || cmd.isBlank()) return;

        long delayMs;
        synchronized (COMMAND_QUEUE_LOCK) {
            long now = System.currentTimeMillis();
            long scheduledTime = Math.max(now, nextCommandTime);
            nextCommandTime = scheduledTime + COMMAND_COOLDOWN_MS;
            delayMs = Math.max(0L, scheduledTime - now);
        }

        COMMAND_EXECUTOR.schedule(() -> client.execute(() -> {
            if (client.player == null || client.getConnection() == null) return;
            if (cmd.startsWith("/")) {
                client.getConnection().sendCommand(cmd.substring(1));
            } else {
                client.getConnection().sendChat(cmd);
            }
        }), delayMs, TimeUnit.MILLISECONDS);
    }

    public static boolean isInventoryScreenOpen() {
        Minecraft client = Minecraft.getInstance();
        return client != null && client.screen instanceof AbstractContainerScreen<?>;
    }

    public static AbstractContainerScreen<?> getOpenContainerScreen() {
        Minecraft client = Minecraft.getInstance();
        return client != null && client.screen instanceof AbstractContainerScreen<?> screen ? screen : null;
    }

    /**
     * Simulates clicking a slot in an open container screen by routing through the
     * screen's own {@code slotClicked} handler, matching real client behaviour.
     */
    public static void performSlotClick(AbstractContainerScreen<?> screen, int slotIndex, int mouseButton, ContainerInput type) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || screen.getMenu() == null) return;
        List<Slot> slots = screen.getMenu().slots;
        if (slotIndex < 0 || slotIndex >= slots.size()) return;
        Slot slot = slots.get(slotIndex);
        ((AccessorAbstractContainerScreen) screen).invokeSlotClicked(slot, slot.index, mouseButton, type);
    }

    /**
     * Experimental alternative to {@link #performSlotClick} (2026-08-01): rather than invoking
     * the screen's {@code slotClicked} handler directly (bypassing all normal mouse-input
     * plumbing), this computes the slot's real on-screen pixel position and routes through the
     * screen's actual {@code mouseClicked}/{@code mouseReleased} methods, the same path a genuine
     * click takes. Built specifically because a real, reproduced failure showed a
     * {@link #performSlotClick} click having zero effect on one particular screen (Hypixel's Buy
     * Order price-preset screen, reached via a sign) while working everywhere else this project
     * uses it - if some extra client-side bookkeeping that only the full mouse-input path
     * triggers turns out to matter there, this is the fix; if not, at least it's now ruled out.
     */
    public static void performRealMouseClick(AbstractContainerScreen<?> screen, int slotIndex, int mouseButton) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || screen.getMenu() == null) return;
        List<Slot> slots = screen.getMenu().slots;
        if (slotIndex < 0 || slotIndex >= slots.size()) return;
        Slot slot = slots.get(slotIndex);

        AccessorAbstractContainerScreen accessor = (AccessorAbstractContainerScreen) screen;
        double mouseX = accessor.getLeftPos() + slot.x + 8;
        double mouseY = accessor.getTopPos() + slot.y + 8;

        net.minecraft.client.input.MouseButtonEvent event = new net.minecraft.client.input.MouseButtonEvent(
                mouseX, mouseY, new net.minecraft.client.input.MouseButtonInfo(mouseButton, 0));
        screen.mouseClicked(event, false);
        screen.mouseReleased(event);
    }

    /** Fallback address if a real server was never captured (e.g. the flipper started before {@link #rememberCurrentServerIfConnected} ever ran) - confirmed via a real client log line ("Connecting to hypixel.net, 25565"), not guessed. This mod is Hypixel-specific already (the whole Bazaar flow only exists there), so hardcoding it here is reasonable. */
    private static final String FALLBACK_SERVER_IP = "hypixel.net";

    private static volatile ServerData lastKnownServer;

    /**
     * Whether the client is currently in a world at all - not connected to any server (including
     * mid-reconnect, on the title/disconnected screen) if false. Cheaper and more direct than
     * checking for a specific screen type, and matches how the game itself tracks "am I playing".
     */
    public static boolean isConnectedToServer() {
        Minecraft client = Minecraft.getInstance();
        return client != null && client.level != null;
    }

    /**
     * Records the server currently connected to, if any - call this periodically while known to
     * be connected. {@link Minecraft#getCurrentServer()} isn't reliably still populated once
     * already disconnected, so this needs to be captured proactively beforehand, not read fresh
     * at the moment a reconnect is actually needed.
     */
    public static void rememberCurrentServerIfConnected() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        ServerData current = client.getCurrentServer();
        if (current != null) {
            lastKnownServer = current;
        }
    }

    /**
     * Reconnects to the last server remembered via {@link #rememberCurrentServerIfConnected}
     * (falling back to a hardcoded Hypixel address if none was ever captured), dispatched on the
     * client thread. Uses the exact same call vanilla Minecraft's own "Join Server" flow makes -
     * {@code ConnectScreen.startConnecting(Screen, Minecraft, ServerAddress, ServerData, boolean,
     * TransferState)} - confirmed by disassembling {@code JoinMultiplayerScreen.join(ServerData)}
     * in the real client jar rather than guessing at this version's exact API surface.
     */
    public static void reconnectToLastServer() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;

        ServerData server = lastKnownServer;
        if (server == null) {
            server = new ServerData("Hypixel", FALLBACK_SERVER_IP, ServerData.Type.OTHER);
        }
        ServerData toConnect = server;

        client.execute(() -> {
            try {
                ServerAddress address = ServerAddress.parseString(toConnect.ip);
                ConnectScreen.startConnecting(client.screen, client, address, toConnect, false, null);
            } catch (Exception e) {
                System.err.println("[BazaarMacro] Failed to start reconnecting: " + e.getMessage());
            }
        });
    }

    public static void closeScreen() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        client.execute(() -> {
            if (client.player != null && client.screen != null) {
                client.player.closeContainer();
            }
        });
    }

    /** Parses the scoreboard sidebar's "Purse:" line. Returns -1 if not found. */
    public static long getPurse() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || client.player == null) return -1;

        Scoreboard scoreboard = client.level.getScoreboard();
        if (scoreboard == null) return -1;

        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) return -1;

        Collection<PlayerScoreEntry> scores = scoreboard.listPlayerScores(sidebar);
        for (PlayerScoreEntry entry : scores) {
            String entryName = entry.owner();
            PlayerTeam team = scoreboard.getPlayersTeam(entryName);
            String fullText = entryName;
            if (team != null) {
                fullText = team.getPlayerPrefix().getString() + entryName + team.getPlayerSuffix().getString();
            }
            String line = stripColors(fullText).replace(",", "").trim();
            if (line.contains("Purse:")) {
                try {
                    String valuePart = line.split("Purse:")[1].trim();
                    String mainBalance = valuePart.split(" ")[0].replaceAll("[^0-9]", "");
                    return Long.parseLong(mainBalance);
                } catch (Exception ignored) {
                }
            }
        }
        return -1;
    }

    /**
     * Counts how many of a SkyBlock item (matched by its {@code ExtraAttributes.id}, e.g.
     * "ENCHANTED_MYCELIUM" - not a vanilla item type) are sitting in the player's 36 main
     * inventory slots right now. Ground truth for "did an order actually fill", since Hypixel's
     * own UI doesn't otherwise expose that - only the inventory itself can't be faked by a
     * mis-timed click.
     */
    public static long countItem(String skyblockId) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || skyblockId == null) return 0;

        long total = 0;
        Inventory inventory = client.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && skyblockId.equals(resolveSkyblockId(stack))) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * Real remaining room for more of {@code skyblockId} across the 36 main inventory slots - an
     * empty slot contributes a full stack, a slot already holding the same item contributes
     * whatever's left in that stack, and anything else (tools, armor, unrelated items) contributes
     * nothing. Ground truth for "how much more of this item could actually fit right now" - unlike
     * assuming the whole 36-slot inventory is empty, which silently breaks the moment the player is
     * carrying anything else (a real, near-universal case, not an edge case).
     */
    public static long freeInventoryCapacity(String skyblockId) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || skyblockId == null) return 0;

        long free = 0;
        Inventory inventory = client.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                free += 64; // every current Bazaar-tradeable item stacks to 64
            } else if (skyblockId.equals(resolveSkyblockId(stack))) {
                free += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            }
        }
        return free;
    }

    /**
     * Returns the colour-stripped lore lines of a slot in the currently open container screen,
     * or an empty list if no screen is open, the slot is out of range, or the item has no lore.
     * Used to read Hypixel's own stated limits (e.g. a "Buy up to X" line) directly off the item
     * rather than trusting our own math for something the game already tells us.
     */
    public static List<String> getOpenSlotLore(int slotIndex) {
        AbstractContainerScreen<?> screen = getOpenContainerScreen();
        if (screen == null || screen.getMenu() == null) return List.of();
        List<Slot> slots = screen.getMenu().slots;
        if (slotIndex < 0 || slotIndex >= slots.size()) return List.of();

        ItemStack stack = slots.get(slotIndex).getItem();
        if (stack.isEmpty()) return List.of();
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return List.of();

        List<String> lines = new java.util.ArrayList<>();
        for (Component line : lore.lines()) {
            lines.add(stripColors(line.getString()));
        }
        return lines;
    }

    /**
     * Whether a slot in the currently open container screen has an item in it at all. Used to
     * check whether an order still shows up in the manage-orders (F6) menu without caring what's
     * actually in it - a direct, unambiguous "is this order still open" signal that doesn't depend
     * on inferring anything from a shared counter (purse, inventory count) that other concurrent
     * activity can also move. Returns false (not true) if no screen is open or the slot is out of
     * range, since "can't tell" and "definitely not populated" both mean the caller shouldn't try
     * to click it.
     */
    public static boolean isSlotPopulated(int slotIndex) {
        AbstractContainerScreen<?> screen = getOpenContainerScreen();
        if (screen == null || screen.getMenu() == null) return false;
        List<Slot> slots = screen.getMenu().slots;
        if (slotIndex < 0 || slotIndex >= slots.size()) return false;
        return !slots.get(slotIndex).getItem().isEmpty();
    }

    /**
     * Diagnostic only: dumps every non-empty main-inventory slot's vanilla item id, resolved
     * SkyBlock id (whatever {@link #resolveSkyblockId} comes up with, including {@code null}),
     * and raw NBT to chat and the console log. For figuring out why {@link #countItem} isn't
     * matching a real item that's genuinely sitting in the inventory - reads the user's actual
     * data instead of guessing at NBT structure again.
     */
    public static void debugDumpInventory() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return;
        String text = captureInventoryDump();

        sendMessage("§7--- Inventory dump (also printed to the game's console/log) ---");
        System.out.println("[BazaarMacro] Inventory dump:");
        for (String line : text.split("\n")) {
            sendMessage("§7" + line);
            System.out.println("[BazaarMacro]   " + line);
        }
        sendMessage("§7--- end dump ---");
    }

    /** Same scan {@link #debugDumpInventory} does, as plain text (one line per slot, plus raw NBT) - used by {@link ErrorReporter} to embed a full inventory snapshot in a crash report without spamming chat/console. */
    public static String captureInventoryDump() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return "(no player)";

        Inventory inventory = client.player.getInventory();
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            any = true;

            String vanillaId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String skyblockId = resolveSkyblockId(stack);
            String displayName = stripColors(stack.getHoverName().getString());
            CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
            String rawNbt = customData != null ? customData.copyTag().toString() : "(no CUSTOM_DATA component)";

            sb.append("slot ").append(i).append(": x").append(stack.getCount()).append(' ').append(vanillaId)
                    .append(" | skyblockId=").append(skyblockId).append(" | displayName=\"").append(displayName).append("\"\n");
            sb.append("  nbt=").append(rawNbt).append('\n');
        }
        if (!any) {
            sb.append("(inventory is empty)\n");
        }
        return sb.toString();
    }

    /**
     * Diagnostic only: dumps every non-empty slot of whatever container screen is currently open
     * (spreadsheet-style coordinate, item name, lore) to chat and the console log. Built to answer
     * one specific open question: how Hypixel's "manage orders" (F6) menu lays out two
     * simultaneously-active orders on the same item, since {@link dev.bazaarmacro.flipper.FlipperEngine}
     * currently assumes only one is ever open at a time (its claim slot is hardcoded to the first
     * position) - reads the real menu instead of guessing at a second order's position.
     */
    public static void debugDumpOpenScreen() {
        if (getOpenContainerScreen() == null) {
            sendMessage("§cNo container screen is open right now.");
            return;
        }
        String text = captureOpenScreenDump();

        sendMessage("§7--- Open screen dump (also printed to the game's console/log) ---");
        System.out.println("[BazaarMacro] Open screen dump:");
        for (String line : text.split("\n")) {
            sendMessage("§7" + line);
            System.out.println("[BazaarMacro]   " + line);
        }
        sendMessage("§7--- end dump ---");
    }

    /** Same scan {@link #debugDumpOpenScreen} does, as plain text (slot, coordinate, name, lore) - used by {@link ErrorReporter} to embed the open screen's real contents in a crash report. "(no screen open)" if nothing's open right now. */
    public static String captureOpenScreenDump() {
        AbstractContainerScreen<?> screen = getOpenContainerScreen();
        if (screen == null || screen.getMenu() == null) return "(no screen open)";

        List<Slot> slots = screen.getMenu().slots;
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (int i = 0; i < slots.size(); i++) {
            ItemStack stack = slots.get(i).getItem();
            if (stack.isEmpty()) continue;
            any = true;

            String coordinate = SlotCoordinate.toCoordinate(i, SlotCoordinate.DEFAULT_WIDTH);
            String name = stripColors(stack.getHoverName().getString());
            sb.append("slot ").append(i).append(" (").append(coordinate).append("): ").append(name).append('\n');
            for (String line : getOpenSlotLore(i)) {
                sb.append("  ").append(line).append('\n');
            }
        }
        if (!any) {
            sb.append("(screen has no items)\n");
        }
        return sb.toString();
    }

    /**
     * {@code id} sits at the top level of the custom-data tag in this game's actual item format -
     * confirmed via {@code /bfm debug inventory} against real items (e.g. {@code {id:"ENCHANTED_WHEAT"}}
     * on a stack of enchanted wheat, and the same top-level shape on every other item checked, from
     * tools to menu items). No "ExtraAttributes" wrapper exists to nest it under - that was a wrong
     * assumption carried over from an older reference project's NBT convention, and it silently
     * broke every single item match (never found the id, always returned null) since nothing was
     * ever nested where the old code looked for it.
     */
    private static String resolveSkyblockId(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return null;
        CompoundTag tag = customData.copyTag();
        String id = tag.getString("id").orElse("");
        return id.isEmpty() ? null : id;
    }

    /**
     * Reads the real level of {@code enchantId} directly off an unapplied enchanted book's own
     * flat NBT {@code enchantments} compound (e.g. {@code {enchantments:{scuba:1},id:"ENCHANTED_BOOK",...}}),
     * confirmed against a real crafted item via {@code /bfm debug inventory}-style dump - unapplied
     * SkyBlock enchant books all share the generic display name "Enchanted Book" regardless of
     * which enchant/level they are, so display-name matching can never distinguish them; this reads
     * the one field that actually does. Returns -1 if the stack isn't this enchant at all.
     */
    private static int resolveEnchantLevel(ItemStack stack, String enchantId) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return -1;
        CompoundTag tag = customData.copyTag();
        return tag.getCompoundOrEmpty("enchantments").getIntOr(enchantId, -1);
    }

    /** Sums how many of {@code enchantId} at exactly {@code level} are held across the player's own 36 inventory slots. */
    public static long countEnchantBooksAtLevel(String enchantId, int level) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return 0;

        long total = 0;
        Inventory inventory = client.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            if (resolveEnchantLevel(stack, enchantId) == level) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** An enchant book's real (enchant id, level) identity, read directly from its own NBT - see {@link #resolveEnchantLevel}. */
    public record EnchantBookInfo(String enchantId, int level) {
    }

    /**
     * Reads whichever single enchant/level is on this stack's own NBT {@code enchantments}
     * compound, without needing to already know which one to look for - companion to
     * {@link #resolveEnchantLevel} (which requires a known {@code enchantId} up front). Needed
     * when the identity of a book isn't yet known and must be discovered from the real item
     * itself rather than guessed from a Bazaar tag name (see the Book Flipper plan: the mapping
     * from a Bazaar tag's name segment to the NBT enchant-id key has only ever been independently
     * confirmed for Scuba - {@code "scuba"} - not assumed to generalize to every other enchant).
     * Returns {@code null} if the stack isn't an enchant book at all.
     */
    private static EnchantBookInfo resolveAnyEnchantBookInfo(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return null;
        CompoundTag tag = customData.copyTag();
        CompoundTag enchantments = tag.getCompoundOrEmpty("enchantments");
        if (enchantments.isEmpty()) return null;
        for (String key : enchantments.keySet()) {
            int level = enchantments.getIntOr(key, -1);
            if (level > 0) return new EnchantBookInfo(key, level);
        }
        return null;
    }

    /**
     * Every distinct enchant book currently held in the player's own 36 inventory slots, mapped
     * to how many of each - meant to be snapshotted before and after an action (e.g. claiming a
     * buy-order fill) so the caller can diff the two maps and see exactly which book newly
     * appeared, rather than assuming which one it must have been.
     */
    public static Map<EnchantBookInfo, Long> countAllEnchantBooks() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return Map.of();

        Map<EnchantBookInfo, Long> counts = new HashMap<>();
        Inventory inventory = client.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            EnchantBookInfo info = resolveAnyEnchantBookInfo(stack);
            if (info != null) {
                counts.merge(info, (long) stack.getCount(), Long::sum);
            }
        }
        return counts;
    }

    /**
     * Scans {@code [scanStart, scanEnd]} of the currently open container screen's slots for an
     * enchanted book at exactly {@code enchantId}/{@code level} and returns its slot index, or -1
     * if none is open or none matches. Companion to {@link #countEnchantBooksAtLevel} - same
     * ground-truth NBT read, but for locating a slot to click rather than counting.
     */
    public static int findEnchantBookSlotInOpenScreen(String enchantId, int level, int scanStart, int scanEnd) {
        AbstractContainerScreen<?> screen = getOpenContainerScreen();
        if (screen == null) return -1;

        List<Slot> slots = screen.getMenu().slots;
        int end = Math.min(scanEnd, slots.size() - 1);
        for (int i = Math.max(0, scanStart); i <= end; i++) {
            Slot slot = slots.get(i);
            if (!slot.hasItem()) continue;
            if (resolveEnchantLevel(slot.getItem(), enchantId) == level) {
                return i;
            }
        }
        return -1;
    }
}

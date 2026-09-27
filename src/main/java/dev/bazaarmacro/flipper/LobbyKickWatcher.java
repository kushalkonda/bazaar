package dev.bazaarmacro.flipper;

import dev.bazaarmacro.util.ClientUtils;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/**
 * Listens for Hypixel's real "you were put in the SkyBlock lobby" kick - {@code "A kick occurred
 * in your connection, so you were put in the SkyBlock lobby!"} - a *different* failure mode from a
 * real Minecraft disconnect (see {@link dev.bazaarmacro.util.ClientUtils#isConnectedToServer}):
 * the client never actually leaves the server connection, it's just moved to Hypixel's own lobby
 * world, so {@code client.level} stays non-null the whole time and the disconnect-detection path
 * in {@link FlipperEngine} never fires. Real gameplay showed exactly this happen (following a real
 * "Sending packets too fast!" rate-limit kick) and cascade into every macro failing with screen
 * timeouts and an unreadable purse, ending in the flipper just giving up - this message is the
 * direct, unambiguous signal needed to catch that specific case and warp back instead.
 */
public final class LobbyKickWatcher {
    private static final String KICK_TEXT = "you were put in the SkyBlock lobby";

    private static volatile boolean kicked = false;

    private LobbyKickWatcher() {
    }

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String text = ClientUtils.stripColors(message.getString());
            if (text.contains(KICK_TEXT)) {
                kicked = true;
            }
        });
    }

    /** Clears and returns whether a lobby-kick was seen since the last call. */
    public static boolean drainKicked() {
        boolean was = kicked;
        kicked = false;
        return was;
    }
}

package dev.bazaarmacro.flipper;

import dev.bazaarmacro.util.ClientUtils;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/**
 * Listens for Hypixel's real rejection of a cancel attempt - {@code "[Bazaar] You have goods to
 * claim on this order!"} - which fires instead of an actual cancellation when the order still has
 * an unclaimed fill sitting on it (most plausibly a partial fill landing in the gap between this
 * engine's own claim pass and its cancel click, or a claim pass that didn't fully drain
 * everything). Real gameplay showed the click sequence itself complete normally (every click
 * lands, {@link dev.bazaarmacro.macro.MacroExecutor#runBlocking} reports success) while Hypixel
 * silently refused the actual cancel behind it - this message is the only direct way to know that
 * happened, so {@link FlipperEngine#claimThenCancel} checks it and reacts by claiming again and
 * retrying the cancel, instead of either wrongly declaring success or just giving up.
 */
public final class CancelRejectionWatcher {
    private static final String REJECTION_TEXT = "You have goods to claim on this order!";

    private static volatile boolean rejected = false;

    private CancelRejectionWatcher() {
    }

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String text = ClientUtils.stripColors(message.getString());
            if (text.contains(REJECTION_TEXT)) {
                rejected = true;
            }
        });
    }

    /** Clears and returns whether a rejection was seen since the last call. */
    public static boolean drainRejected() {
        boolean was = rejected;
        rejected = false;
        return was;
    }
}

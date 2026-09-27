package dev.bazaarmacro.util;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

/**
 * Durable, self-contained crash/anomaly reports for every autonomous engine in this mod
 * ({@code FlipperEngine}, {@code BuyOrderEngine}, {@code SellOrderEngine},
 * {@code LegionCraftScript}) - built so a real failure leaves behind
 * more than a single chat line and whatever happens to still be in {@code latest.log} by the time
 * anyone looks. Every report is a plain-text file under
 * {@code config/bazaarmacro/crash-logs/<source>_<timestamp>.log} containing the failure reason,
 * the full stack trace (if any), the last {@link #CHAT_HISTORY_LINES} real chat lines seen before
 * the failure, and a full snapshot of the inventory and whatever screen was open at the moment -
 * everything needed to diagnose what actually happened without asking for a live reproduction.
 *
 * <p>The chat ring buffer is maintained continuously (registered once, like every other watcher in
 * this mod - see {@code BazaarMacroClient}), not just started when a failure occurs, since the
 * lines *leading up to* a failure (an outbid message, a rejected cancel, a rate-limit kick) are
 * often more diagnostic than the failure line itself.
 */
public final class ErrorReporter {
    private static final int CHAT_HISTORY_LINES = 200;
    /** Oldest reports beyond this count are deleted on each new report, so a persistent recurring failure can't quietly fill the disk. */
    private static final int MAX_RETAINED_REPORTS = 50;
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final Path CRASH_LOG_DIR = ConfigPaths.crashLogDir();

    private static final Deque<String> recentChat = new ArrayDeque<>();

    private ErrorReporter() {
    }

    public static void register() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String text = ClientUtils.stripColors(message.getString());
            synchronized (recentChat) {
                recentChat.addLast(LocalDateTime.now() + " " + text);
                while (recentChat.size() > CHAT_HISTORY_LINES) {
                    recentChat.removeFirst();
                }
            }
        });
    }

    /** For a real, thrown exception - see {@link #report(String, String, Throwable)}. */
    public static Path report(String source, String reason, Throwable error) {
        return report(source, reason, error, null);
    }

    /** For an operational failure with no exception object (e.g. "gave up after N attempts", "order placement returned 0"). */
    public static Path report(String source, String reason) {
        return report(source, reason, null, null);
    }

    /**
     * Writes a full crash report and returns its path (or {@code null} if writing failed - this
     * never throws, since a broken error-reporting path must never itself take down the caller).
     *
     * @param extraContext optional free-form extra state (e.g. current price/qty fields) the
     *                      caller wants captured alongside the standard sections - may be null.
     */
    public static Path report(String source, String reason, Throwable error, String extraContext) {
        try {
            Files.createDirectories(CRASH_LOG_DIR);

            StringBuilder sb = new StringBuilder();
            sb.append("=== BazaarMacro crash report ===\n");
            sb.append("source: ").append(source).append('\n');
            sb.append("time: ").append(LocalDateTime.now()).append('\n');
            sb.append("reason: ").append(reason).append('\n');
            sb.append('\n');

            if (error != null) {
                sb.append("--- exception ---\n");
                StringWriter sw = new StringWriter();
                error.printStackTrace(new PrintWriter(sw));
                sb.append(sw).append('\n');
            }

            if (extraContext != null && !extraContext.isBlank()) {
                sb.append("--- extra context ---\n").append(extraContext).append('\n').append('\n');
            }

            sb.append("--- last ").append(CHAT_HISTORY_LINES).append(" chat lines ---\n");
            synchronized (recentChat) {
                for (String line : recentChat) {
                    sb.append(line).append('\n');
                }
            }
            sb.append('\n');

            sb.append("--- inventory at time of failure ---\n");
            sb.append(ClientUtils.captureInventoryDump()).append('\n');

            sb.append("--- open screen at time of failure ---\n");
            sb.append(ClientUtils.captureOpenScreenDump()).append('\n');

            String filename = source.replaceAll("[^a-zA-Z0-9_-]", "_") + "_" + FILE_TIMESTAMP.format(LocalDateTime.now()) + ".log";
            Path file = CRASH_LOG_DIR.resolve(filename);
            Files.writeString(file, sb.toString());

            pruneOldReports();

            String absolutePath = file.toAbsolutePath().toString();
            System.out.println("[BazaarMacro] Crash report written: " + absolutePath);
            ClientUtils.sendMessage("§cCrash report written: §f" + absolutePath);
            return file;
        } catch (Exception e) {
            System.err.println("[BazaarMacro] Failed to write crash report: " + e.getMessage());
            return null;
        }
    }

    private static void pruneOldReports() {
        try (Stream<Path> files = Files.list(CRASH_LOG_DIR)) {
            List<Path> sorted = files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong(ErrorReporter::lastModifiedOrZero).reversed())
                    .toList();
            for (int i = MAX_RETAINED_REPORTS; i < sorted.size(); i++) {
                try {
                    Files.deleteIfExists(sorted.get(i));
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static long lastModifiedOrZero(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}

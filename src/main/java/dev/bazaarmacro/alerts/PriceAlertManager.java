package dev.bazaarmacro.alerts;

import dev.bazaarmacro.bazaar.HypixelBazaarClient;
import dev.bazaarmacro.flipper.FlipperSide;
import dev.bazaarmacro.macro.ExecutionContext;
import dev.bazaarmacro.util.ClientUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Background poller for price alerts - deliberately independent of {@link
 * dev.bazaarmacro.flipper.FlipperEngine}, so alerts keep working whether or not the flipper is
 * running. Polls every 30s to match {@link HypixelBazaarClient}'s own cache TTL - checking more
 * often wouldn't see fresher data anyway.
 */
public final class PriceAlertManager {
    private static final long POLL_INTERVAL_MS = 30_000L;
    private static final List<PriceAlert> alerts = new CopyOnWriteArrayList<>(PriceAlertStorage.load());
    private static Thread thread;

    private PriceAlertManager() {
    }

    public static synchronized void ensureRunning() {
        if (thread != null && thread.isAlive()) return;
        thread = new Thread(PriceAlertManager::runLoop, "bazaarmacro-alert-poller");
        thread.setDaemon(true);
        thread.start();
    }

    public static void add(PriceAlert alert) {
        alerts.add(alert);
        PriceAlertStorage.save(new ArrayList<>(alerts));
        ensureRunning();
    }

    public static List<PriceAlert> list() {
        return new ArrayList<>(alerts);
    }

    public static boolean removeAt(int index) {
        if (index < 0 || index >= alerts.size()) return false;
        alerts.remove(index);
        PriceAlertStorage.save(new ArrayList<>(alerts));
        return true;
    }

    private static void runLoop() {
        while (true) {
            try {
                pollOnce();
            } catch (Exception e) {
                System.err.println("[BazaarMacro] Alert poll failed: " + e.getMessage());
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void pollOnce() {
        for (PriceAlert alert : alerts) {
            try {
                HypixelBazaarClient.OrderBook book = HypixelBazaarClient.getOrderBook(alert.itemTag());
                double currentPrice = alert.watchSide() == FlipperSide.BUY_ORDER ? book.topBuyPrice() : book.topSellPrice();
                if (currentPrice > 0 && alert.isCrossed(currentPrice)) {
                    fire(alert, currentPrice);
                    alerts.remove(alert);
                    PriceAlertStorage.save(new ArrayList<>(alerts));
                }
            } catch (Exception e) {
                // Skip this alert this cycle - a transient fetch failure shouldn't drop it.
            }
        }
    }

    private static void fire(PriceAlert alert, double currentPrice) {
        String sideLabel = alert.watchSide() == FlipperSide.BUY_ORDER ? "buy order" : "sell offer";
        ClientUtils.sendMessage("§d[Alert] §f" + alert.searchTerm() + " §d" + sideLabel + " price hit §f"
                + ExecutionContext.formatDisplay(currentPrice) + " §d(target was §f"
                + ExecutionContext.formatDisplay(alert.targetPrice()) + "§d).");
    }
}

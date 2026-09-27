package dev.bazaarmacro.macro;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * Single background worker thread for macro execution. All blocking sleeps/polling used
 * while running a macro happen here, never on the render thread; anything touching
 * Minecraft screen/menu state must still be dispatched via {@code client.execute(...)}.
 */
public final class MacroWorkerThread {
    private static final MacroWorkerThread INSTANCE = new MacroWorkerThread();

    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private volatile boolean cancelled = false;
    private volatile boolean running = false;
    private volatile Thread thread;

    private MacroWorkerThread() {
        start();
    }

    public static MacroWorkerThread getInstance() {
        return INSTANCE;
    }

    private void start() {
        thread = new Thread(() -> {
            while (true) {
                try {
                    Runnable task = queue.take();
                    running = true;
                    try {
                        task.run();
                    } finally {
                        running = false;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "bazaarmacro-worker");
        thread.setDaemon(true);
        thread.start();
    }

    /** Queues work to run on the worker thread; runs are executed in submission order. */
    public void submit(String label, Runnable task) {
        cancelled = false;
        queue.add(task);
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** True while a submitted task is actively executing (queued-but-not-started doesn't count). */
    public boolean isRunning() {
        return running;
    }

    /** Sleeps without throwing, restoring the interrupt flag if interrupted. */
    public static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

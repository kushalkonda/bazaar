package dev.bazaarmacro.renderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Queues NanoVG draw work discovered during GUI render-state extraction and
 * runs it during the actual render pass (see MixinGuiRenderer).
 */
public final class BmRenderQueue {
    private static final List<Runnable> TASKS = new ArrayList<>();

    private BmRenderQueue() {
    }

    public static void enqueue(Runnable task) {
        if (task == null) {
            return;
        }
        synchronized (TASKS) {
            TASKS.add(task);
        }
    }

    public static void flush() {
        while (true) {
            List<Runnable> tasks;
            synchronized (TASKS) {
                if (TASKS.isEmpty()) {
                    return;
                }
                tasks = new ArrayList<>(TASKS);
                TASKS.clear();
            }

            for (Runnable task : tasks) {
                try {
                    task.run();
                } catch (RuntimeException | LinkageError e) {
                    e.printStackTrace();
                }
            }
        }
    }
}

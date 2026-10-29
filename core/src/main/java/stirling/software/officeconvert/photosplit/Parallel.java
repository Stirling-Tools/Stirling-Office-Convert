package stirling.software.officeconvert.photosplit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.function.IntConsumer;

final class Parallel {

    private static final ScopedValue<Thread> OWNER = ScopedValue.newInstance();
    private static final int STRIPS_PER_THREAD = 4;

    interface Strip {
        void run(int from, int to);
    }

    private Parallel() {}

    static boolean ownerInterrupted() {
        return OWNER.isBound() && OWNER.get().isInterrupted();
    }

    static boolean enabled() {
        return Runtime.getRuntime().availableProcessors() > 1;
    }

    static void each(int count, IntConsumer task) {
        if (count <= 1 || !enabled()) {
            for (int i = 0; i < count; i++) {
                PhotoSplitter.checkpoint();
                task.accept(i);
            }
            return;
        }
        Thread owner = OWNER.isBound() ? OWNER.get() : Thread.currentThread();
        List<RecursiveAction> tasks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int index = i;
            tasks.add(new RecursiveAction() {
                @Override
                protected void compute() {
                    ScopedValue.where(OWNER, owner).run(() -> {
                        PhotoSplitter.checkpoint();
                        task.accept(index);
                    });
                }
            });
        }
        ForkJoinTask.invokeAll(tasks);
        PhotoSplitter.checkpoint();
    }

    static void strips(int length, int minimum, Strip task) {
        int threads = Runtime.getRuntime().availableProcessors();
        int count = threads <= 1 ? 1 : Math.clamp(length / Math.max(1, minimum), 1, threads * STRIPS_PER_THREAD);
        if (count <= 1) {
            task.run(0, length);
            return;
        }
        each(count, k -> task.run((int) ((long) length * k / count), (int) ((long) length * (k + 1) / count)));
    }
}

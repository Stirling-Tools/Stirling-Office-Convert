package stirling.software.officeconvert.photosplit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

final class Parallel {

    private static final ScopedValue<Owner> OWNER = ScopedValue.newInstance();
    private static final int STRIPS_PER_THREAD = 4;

    interface Strip {
        void run(int from, int to);
    }

    private record Owner(Thread thread, AtomicBoolean cancelled, Owner parent) {

        boolean stopped() {
            return cancelled.get() || thread.isInterrupted() || parent != null && parent.stopped();
        }
    }

    private Parallel() {}

    static boolean ownerInterrupted() {
        return OWNER.isBound() && OWNER.get().stopped();
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
        PhotoSplitter.checkpoint();
        Owner owner = new Owner(Thread.currentThread(), new AtomicBoolean(), OWNER.isBound() ? OWNER.get() : null);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(count);
        List<RecursiveAction> tasks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int index = i;
            tasks.add(new RecursiveAction() {
                @Override
                protected void compute() {
                    try {
                        if (failure.get() == null) {
                            ScopedValue.where(OWNER, owner).run(() -> {
                                PhotoSplitter.checkpoint();
                                task.accept(index);
                            });
                        }
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        done.countDown();
                    }
                }
            });
        }
        for (int i = count - 1; i > 0; i--) {
            tasks.get(i).fork();
        }
        tasks.get(0).invoke();
        for (int i = 1; i < count; i++) {
            if (tasks.get(i).tryUnfork()) {
                tasks.get(i).invoke();
            }
        }
        boolean interrupted = false;
        while (true) {
            try {
                done.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
                owner.cancelled().set(true);
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        Throwable first = failure.get();
        if (first instanceof RuntimeException e) {
            throw e;
        }
        if (first instanceof Error e) {
            throw e;
        }
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

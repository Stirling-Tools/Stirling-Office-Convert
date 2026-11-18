package stirling.software.officeconvert.app;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

final class Worker<T> {

    private static final int WAITING = 0;
    private static final int RUNNING = 1;
    private static final int CANCELLED = 2;

    private final Callable<T> task;
    private final Runnable release;
    private final AtomicInteger state = new AtomicInteger(WAITING);
    private Thread thread;
    private final CompletableFuture<T> result = new CompletableFuture<>();
    private final CountDownLatch exited = new CountDownLatch(1);

    Worker(Callable<T> task, Runnable release) {
        this.task = task;
        this.release = release;
    }

    void start(Executor executor) {
        executor.execute(this::run);
    }

    private void run() {
        synchronized (this) {
            if (!state.compareAndSet(WAITING, RUNNING)) {
                return;
            }
            thread = Thread.currentThread();
        }
        try {
            result.complete(task.call());
        } catch (Throwable t) {
            result.completeExceptionally(t);
        } finally {
            synchronized (this) {
                thread = null;
                Thread.interrupted();
            }
            try {
                release.run();
            } finally {
                exited.countDown();
            }
        }
    }

    T await(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return result.get(timeout, unit);
    }

    boolean stop(long wait, TimeUnit unit) {
        synchronized (this) {
            if (thread != null) {
                thread.interrupt();
            }
        }
        if (state.compareAndSet(WAITING, CANCELLED)) {
            try {
                release.run();
            } finally {
                exited.countDown();
            }
            return true;
        }
        try {
            return exited.await(wait, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return exited.getCount() == 0;
        }
    }

    boolean exited() {
        return exited.getCount() == 0;
    }
}

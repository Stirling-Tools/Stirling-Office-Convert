package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import stirling.software.officeconvert.memory.Admission;

final class Deadline {

    private static final long STACK_BYTES = 8L << 20;

    private static final long STOP_MILLIS = 1_000;

    private static final Duration GRACE = Duration.ofSeconds(10);

    @FunctionalInterface
    interface Work<T> {
        T call() throws IOException;
    }

    private Deadline() {}

    static <T> T run(Duration timeout, Work<T> work) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Optimization interrupted");
        }
        long limit = nanos(timeout);
        FutureTask<T> task = new FutureTask<>(work::call);
        Thread worker = Thread.ofPlatform().name("pdf-optimize").daemon().stackSize(STACK_BYTES).unstarted(task);
        boolean finished = false;
        try {
            long started = System.nanoTime();
            worker.start();
            T result = limit == 0 ? task.get() : task.get(limit, TimeUnit.NANOSECONDS);
            finished = true;
            if (limit != 0 && System.nanoTime() - started > limit) {
                throw new PdfOptimizer.TimedOut(timeout);
            }
            return result;
        } catch (TimeoutException e) {
            throw new PdfOptimizer.TimedOut(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while optimizing");
        } catch (ExecutionException e) {
            finished = true;
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException r) {
                throw r;
            }
            if (cause instanceof OutOfMemoryError) {
                throw new IOException("The PDF needs more memory to optimize than is available", cause);
            }
            if (cause instanceof StackOverflowError) {
                throw new IOException("The PDF nests its objects too deeply to optimize", cause);
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IOException(cause);
        } finally {
            if (!finished) {
                task.cancel(true);
                stop(worker);
            }
        }
    }

    private static void stop(Thread worker) {
        boolean interrupted = false;
        long end = System.nanoTime() + GRACE.toNanos();
        while (worker.isAlive() && end - System.nanoTime() > 0) {
            worker.interrupt();
            try {
                worker.join(Math.max(1, Math.min(STOP_MILLIS, (end - System.nanoTime()) / 1_000_000)));
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        Admission.abandoned(worker);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static long nanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException e) {
            return 0;
        }
    }
}

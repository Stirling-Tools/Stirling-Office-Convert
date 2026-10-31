package stirling.software.officeconvert.memory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

public final class Deadline {

    private static final long STACK_BYTES = 8L << 20;

    private static final long STOP_MILLIS = 1_000;

    private static final Duration GRACE = Duration.ofSeconds(10);

    @FunctionalInterface
    public interface Work<T> {
        T call() throws IOException;
    }

    private final String threadName;

    private final String interrupted;

    private final String verb;

    private final String gerund;

    private final Function<Duration, ? extends IOException> timedOut;

    public Deadline(String threadName, String interrupted, String verb, String gerund,
            Function<Duration, ? extends IOException> timedOut) {
        this.threadName = threadName;
        this.interrupted = interrupted;
        this.verb = verb;
        this.gerund = gerund;
        this.timedOut = timedOut;
    }

    public <T> T run(Duration timeout, Work<T> work) throws IOException {
        return run(timeout, GRACE, work);
    }

    public <T> T run(Duration timeout, Duration grace, Work<T> work) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException(interrupted);
        }
        long limit = nanos(timeout);
        FutureTask<T> task = new FutureTask<>(work::call);
        Thread worker = Thread.ofPlatform().name(threadName).daemon().stackSize(STACK_BYTES).unstarted(task);
        boolean finished = false;
        try {
            long started = System.nanoTime();
            worker.start();
            T result = limit == 0 ? task.get() : task.get(limit, TimeUnit.NANOSECONDS);
            finished = true;
            if (limit != 0 && System.nanoTime() - started > limit) {
                throw timedOut.apply(timeout);
            }
            return result;
        } catch (TimeoutException e) {
            throw timedOut.apply(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while " + gerund);
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
                throw new IOException("The PDF needs more memory to " + verb + " than is available", cause);
            }
            if (cause instanceof StackOverflowError) {
                throw new IOException("The PDF nests its objects too deeply to " + verb, cause);
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IOException(cause);
        } finally {
            if (!finished) {
                task.cancel(true);
                stop(worker, grace);
            }
        }
    }

    private static void stop(Thread worker, Duration grace) {
        boolean interrupted = false;
        long end = System.nanoTime() + grace.toNanos();
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

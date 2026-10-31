package stirling.software.officeconvert.topdf;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.io.SecureXml;

final class ConversionWorker {

    private ConversionWorker() {}

    private static final long STACK_BYTES = 8L << 20;

    @FunctionalInterface
    interface Work<T> {
        T call() throws IOException;
    }

    static <T> T run(Duration timeout, Work<T> work) throws IOException {
        return run(timeout, System.nanoTime(), new AtomicBoolean(), OfficeToPdf.STOP_MILLIS, work);
    }

    static <T> T run(Duration timeout, long started, AtomicBoolean abandoned, long stopMillis, Work<T> work)
            throws IOException {
        OfficeToPdf.stopIfInterrupted();
        long limit = limitNanos(timeout);
        long wait = limit - (System.nanoTime() - started);
        if (limit > 0 && wait <= 0) {
            throw new OfficeToPdf.TimedOut(timeout);
        }
        FutureTask<T> task = new FutureTask<>(() -> guarded(work));
        Thread worker = Thread.ofPlatform().name("office-to-pdf").daemon().stackSize(STACK_BYTES).unstarted(task);
        boolean finished = false;
        try {
            worker.start();
            T result = limit == 0 ? task.get() : task.get(wait, TimeUnit.NANOSECONDS);
            finished = true;
            return result;
        } catch (TimeoutException e) {
            throw new OfficeToPdf.TimedOut(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while converting");
        } catch (ExecutionException e) {
            finished = true;
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException r) {
                throw r;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IOException(cause);
        } finally {
            if (!finished) {
                abandoned.set(true);
                task.cancel(true);
                join(worker, stopMillis);
            }
        }
    }

    static long limitNanos(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException e) {
            return 0;
        }
    }

    private static <T> T guarded(Work<T> work) throws IOException {
        try {
            return work.call();
        } catch (InterruptedIOException e) {
            if (outOfMemory(e)) {
                throw new IOException(RenderJob.NEEDS_MEMORY, e);
            }
            throw e;
        } catch (IOException e) {
            if (outOfMemory(e)) {
                throw new IOException(RenderJob.NEEDS_MEMORY, e);
            }
            if (scratchExceeded(e)) {
                throw new OfficeToPdf.OutputTooLarge(e);
            }
            throw plain(e);
        } catch (RuntimeException e) {
            Throwable real = selfSuppressed(e);
            if (real != e) {
                return guarded(() -> {
                    throw rethrow(real);
                });
            }
            if (outOfMemory(e)) {
                throw new IOException(RenderJob.NEEDS_MEMORY, e);
            }
            if (scratchExceeded(e)) {
                throw new OfficeToPdf.OutputTooLarge(e);
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Conversion interrupted");
            }
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new IOException("The document could not be converted: " + reason, e);
        } catch (StackOverflowError e) {
            throw new IOException("The document nests too deeply to convert", e);
        } catch (OutOfMemoryError e) {
            throw new IOException(RenderJob.NEEDS_MEMORY, e);
        }
    }

    static Throwable selfSuppressed(Throwable e) {
        Throwable t = e;
        for (int depth = 0; depth < 16 && t instanceof IllegalArgumentException && t.getCause() != null
                && "Self-suppression not permitted".equals(t.getMessage()); depth++) {
            t = t.getCause();
        }
        return t;
    }

    static IOException rethrow(Throwable t) throws IOException {
        if (t instanceof IOException io) {
            throw io;
        }
        if (t instanceof RuntimeException r) {
            throw r;
        }
        if (t instanceof Error err) {
            throw err;
        }
        throw new IOException(t);
    }

    private static boolean outOfMemory(Throwable e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth++ < 16; t = t.getCause()) {
            if (t instanceof RenderJob.OutOfMemory || t instanceof Admission.Stopped) {
                return true;
            }
        }
        return false;
    }

    // PDFBox's own words when the output passes maxScratchBytes
    static boolean scratchExceeded(Throwable e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth++ < 16; t = t.getCause()) {
            if (t instanceof OfficeToPdf.OutputTooLarge || t instanceof IOException && t.getMessage() != null
                    && t.getMessage().startsWith("Maximum allowed scratch file memory exceeded")) {
                return true;
            }
        }
        return false;
    }

    // One plain reason for a refused DOCTYPE, whichever parser (ours or POI's) met it first
    private static IOException plain(IOException e) {
        return SecureXml.refusedDoctype(e) ? new IOException(OfficeToPdf.DOCTYPE, e) : e;
    }

    private static void join(Thread worker, long millis) {
        try {
            worker.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Admission.abandoned(worker);
    }
}

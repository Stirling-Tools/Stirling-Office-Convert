package stirling.software.officeconvert.extract;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;

/** Warnings of PDF-to-Office conversions: logged as before, and collected for the conversion that asked for them. */
public final class Warnings {

    // Inherited so the worker thread a conversion starts reports into the caller's sink
    private static final InheritableThreadLocal<Sink> CURRENT = new InheritableThreadLocal<>();

    private static final int MAX_KEPT = 1000;

    private Warnings() {}

    /** What one conversion reported. Writes after {@link #close} (from a worker that outlived its timeout) are dropped. */
    public static final class Sink implements AutoCloseable {

        private final Sink previous;
        private final List<String> messages = new ArrayList<>();
        private boolean partial;
        private int pages;
        private boolean closed;

        private Sink(Sink previous) {
            this.previous = previous;
        }

        private synchronized void add(String message, boolean leftOut) {
            if (closed) {
                return;
            }
            partial |= leftOut;
            if (messages.size() < MAX_KEPT && !messages.contains(message)) {
                messages.add(message);
            }
        }

        private synchronized void pages(int n) {
            if (!closed) {
                pages = n;
            }
        }

        /** Forgets what an attempt that was stopped and is being run again reported. */
        public synchronized void reset() {
            messages.clear();
            partial = false;
            pages = 0;
        }

        public synchronized List<String> messages() {
            return List.copyOf(messages);
        }

        public synchronized boolean partial() {
            return partial;
        }

        public synchronized int pages() {
            return pages;
        }

        @Override
        public synchronized void close() {
            closed = true;
            if (CURRENT.get() == this) {
                CURRENT.set(previous);
            }
        }
    }

    /** Starts collecting on this thread and the threads it starts until the sink is closed. */
    public static Sink open() {
        Sink sink = new Sink(CURRENT.get());
        CURRENT.set(sink);
        return sink;
    }

    /** A warning that does not mean content is missing from the output. */
    public static void note(Log log, String message) {
        note(log, message, null);
    }

    public static void note(Log log, String message, Throwable cause) {
        report(log, message, cause, false);
    }

    /** Content of the PDF that the output leaves out: the conversion is partial. */
    public static void leftOut(Log log, String message) {
        leftOut(log, message, null);
    }

    public static void leftOut(Log log, String message, Throwable cause) {
        report(log, message, cause, true);
    }

    static void stillLeftOut(String message) {
        Sink sink = CURRENT.get();
        if (sink != null) {
            sink.add(message, true);
        }
    }

    static void pages(int n) {
        Sink sink = CURRENT.get();
        if (sink != null) {
            sink.pages(n);
        }
    }

    private static void report(Log log, String message, Throwable cause, boolean leftOut) {
        if (cause == null) {
            log.warn(message);
        } else {
            log.warn(message, cause);
        }
        Sink sink = CURRENT.get();
        if (sink != null) {
            sink.add(message, leftOut);
        }
    }
}

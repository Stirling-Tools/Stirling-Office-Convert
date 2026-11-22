package stirling.software.officeconvert.topdf.font;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.FileSystemException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

// Font caches live as long as the JVM, so only a failure that the same bytes would repeat may be kept for good
final class Failures {

    static final long RETRY_NANOS = TimeUnit.MINUTES.toNanos(1);

    static final long NEVER = Long.MAX_VALUE;

    static volatile LongSupplier clock = System::nanoTime;

    private static final ThreadLocal<long[]> MISSES = ThreadLocal.withInitial(() -> new long[1]);

    private Failures() {}

    static long now() {
        return clock.getAsLong();
    }

    static long retryAt() {
        return now() + RETRY_NANOS;
    }

    static boolean due(long retryAt) {
        return retryAt != NEVER && now() - retryAt >= 0;
    }

    // A timeout or memory stop that hit the read, which says nothing about the font
    static boolean interrupted(Throwable e) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof InterruptedIOException || c instanceof ClosedChannelException
                    || c instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    // The file system refused the read (locked, missing, out of handles), so the same file may read next time
    static boolean fileSystem(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof Unreadable || c instanceof FileSystemException || c instanceof FileNotFoundException) {
                return true;
            }
        }
        return false;
    }

    // The operating system failed a read of a font file, as against the bytes read not making a font
    static final class Unreadable extends IOException {
        private static final long serialVersionUID = 1L;

        Unreadable(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }

    // Counts lookups that skipped a font which may load later, so their answer is not cached
    static void missed() {
        MISSES.get()[0]++;
    }

    static long misses() {
        return MISSES.get()[0];
    }
}

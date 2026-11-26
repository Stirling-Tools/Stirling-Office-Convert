package stirling.software.officeconvert.layout;

import java.io.InterruptedIOException;

import stirling.software.officeconvert.extract.PdfFiles;

/** Interrupt and memory polls for layout, table and build stages, which do not declare checked exceptions. */
public final class Polls {

    private static final int EVERY = 64;

    private Polls() {}

    public static void check() {
        try {
            PdfFiles.stopIfInterrupted();
        } catch (InterruptedIOException e) {
            throw new Stopped(e);
        }
    }

    /** Polls on every 64th iteration so tight loops stay cheap. */
    public static void check(int iteration) {
        if (iteration % EVERY == 0) {
            check();
        }
    }

    /** Carries a stop out of stages; entry points unwrap it so callers see the original exception. */
    public static final class Stopped extends RuntimeException {

        Stopped(InterruptedIOException cause) {
            super(cause.getMessage(), cause, false, false);
        }

        public InterruptedIOException stop() {
            return (InterruptedIOException) getCause();
        }
    }
}

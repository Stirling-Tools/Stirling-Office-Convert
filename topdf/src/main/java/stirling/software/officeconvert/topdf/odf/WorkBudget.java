package stirling.software.officeconvert.topdf.odf;

import java.io.InterruptedIOException;

import stirling.software.officeconvert.topdf.io.OfficeZip;

final class WorkBudget {

    static final long FLOOR_BYTES = 8L << 20;

    static final int SOURCE_FACTOR = 8;

    static final long MAX_BYTES = 192L << 20;

    static final int HEAP_FACTOR = 6;

    static final String WARNING = "Only part of the document was converted: its repeated rows, columns or nested"
            + " tables would make it too large";

    private final long limit;

    private long bytes;

    private long charges;

    private boolean spent;

    WorkBudget(long sourceBytes) {
        this.limit = limit(sourceBytes);
    }

    static long limit(long sourceBytes) {
        return Math.min(MAX_BYTES, FLOOR_BYTES + SOURCE_FACTOR * Math.max(0, sourceBytes));
    }

    static long heap(long sourceBytes) {
        return HEAP_FACTOR * limit(sourceBytes);
    }

    boolean charge(long n) throws InterruptedIOException {
        if ((charges++ & 1023) == 0) {
            OfficeZip.checkNotInterrupted();
        }
        bytes += Math.max(0, n);
        if (bytes > limit) {
            spent = true;
        }
        return !spent;
    }

    boolean spent() {
        return spent;
    }
}

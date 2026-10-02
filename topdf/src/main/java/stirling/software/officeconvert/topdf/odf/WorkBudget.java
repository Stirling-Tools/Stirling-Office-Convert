package stirling.software.officeconvert.topdf.odf;

import java.io.InterruptedIOException;

import stirling.software.officeconvert.topdf.io.OfficeZip;

final class WorkBudget {

    static final long MAX_CELLS = 250_000;

    static final long MAX_CHARS = 32L << 20;

    static final String WARNING = "Only part of the document was converted: its repeated rows, columns or nested"
            + " tables would make it too large";

    private long cells;

    private long chars;

    private boolean spent;

    boolean cell() throws InterruptedIOException {
        if ((cells & 1023) == 0) {
            OfficeZip.checkNotInterrupted();
        }
        if (spent || ++cells > MAX_CELLS) {
            spent = true;
            return false;
        }
        return true;
    }

    boolean chars(long n) {
        chars += n;
        if (chars > MAX_CHARS) {
            spent = true;
        }
        return !spent;
    }

    boolean spent() {
        return spent;
    }
}

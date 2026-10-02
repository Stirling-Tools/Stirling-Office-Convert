package stirling.software.officeconvert.topdf.odf;

import java.io.InterruptedIOException;

import stirling.software.officeconvert.topdf.io.OfficeZip;

final class WorkBudget {

    static final long MAX_CELLS = 250_000;

    static final long MAX_CHARS = 32L << 20;

    static final long MAX_SHEET_CELLS = 4_000_000;

    static final long MAX_SHEET_ROWS = 1_000_000;

    static final int MAX_MERGES = 100_000;

    static final String WARNING = "Only part of the document was converted: its repeated rows, columns or nested"
            + " tables would make it too large";

    private long cells;

    private long chars;

    private long sheetCells;

    private long sheetRows;

    private int merges;

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

    boolean sheetCells(long n) {
        sheetCells += n;
        if (sheetCells > MAX_SHEET_CELLS) {
            spent = true;
            return false;
        }
        return true;
    }

    boolean sheetRow() throws InterruptedIOException {
        if ((sheetRows & 1023) == 0) {
            OfficeZip.checkNotInterrupted();
        }
        if (++sheetRows > MAX_SHEET_ROWS) {
            spent = true;
            return false;
        }
        return true;
    }

    boolean merge() {
        if (merges >= MAX_MERGES) {
            spent = true;
            return false;
        }
        merges++;
        return true;
    }

    boolean spent() {
        return spent;
    }
}

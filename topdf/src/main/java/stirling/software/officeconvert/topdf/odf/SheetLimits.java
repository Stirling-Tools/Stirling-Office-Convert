package stirling.software.officeconvert.topdf.odf;

final class SheetLimits {

    static final int MAX_ROWS = 300_000;

    static final long MAX_CELLS = 4_000_000;

    static final int MAX_MERGES = 100_000;

    private final String sheet;

    private int rows;

    private long cells;

    private int merges;

    private String cut;

    SheetLimits(String sheet) {
        this.sheet = sheet;
    }

    boolean row() {
        if (rows >= MAX_ROWS) {
            cut("Only the first " + MAX_ROWS + " rows of the sheet \"" + sheet + "\" were converted");
            return false;
        }
        rows++;
        return true;
    }

    boolean cells(long n) {
        if (cells + n > MAX_CELLS) {
            cut("Only the first " + MAX_CELLS + " cells of the sheet \"" + sheet + "\" were converted");
            return false;
        }
        cells += n;
        return true;
    }

    boolean merge() {
        if (merges >= MAX_MERGES) {
            cut("Only the first " + MAX_MERGES + " merged cells of the sheet \"" + sheet + "\" were converted");
            return false;
        }
        merges++;
        return true;
    }

    String cut() {
        return cut;
    }

    private void cut(String warning) {
        if (cut == null) {
            cut = warning;
        }
    }
}

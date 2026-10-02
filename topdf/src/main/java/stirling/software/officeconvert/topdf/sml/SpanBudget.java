package stirling.software.officeconvert.topdf.sml;

final class SpanBudget {

    static final int ROWS = 1_048_576;

    private int left = ROWS;

    int take(int rows) {
        int granted = Math.min(left, Math.max(0, rows));
        left -= granted;
        return granted;
    }
}

package stirling.software.officeconvert.sheet;

final class Continuation {

    private static final float PAGE_EDGE_SHARE = 0.45f;

    private Continuation() {}

    static boolean carriesOn(TableGrid prev, float prevPageHeight, TableGrid next, float pageHeight) {
        if (prev.cols != next.cols || prev.bottom < prevPageHeight * (1 - PAGE_EDGE_SHARE)
                || next.top > pageHeight * PAGE_EDGE_SHARE || Math.abs(prev.left - next.left) > 12f) {
            return false;
        }
        for (int c = 0; c < next.cols; c++) {
            float wa = prev.widths[c];
            float wb = next.widths[c];
            if (Math.abs(wa - wb) > Math.max(6f, 0.1f * Math.max(wa, wb))) {
                return false;
            }
        }
        return true;
    }

    static int repeatedHeader(TableGrid prev, TableGrid next) {
        int n = Math.max(1, next.headerRows);
        if (n > next.rows || n > prev.rows) {
            return 0;
        }
        for (int r = 0; r < n; r++) {
            if (!rowText(prev, r).equals(rowText(next, r))) {
                return 0;
            }
        }
        return rowText(next, 0).isBlank() ? 0 : n;
    }

    private static String rowText(TableGrid g, int r) {
        StringBuilder sb = new StringBuilder();
        for (TableGrid.Anchor a : g.anchors) {
            if (a.row() == r) {
                sb.append(a.col()).append(':').append(a.value().text().replaceAll("\\s+", " ").strip()).append('|');
            }
        }
        return sb.toString();
    }
}

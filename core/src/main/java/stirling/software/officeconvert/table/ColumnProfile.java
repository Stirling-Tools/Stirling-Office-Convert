package stirling.software.officeconvert.table;

import java.util.List;

import stirling.software.officeconvert.table.PageContent.PdfWord;

record ColumnProfile(boolean[] numeric, int key, float[] wrapWidths) {

    private static final float NUMERIC_SHARE = 0.6f;

    static ColumnProfile of(List<CellLine> lines, int cols) {
        return withWrapWidths(lines, contentWidths(lines, cols));
    }

    static ColumnProfile withWrapWidths(List<CellLine> lines, float[] wrapWidths) {
        int cols = wrapWidths.length;
        return new ColumnProfile(numeric(lines, cols), keyColumn(lines, cols), wrapWidths);
    }

    private static boolean[] numeric(List<CellLine> lines, int cols) {
        boolean[] numeric = new boolean[cols];
        for (int c = 0; c < cols; c++) {
            int filled = 0;
            int numbers = 0;
            for (CellLine line : lines) {
                if (line.has(c)) {
                    filled++;
                    if (NumericValue.looksNumeric(TextLine.joinWords(line.cell(c)))) {
                        numbers++;
                    }
                }
            }
            numeric[c] = numbers >= 2 && numbers >= filled * NUMERIC_SHARE;
        }
        return numeric;
    }

    private static int keyColumn(List<CellLine> lines, int cols) {
        int best = 0;
        int bestCount = -1;
        for (int c = 0; c < cols; c++) {
            int count = 0;
            for (CellLine line : lines) {
                if (line.has(c)) {
                    count++;
                }
            }
            if (count > bestCount) {
                best = c;
                bestCount = count;
            }
        }
        return best;
    }

    private static float[] contentWidths(List<CellLine> lines, int cols) {
        float[] widths = new float[cols];
        for (CellLine line : lines) {
            for (int c = 0; c < cols; c++) {
                if (line.has(c)) {
                    List<PdfWord> cell = line.cell(c);
                    widths[c] = Math.max(widths[c], cell.getLast().right() - cell.getFirst().x());
                }
            }
        }
        return widths;
    }
}

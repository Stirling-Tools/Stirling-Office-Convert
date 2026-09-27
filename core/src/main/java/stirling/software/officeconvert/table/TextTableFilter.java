package stirling.software.officeconvert.table;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import stirling.software.officeconvert.table.PageContent.PdfWord;

final class TextTableFilter {

    private static final float MAX_WORDS_PER_CELL_LINE = 7f;

    private static final int PROSE_WORDS_PER_LINE = 4;

    private static final Pattern EQUATION_NUMBER = Pattern.compile("\\(\\d+(\\.\\d+)*[a-z]?\\)");

    private TextTableFilter() {}

    static boolean accepts(
            List<CellLine> lines, List<LineRange> rows, ColumnProfile columns, boolean ruled) {
        int cols = columns.wrapWidths().length;
        if (rows.size() < 2 || (rows.size() < 3 && !ruled)) {
            return false;
        }
        return !allColumnsProse(lines, columns)
                && cellsRecur(lines, rows, cols, ruled)
                && !hasEquationNumbers(lines, cols)
                && !wordy(lines, cols)
                && !isList(lines, cols);
    }

    private static boolean cellsRecur(
            List<CellLine> lines, List<LineRange> rows, int cols, boolean ruled) {
        int multiCellRows = 0;
        int[] rowsPerColumn = new int[cols];
        for (LineRange row : rows) {
            Set<Integer> filled = RowGrouper.filledColumns(row, lines);
            for (int c : filled) {
                rowsPerColumn[c]++;
            }
            if (filled.size() >= 2) {
                multiCellRows++;
            }
        }
        if (multiCellRows < (ruled ? 2 : 3) || multiCellRows * 2 < rows.size()) {
            return false;
        }
        int oneRowColumns = 0;
        for (int n : rowsPerColumn) {
            if (n <= 1) {
                oneRowColumns++;
            }
        }
        return oneRowColumns * 2 <= cols && cols - oneRowColumns >= 2;
    }

    private static boolean allColumnsProse(List<CellLine> lines, ColumnProfile columns) {
        float[] widths = columns.wrapWidths();
        for (int c = 0; c < widths.length; c++) {
            int cellLines = 0;
            int words = 0;
            int full = 0;
            for (CellLine line : lines) {
                if (!line.has(c)) {
                    continue;
                }
                List<PdfWord> cell = line.cell(c);
                cellLines++;
                words += cell.size();
                if (cell.getLast().right() - cell.getFirst().x() >= widths[c] * 0.8f) {
                    full++;
                }
            }
            boolean prose =
                    cellLines >= 2
                            && words >= cellLines * PROSE_WORDS_PER_LINE
                            && full * 10 >= cellLines * 6;
            if (!prose) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasEquationNumbers(List<CellLine> lines, int cols) {
        for (int c = 0; c < cols; c++) {
            int filled = 0;
            int labels = 0;
            for (CellLine line : lines) {
                if (line.has(c)) {
                    filled++;
                    if (EQUATION_NUMBER.matcher(TextLine.joinWords(line.cell(c))).matches()) {
                        labels++;
                    }
                }
            }
            if (labels > 0 && labels == filled) {
                return true;
            }
        }
        return false;
    }

    private static boolean wordy(List<CellLine> lines, int cols) {
        int cellLines = 0;
        int words = 0;
        for (CellLine line : lines) {
            for (int c = 0; c < cols; c++) {
                if (line.has(c)) {
                    cellLines++;
                    words += line.cell(c).size();
                }
            }
        }
        return cellLines == 0 || (float) words / cellLines > MAX_WORDS_PER_CELL_LINE;
    }

    private static boolean isList(List<CellLine> lines, int cols) {
        if (cols > 2) {
            return false;
        }
        int firstColumn = 0;
        int markers = 0;
        for (CellLine line : lines) {
            if (line.has(0)) {
                firstColumn++;
                if (ListMarkers.isMarker(line.cell(0).getFirst().text())) {
                    markers++;
                }
            }
        }
        return markers * 10 >= firstColumn * 6;
    }
}

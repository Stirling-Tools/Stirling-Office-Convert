package stirling.software.officeconvert.table;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import stirling.software.officeconvert.table.DraftTable.Borders;
import stirling.software.officeconvert.table.PageContent.PdfWord;

final class RuledBandSplitter {

    private static final float CELL_PADDING = 2f;

    private RuledBandSplitter() {}

    private record Split(List<CellLine> lines, List<LineRange> rows, float[] boundaries) {}

    static DraftTable split(DraftTable grid) {
        int rows = grid.rows();
        List<List<DraftTable.Cell>> byBand = new ArrayList<>();
        boolean[] spanned = new boolean[rows];
        for (int r = 0; r < rows; r++) {
            byBand.add(new ArrayList<>());
        }
        for (DraftTable.Cell cell : grid.cells()) {
            byBand.get(cell.row()).add(cell);
            if (cell.rowSpan() > 1) {
                for (int r = cell.row(); r < cell.row() + cell.rowSpan(); r++) {
                    spanned[r] = true;
                }
            }
        }
        List<Split> splits = new ArrayList<>();
        int[] start = new int[rows + 1];
        for (int r = 0; r < rows; r++) {
            Split split = spanned[r] ? null : splitBand(byBand.get(r), grid.cols());
            splits.add(split);
            start[r + 1] = start[r] + (split == null ? 1 : split.rows().size());
        }
        if (start[rows] == rows || !bandsHideRows(splits, byBand)) {
            return grid;
        }
        List<DraftTable.Cell> cells = new ArrayList<>();
        for (DraftTable.Cell cell : grid.cells()) {
            Split split = splits.get(cell.row());
            if (split == null) {
                int rowSpan = start[cell.row() + cell.rowSpan()] - start[cell.row()];
                cells.add(
                        new DraftTable.Cell(
                                start[cell.row()],
                                cell.col(),
                                rowSpan,
                                cell.colSpan(),
                                cell.box(),
                                cell.wrapWidth(),
                                cell.borders(),
                                cell.words()));
                continue;
            }
            for (int k = 0; k < split.rows().size(); k++) {
                cells.add(part(cell, split, k, start[cell.row()] + k));
            }
        }
        return new DraftTable(
                grid.page(), start[rows], grid.cols(), grid.colEdges(), true, cells, 0);
    }

    private static boolean bandsHideRows(List<Split> splits, List<List<DraftTable.Cell>> byBand) {
        int plainBands = 0;
        int hiddenRows = 0;
        for (int r = 0; r < splits.size(); r++) {
            if (splits.get(r) != null) {
                hiddenRows += splits.get(r).rows().size();
            } else if (byBand.get(r).stream().anyMatch(c -> !c.words().isEmpty())) {
                plainBands++;
            }
        }
        return hiddenRows >= plainBands;
    }

    private static DraftTable.Cell part(DraftTable.Cell cell, Split split, int k, int row) {
        int last = split.rows().size() - 1;
        float top = k == 0 ? cell.box().top() : split.boundaries()[k - 1];
        float bottom = k == last ? cell.box().bottom() : split.boundaries()[k];
        LineRange lines = split.rows().get(k);
        List<PdfWord> words = new ArrayList<>();
        for (int li = lines.first(); li <= lines.last(); li++) {
            words.addAll(split.lines().get(li).cell(cell.col()));
        }
        Borders b = cell.borders();
        return new DraftTable.Cell(
                row,
                cell.col(),
                1,
                cell.colSpan(),
                new Box(cell.box().left(), top, cell.box().right(), bottom),
                cell.wrapWidth(),
                new Borders(k == 0 && b.top(), k == last && b.bottom(), b.left(), b.right()),
                words);
    }

    private static Split splitBand(List<DraftTable.Cell> cells, int cols) {
        Map<PdfWord, Integer> colOf = new IdentityHashMap<>();
        List<PdfWord> words = new ArrayList<>();
        float[] wrapWidths = new float[cols];
        for (DraftTable.Cell cell : cells) {
            wrapWidths[cell.col()] = Math.max(1f, cell.box().width() - 2 * CELL_PADDING);
            for (PdfWord w : cell.words()) {
                colOf.put(w, cell.col());
                words.add(w);
            }
        }
        List<TextLine> textLines = TextLine.group(words);
        if (textLines.size() < 2) {
            return null;
        }
        List<CellLine> lines = new ArrayList<>();
        for (TextLine line : textLines) {
            List<List<PdfWord>> byColumn = new ArrayList<>();
            for (int c = 0; c < cols; c++) {
                byColumn.add(new ArrayList<>());
            }
            for (PdfWord w : line.words()) {
                byColumn.get(colOf.get(w)).add(w);
            }
            lines.add(new CellLine(line, byColumn));
        }
        ColumnProfile profile = ColumnProfile.withWrapWidths(lines, wrapWidths);
        List<LineRange> rows = RowGrouper.group(lines, profile, Separators.NONE);
        if (rows.size() < 2 || !clearlySeveralRows(lines, rows, profile)) {
            return null;
        }
        float[] boundaries = new float[rows.size() - 1];
        for (int k = 0; k + 1 < rows.size(); k++) {
            float bottom = lines.get(rows.get(k).last()).line().bottom();
            float top = lines.get(rows.get(k + 1).first()).line().top();
            boundaries[k] = (bottom + top) / 2f;
        }
        return new Split(lines, rows, boundaries);
    }

    private static boolean clearlySeveralRows(
            List<CellLine> lines, List<LineRange> rows, ColumnProfile profile) {
        int multiCell = 0;
        int withNumbers = 0;
        for (LineRange row : rows) {
            Set<Integer> filled = RowGrouper.filledColumns(row, lines);
            if (filled.size() >= 2) {
                multiCell++;
            }
            if (filled.stream().anyMatch(c -> profile.numeric()[c] && holdsDigits(lines, row, c))) {
                withNumbers++;
            }
        }
        return withNumbers >= 2
                || (multiCell >= 3
                        && multiCell == rows.size()
                        && !anyCellWraps(lines, rows, profile));
    }

    private static boolean anyCellWraps(
            List<CellLine> lines, List<LineRange> rows, ColumnProfile profile) {
        float[] wrapWidths = profile.wrapWidths();
        for (int c = 0; c < wrapWidths.length; c++) {
            for (int k = 0; k + 1 < rows.size(); k++) {
                List<PdfWord> above = lastCell(lines, rows.get(k), c);
                List<PdfWord> below = firstCell(lines, rows.get(k + 1), c);
                if (!above.isEmpty()
                        && !below.isEmpty()
                        && CellText.wrapsInto(above, below.getFirst(), wrapWidths[c])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean holdsDigits(List<CellLine> lines, LineRange row, int col) {
        for (int li = row.first(); li <= row.last(); li++) {
            if (lines.get(li).has(col) && lines.get(li).cell(col).stream().anyMatch(w -> w.text().chars().anyMatch(Character::isDigit))) {
                return true;
            }
        }
        return false;
    }

    private static List<PdfWord> lastCell(List<CellLine> lines, LineRange row, int col) {
        for (int li = row.last(); li >= row.first(); li--) {
            if (lines.get(li).has(col)) {
                return lines.get(li).cell(col);
            }
        }
        return List.of();
    }

    private static List<PdfWord> firstCell(List<CellLine> lines, LineRange row, int col) {
        for (int li = row.first(); li <= row.last(); li++) {
            if (lines.get(li).has(col)) {
                return lines.get(li).cell(col);
            }
        }
        return List.of();
    }
}

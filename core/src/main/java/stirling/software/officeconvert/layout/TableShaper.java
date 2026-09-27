package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.table.PageContent.PdfWord;
import stirling.software.officeconvert.table.TableDetection.FoundCell;
import stirling.software.officeconvert.table.TableDetection;

final class TableShaper {

    private TableShaper() {}

    static List<List<Word>> assign(TableDetection.Found t, List<Word> words) {
        List<List<Word>> out = new ArrayList<>();
        for (int i = 0; i < t.cells().size(); i++) {
            out.add(new ArrayList<>());
        }
        for (Word w : words) {
            float cx = (w.x + w.right) / 2f;
            Glyph g = w.first();
            float cy = g.baseline - g.size * 0.3f;
            int best = -1;
            float bestArea = Float.MAX_VALUE;
            float bestDist = Float.MAX_VALUE;
            for (int i = 0; i < t.cells().size(); i++) {
                FoundCell c = t.cells().get(i);
                float dx = cx < c.left() ? c.left() - cx : cx > c.right() ? cx - c.right() : 0;
                float dy = cy < c.top() ? c.top() - cy : cy > c.bottom() ? cy - c.bottom() : 0;
                float dist = dx + dy;
                float area = (c.right() - c.left()) * (c.bottom() - c.top());
                if (dist < bestDist - 0.01f || Math.abs(dist - bestDist) <= 0.01f && area < bestArea) {
                    best = i;
                    bestDist = dist;
                    bestArea = area;
                }
            }
            if (best >= 0) {
                out.get(best).add(w);
            }
        }
        return out;
    }

    static void keepPhrasesWhole(TableDetection.Found t, List<List<Word>> perCell) {
        for (int i = 0; i < t.cells().size(); i++) {
            FoundCell c = t.cells().get(i);
            List<Word> moving = new ArrayList<>(perCell.get(i));
            moving.sort(Comparator.comparingDouble(w -> w.x));
            for (Word w : moving) {
                if (!isProse(w.text)) {
                    continue;
                }
                for (int j = 0; j < t.cells().size(); j++) {
                    FoundCell o = t.cells().get(j);
                    if (j == i || o.row() != c.row() || o.col() + o.colSpan() > c.col() || !continues(perCell.get(j), w)) {
                        continue;
                    }
                    perCell.get(i).remove(w);
                    perCell.get(j).add(w);
                    break;
                }
            }
        }
    }

    private static boolean continues(List<Word> words, Word w) {
        Glyph g = w.first();
        for (Word v : words) {
            Glyph h = v.first();
            float gap = w.x - v.right;
            if (Math.abs(h.baseline - g.baseline) < 0.3f * g.size && gap > 0 && gap <= 0.35f * g.size && isProse(v.text)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isProse(String s) {
        int letters = 0;
        boolean lower = false;
        for (int k = 0; k < s.length(); k++) {
            char ch = s.charAt(k);
            letters += Character.isLetter(ch) ? 1 : 0;
            lower |= Character.isLowerCase(ch);
        }
        return lower && letters >= 2 && letters >= 0.6f * s.length();
    }

    private static final Pattern SECTION_NUMBER = Pattern.compile("\\(?[0-9]{1,3}(\\.[0-9]{1,3})+\\.?\\)?");
    private static final Pattern BARE_NUMBER = Pattern.compile("[0-9]{1,3}");

    private static final Pattern NUMERIC = Pattern.compile("[-+(]?[$€£]?[0-9][0-9.,]*%?\\)?");

    private static boolean mostlyNumeric(TableDetection.Found t, List<List<Word>> cellWords) {
        int cells = 0;
        int numbers = 0;
        for (int i = 0; i < t.cells().size(); i++) {
            if (t.cells().get(i).col() == 0 || cellWords.get(i).isEmpty()) {
                continue;
            }
            cells++;
            List<Word> ws = cellWords.get(i);
            if (ws.size() == 1 && NUMERIC.matcher(ws.getFirst().text).matches()) {
                numbers++;
            }
        }
        return cells > 0 && numbers * 2 >= cells;
    }

    static boolean looksLikeList(TableDetection.Found t, List<List<Word>> cellWords) {
        if (t.ruled()) {
            return false;
        }
        int markers = 0;
        int filled = 0;
        int bare = 0;
        for (int i = 0; i < t.cells().size(); i++) {
            FoundCell c = t.cells().get(i);
            if (c.col() != 0 || cellWords.get(i).isEmpty()) {
                continue;
            }
            filled++;
            List<Word> ws = cellWords.get(i);
            String text = ws.getFirst().text;
            if (ws.size() != 1) {
                continue;
            }
            if (Marker.parse(text, ws.getFirst().first().font) != null || SECTION_NUMBER.matcher(text).matches()) {
                markers++;
            } else if (BARE_NUMBER.matcher(text).matches()) {
                bare++;
            }
        }
        if (!mostlyNumeric(t, cellWords)) {
            markers += bare;
        }
        return filled >= 2 && markers * 10 >= filled * 7;
    }

    static TableDetection.Found trimToRules(TableDetection.Found t, List<stirling.software.officeconvert.extract.PageGraphics.Rule> rules,
            List<PdfWord> words) {
        if (t.ruled()) {
            return t;
        }
        float width = t.right() - t.left();
        float top = Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        int n = 0;
        for (var r : rules) {
            if (!r.horizontal() || r.length() < width * 0.8f || r.start() > t.left() + width * 0.2f
                    || r.end() < t.right() - width * 0.2f || r.pos() < t.top() - 3 || r.pos() > t.bottom() + 3) {
                continue;
            }
            top = Math.min(top, r.pos());
            bottom = Math.max(bottom, r.pos());
            n++;
        }
        if (n < 2 || bottom - top < 10) {
            return t;
        }
        int firstRow = Integer.MAX_VALUE;
        int lastRow = -1;
        for (FoundCell c : t.cells()) {
            float cy = (c.top() + c.bottom()) / 2f;
            if (cy > top && cy < bottom) {
                firstRow = Math.min(firstRow, c.row());
                lastRow = Math.max(lastRow, c.row() + c.rowSpan() - 1);
            }
        }
        if (lastRow < 0) {
            return t;
        }
        int inFirst = firstRow;
        int inLast = lastRow;
        if (n < 3) {
            while (firstRow > 0 && TableRowFit.fits(t, firstRow - 1, words)) {
                firstRow--;
            }
            while (lastRow < t.rows() - 1 && TableRowFit.fits(t, lastRow + 1, words)) {
                lastRow++;
            }
        }
        if (firstRow == 0 && lastRow == t.rows() - 1) {
            return t;
        }
        List<FoundCell> cells = new ArrayList<>();
        for (FoundCell c : t.cells()) {
            if (c.row() >= firstRow && c.row() + c.rowSpan() - 1 <= lastRow) {
                cells.add(new FoundCell(c.row() - firstRow, c.col(), c.rowSpan(), c.colSpan(), c.left(), c.top(),
                        c.right(), c.bottom(), c.borderTop(), c.borderBottom(), c.borderLeft(), c.borderRight(),
                        c.fill(), c.hAlign(), c.vAlign(), c.text()));
            }
        }
        for (FoundCell c : cells) {
            if (c.row() + firstRow < inFirst) {
                top = Math.min(top, c.top() + 0.5f);
            }
            if (c.row() + firstRow > inLast) {
                bottom = Math.max(bottom, c.bottom() - 0.5f);
            }
        }
        int header = Math.max(0, Math.min(t.headerRows() - firstRow, lastRow - firstRow));
        return new TableDetection.Found(lastRow - firstRow + 1, t.cols(), t.colEdges(), false, header, t.padding(),
                cells, t.left(), top - 0.5f, t.right(), bottom + 0.5f);
    }

    static TableDetection.Found addFrameHeader(TableDetection.Found t, Box frame, List<Line> segments) {
        List<Line> edgeAbove = FrameEdgeRows.above(frame, t.colEdges(), segments);
        List<Line> inside = new ArrayList<>();
        for (Line l : segments) {
            float cy = (l.top + l.bottom) / 2f;
            if (cy > frame.top() && cy < t.top() - 0.5f && l.x >= frame.x() - 1 && l.right <= frame.right() + 1) {
                inside.add(l);
            }
        }
        List<Line> above = new ArrayList<>(FrameEdgeRows.headerAbove(inside, t.top(), t.colEdges()));
        if (above.size() == inside.size()) {
            above.addAll(edgeAbove);
        } else {
            edgeAbove = List.of();
        }
        List<Line> below = FrameEdgeRows.below(frame, t.colEdges(), segments);
        if (above.isEmpty() && below.isEmpty()) {
            return t;
        }
        List<Row> lead = rows(above);
        List<Row> trail = rows(below);
        int k = lead.size();
        List<FoundCell> cells = new ArrayList<>();
        addRows(cells, lead, 0, t, frame);
        for (FoundCell c : t.cells()) {
            cells.add(new FoundCell(c.row() + k, c.col(), c.rowSpan(), c.colSpan(), c.left(), c.top(), c.right(), c.bottom(),
                    c.borderTop(), c.borderBottom(), c.borderLeft(), c.borderRight(), c.fill(), c.hAlign(), c.vAlign(), c.text()));
        }
        addRows(cells, trail, t.rows() + k, t, frame);
        float top = lead.isEmpty() ? t.top() : frame.top() + 0.5f;
        if (!edgeAbove.isEmpty()) {
            top = Math.min(top, lead.getFirst().top() - 1);
        }
        float bottom = trail.isEmpty() ? t.bottom() : Math.max(t.bottom(), trail.getLast().bottom() + 1);
        int header = lead.isEmpty() ? t.headerRows() : Math.max(k, t.headerRows() + k);
        return new TableDetection.Found(t.rows() + k + trail.size(), t.cols(), t.colEdges(), t.ruled(), header,
                t.padding(), cells, Math.min(t.left(), frame.x()), top, Math.max(t.right(), frame.right()), bottom);
    }

    private record Row(List<Word> words, float top, float bottom) {}

    private static List<Row> rows(List<Line> lines) {
        List<Line> sorted = new ArrayList<>(lines);
        sorted.sort((a, b) -> Float.compare(a.baseline, b.baseline));
        List<Row> rows = new ArrayList<>();
        for (Line l : sorted) {
            if (!rows.isEmpty() && Math.abs(rows.getLast().top() - l.top) < 1.5f) {
                Row last = rows.removeLast();
                List<Word> words = new ArrayList<>(last.words());
                words.addAll(l.words);
                rows.add(new Row(words, last.top(), Math.max(last.bottom(), l.bottom)));
            } else {
                rows.add(new Row(new ArrayList<>(l.words), l.top, l.bottom));
            }
        }
        return rows;
    }

    private static void addRows(List<FoundCell> cells, List<Row> rows, int first, TableDetection.Found t, Box frame) {
        float[] e = t.colEdges();
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            for (int c = 0; c < t.cols(); c++) {
                float lo = c == 0 ? frame.x() : e[c];
                float hi = c == t.cols() - 1 ? frame.right() : e[c + 1];
                StringBuilder sb = new StringBuilder();
                for (Word w : row.words()) {
                    float cx = (w.x + w.right) / 2f;
                    if (cx >= lo && cx < hi) {
                        sb.append(sb.isEmpty() ? "" : " ").append(w.text);
                    }
                }
                cells.add(new FoundCell(first + r, c, 1, 1, e[c], row.top() - 1, e[c + 1], row.bottom() + 1, false, false,
                        false, false, null, TableDetection.HAlign.GENERAL, TableDetection.VAlign.TOP, sb.toString()));
            }
        }
    }

    private record Split(int row, float[] bounds) {}

    static TableDetection.Found splitStacked(TableDetection.Found t, List<List<Word>> cellWords) {
        List<Split> splits = new ArrayList<>();
        for (int r = 0; r < t.rows(); r++) {
            float[] bounds = rowSplit(t, r, cellWords);
            if (bounds != null) {
                splits.add(new Split(r, bounds));
            }
        }
        if (splits.isEmpty()) {
            return t;
        }
        int[] subRows = new int[t.rows()];
        Arrays.fill(subRows, 1);
        float[][] bounds = new float[t.rows()][];
        for (Split s : splits) {
            subRows[s.row()] = s.bounds().length + 1;
            bounds[s.row()] = s.bounds();
        }
        int[] start = new int[t.rows() + 1];
        for (int r = 0; r < t.rows(); r++) {
            start[r + 1] = start[r] + subRows[r];
        }
        List<FoundCell> cells = new ArrayList<>();
        for (FoundCell c : t.cells()) {
            int r = c.row();
            if (c.rowSpan() == 1 && bounds[r] != null) {
                float[] b = bounds[r];
                for (int k = 0; k <= b.length; k++) {
                    float top = k == 0 ? c.top() : b[k - 1];
                    float bottom = k == b.length ? c.bottom() : b[k];
                    cells.add(new FoundCell(start[r] + k, c.col(), 1, c.colSpan(), c.left(), top, c.right(), bottom,
                            k == 0 && c.borderTop(), k == b.length && c.borderBottom(), c.borderLeft(),
                            c.borderRight(), c.fill(), c.hAlign(), TableDetection.VAlign.TOP, ""));
                }
            } else {
                int span = start[Math.min(t.rows(), r + c.rowSpan())] - start[r];
                cells.add(new FoundCell(start[r], c.col(), span, c.colSpan(), c.left(), c.top(), c.right(),
                        c.bottom(), c.borderTop(), c.borderBottom(), c.borderLeft(), c.borderRight(), c.fill(),
                        c.hAlign(), c.vAlign(), c.text()));
            }
        }
        cells.sort(Comparator.comparingInt(FoundCell::row).thenComparingInt(FoundCell::col));
        int header = t.headerRows() <= 0 ? 0 : start[Math.min(t.rows(), t.headerRows())];
        return new TableDetection.Found(start[t.rows()], t.cols(), t.colEdges(), t.ruled(), header, t.padding(),
                cells, t.left(), t.top(), t.right(), t.bottom());
    }

    private static float[] rowSplit(TableDetection.Found t, int r, List<List<Word>> cellWords) {
        List<float[]> perCellBaselines = new ArrayList<>();
        int multiLine = 0;
        float size = 0;
        float[] firstColumn = null;
        int firstCol = Integer.MAX_VALUE;
        for (int i = 0; i < t.cells().size(); i++) {
            FoundCell c = t.cells().get(i);
            if (c.row() != r || c.rowSpan() != 1 || cellWords.get(i).isEmpty()) {
                continue;
            }
            float[] bl = baselines(cellWords.get(i));
            perCellBaselines.add(bl);
            if (c.col() < firstCol) {
                firstCol = c.col();
                firstColumn = bl;
            }
            if (bl.length >= 2) {
                multiLine++;
            }
            for (Word w : cellWords.get(i)) {
                size = Math.max(size, w.size());
            }
        }
        if (multiLine < 2) {
            return null;
        }
        List<Float> all = new ArrayList<>();
        for (float[] bl : perCellBaselines) {
            for (float b : bl) {
                all.add(b);
            }
        }
        all.sort(Float::compare);
        List<Float> shared = new ArrayList<>();
        int i = 0;
        while (i < all.size()) {
            int j = i + 1;
            while (j < all.size() && all.get(j) - all.get(i) <= 1.5f) {
                j++;
            }
            int cellsWithIt = 0;
            for (float[] bl : perCellBaselines) {
                for (float b : bl) {
                    if (Math.abs(b - all.get(i)) <= 1.5f) {
                        cellsWithIt++;
                        break;
                    }
                }
            }
            if (cellsWithIt >= 2) {
                shared.add(all.get(i));
            }
            i = j;
        }
        if (shared.size() < 2 || !startsEveryLine(firstColumn, shared)) {
            return null;
        }
        float[] bounds = new float[shared.size() - 1];
        for (int k = 0; k + 1 < shared.size(); k++) {
            float bottomOfUpper = shared.get(k) + 0.2f * size;
            float topOfLower = shared.get(k + 1) - 0.8f * size;
            bounds[k] = (bottomOfUpper + topOfLower) / 2f;
        }
        return bounds;
    }

    private static boolean startsEveryLine(float[] firstColumn, List<Float> shared) {
        for (float line : shared) {
            boolean found = false;
            for (float b : firstColumn) {
                found |= Math.abs(b - line) <= 1.5f;
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static float[] baselines(List<Word> words) {
        List<Float> bs = new ArrayList<>();
        for (Word w : words) {
            bs.add(w.first().baseline);
        }
        bs.sort(Float::compare);
        List<Float> out = new ArrayList<>();
        for (float b : bs) {
            if (out.isEmpty() || b - out.getLast() > 1.5f) {
                out.add(b);
            }
        }
        float[] a = new float[out.size()];
        for (int k = 0; k < a.length; k++) {
            a[k] = out.get(k);
        }
        return a;
    }
}

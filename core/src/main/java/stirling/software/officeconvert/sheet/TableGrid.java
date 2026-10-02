package stirling.software.officeconvert.sheet;

import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.extract.PageGraphics.Rule;
import stirling.software.officeconvert.layout.PageLayout;
import stirling.software.officeconvert.model.Table;
import stirling.software.officeconvert.sheet.CellStyle.Border;
import stirling.software.officeconvert.sheet.CellStyle.HAlign;
import stirling.software.officeconvert.sheet.CellStyle.VAlign;
import stirling.software.officeconvert.table.TableDetection;

final class TableGrid {

    static final float PADDING = 4f;

    record Anchor(int row, int col, int rowSpan, int colSpan, CellText text, CellValue value, CellStyle style) {}

    private record Raw(TableDetection.FoundCell cell, int row, int col, int rowSpan, int colSpan, CellText text,
            List<ColumnSplit.Piece> pieces) {

        float x0() {
            return pieces == null ? Float.NaN : pieces.getFirst().x();
        }

        float x1() {
            return pieces == null ? Float.NaN : pieces.getLast().right();
        }
    }

    private record Split(List<Raw> raws, float[] edges, int cols) {}

    private static final float WORD_GAP = 0.7f;

    private static int proseRows(List<Raw> raws, int rows) {
        int k = 0;
        while (k < 3 && k < rows - 2) {
            List<Raw> cells = new ArrayList<>();
            for (Raw r : raws) {
                if (r.row() == k && !r.text().text().isEmpty()) {
                    if (r.rowSpan() > 1 || Float.isNaN(r.x0())) {
                        return k;
                    }
                    cells.add(r);
                }
            }
            if (cells.size() < 2) {
                return k;
            }
            cells.sort((a, b) -> Float.compare(a.x0(), b.x0()));
            boolean lower = false;
            for (int i = 0; i < cells.size(); i++) {
                Raw c = cells.get(i);
                lower |= c.text().text().chars().filter(Character::isLowerCase).count() >= 3;
                if (i > 0) {
                    Raw prev = cells.get(i - 1);
                    float em = Math.max(prev.text().size(), c.text().size());
                    if (c.x0() - prev.x1() > WORD_GAP * em) {
                        return k;
                    }
                }
            }
            if (!lower) {
                return k;
            }
            k++;
        }
        return k;
    }

    private static CellText rowText(List<Raw> raws, int row) {
        List<Raw> cells = new ArrayList<>();
        for (Raw r : raws) {
            if (r.row() == row && !r.text().text().isEmpty()) {
                cells.add(r);
            }
        }
        cells.sort((a, b) -> Float.compare(a.x0(), b.x0()));
        StringBuilder sb = new StringBuilder();
        float width = 0;
        for (Raw r : cells) {
            sb.append(sb.isEmpty() ? "" : " ").append(r.text().text());
            width += r.text().width();
        }
        CellText f = cells.getFirst().text();
        return new CellText(sb.toString(), f.size(), f.bold(), f.italic(), f.underline(), f.strike(), f.rgb(), f.scripted(),
                width, 1);
    }

    final int rows;
    final int cols;
    final List<Anchor> anchors;
    final float[] widths;
    final float[] heights;
    final int headerRows;
    final float left;
    final float top;
    final float right;
    final float bottom;
    final Conventions[] conventions;
    final List<CellText> above;

    private TableGrid(int rows, int cols, List<Anchor> anchors, float[] widths, float[] heights, int headerRows,
            TableDetection.Found t, Conventions[] conventions, List<CellText> above) {
        this.above = above;
        this.rows = rows;
        this.cols = cols;
        this.anchors = anchors;
        this.widths = widths;
        this.heights = heights;
        this.headerRows = headerRows;
        this.left = t.left();
        this.top = t.top();
        this.right = t.right();
        this.bottom = t.bottom();
        this.conventions = conventions;
    }

    static TableGrid of(PageLayout.TableItem item, List<Rule> rules, Conventions document, Conventions[] prior,
            boolean typed, boolean dropHyphens) {
        TableDetection.Found t = item.table();
        Split split = split(place(item, dropHyphens), t.colEdges(), t.cols());
        List<Raw> raw = new ArrayList<>(split.raws());
        List<CellText> above = new ArrayList<>();
        int prose = proseRows(raw, t.rows());
        for (int r = 0; r < prose; r++) {
            above.add(rowText(raw, r));
        }
        final int cut = prose;
        raw.removeIf(x -> x.row() < cut);
        boolean[] keepRow = new boolean[t.rows()];
        boolean[] keepCol = new boolean[split.cols()];
        for (Raw r : raw) {
            if (!r.text().text().isEmpty()) {
                keepRow[r.row()] = true;
                keepCol[r.col()] = true;
            }
        }
        int[] rowAt = indexes(keepRow);
        int[] colAt = indexes(keepCol);
        int rows = count(keepRow);
        int cols = count(keepCol);
        if (cols == 0) {
            return null;
        }
        float[] widths = fold(edges(split.edges()), keepCol, cols);
        float[] pdfHeights = fold(edges(item.rowEdges()), keepRow, rows);
        int header = 0;
        for (int r = prose; r < Math.min(t.headerRows(), t.rows()); r++) {
            header += keepRow[r] ? 1 : 0;
        }
        List<Raw> kept = new ArrayList<>();
        for (Raw r : raw) {
            int nr = firstKept(rowAt, r.row(), r.rowSpan());
            int nc = firstKept(colAt, r.col(), r.colSpan());
            if (nr >= 0 && nc >= 0) {
                kept.add(new Raw(r.cell(), nr, nc, keptIn(rowAt, r.row(), r.rowSpan()), keptIn(colAt, r.col(), r.colSpan()),
                        r.text(), r.pieces()));
            }
        }
        if (header == 0 && typed) {
            header = inferredHeader(kept, rows, document);
        }
        Conventions[] columnConventions = new Conventions[cols];
        CellValue[] values = values(kept, cols, header, document, prior, typed, columnConventions);
        boolean[] numeric = numericColumns(kept, values, cols, header);
        List<Anchor> anchors = new ArrayList<>(kept.size());
        for (int i = 0; i < kept.size(); i++) {
            Raw r = kept.get(i);
            boolean head = r.row() < header;
            CellStyle style = style(r, values[i], head, rules, r.colSpan() == 1 && numeric[r.col()]);
            anchors.add(new Anchor(r.row(), r.col(), r.rowSpan(), r.colSpan(), r.text(), values[i], style));
            if (r.colSpan() == 1 && (!values[i].isText() || r.text().lines() == 1)) {
                String shown = values[i].text();
                float need = values[i].isText() ? TextWidth.of(shown, style.size(), style.bold()) + PADDING
                        : TextWidth.ofValue(shown, style.size(), style.bold());
                widths[r.col()] = Math.max(widths[r.col()], need);
            }
        }
        float[] heights = heights(anchors, widths, pdfHeights, rows);
        return new TableGrid(rows, cols, anchors, widths, heights, header, t, columnConventions, above);
    }

    private static List<Raw> place(PageLayout.TableItem item, boolean dropHyphens) {
        TableDetection.Found t = item.table();
        boolean[][] taken = new boolean[t.rows()][t.cols()];
        List<Raw> out = new ArrayList<>();
        for (int i = 0; i < t.cells().size(); i++) {
            TableDetection.FoundCell c = t.cells().get(i);
            int r = c.row();
            int k = c.col();
            if (r < 0 || k < 0 || r >= t.rows() || k >= t.cols()) {
                continue;
            }
            CellText text = CellText.of(item.cellParas().get(i), dropHyphens);
            if (taken[r][k]) {
                absorb(out, r, k, text);
                continue;
            }
            int rs = Math.max(1, Math.min(c.rowSpan(), t.rows() - r));
            int cs = Math.max(1, Math.min(c.colSpan(), t.cols() - k));
            while (cs > 1 && anyTaken(taken, r, k, 1, cs)) {
                cs--;
            }
            while (rs > 1 && anyTaken(taken, r, k, rs, cs)) {
                rs--;
            }
            for (int rr = r; rr < r + rs; rr++) {
                for (int kk = k; kk < k + cs; kk++) {
                    taken[rr][kk] = true;
                }
            }
            out.add(new Raw(c, r, k, rs, cs, text, text.text().isEmpty() ? null : ColumnSplit.pieces(item.cellParas().get(i))));
        }
        return out;
    }

    private static Split split(List<Raw> raws, float[] edges, int cols) {
        List<Integer> colOf = new ArrayList<>();
        List<Integer> spanOf = new ArrayList<>();
        List<List<ColumnSplit.Piece>> pieces = new ArrayList<>();
        for (Raw r : raws) {
            colOf.add(r.col());
            spanOf.add(r.colSpan());
            pieces.add(r.pieces());
        }
        List<float[]> plan = ColumnSplit.plan(cols, colOf, spanOf, pieces);
        if (plan.stream().allMatch(c -> c.length == 0)) {
            return new Split(raws, edges, cols);
        }
        int[] start = new int[cols + 1];
        List<Float> newEdges = new ArrayList<>();
        for (int c = 0; c < cols; c++) {
            start[c + 1] = start[c] + plan.get(c).length + 1;
            newEdges.add(edges[c]);
            for (float cut : plan.get(c)) {
                newEdges.add(cut);
            }
        }
        newEdges.add(edges[cols]);
        float[] e = new float[newEdges.size()];
        for (int i = 0; i < e.length; i++) {
            e[i] = newEdges.get(i);
        }
        List<Raw> out = new ArrayList<>();
        for (Raw r : raws) {
            int first = start[r.col()];
            float[] cuts = plan.get(r.col());
            if (r.colSpan() == 1 && cuts.length > 0 && r.pieces() != null) {
                out.addAll(pieceCells(r, cuts, first, e));
            } else {
                out.add(new Raw(r.cell(), r.row(), first, r.rowSpan(), start[r.col() + r.colSpan()] - first, r.text(),
                        r.pieces()));
            }
        }
        return new Split(out, e, start[cols]);
    }

    private static List<Raw> pieceCells(Raw r, float[] cuts, int first, float[] e) {
        List<StringBuilder> parts = new ArrayList<>();
        List<List<ColumnSplit.Piece>> pieces = new ArrayList<>();
        float[] width = new float[cuts.length + 1];
        for (int k = 0; k <= cuts.length; k++) {
            parts.add(new StringBuilder());
            pieces.add(new ArrayList<>());
        }
        for (ColumnSplit.Piece p : r.pieces()) {
            int k = ColumnSplit.part(cuts, p.x());
            parts.get(k).append(parts.get(k).isEmpty() ? "" : " ").append(p.text());
            pieces.get(k).add(p);
            width[k] += p.right() - p.x();
        }
        List<Raw> out = new ArrayList<>();
        TableDetection.FoundCell c = r.cell();
        CellText t = r.text();
        for (int k = 0; k <= cuts.length; k++) {
            int col = first + k;
            TableDetection.FoundCell sub = new TableDetection.FoundCell(c.row(), c.col(), c.rowSpan(), 1, e[col], c.top(),
                    e[col + 1], c.bottom(), c.borderTop(), c.borderBottom(), k == 0 && c.borderLeft(),
                    k == cuts.length && c.borderRight(), c.fill(), TableDetection.HAlign.GENERAL, c.vAlign(), "");
            String text = parts.get(k).toString();
            CellText piece = text.isEmpty() ? CellText.EMPTY : new CellText(text, t.size(), t.bold(), t.italic(),
                    t.underline(), t.strike(), t.rgb(), t.scripted(), width[k], 1);
            out.add(new Raw(sub, r.row(), col, r.rowSpan(), 1, piece, text.isEmpty() ? null : pieces.get(k)));
        }
        return out;
    }

    private static void absorb(List<Raw> out, int r, int k, CellText text) {
        if (text.text().isEmpty()) {
            return;
        }
        for (int i = 0; i < out.size(); i++) {
            Raw o = out.get(i);
            if (r >= o.row() && r < o.row() + o.rowSpan() && k >= o.col() && k < o.col() + o.colSpan()) {
                CellText a = o.text();
                String joined = a.text().isEmpty() ? text.text() : a.text() + "\n" + text.text();
                CellText merged = new CellText(joined, a.size() > 0 ? a.size() : text.size(), a.bold(), a.italic(),
                        a.underline(), a.strike(), a.rgb(), a.scripted() || text.scripted(), a.width() + text.width(),
                        a.lines() + text.lines());
                out.set(i, new Raw(o.cell(), o.row(), o.col(), o.rowSpan(), o.colSpan(), merged, null));
                return;
            }
        }
    }

    private static boolean anyTaken(boolean[][] taken, int r, int k, int rs, int cs) {
        for (int rr = r; rr < r + rs; rr++) {
            for (int kk = k; kk < k + cs; kk++) {
                if (taken[rr][kk]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static CellValue[] values(List<Raw> cells, int cols, int header, Conventions document, Conventions[] prior,
            boolean typed, Conventions[] used) {
        CellValue[] out = new CellValue[cells.size()];
        for (int c = 0; c < cols; c++) {
            Conventions base = prior != null && c < prior.length && prior[c] != null ? prior[c].or(document) : document;
            List<Integer> at = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            for (int i = 0; i < cells.size(); i++) {
                Raw r = cells.get(i);
                if (r.col() == c && r.colSpan() == 1 && r.row() >= header && typed && !r.text().scripted()) {
                    at.add(i);
                    texts.add(plain(r.text().text()));
                }
            }
            ConventionEvidence evidence = new ConventionEvidence();
            texts.forEach(evidence::add);
            used[c] = evidence.conventions(base);
            List<CellValue> typedColumn = CellTyper.column(texts, base);
            for (int j = 0; j < at.size(); j++) {
                out[at.get(j)] = typedColumn.get(j);
            }
        }
        for (int i = 0; i < out.length; i++) {
            if (out[i] == null) {
                out[i] = CellValue.text(plain(cells.get(i).text().text()));
            }
        }
        return out;
    }

    private static int inferredHeader(List<Raw> cells, int rows, Conventions document) {
        if (rows < 3) {
            return 0;
        }
        int labels = 0;
        List<Integer> labelled = new ArrayList<>();
        for (Raw r : cells) {
            if (r.row() == 0 && !r.text().text().isEmpty()) {
                if (!CellTyper.type(r.text().text(), document).isText() || r.rowSpan() > 1) {
                    return 0;
                }
                labels++;
                labelled.add(r.col());
            }
        }
        if (labels < 2) {
            return 0;
        }
        for (int col : labelled) {
            int filled = 0;
            int numbers = 0;
            for (Raw r : cells) {
                if (r.row() > 0 && r.col() == col && r.colSpan() == 1 && !r.text().text().isEmpty()) {
                    filled++;
                    numbers += CellTyper.type(r.text().text(), document).isText() ? 0 : 1;
                }
            }
            if (numbers > 0 && numbers * 2 >= filled) {
                return 1;
            }
        }
        return 0;
    }

    private static String plain(String s) {
        return s.replace('\t', ' ');
    }

    private static boolean[] numericColumns(List<Raw> cells, CellValue[] values, int cols, int header) {
        int[] filled = new int[cols];
        int[] numbers = new int[cols];
        for (int i = 0; i < cells.size(); i++) {
            Raw r = cells.get(i);
            if (r.row() >= header && r.colSpan() == 1 && !values[i].isEmpty()) {
                filled[r.col()]++;
                numbers[r.col()] += values[i].isText() ? 0 : 1;
            }
        }
        boolean[] out = new boolean[cols];
        for (int c = 0; c < cols; c++) {
            out[c] = numbers[c] > 0 && numbers[c] * 2 > filled[c];
        }
        return out;
    }

    private static CellStyle style(Raw r, CellValue value, boolean header, List<Rule> rules, boolean numericColumn) {
        TableDetection.FoundCell c = r.cell();
        CellText t = r.text();
        boolean number = !value.isText();
        HAlign h = switch (c.hAlign()) {
            case LEFT -> number ? HAlign.LEFT : HAlign.GENERAL;
            case RIGHT -> number ? HAlign.GENERAL : HAlign.RIGHT;
            case CENTER -> HAlign.CENTER;
            default -> !number && numericColumn ? HAlign.RIGHT : HAlign.GENERAL;
        };
        VAlign v = switch (c.vAlign()) {
            case CENTER -> VAlign.CENTER;
            case BOTTOM -> VAlign.BOTTOM;
            default -> VAlign.TOP;
        };
        Border top = c.borderTop() ? border(rules, true, c.top(), c.left(), c.right()) : Border.NONE;
        Border bottom = c.borderBottom() ? border(rules, true, c.bottom(), c.left(), c.right()) : Border.NONE;
        Border left = c.borderLeft() ? border(rules, false, c.left(), c.top(), c.bottom()) : Border.NONE;
        Border right = c.borderRight() ? border(rules, false, c.right(), c.top(), c.bottom()) : Border.NONE;
        return new CellStyle(t.size(), t.bold() || header, t.italic(), t.underline(), t.strike(), t.rgb(),
                c.fill() == null ? -1 : c.fill() & 0xFFFFFF, top, bottom, left, right, h, v, !number, value.format());
    }

    private static Border border(List<Rule> rules, boolean horizontal, float pos, float from, float to) {
        Rule best = null;
        float bestD = Table.BORDER_REACH;
        float mid = (from + to) / 2f;
        for (Rule r : rules) {
            if (r.horizontal() != horizontal || r.start() > mid || r.end() < mid) {
                continue;
            }
            float d = Math.abs(r.pos() - pos);
            if (d < bestD) {
                bestD = d;
                best = r;
            }
        }
        return best == null ? new Border(0.5f, 0) : new Border(Math.max(0.25f, best.thickness()), best.rgb() & 0xFFFFFF);
    }

    private static float[] heights(List<Anchor> anchors, float[] widths, float[] pdf, int rows) {
        float[] need = new float[rows];
        boolean[] fixed = new boolean[rows];
        for (int r = 0; r < rows; r++) {
            need[r] = lineHeight(11f);
        }
        for (Anchor a : anchors) {
            if (a.rowSpan() == 1) {
                need[a.row()] = Math.max(need[a.row()], textHeight(a, widths));
                if (a.colSpan() > 1 && a.value().isText() && lines(a, widths) > 1) {
                    fixed[a.row()] = true;
                }
            }
        }
        for (Anchor a : anchors) {
            if (a.rowSpan() > 1) {
                int last = a.row() + a.rowSpan() - 1;
                float have = 0;
                for (int r = a.row(); r <= last; r++) {
                    have += need[r];
                    fixed[r] = true;
                }
                float want = textHeight(a, widths);
                if (want > have) {
                    need[last] += want - have;
                }
            }
        }
        float[] out = new float[rows];
        for (int r = 0; r < rows; r++) {
            out[r] = fixed[r] ? Math.max(need[r], Float.isNaN(pdf[r]) ? 0 : pdf[r]) : Float.NaN;
        }
        return out;
    }

    private static int lines(Anchor a, float[] widths) {
        return TextWidth.lines(a.value().text(), a.style().size(), a.style().bold(), span(a, widths) - PADDING);
    }

    private static float textHeight(Anchor a, float[] widths) {
        if (a.value().isEmpty()) {
            return 0;
        }
        return lines(a, widths) * lineHeight(a.style().size()) + 2f;
    }

    static float lineHeight(float size) {
        return TextWidth.lineHeight(size);
    }

    private static float span(Anchor a, float[] widths) {
        float w = 0;
        for (int c = a.col(); c < a.col() + a.colSpan() && c < widths.length; c++) {
            w += widths[c];
        }
        return w;
    }

    private static float[] edges(float[] e) {
        float[] d = new float[Math.max(0, e.length - 1)];
        for (int i = 0; i + 1 < e.length; i++) {
            d[i] = Math.max(0, e[i + 1] - e[i]);
        }
        return d;
    }

    private static float[] fold(float[] sizes, boolean[] keep, int n) {
        float[] out = new float[n];
        int target = -1;
        float pending = 0;
        int k = 0;
        for (int i = 0; i < keep.length; i++) {
            float size = i < sizes.length ? sizes[i] : 0;
            if (keep[i]) {
                target = k++;
                out[target] = size + pending;
                pending = 0;
            } else if (target >= 0) {
                out[target] += size;
            } else {
                pending += size;
            }
        }
        return out;
    }

    private static int[] indexes(boolean[] keep) {
        int[] at = new int[keep.length];
        int k = 0;
        for (int i = 0; i < keep.length; i++) {
            at[i] = keep[i] ? k++ : -1;
        }
        return at;
    }

    private static int firstKept(int[] at, int from, int span) {
        for (int i = from; i < from + span && i < at.length; i++) {
            if (at[i] >= 0) {
                return at[i];
            }
        }
        return -1;
    }

    private static int keptIn(int[] at, int from, int span) {
        int n = 0;
        for (int i = from; i < from + span && i < at.length; i++) {
            n += at[i] >= 0 ? 1 : 0;
        }
        return Math.max(1, n);
    }

    private static int count(boolean[] keep) {
        int n = 0;
        for (boolean b : keep) {
            n += b ? 1 : 0;
        }
        return n;
    }
}

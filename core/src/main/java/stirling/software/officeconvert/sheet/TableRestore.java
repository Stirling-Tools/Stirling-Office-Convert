package stirling.software.officeconvert.sheet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.PageLayout;
import stirling.software.officeconvert.layout.ParaDraft;
import stirling.software.officeconvert.layout.Word;
import stirling.software.officeconvert.table.TableDetection;

final class TableRestore {

    record Result(PageLayout.TableItem item, Set<Line> taken) {}

    private record Band(List<Line> lines, float top, float bottom) {

        float centre() {
            return (top + bottom) / 2f;
        }
    }

    private static final float REACH_ROWS = 1.6f;

    private TableRestore() {}

    static Result restore(PageLayout.TableItem item, List<Line> offered, Set<Line> strict, Set<Line> flow) {
        TableDetection.Found t = item.table();
        float[] e = t.colEdges();
        float[] rows = item.rowEdges();
        Set<Line> taken = Collections.newSetFromMap(new IdentityHashMap<>());
        if (e.length < 2 || rows.length != t.rows() + 1 || offered.isEmpty()) {
            return new Result(item, taken);
        }
        float left = e[0] - 4f;
        float right = e[e.length - 1] + 4f;
        List<Line> near = new ArrayList<>();
        for (Line l : offered) {
            if (l.x >= left && l.right <= right) {
                near.add(l);
            }
        }
        if (near.isEmpty()) {
            return new Result(item, taken);
        }
        float reach = REACH_ROWS * rowHeight(rows);
        List<Band> inside = new ArrayList<>();
        List<Band> above = new ArrayList<>();
        List<Band> below = new ArrayList<>();
        for (Band b : bands(near)) {
            if (b.centre() >= rows[0] && b.centre() <= rows[rows.length - 1]) {
                inside.add(b);
            } else if (b.centre() < rows[0]) {
                above.add(b);
            } else {
                below.add(b);
            }
        }
        above.sort(Comparator.comparingDouble(Band::centre).reversed());
        below.sort(Comparator.comparingDouble(Band::centre));
        List<Band> top = new ArrayList<>();
        float edge = rows[0];
        for (Band b : above) {
            if (edge - b.bottom() > reach || columns(b, e) < 2 || !tabular(b)
                    || b.lines().stream().anyMatch(flow::contains)) {
                break;
            }
            top.addFirst(b);
            edge = b.top();
        }
        List<Band> bottom = new ArrayList<>();
        edge = rows[rows.length - 1];
        for (Band b : below) {
            boolean running = b.lines().stream().anyMatch(strict::contains);
            int filled = columns(b, e);
            boolean paragraph = b.lines().stream().anyMatch(flow::contains);
            if (b.top() - edge > reach || running && (filled < 2 || !tabular(b))
                    || paragraph && (e.length - 1 < 3 || filled * 2 < e.length - 1)) {
                break;
            }
            bottom.add(b);
            edge = b.bottom();
        }
        if (inside.isEmpty() && top.isEmpty() && bottom.isEmpty()) {
            return new Result(item, taken);
        }
        for (List<Band> group : List.of(inside, top, bottom)) {
            for (Band b : group) {
                taken.addAll(b.lines());
            }
        }
        return new Result(rebuild(item, inside, top, bottom), taken);
    }

    private static PageLayout.TableItem rebuild(PageLayout.TableItem item, List<Band> inside, List<Band> top,
            List<Band> bottom) {
        TableDetection.Found t = item.table();
        float[] e = t.colEdges();
        float[] rows = item.rowEdges();
        int k = top.size();
        List<TableDetection.FoundCell> cells = new ArrayList<>();
        List<List<ParaDraft>> paras = new ArrayList<>();
        for (int r = 0; r < k; r++) {
            addRow(cells, paras, r, top.get(r), e);
        }
        for (int i = 0; i < t.cells().size(); i++) {
            TableDetection.FoundCell c = t.cells().get(i);
            cells.add(new TableDetection.FoundCell(c.row() + k, c.col(), c.rowSpan(), c.colSpan(), c.left(), c.top(),
                    c.right(), c.bottom(), c.borderTop(), c.borderBottom(), c.borderLeft(), c.borderRight(), c.fill(),
                    c.hAlign(), c.vAlign(), c.text()));
            paras.add(new ArrayList<>(item.cellParas().get(i)));
        }
        for (Band b : inside) {
            int r = rowAt(rows, b.centre());
            for (List<Word> words : byColumn(b, e)) {
                if (words.isEmpty()) {
                    continue;
                }
                int col = column(e, centre(words.getFirst()));
                int at = anchorAt(t, r, col);
                if (at >= 0) {
                    paras.get(k * (e.length - 1) + at).add(para(words, e, col));
                }
            }
        }
        for (int j = 0; j < bottom.size(); j++) {
            addRow(cells, paras, t.rows() + k + j, bottom.get(j), e);
        }
        float[] edges = new float[t.rows() + k + bottom.size() + 1];
        for (int r = 0; r < k; r++) {
            edges[r] = top.get(r).top() - 1f;
        }
        System.arraycopy(rows, 0, edges, k, rows.length);
        for (int j = 0; j < bottom.size(); j++) {
            edges[k + rows.length + j] = bottom.get(j).bottom() + 1f;
        }
        edges[k] = k > 0 ? Math.min(rows[0], top.getLast().bottom() + 1f) : rows[0];
        int header = k > 0 ? t.headerRows() + k : t.headerRows();
        TableDetection.Found grown = new TableDetection.Found(edges.length - 1, t.cols(), e, t.ruled(), header, t.padding(),
                cells, t.left(), Math.min(t.top(), edges[0]), t.right(), Math.max(t.bottom(), edges[edges.length - 1]));
        return new PageLayout.TableItem(grown, paras, edges, item.pad());
    }

    private static void addRow(List<TableDetection.FoundCell> cells, List<List<ParaDraft>> paras, int r, Band b, float[] e) {
        List<List<Word>> byCol = byColumn(b, e);
        for (int c = 0; c + 1 < e.length; c++) {
            cells.add(new TableDetection.FoundCell(r, c, 1, 1, e[c], b.top(), e[c + 1], b.bottom(), false, false, false,
                    false, null, TableDetection.HAlign.GENERAL, TableDetection.VAlign.TOP, ""));
            List<ParaDraft> p = new ArrayList<>();
            if (!byCol.get(c).isEmpty()) {
                p.add(para(byCol.get(c), e, c));
            }
            paras.add(p);
        }
    }

    private static ParaDraft para(List<Word> words, float[] e, int col) {
        List<Word> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble(w -> w.x));
        ParaDraft p = new ParaDraft(e[col], e[Math.min(col + 1, e.length - 1)]);
        p.lines.add(new Line(sorted, new byte[sorted.size()]));
        return p;
    }

    private static List<List<Word>> byColumn(Band b, float[] e) {
        List<List<Word>> out = new ArrayList<>();
        for (int c = 0; c + 1 < e.length; c++) {
            out.add(new ArrayList<>());
        }
        for (Line l : b.lines()) {
            for (Word w : l.words) {
                out.get(column(e, centre(w))).add(w);
            }
        }
        return out;
    }

    private static boolean tabular(Band b) {
        if (b.lines().size() > 1) {
            return true;
        }
        Line l = b.lines().getFirst();
        for (int i = 1; i < l.gaps.length; i++) {
            if (l.gaps[i] != Line.SPACE) {
                return true;
            }
        }
        return false;
    }

    private static int columns(Band b, float[] e) {
        int n = 0;
        for (List<Word> words : byColumn(b, e)) {
            n += words.isEmpty() ? 0 : 1;
        }
        return n;
    }

    private static List<Band> bands(List<Line> lines) {
        List<Line> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingDouble(l -> l.baseline));
        List<Band> out = new ArrayList<>();
        List<Line> cur = new ArrayList<>();
        for (Line l : sorted) {
            if (!cur.isEmpty() && Math.abs(l.baseline - cur.getLast().baseline) > 2f) {
                out.add(band(cur));
                cur = new ArrayList<>();
            }
            cur.add(l);
        }
        if (!cur.isEmpty()) {
            out.add(band(cur));
        }
        return out;
    }

    private static Band band(List<Line> lines) {
        float top = Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        for (Line l : lines) {
            top = Math.min(top, l.top);
            bottom = Math.max(bottom, l.bottom);
        }
        return new Band(List.copyOf(lines), top, bottom);
    }

    private static float rowHeight(float[] rows) {
        float[] h = new float[rows.length - 1];
        for (int i = 0; i + 1 < rows.length; i++) {
            h[i] = rows[i + 1] - rows[i];
        }
        Arrays.sort(h);
        return h.length == 0 ? 14f : Math.max(8f, h[h.length / 2]);
    }

    private static int rowAt(float[] rows, float y) {
        for (int r = 0; r + 1 < rows.length; r++) {
            if (y < rows[r + 1]) {
                return r;
            }
        }
        return rows.length - 2;
    }

    private static int column(float[] e, float x) {
        for (int c = 0; c + 2 < e.length; c++) {
            if (x < e[c + 1]) {
                return c;
            }
        }
        return e.length - 2;
    }

    private static int anchorAt(TableDetection.Found t, int r, int c) {
        for (int i = 0; i < t.cells().size(); i++) {
            TableDetection.FoundCell x = t.cells().get(i);
            if (r >= x.row() && r < x.row() + x.rowSpan() && c >= x.col() && c < x.col() + x.colSpan()) {
                return i;
            }
        }
        return -1;
    }

    private static float centre(Word w) {
        return (w.x + w.right) / 2f;
    }
}

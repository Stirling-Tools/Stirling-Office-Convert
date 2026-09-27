package stirling.software.officeconvert.table;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import stirling.software.officeconvert.table.PageContent.PdfWord;
import stirling.software.officeconvert.table.PageContent.Ruling;

final class LatticeFinder {

    private static final float JOIN_TOLERANCE = 2.5f;

    private static final float MIN_BAND = 4.5f;

    private static final int MAX_RULINGS = 4000;

    private static final float MIN_BORDER_LENGTH = 6f;

    private static final int MAX_GRID_CELLS = 20_000;

    private static final int PARAGRAPH_WORDS = 40;

    private static final float LABEL_COLUMN = 0.3f;
    private static final int LABEL_WORDS = 6;

    private LatticeFinder() {}

    record Found(DraftTable table, Box region) {}

    static List<Found> find(
            int page, List<Ruling> horizontals, List<Ruling> verticals, List<PdfWord> words) {
        List<Ruling> hs = longEnough(horizontals);
        List<Ruling> vs = longEnough(verticals);
        if (hs.size() < 2 || vs.size() < 2 || hs.size() > MAX_RULINGS || vs.size() > MAX_RULINGS) {
            return List.of();
        }
        List<Found> found = new ArrayList<>();
        for (List<List<Ruling>> component : connected(hs, vs)) {
            List<Ruling> componentHs = component.get(0);
            List<Ruling> componentVs = component.get(1);
            if (componentHs.size() >= 2 && componentVs.size() >= 2) {
                Found f = build(page, componentHs, componentVs, words);
                if (f != null) {
                    found.add(f);
                }
            }
        }
        found.sort((a, b) -> Float.compare(a.region().top(), b.region().top()));
        return found;
    }

    private static List<Ruling> longEnough(List<Ruling> rulings) {
        List<Ruling> kept = new ArrayList<>(rulings.size());
        for (Ruling r : rulings) {
            if (r.length() >= MIN_BORDER_LENGTH) {
                kept.add(r);
            }
        }
        return kept;
    }

    private static List<List<List<Ruling>>> connected(List<Ruling> hs, List<Ruling> vs) {
        UnionFind groups = new UnionFind(hs.size() + vs.size());
        for (int i = 0; i < hs.size(); i++) {
            Ruling h = hs.get(i);
            for (int j = 0; j < vs.size(); j++) {
                Ruling v = vs.get(j);
                if (v.pos() >= h.start() - JOIN_TOLERANCE
                        && v.pos() <= h.end() + JOIN_TOLERANCE
                        && h.pos() >= v.start() - JOIN_TOLERANCE
                        && h.pos() <= v.end() + JOIN_TOLERANCE) {
                    groups.union(i, hs.size() + j);
                }
            }
        }
        Map<Integer, List<List<Ruling>>> byRoot = new TreeMap<>();
        for (int i = 0; i < hs.size() + vs.size(); i++) {
            boolean horizontal = i < hs.size();
            Ruling r = horizontal ? hs.get(i) : vs.get(i - hs.size());
            byRoot.computeIfAbsent(
                            groups.find(i), k -> List.of(new ArrayList<>(), new ArrayList<>()))
                    .get(horizontal ? 0 : 1)
                    .add(r);
        }
        return new ArrayList<>(byRoot.values());
    }

    private static Found build(int page, List<Ruling> hs, List<Ruling> vs, List<PdfWord> words) {
        float[][] edges = gridEdges(hs, vs);
        float[] xs = edges[0];
        float[] ys = edges[1];
        if ((long) xs.length * ys.length > MAX_GRID_CELLS * 4L) {
            return null;
        }
        List<PdfWord> inside = new ArrayList<>();
        Box outline = new Box(xs[0], ys[0], xs[xs.length - 1], ys[ys.length - 1]);
        for (PdfWord w : words) {
            float cx = w.centreX();
            float cy = w.centreY();
            if (cx >= outline.left()
                    && cx <= outline.right()
                    && cy >= outline.top()
                    && cy <= outline.bottom()) {
                inside.add(w);
            }
        }
        xs = dropThinBands(xs, inside, true);
        ys = dropThinBands(ys, inside, false);
        if (xs.length >= 3) {
            xs = addTextGutters(xs, inside);
        }
        if (inside.isEmpty()) {
            return null;
        }
        Box region = new Box(xs[0], ys[0], xs[xs.length - 1], ys[ys.length - 1]);
        if (xs.length < 3) {
            return new Found(null, region);
        }
        if ((long) (xs.length - 1) * (ys.length - 1) > MAX_GRID_CELLS) {
            return null;
        }
        RuledGrid grid = RuledGrid.measure(xs, ys, hs, vs);
        grid.addTextColumnLines(inside);
        DraftTable table = cells(page, grid, inside);
        if (!looksLikeTable(table) || proseOutside(table, grid)) {
            return null;
        }
        return new Found(RuledBandSplitter.split(table), region);
    }

    private static float[][] gridEdges(List<Ruling> hs, List<Ruling> vs) {
        float left = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        List<Float> xPositions = new ArrayList<>();
        List<Float> yPositions = new ArrayList<>();
        for (Ruling v : vs) {
            xPositions.add(v.pos());
            top = Math.min(top, v.start());
            bottom = Math.max(bottom, v.end());
        }
        for (Ruling h : hs) {
            yPositions.add(h.pos());
            left = Math.min(left, h.start());
            right = Math.max(right, h.end());
        }
        xPositions.add(left);
        xPositions.add(right);
        yPositions.add(top);
        yPositions.add(bottom);
        return new float[][] {
            Rulings.cluster(xPositions, RuledGrid.LINE_TOLERANCE),
            Rulings.cluster(yPositions, RuledGrid.LINE_TOLERANCE)
        };
    }

    private static DraftTable cells(int page, RuledGrid grid, List<PdfWord> words) {
        int rows = grid.rows();
        int cols = grid.cols();
        int[][] anchor = grid.anchors();
        List<List<PdfWord>> wordsByAnchor = new ArrayList<>(rows * cols);
        for (int i = 0; i < rows * cols; i++) {
            wordsByAnchor.add(new ArrayList<>());
        }
        for (PdfWord w : words) {
            int r = grid.rowAt(w.centreY());
            for (PdfWord piece : splitAtBorders(w, grid.xs, grid.verticalsInRow(r))) {
                wordsByAnchor.get(anchor[r][grid.colAt(piece.centreX())]).add(piece);
            }
        }
        List<DraftTable.Cell> cells = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int a = anchor[r][c];
                if (a != r * cols + c) {
                    continue;
                }
                int r1 = r;
                int c1 = c;
                while (r1 + 1 < rows && anchor[r1 + 1][c] == a) {
                    r1++;
                }
                while (c1 + 1 < cols && anchor[r][c1 + 1] == a) {
                    c1++;
                }
                Box box = new Box(grid.xs[c], grid.ys[r], grid.xs[c1 + 1], grid.ys[r1 + 1]);
                cells.add(
                        new DraftTable.Cell(
                                r,
                                c,
                                r1 - r + 1,
                                c1 - c + 1,
                                box,
                                box.width(),
                                grid.borders(r, c, r1, c1),
                                wordsByAnchor.get(a)));
            }
        }
        return new DraftTable(page, rows, cols, grid.xs, true, cells, 0);
    }

    private static List<PdfWord> splitAtBorders(PdfWord w, float[] xs, boolean[] drawn) {
        List<PdfWord> pieces = new ArrayList<>();
        int from = 0;
        int n = w.glyphLeft().length;
        for (int c = 1; c < xs.length - 1; c++) {
            if (!drawn[c] || xs[c] <= w.x() + 0.5f || xs[c] >= w.right() - 0.5f) {
                continue;
            }
            int cut = from;
            while (cut < n && (w.glyphLeft()[cut] + w.glyphRight()[cut]) / 2f < xs[c]) {
                cut++;
            }
            PdfWord piece = w.slice(from, cut);
            if (piece != null) {
                pieces.add(piece);
            }
            from = cut;
        }
        if (from == 0) {
            return List.of(w);
        }
        PdfWord rest = w.slice(from, n);
        if (rest != null) {
            pieces.add(rest);
        }
        return pieces;
    }

    private static boolean proseOutside(DraftTable table, RuledGrid grid) {
        for (int[] slot : grid.openOutline()) {
            for (DraftTable.Cell cell : table.cells()) {
                boolean covers = slot[0] >= cell.row() && slot[0] < cell.row() + cell.rowSpan()
                        && slot[1] >= cell.col() && slot[1] < cell.col() + cell.colSpan();
                if (covers && running(cell.words())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean running(List<PdfWord> words) {
        java.util.Set<Integer> lines = new java.util.HashSet<>();
        int real = 0;
        for (PdfWord w : words) {
            if (w.text().codePoints().filter(Character::isLetter).count() >= 2) {
                real++;
                lines.add(Math.round(w.centreY() / 2f));
            }
        }
        return real > PARAGRAPH_WORDS && real >= PROSE_LINE_WORDS * lines.size();
    }

    private static final int PROSE_LINE_WORDS = 6;

    private static boolean looksLikeTable(DraftTable table) {
        int filled = 0;
        int paragraphs = 0;
        for (DraftTable.Cell cell : table.cells()) {
            if (!cell.words().isEmpty()) {
                filled++;
                if (cell.words().size() > PARAGRAPH_WORDS) {
                    paragraphs++;
                }
            }
        }
        boolean layoutBoxes = filled > 0 && paragraphs * 2 > filled && !labelColumn(table);
        return filled >= 2
                && table.cells().size() >= 2
                && !layoutBoxes
                && !looksLikeChart(table, filled);
    }

    private static boolean labelColumn(DraftTable table) {
        float[] e = table.colEdges();
        float width = e[e.length - 1] - e[0];
        for (int c = 0; c + 1 < e.length; c++) {
            if (e[c + 1] - e[c] > LABEL_COLUMN * width) {
                continue;
            }
            int labels = 0;
            boolean shortOnly = true;
            for (DraftTable.Cell cell : table.cells()) {
                if (cell.col() == c && cell.colSpan() == 1 && !cell.words().isEmpty()) {
                    labels++;
                    shortOnly &= cell.words().size() <= LABEL_WORDS;
                }
            }
            if (labels > 0 && shortOnly) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeChart(DraftTable table, int filled) {
        int anchors = table.cells().size();
        if (anchors < 12 || filled * 100 >= anchors * 35) {
            return false;
        }
        boolean[] mergedRow = new boolean[table.rows()];
        for (DraftTable.Cell cell : table.cells()) {
            if (cell.colSpan() > 1 && cell.colSpan() < table.cols()) {
                mergedRow[cell.row()] = true;
            }
        }
        int merged = 0;
        for (boolean m : mergedRow) {
            if (m) {
                merged++;
            }
        }
        return filled * 5 < anchors || merged * 2 > table.rows();
    }

    private static float[] addTextGutters(float[] xs, List<PdfWord> words) {
        List<List<PdfWord>> columns = new ArrayList<>();
        for (int c = 0; c + 1 < xs.length; c++) {
            columns.add(new ArrayList<>());
        }
        for (PdfWord w : words) {
            columns.get(RuledGrid.band(xs, w.centreX())).add(w);
        }
        List<Float> result = new ArrayList<>();
        for (int c = 0; c + 1 < xs.length; c++) {
            result.add(xs[c]);
            result.addAll(gutterPositions(columns.get(c), xs[c], xs[c + 1]));
        }
        result.add(xs[xs.length - 1]);
        return toArray(result);
    }

    private static List<Float> gutterPositions(List<PdfWord> words, float left, float right) {
        List<PdfWord> text = new ArrayList<>();
        for (PdfWord w : words) {
            if (!ListMarkers.isBullet(w.text())) {
                text.add(w);
            }
        }
        List<ChunkedLine> lines = new ArrayList<>();
        int tabular = 0;
        for (TextLine line : TextLine.group(text)) {
            ChunkedLine chunked = ChunkedLine.of(line);
            lines.add(chunked);
            if (chunked.tabular()) {
                tabular++;
            }
        }
        if (tabular < 3 || tabular * 2 < lines.size()) {
            return List.of();
        }
        TextColumns columns = TextColumns.find(lines);
        float[] edges = columns.edges();
        List<Float> gutters = new ArrayList<>();
        for (int c = 1; c < columns.count(); c++) {
            if (edges[c] > left + 2f && edges[c] < right - 2f) {
                gutters.add(edges[c]);
            }
        }
        return gutters;
    }

    private static float[] dropThinBands(float[] edges, List<PdfWord> words, boolean vertical) {
        float[] centres = new float[words.size()];
        for (int i = 0; i < centres.length; i++) {
            centres[i] = vertical ? words.get(i).centreX() : words.get(i).centreY();
        }
        Arrays.sort(centres);
        List<Float> kept = new ArrayList<>();
        for (float e : edges) {
            kept.add(e);
        }
        int i = 0;
        while (i + 1 < kept.size() && kept.size() > 2) {
            float a = kept.get(i);
            float b = kept.get(i + 1);
            if (b - a >= MIN_BAND || hasCentreBetween(centres, a, b)) {
                i++;
                continue;
            }
            if (i == 0) {
                kept.remove(i + 1);
            } else if (i + 1 == kept.size() - 1) {
                kept.remove(i);
                i--;
            } else {
                kept.set(i, (a + b) / 2f);
                kept.remove(i + 1);
                i--;
            }
            i = Math.max(i, 0);
        }
        return toArray(kept);
    }

    private static boolean hasCentreBetween(float[] sortedCentres, float from, float to) {
        int idx = Arrays.binarySearch(sortedCentres, from);
        idx = idx < 0 ? -idx - 1 : idx;
        while (idx < sortedCentres.length && sortedCentres[idx] <= from) {
            idx++;
        }
        return idx < sortedCentres.length && sortedCentres[idx] < to;
    }

    private static float[] toArray(List<Float> values) {
        float[] out = new float[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }
}

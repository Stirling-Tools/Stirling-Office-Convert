package stirling.software.officeconvert.table;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import stirling.software.officeconvert.table.PageContent.PdfWord;

final class TextColumns {

    private static final float MIN_GUTTER_EM = 0.4f;

    private static final float EDGE_ALIGNMENT = 0.6f;

    private static final float HEADED_GUTTER = 0.75f;

    record Span(float left, float right) {

        float width() {
            return right - left;
        }
    }

    private final List<Span> spans;

    private TextColumns(List<Span> spans) {
        this.spans = spans;
    }

    static TextColumns find(List<ChunkedLine> lines) {
        int tabular = 0;
        List<Span> words = new ArrayList<>();
        List<Float> sizes = new ArrayList<>();
        for (ChunkedLine line : lines) {
            if (!line.tabular()) {
                continue;
            }
            tabular++;
            for (List<PdfWord> chunk : line.chunks()) {
                for (PdfWord w : chunk) {
                    words.add(new Span(w.x(), w.right()));
                    sizes.add(w.fontSize());
                }
            }
        }
        if (words.isEmpty()) {
            return new TextColumns(List.of());
        }
        sizes.sort(Float::compare);
        float minGutter = Math.max(1f, MIN_GUTTER_EM * sizes.get(sizes.size() / 2));
        int allowance = tabular >= 4 ? Math.max(1, tabular / 10) : 0;
        return new TextColumns(sweep(words, allowance, minGutter, lines));
    }

    private static List<Span> sweep(
            List<Span> words, int allowance, float minGutter, List<ChunkedLine> lines) {
        float[][] events = new float[words.size() * 2][];
        float left = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        int e = 0;
        for (Span w : words) {
            events[e++] = new float[] {w.left(), 1};
            events[e++] = new float[] {w.right(), -1};
            left = Math.min(left, w.left());
            right = Math.max(right, w.right());
        }
        Arrays.sort(
                events,
                (a, b) -> a[0] != b[0] ? Float.compare(a[0], b[0]) : Float.compare(b[1], a[1]));

        List<Span> columns = new ArrayList<>();
        int depth = 0;
        float columnStart = left;
        float gutterStart = Float.NaN;
        float emptyStart = Float.NaN;
        Span widestEmpty = null;
        for (float[] event : events) {
            float x = event[0];
            int before = depth;
            depth += (int) event[1];
            if (before > allowance && depth <= allowance && x < right) {
                gutterStart = x;
                widestEmpty = null;
            }
            if (!Float.isNaN(gutterStart)) {
                if (before > 0 && depth == 0) {
                    emptyStart = x;
                } else if (before == 0 && depth > 0 && !Float.isNaN(emptyStart)) {
                    if (widestEmpty == null || x - emptyStart > widestEmpty.width()) {
                        widestEmpty = new Span(emptyStart, x);
                    }
                    emptyStart = Float.NaN;
                }
            }
            if (before <= allowance && depth > allowance && !Float.isNaN(gutterStart)) {
                boolean coreQualifies =
                        widestEmpty != null
                                && (widestEmpty.width() >= minGutter
                                        || alignedBoundary(lines, widestEmpty, minGutter));
                Span gutter = coreQualifies ? widestEmpty : new Span(gutterStart, x);
                if (coreQualifies || gutter.width() >= minGutter) {
                    columns.add(new Span(columnStart, gutter.left()));
                    columnStart = gutter.right();
                }
                gutterStart = Float.NaN;
                widestEmpty = null;
            }
        }
        columns.add(new Span(columnStart, right));
        return columns;
    }

    private static boolean alignedBoundary(List<ChunkedLine> lines, Span gap, float minGutter) {
        if (gap.width() < 1f) {
            return false;
        }
        int ending = 0;
        int starting = 0;
        float minStart = Float.MAX_VALUE;
        float maxStart = -Float.MAX_VALUE;
        for (ChunkedLine line : lines) {
            for (List<PdfWord> chunk : line.chunks()) {
                for (PdfWord w : chunk) {
                    if (Math.abs(w.right() - gap.left()) <= EDGE_ALIGNMENT) {
                        ending++;
                        minStart = Math.min(minStart, w.x());
                        maxStart = Math.max(maxStart, w.x());
                    }
                    if (Math.abs(w.x() - gap.right()) <= EDGE_ALIGNMENT) {
                        starting++;
                    }
                }
            }
        }
        boolean rightAligned = ending >= 3 && maxStart - minStart >= 2f;
        return rightAligned && (starting >= 3 || starting >= 1 && gap.width() >= HEADED_GUTTER * minGutter);
    }

    int count() {
        return spans.size();
    }

    float[] edges() {
        int cols = spans.size();
        float[] edges = new float[cols + 1];
        edges[0] = spans.getFirst().left();
        for (int c = 1; c < cols; c++) {
            edges[c] = (spans.get(c - 1).right() + spans.get(c).left()) / 2f;
        }
        edges[cols] = spans.getLast().right();
        return edges;
    }

    int indexOf(float x) {
        for (int c = 0; c < spans.size(); c++) {
            Span span = spans.get(c);
            if (x < span.right() + 0.5f) {
                if (c > 0 && x < span.left()) {
                    return span.left() - x < x - spans.get(c - 1).right() ? c : c - 1;
                }
                return c;
            }
        }
        return spans.size() - 1;
    }

    CellLine assign(ChunkedLine line) {
        List<List<PdfWord>> cells = new ArrayList<>();
        for (int c = 0; c < spans.size(); c++) {
            cells.add(new ArrayList<>());
        }
        for (List<PdfWord> chunk : line.chunks()) {
            int current = indexOf(chunk.getFirst().x());
            cells.get(current).add(chunk.getFirst());
            for (int k = 1; k < chunk.size(); k++) {
                PdfWord prev = chunk.get(k - 1);
                PdfWord w = chunk.get(k);
                int next = indexOf(w.x());
                if (next > current && gapInGutter(prev.right(), w.x(), current)) {
                    current = next;
                }
                cells.get(current).add(w);
            }
        }
        return new CellLine(line.line(), cells);
    }

    private boolean gapInGutter(float gapStart, float gapEnd, int col) {
        if (col + 1 >= spans.size()) {
            return false;
        }
        float gutterStart = spans.get(col).right();
        float gutterEnd = spans.get(col + 1).left();
        return gapStart <= gutterEnd + 0.5f && gapEnd >= gutterStart - 0.5f;
    }
}

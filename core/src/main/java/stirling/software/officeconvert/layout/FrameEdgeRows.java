package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class FrameEdgeRows {

    private static final float MIN_EDGE_GAP = 0.8f;

    private FrameEdgeRows() {}

    static List<Line> above(Box frame, float[] edges, List<Line> segments) {
        return nearest(frame, edges, segments, true);
    }

    static List<Line> below(Box frame, float[] edges, List<Line> segments) {
        List<Line> row = nearest(frame, edges, segments, false);
        if (row.isEmpty()) {
            return row;
        }
        float bottom = row.stream().map(l -> l.bottom).max(Float::compare).orElseThrow();
        Box past = new Box(frame.x(), frame.top(), frame.right(), bottom);
        return nearest(past, edges, segments, false).isEmpty() ? row : List.of();
    }

    private static final int MAX_HEADER_ROWS = 4;

    static List<Line> headerAbove(List<Line> inside, float tableTop, float[] edges) {
        List<List<Line>> rows = new ArrayList<>();
        List<Line> sorted = new ArrayList<>(inside);
        sorted.sort(Comparator.comparingDouble((Line l) -> -l.baseline));
        for (Line l : sorted) {
            if (!rows.isEmpty() && Math.abs(rows.getLast().getFirst().baseline - l.baseline) < 1.5f) {
                rows.getLast().add(l);
            } else {
                rows.add(new ArrayList<>(List.of(l)));
            }
        }
        List<Line> out = new ArrayList<>();
        float below = tableTop;
        for (List<Line> row : rows) {
            float top = row.stream().map(l -> l.top).min(Float::compare).orElseThrow();
            float bottom = row.stream().map(l -> l.bottom).max(Float::compare).orElseThrow();
            float size = row.getFirst().size;
            boolean close = below - bottom < (out.isEmpty() ? 2.5f : 1.8f) * size;
            boolean wraps = !out.isEmpty() && below - bottom < 0.6f * size && !straddles(row, edges);
            if (!close || out.size() >= MAX_HEADER_ROWS || !(fits(row, edges) || wraps)) {
                break;
            }
            out.addAll(row);
            below = top;
        }
        return out;
    }

    private static boolean straddles(List<Line> row, float[] edges) {
        for (Line l : row) {
            for (Word w : l.words) {
                for (int e = 1; e < edges.length - 1; e++) {
                    if (w.x < edges[e] - 1 && w.right > edges[e] + 1) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<Line> nearest(Box frame, float[] edges, List<Line> segments, boolean above) {
        Line closest = null;
        for (Line l : segments) {
            boolean outside = above ? l.bottom <= frame.top() + 1 && l.bottom > frame.top() - 2 * l.size
                    : l.top >= frame.bottom() - 1 && l.top < frame.bottom() + 2 * l.size;
            if (outside && l.x >= frame.x() - 1 && l.right <= frame.right() + 1
                    && (closest == null || (above ? l.bottom > closest.bottom : l.top < closest.top))) {
                closest = l;
            }
        }
        if (closest == null) {
            return List.of();
        }
        List<Line> row = new ArrayList<>();
        for (Line l : segments) {
            if (Math.abs(l.baseline - closest.baseline) < 1.5f) {
                if (l.x < frame.x() - 1 || l.right > frame.right() + 1) {
                    return List.of();
                }
                row.add(l);
            }
        }
        return fits(row, edges) ? row : List.of();
    }

    private static boolean fits(List<Line> row, float[] edges) {
        List<Word> words = new ArrayList<>();
        row.forEach(l -> words.addAll(l.words));
        words.sort(Comparator.comparingDouble(w -> w.x));
        int firstColumn = -1;
        boolean several = false;
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            for (int e = 1; e < edges.length - 1; e++) {
                if (w.x < edges[e] - 1 && w.right > edges[e] + 1) {
                    return false;
                }
            }
            int col = column((w.x + w.right) / 2f, edges);
            several |= firstColumn >= 0 && col != firstColumn;
            firstColumn = firstColumn < 0 ? col : firstColumn;
            if (i > 0) {
                Word prev = words.get(i - 1);
                boolean acrossEdge = column((prev.x + prev.right) / 2f, edges) != col;
                if (acrossEdge && w.x - prev.right < MIN_EDGE_GAP * w.size()) {
                    return false;
                }
            }
        }
        return several;
    }

    private static int column(float x, float[] edges) {
        int col = 0;
        for (int i = 1; i < edges.length - 1; i++) {
            if (x >= edges[i]) {
                col = i;
            }
        }
        return col;
    }
}

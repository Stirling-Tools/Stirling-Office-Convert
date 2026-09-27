package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import stirling.software.officeconvert.table.TableDetection.FoundCell;
import stirling.software.officeconvert.table.TableDetection;

final class KeyValueTables {

    private static final int MIN_ROWS = 3;
    private static final int MAX_LABEL_WORDS = 6;
    private static final int MAX_LABEL_LINES = 3;

    private KeyValueTables() {}

    private static final class Row {
        final List<Line> label = new ArrayList<>();
        final List<Line> value = new ArrayList<>();
        float top = Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;

        void add(Line l, boolean isLabel) {
            (isLabel ? label : value).add(l);
            top = Math.min(top, l.top);
            bottom = Math.max(bottom, l.bottom);
        }
    }

    static List<TableDetection.Found> find(List<Line> segments, float frameLeft, float frameRight) {
        List<Line> lines = new ArrayList<>(segments);
        lines.sort(Comparator.comparingDouble((Line l) -> Math.round(l.baseline / 2f)).thenComparingDouble(l -> l.x));
        List<TableDetection.Found> out = new ArrayList<>();
        for (float valueX : valueColumns(lines, frameLeft)) {
            for (List<Row> run : runs(lines, valueX)) {
                TableDetection.Found t = accept(run, valueX, frameLeft, frameRight);
                if (t != null && out.stream().noneMatch(o -> overlaps(o, t))) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static List<Float> valueColumns(List<Line> lines, float frameLeft) {
        List<Float> starts = new ArrayList<>();
        for (Line l : lines) {
            if (l.x > frameLeft + 40) {
                starts.add(l.x);
            }
            for (int i = 1; i < l.words.size(); i++) {
                Word w = l.words.get(i);
                if (w.x - l.words.get(i - 1).right > 1.5f * l.size && w.x > frameLeft + 40) {
                    starts.add(w.x);
                }
            }
        }
        starts.sort(Float::compare);
        List<Float> columns = new ArrayList<>();
        int i = 0;
        while (i < starts.size()) {
            int j = i;
            while (j < starts.size() && starts.get(j) - starts.get(i) <= 2f) {
                j++;
            }
            if (j - i >= MIN_ROWS) {
                columns.add(starts.get(i));
            }
            i = j;
        }
        return columns;
    }

    private static List<List<Row>> runs(List<Line> lines, float valueX) {
        List<List<Row>> out = new ArrayList<>();
        List<Row> run = new ArrayList<>();
        Line lastLabel = null;
        float lastBottom = Float.NaN;
        for (Line l : lines) {
            Line left = part(l, valueX, true);
            Line right = part(l, valueX, false);
            boolean offColumn = left == null && right != null && right.x > valueX + 2 * right.size;
            boolean crosses = left == null && right == null || offColumn;
            boolean gap = !Float.isNaN(lastBottom) && l.top - lastBottom > 3f * l.size;
            if (crosses || gap) {
                out.add(run);
                run = new ArrayList<>();
                lastLabel = null;
                lastBottom = Float.NaN;
                if (crosses) {
                    continue;
                }
            }
            if (left != null) {
                boolean wraps = lastLabel != null && !run.isEmpty() && left.top - lastLabel.bottom < 0.6f * left.size
                        && run.getLast().label.size() < MAX_LABEL_LINES;
                if (!wraps) {
                    run.add(new Row());
                }
                run.getLast().add(left, true);
                lastLabel = left;
            }
            if (right != null && run.isEmpty() && left == null) {
                run.add(new Row());
            }
            if (right != null) {
                run.getLast().add(right, false);
            }
            lastBottom = l.bottom;
        }
        out.add(run);
        return out;
    }

    private static Line part(Line l, float valueX, boolean label) {
        int split = 0;
        while (split < l.words.size() && l.words.get(split).x < valueX - 2) {
            if (l.words.get(split).right > valueX - 2) {
                return null;
            }
            split++;
        }
        if (split == 0 && l.x < valueX - 2) {
            return null;
        }
        if (label) {
            return split == 0 ? null : split == l.words.size() ? l : l.slice(0, split);
        }
        return split == l.words.size() ? null : split == 0 ? l : l.slice(split, l.words.size());
    }

    private static TableDetection.Found accept(List<Row> rows, float valueX, float frameLeft, float frameRight) {
        List<Row> run = new ArrayList<>(rows);
        while (!run.isEmpty() && run.getFirst().value.isEmpty()) {
            run.removeFirst();
        }
        while (!run.isEmpty() && run.getLast().value.isEmpty()) {
            run.removeLast();
        }
        if (run.size() < MIN_ROWS) {
            return null;
        }
        float labelLeft = Float.MAX_VALUE;
        float labelRight = -Float.MAX_VALUE;
        float valueRight = valueX;
        int bold = 0;
        int colons = 0;
        int markers = 0;
        int labelLines = 0;
        int withValue = 0;
        for (Row r : run) {
            if (r.label.isEmpty() && r != run.getFirst()) {
                return null;
            }
            withValue += r.value.isEmpty() ? 0 : 1;
            if (!r.label.isEmpty()) {
                Word first = r.label.getFirst().words.getFirst();
                markers += Marker.parse(first.text, first.first().font) != null ? 1 : 0;
                colons += r.label.getLast().text().strip().endsWith(":") ? 1 : 0;
            }
            for (Line l : r.label) {
                if (l.words.size() > MAX_LABEL_WORDS) {
                    return null;
                }
                labelLeft = Math.min(labelLeft, l.x);
                labelRight = Math.max(labelRight, l.right);
                bold += l.bold ? 1 : 0;
                labelLines++;
            }
            for (Line l : r.value) {
                valueRight = Math.max(valueRight, l.right);
            }
        }
        boolean labelsLookLikeLabels = (bold * 10 >= labelLines * 7 || colons * 10 >= run.size() * 7) && markers * 2 < run.size();
        boolean narrowLabels = labelRight - labelLeft <= 0.45f * (frameRight - labelLeft);
        if (withValue * 10 < run.size() * 9 || !labelsLookLikeLabels || !narrowLabels || valueX - labelRight < 4) {
            return null;
        }
        float[] edges = {labelLeft - 2, valueX - 4, Math.max(frameRight, valueRight + 2)};
        List<FoundCell> cells = new ArrayList<>();
        for (int i = 0; i < run.size(); i++) {
            float top = run.get(i).top - 1;
            float bottom = i + 1 < run.size() ? run.get(i + 1).top - 1 : run.get(i).bottom + 1;
            for (int c = 0; c < 2; c++) {
                cells.add(new FoundCell(i, c, 1, 1, edges[c], top, edges[c + 1], bottom, false, false, false, false,
                        null, TableDetection.HAlign.GENERAL, TableDetection.VAlign.TOP, ""));
            }
        }
        return new TableDetection.Found(run.size(), 2, edges, false, 0, 0f, cells, edges[0], run.getFirst().top - 1,
                edges[2], run.getLast().bottom + 1);
    }

    private static boolean overlaps(TableDetection.Found a, TableDetection.Found b) {
        return a.left() < b.right() && b.left() < a.right() && a.top() < b.bottom() && b.top() < a.bottom();
    }
}

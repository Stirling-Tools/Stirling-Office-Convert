package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import stirling.software.officeconvert.layout.ColumnFinder.Item;

final class GutterSide {

    static final int MIN_ROWS = 3;

    private final List<Item> rows;
    private final Set<Float> baselines;
    private final float edge;
    private final boolean leftOfGutter;

    GutterSide(List<Item> text, Set<Float> baselines, List<Item> pictures, float mid, float edge, boolean leftOfGutter) {
        this.rows = unwrapped(nearest(text, leftOfGutter), pictures, mid, leftOfGutter);
        this.baselines = baselines;
        this.edge = edge;
        this.leftOfGutter = leftOfGutter;
    }

    int nearRows() {
        return rows.size();
    }

    int rowCount() {
        return baselines.size();
    }

    int sharedRows(GutterSide other) {
        int n = 0;
        for (float y : baselines) {
            if (other.baselines.contains(y) || other.baselines.contains(y - 0.5f) || other.baselines.contains(y + 0.5f)) {
                n++;
            }
        }
        return n;
    }

    int sharedRowsWithin(GutterSide other, float tolerance) {
        int n = 0;
        for (float y : baselines) {
            for (float o : other.baselines) {
                if (Math.abs(y - o) <= tolerance) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    float width() {
        return rows.isEmpty() ? 0 : Math.abs(edge - outerEdge(rows, leftOfGutter));
    }

    boolean alongside(GutterSide text) {
        float top = Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        for (Item r : text.rows) {
            top = Math.min(top, r.top());
            bottom = Math.max(bottom, r.bottom());
        }
        int within = 0;
        for (Item r : rows) {
            if (r.baseline() >= top && r.baseline() <= bottom) {
                within++;
            }
        }
        return within * 2 >= rows.size();
    }

    boolean hugs() {
        float[] gaps = new float[rows.size()];
        for (int i = 0; i < gaps.length; i++) {
            Item r = rows.get(i);
            gaps[i] = leftOfGutter ? edge - r.right() : r.x() - edge;
        }
        Arrays.sort(gaps);
        return gaps.length > 0 && gaps[gaps.length / 2] <= 1.5f * medianHeight(rows);
    }

    boolean isTextColumn(float textWidth) {
        if (rows.size() < MIN_ROWS) {
            return false;
        }
        float[] starts = new float[rows.size()];
        for (int i = 0; i < starts.length; i++) {
            starts[i] = rows.get(i).x();
        }
        Arrays.sort(starts);
        float medianStart = starts[starts.length / 2];
        float em = medianHeight(rows);
        List<Item> own = new ArrayList<>();
        for (Item it : rows) {
            boolean adjacent = leftOfGutter
                    ? it.right() > medianStart && it.x() >= medianStart - 3f * em
                    : it.x() <= edge + 3f * em;
            if (adjacent) {
                own.add(it);
            }
        }
        if (own.size() < MIN_ROWS) {
            return false;
        }
        float width = Math.abs(edge - outerEdge(own, leftOfGutter));
        List<Item> kept = withoutWrapEdge(own, width);
        if (width < Math.max(54f, 0.14f * textWidth)) {
            return false;
        }
        int full = 0;
        int prose = 0;
        for (Item it : kept) {
            if (it.right() - it.x() >= 0.6f * width) {
                full++;
            }
            if (it.chars() >= 20) {
                prose++;
            }
        }
        return full >= Math.max(2, Math.round(0.4f * kept.size())) && prose * 2 >= kept.size();
    }

    private List<Item> withoutWrapEdge(List<Item> own, float width) {
        List<Item> out = new ArrayList<>();
        for (Item r : own) {
            float inner = leftOfGutter ? r.right() : r.x();
            int shared = 0;
            for (Item o : own) {
                if (Math.abs((leftOfGutter ? o.right() : o.x()) - inner) <= 1.5f) {
                    shared++;
                }
            }
            if (Math.abs(edge - inner) < 0.4f * width || shared < 3) {
                out.add(r);
            }
        }
        return out.size() >= MIN_ROWS ? out : own;
    }

    private static List<Item> nearest(List<Item> side, boolean leftOfGutter) {
        List<Item> sorted = new ArrayList<>(side);
        sorted.sort(Comparator.comparingDouble(Item::baseline));
        List<Item> out = new ArrayList<>();
        int i = 0;
        while (i < sorted.size()) {
            int j = i;
            Item best = sorted.get(i);
            while (j < sorted.size() && Math.abs(sorted.get(j).baseline() - sorted.get(i).baseline()) < 1f) {
                Item it = sorted.get(j);
                if (leftOfGutter ? it.right() > best.right() : it.x() < best.x()) {
                    best = it;
                }
                j++;
            }
            out.add(withNeighbours(best, sorted.subList(i, j), leftOfGutter));
            i = j;
        }
        return out;
    }

    private static Item withNeighbours(Item best, List<Item> row, boolean leftOfGutter) {
        float reach = 3f * (best.bottom() - best.top());
        Item out = best;
        for (Item it : row) {
            boolean near = leftOfGutter ? it.right() >= best.right() - reach : it.x() <= best.x() + reach;
            if (it != best && it.text() && near) {
                out = new Item(Math.min(out.x(), it.x()), Math.min(out.top(), it.top()), Math.max(out.right(), it.right()),
                        Math.max(out.bottom(), it.bottom()), out.baseline(), true, out.chars() + it.chars());
            }
        }
        return out;
    }

    private static List<Item> unwrapped(List<Item> rows, List<Item> pictures, float gutter, boolean leftOfGutter) {
        List<Item> out = new ArrayList<>();
        for (Item r : rows) {
            boolean wrapped = false;
            for (Item p : pictures) {
                boolean beside = leftOfGutter
                        ? p.x() >= r.right() - 1 && p.right() <= gutter
                        : p.right() <= r.x() + 1 && p.x() >= gutter;
                wrapped |= beside && p.top() < r.bottom() && p.bottom() > r.top();
            }
            if (!wrapped) {
                out.add(r);
            }
        }
        return out;
    }

    private static float outerEdge(List<Item> rows, boolean leftOfGutter) {
        float[] edges = new float[rows.size()];
        for (int i = 0; i < edges.length; i++) {
            edges[i] = leftOfGutter ? rows.get(i).x() : rows.get(i).right();
        }
        Arrays.sort(edges);
        int at = Math.round(0.2f * (edges.length - 1));
        return leftOfGutter ? edges[at] : edges[edges.length - 1 - at];
    }

    private static float medianHeight(List<Item> rows) {
        float[] h = new float[rows.size()];
        for (int i = 0; i < h.length; i++) {
            h[i] = rows.get(i).bottom() - rows.get(i).top();
        }
        Arrays.sort(h);
        return h[h.length / 2];
    }
}

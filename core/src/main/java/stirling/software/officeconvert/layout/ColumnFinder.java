package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class ColumnFinder {

    public record Band(float top, float bottom, List<float[]> columns) {

        public boolean multiColumn() {
            return columns.size() > 1;
        }

        public int columnAt(float x) {
            int best = 0;
            float bestD = Float.MAX_VALUE;
            for (int i = 0; i < columns.size(); i++) {
                float[] c = columns.get(i);
                float d = x < c[0] ? c[0] - x : x > c[1] ? x - c[1] : 0;
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            return best;
        }
    }

    public record Item(float x, float top, float right, float bottom, float baseline, boolean text, int chars,
            boolean picture) {

        public Item(float x, float top, float right, float bottom, float baseline, boolean text, int chars) {
            this(x, top, right, bottom, baseline, text, chars, false);
        }

        public Item(float x, float top, float right, float bottom, float baseline, boolean text) {
            this(x, top, right, bottom, baseline, text, 0, false);
        }
    }

    private record Gutter(float left, float right, float top, float bottom) {}

    private static final int LIST_ROWS = 8;

    private ColumnFinder() {}

    public static List<Band> find(
            List<Item> items, float textLeft, float textRight, float pageHeight, float bodySize) {
        List<Gutter> gutters = gutters(items, textLeft, textRight, bodySize);
        return bands(gutters, textLeft, textRight, pageHeight);
    }

    private static List<Gutter> gutters(
            List<Item> items, float textLeft, float textRight, float bodySize) {
        float minGutter = Math.max(0.75f * bodySize, 5f);
        List<Item> texts = new ArrayList<>();
        for (Item it : items) {
            if (it.text()) {
                texts.add(it);
            }
        }
        texts.sort(Comparator.comparingDouble(Item::x));
        List<float[]> seeds = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            Item a = texts.get(i);
            Item nearest = null;
            for (int j = 0; j < texts.size(); j++) {
                Item b = texts.get(j);
                if (b.x() - a.right() < minGutter
                        || b.top() >= a.bottom() || a.top() >= b.bottom()) {
                    continue;
                }
                if (nearest == null || b.x() < nearest.x()) {
                    nearest = b;
                }
            }
            if (nearest != null) {
                float y = (Math.max(a.top(), nearest.top()) + Math.min(a.bottom(), nearest.bottom())) / 2f;
                seeds.add(new float[] {a.right(), nearest.x(), y});
            }
        }
        List<Gutter> accepted = new ArrayList<>();
        float textWidth = textRight - textLeft;
        for (float[] seed : seeds) {
            Gutter g = grow(seed, items, minGutter, textWidth);
            if (g == null) {
                continue;
            }
            for (int pass = 0; pass < 2; pass++) {
                Gutter again = grow(new float[] {g.left(), g.right(), seed[2]}, items, minGutter, textWidth);
                if (again == null || again.equals(g)) {
                    break;
                }
                g = again;
            }
            for (float[] edge : new float[][] {{g.right() - minGutter, g.right()}, {g.left(), g.left() + minGutter}}) {
                Gutter along = edge[1] - edge[0] >= minGutter ? grow(new float[] {edge[0], edge[1], seed[2]}, items, minGutter, textWidth) : null;
                if (along != null && along.bottom() - along.top() > g.bottom() - g.top()) {
                    g = along;
                }
            }
            boolean dup = false;
            for (int i = 0; i < accepted.size(); i++) {
                Gutter o = accepted.get(i);
                boolean xOverlap = Math.min(o.right(), g.right()) - Math.max(o.left(), g.left()) > 0;
                boolean yOverlap = Math.min(o.bottom(), g.bottom()) - Math.max(o.top(), g.top()) > 0;
                if (xOverlap && yOverlap) {
                    if (g.bottom() - g.top() > o.bottom() - o.top()) {
                        accepted.remove(i--);
                    } else {
                        dup = true;
                        break;
                    }
                }
            }
            if (!dup) {
                accepted.add(g);
            }
        }
        return accepted;
    }

    private static Gutter grow(float[] seed, List<Item> items, float minGutter, float textWidth) {
        float a = seed[0];
        float b = seed[1];
        float y = seed[2];
        float mid = (a + b) / 2f;
        float core = Math.min((b - a) / 2f, minGutter / 2f);
        float top = -Float.MAX_VALUE;
        float bottom = Float.MAX_VALUE;
        for (Item it : items) {
            boolean blocks = it.x() < mid + core && it.right() > mid - core;
            if (!blocks) {
                continue;
            }
            float cy = it.text() ? it.baseline() : (it.top() + it.bottom()) / 2f;
            if (it.bottom() < y - 0.5f && cy < y) {
                top = Math.max(top, it.bottom());
            } else if (it.top() > y + 0.5f || cy > y) {
                bottom = Math.min(bottom, it.top());
            } else {
                return null;
            }
        }
        float left = -Float.MAX_VALUE;
        float right = Float.MAX_VALUE;
        Set<Float> leftRows = new HashSet<>();
        Set<Float> rightRows = new HashSet<>();
        List<Item> leftSide = new ArrayList<>();
        List<Item> rightSide = new ArrayList<>();
        List<Item> pictures = new ArrayList<>();
        for (Item it : items) {
            float cy = (it.top() + it.bottom()) / 2f;
            if (cy <= top || cy >= bottom) {
                continue;
            }
            if (!it.text()) {
                pictures.add(it);
            }
            if (it.right() <= mid) {
                left = Math.max(left, it.right());
                if (it.text()) {
                    leftRows.add(Math.round(it.baseline() * 2f) / 2f);
                    leftSide.add(it);
                }
            } else if (it.x() >= mid) {
                right = Math.min(right, it.x());
                if (it.text()) {
                    rightRows.add(Math.round(it.baseline() * 2f) / 2f);
                    rightSide.add(it);
                }
            }
        }
        if (right - left < minGutter
                || leftRows.size() < GutterSide.MIN_ROWS
                || rightRows.size() < GutterSide.MIN_ROWS) {
            return null;
        }
        GutterSide leftText = new GutterSide(leftSide, leftRows, pictures, mid, left, true);
        GutterSide rightText = new GutterSide(rightSide, rightRows, pictures, mid, right, false);
        if (!accepts(leftText, rightText, textWidth)) {
            return null;
        }
        float t = Float.MAX_VALUE;
        float bt = -Float.MAX_VALUE;
        for (Item it : leftSide) {
            t = Math.min(t, it.top());
            bt = Math.max(bt, it.bottom());
        }
        for (Item it : rightSide) {
            t = Math.min(t, it.top());
            bt = Math.max(bt, it.bottom());
        }
        for (Item it : pictures) {
            if (it.picture()) {
                t = Math.min(t, Math.max(it.top(), top));
                bt = Math.max(bt, Math.min(it.bottom(), bottom));
            }
        }
        return new Gutter(left, right, t, bt);
    }

    private static boolean accepts(GutterSide left, GutterSide right, float textWidth) {
        boolean leftText = left.isTextColumn(textWidth);
        boolean rightText = right.isTextColumn(textWidth);
        if (leftText == rightText) {
            return leftText || twoLists(left, right, textWidth);
        }
        GutterSide text = leftText ? left : right;
        GutterSide other = leftText ? right : left;
        return text.nearRows() >= GutterSide.MIN_ROWS + 2
                && left.sharedRows(right) * 5 <= Math.min(left.rowCount(), right.rowCount())
                && other.width() >= 0.2f * textWidth
                && text.hugs()
                && other.alongside(text);
    }

    private static boolean twoLists(GutterSide left, GutterSide right, float textWidth) {
        int shared = left.sharedRowsWithin(right, 2.5f);
        return left.rowCount() >= LIST_ROWS && right.rowCount() >= LIST_ROWS && right.hugs()
                && (left.rowCount() - shared) * 4 >= left.rowCount()
                && (right.rowCount() - shared) * 4 >= right.rowCount()
                && left.width() >= 0.2f * textWidth && right.width() >= 0.2f * textWidth;
    }

    private static List<Band> bands(
            List<Gutter> gutters, float textLeft, float textRight, float pageHeight) {
        List<Band> out = new ArrayList<>();
        if (gutters.isEmpty()) {
            out.add(new Band(0, pageHeight, List.of(new float[] {textLeft, textRight})));
            return out;
        }
        TreeSet<Float> cuts = new TreeSet<>();
        cuts.add(0f);
        cuts.add(pageHeight);
        for (Gutter g : gutters) {
            cuts.add(Math.max(0f, g.top()));
            cuts.add(Math.min(pageHeight, g.bottom()));
        }
        Float[] ys = cuts.toArray(new Float[0]);
        List<Gutter> prevActive = null;
        float bandTop = 0;
        for (int i = 0; i + 1 < ys.length; i++) {
            float mid = (ys[i] + ys[i + 1]) / 2f;
            List<Gutter> active = new ArrayList<>();
            for (Gutter g : gutters) {
                if (g.top() <= mid && g.bottom() >= mid) {
                    active.add(g);
                }
            }
            active.sort(Comparator.comparingDouble(Gutter::left));
            if (prevActive != null && !sameSet(prevActive, active)) {
                out.add(band(bandTop, ys[i], prevActive, textLeft, textRight));
                bandTop = ys[i];
            }
            prevActive = active;
        }
        out.add(band(bandTop, pageHeight, prevActive, textLeft, textRight));
        return merge(out);
    }

    private static boolean sameSet(List<Gutter> a, List<Gutter> b) {
        return a.size() == b.size() && a.containsAll(b);
    }

    private static Band band(float top, float bottom, List<Gutter> active, float l, float r) {
        List<float[]> cols = new ArrayList<>();
        float x = l;
        for (Gutter g : active) {
            if (g.left() <= x || g.right() >= r) {
                continue;
            }
            cols.add(new float[] {x, g.left()});
            x = g.right();
        }
        cols.add(new float[] {x, r});
        return new Band(top, bottom, cols);
    }

    private static List<Band> merge(List<Band> bands) {
        List<Band> out = new ArrayList<>();
        for (Band b : bands) {
            if (!out.isEmpty()) {
                Band last = out.getLast();
                if (sameColumns(last, b)) {
                    out.set(out.size() - 1, new Band(last.top(), b.bottom(), last.columns()));
                    continue;
                }
            }
            out.add(b);
        }
        return out;
    }

    private static boolean sameColumns(Band a, Band b) {
        if (a.columns().size() != b.columns().size()) {
            return false;
        }
        for (int i = 0; i < a.columns().size(); i++) {
            float[] x = a.columns().get(i);
            float[] y = b.columns().get(i);
            if (Math.abs(x[0] - y[0]) > 3f || Math.abs(x[1] - y[1]) > 3f) {
                return false;
            }
        }
        return true;
    }
}

package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import stirling.software.officeconvert.extract.Glyph;

final class GlyphRows {

    private static final float ROW_TOLERANCE = 0.3f;

    private static final float ARABIC_TOLERANCE = 0.62f;

    private static final float SATELLITE_REACH = 0.62f;

    private static final float CASCADE_RISE = 1.1f;

    private static final float CASCADE_DROP = 0.35f;

    private static final int MAIN_MIN = 5;

    private static final int MAIN_SHARE = 4;

    private static final float SATELLITE_MAX_SIZE = 0.86f;

    private GlyphRows() {}

    static List<Row> of(List<Glyph> ink, List<Glyph> spaces) {
        List<Row> rows = cluster(ink);
        mergeSatellites(rows);
        markInlineScripts(rows);
        attachSpaces(rows, spaces);
        return rows;
    }

    static final class Row {
        final List<Glyph> glyphs = new ArrayList<>();
        final List<Glyph> spaces = new ArrayList<>();
        float baseline;
        float size;
        double baseSum;
        boolean arabic;

        void add(Glyph g) {
            glyphs.add(g);
            arabic |= arabic(g);
            baseSum += g.baseline;
            baseline = (float) (baseSum / glyphs.size());
            size = Math.max(size, g.size);
        }

        float maxSize() {
            float m = 0;
            for (Glyph g : glyphs) {
                m = Math.max(m, g.size);
            }
            return m;
        }
    }

    private static final Comparator<Glyph> BY_BASELINE = (a, b) -> Double.compare(a.baseline, b.baseline);

    private static final Comparator<Glyph> BY_X = (a, b) -> {
        int c = Double.compare(a.x, b.x);
        return c != 0 ? c : Integer.compare(a.seq, b.seq);
    };

    private static List<Row> cluster(List<Glyph> ink) {
        Map<Glyph, Float> key = cascadeBaselines(ink);
        List<Glyph> sorted = new ArrayList<>(ink);
        sorted.sort(key.isEmpty() ? BY_BASELINE
                : Comparator.comparingDouble((Glyph g) -> key.getOrDefault(g, g.baseline)));
        List<Row> rows = new ArrayList<>();
        Row row = null;
        for (Glyph g : sorted) {
            if (row != null) {
                float share = row.arabic && arabic(g) ? ARABIC_TOLERANCE : ROW_TOLERANCE;
                float tol = Math.max(0.8f, share * Math.min(g.size, row.size));
                float at = key.isEmpty() ? g.baseline : key.getOrDefault(g, g.baseline);
                if (Math.abs(at - row.baseline) <= tol) {
                    row.add(g);
                    continue;
                }
            }
            row = new Row();
            row.add(g);
            rows.add(row);
        }
        for (Row r : rows) {
            r.glyphs.sort(BY_X);
        }
        return rows;
    }

    private static Map<Glyph, Float> cascadeBaselines(List<Glyph> ink) {
        Map<Glyph, Float> out = new IdentityHashMap<>();
        if (!anyArabic(ink)) {
            return out;
        }
        Map<Integer, TreeMap<Integer, Integer>> bySize = new HashMap<>();
        for (Glyph g : ink) {
            bySize.computeIfAbsent(Math.round(g.size), k -> new TreeMap<>()).merge(Math.round(g.baseline * 2), 1, Integer::sum);
        }
        Map<Integer, List<Float>> mains = new HashMap<>();
        for (Map.Entry<Integer, TreeMap<Integer, Integer>> size : bySize.entrySet()) {
            TreeMap<Integer, Integer> counts = size.getValue();
            int reach = Math.round(2 * CASCADE_RISE * size.getKey());
            List<Float> main = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
                int best = 0;
                for (int c : counts.subMap(e.getKey() - reach, true, e.getKey() + reach, true).values()) {
                    best = Math.max(best, c);
                }
                if (e.getValue() >= MAIN_MIN && e.getValue() * MAIN_SHARE >= best) {
                    main.add(e.getKey() / 2f);
                }
            }
            mains.put(size.getKey(), main);
        }
        for (Glyph g : ink) {
            List<Float> main = mains.get(Math.round(g.size));
            if (!arabic(g) || main == null) {
                continue;
            }
            float near = Float.NaN;
            float below = Float.NaN;
            for (float m : main) {
                if (Math.abs(m - g.baseline) <= ROW_TOLERANCE * g.size) {
                    near = m;
                }
                if (Float.isNaN(below) && m >= g.baseline - CASCADE_DROP * g.size && m - g.baseline <= CASCADE_RISE * g.size) {
                    below = m;
                }
            }
            if (Float.isNaN(near) && !Float.isNaN(below)) {
                out.put(g, below);
            }
        }
        return out;
    }

    private static boolean anyArabic(List<Glyph> glyphs) {
        for (Glyph g : glyphs) {
            if (arabic(g)) {
                return true;
            }
        }
        return false;
    }

    private static boolean arabic(Glyph g) {
        return !g.text.isEmpty() && g.text.charAt(0) >= 0x0600
                && Character.UnicodeScript.of(g.text.codePointAt(0)) == Character.UnicodeScript.ARABIC;
    }

    private static void mergeSatellites(List<Row> rows) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < rows.size(); i++) {
                Row s = rows.get(i);
                Row host = null;
                for (int d = -2; d <= 2; d++) {
                    int j = i + d;
                    if (d == 0 || j < 0 || j >= rows.size()) {
                        continue;
                    }
                    Row m = rows.get(j);
                    if (isSatelliteOf(s, m) && (host == null || m.maxSize() > host.maxSize())) {
                        host = m;
                    }
                }
                if (host != null) {
                    int script = s.baseline < host.baseline ? 1 : -1;
                    for (Glyph g : s.glyphs) {
                        g.vertAlign = script;
                        host.glyphs.add(g);
                    }
                    host.glyphs.sort(BY_X);
                    rows.remove(i);
                    changed = true;
                    break;
                }
            }
        }
    }

    private static boolean isSatelliteOf(Row s, Row m) {
        float mSize = m.maxSize();
        if (s.maxSize() > SATELLITE_MAX_SIZE * mSize
                || Math.abs(s.baseline - m.baseline) > SATELLITE_REACH * mSize
                || s.glyphs.size() > Math.max(12, m.glyphs.size())) {
            return false;
        }
        List<float[]> clusters = new ArrayList<>();
        float gapLimit = 0.5f * mSize;
        float cx = s.glyphs.getFirst().x;
        float cr = s.glyphs.getFirst().right();
        for (int i = 1; i < s.glyphs.size(); i++) {
            Glyph g = s.glyphs.get(i);
            if (g.x - cr > gapLimit) {
                clusters.add(new float[] {cx, cr});
                cx = g.x;
            }
            cr = Math.max(cr, g.right());
        }
        clusters.add(new float[] {cx, cr});
        float reach = 0.35f * mSize;
        for (float[] c : clusters) {
            boolean touches = false;
            for (Glyph g : m.glyphs) {
                float overlap = Math.min(c[1], g.right()) - Math.max(c[0], g.x);
                if (overlap > Math.min(g.width, c[1] - c[0]) * 0.35f) {
                    return false;
                }
                if (g.right() <= c[0] + 0.5f && c[0] - g.right() <= reach
                        || g.x >= c[1] - 0.5f && g.x - c[1] <= reach) {
                    touches = true;
                }
            }
            if (!touches) {
                return false;
            }
        }
        return true;
    }

    private static void markInlineScripts(List<Row> rows) {
        for (Row r : rows) {
            float main = 0;
            for (Glyph g : r.glyphs) {
                if (g.vertAlign == 0) {
                    main = Math.max(main, g.size);
                }
            }
            List<Glyph> gs = r.glyphs;
            for (int i = 0; i < gs.size(); i++) {
                Glyph g = gs.get(i);
                if (g.vertAlign != 0 || g.size >= main * 0.8f || !besideMainText(gs, i, main)) {
                    continue;
                }
                float shift = r.baseline - g.baseline;
                if (shift > main * 0.12f) {
                    g.vertAlign = 1;
                } else if (shift < -main * 0.08f) {
                    g.vertAlign = -1;
                }
            }
        }
    }

    private static boolean besideMainText(List<Glyph> gs, int i, float main) {
        float reach = main * 0.5f;
        int lo = i;
        while (lo > 0 && gs.get(lo - 1).size < main * 0.8f && gs.get(lo).x - gs.get(lo - 1).right() < reach) {
            lo--;
        }
        int hi = i;
        while (hi + 1 < gs.size() && gs.get(hi + 1).size < main * 0.8f && gs.get(hi + 1).x - gs.get(hi).right() < reach) {
            hi++;
        }
        boolean left = lo > 0 && gs.get(lo).x - gs.get(lo - 1).right() < reach;
        boolean right = hi + 1 < gs.size() && gs.get(hi + 1).x - gs.get(hi).right() < reach;
        return left || right;
    }

    private static void attachSpaces(List<Row> rows, List<Glyph> spaces) {
        if (spaces.isEmpty() || rows.isEmpty()) {
            return;
        }
        float[] baselines = new float[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            baselines[i] = rows.get(i).baseline;
        }
        for (Glyph sp : spaces) {
            int idx = Arrays.binarySearch(baselines, sp.baseline);
            if (idx < 0) {
                idx = -idx - 1;
            }
            Row best = null;
            float bestD = Float.MAX_VALUE;
            for (int j = Math.max(0, idx - 2); j <= Math.min(rows.size() - 1, idx + 1); j++) {
                float d = Math.abs(rows.get(j).baseline - sp.baseline);
                if (d < bestD) {
                    bestD = d;
                    best = rows.get(j);
                }
            }
            if (best != null && bestD <= Math.max(1f, 0.4f * best.size)) {
                best.spaces.add(sp);
            }
        }
    }
}

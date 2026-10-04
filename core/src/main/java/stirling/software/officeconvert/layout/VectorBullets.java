package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics.VectorMark;

final class VectorBullets {

    private VectorBullets() {}

    static List<Glyph> synthesize(List<Glyph> glyphs, List<VectorMark> marks, List<Fill> fills, Set<Object> used) {
        List<Glyph> out = new ArrayList<>();
        GlyphIndex index = null;
        for (VectorMark m : marks) {
            if ((m.filled() || m.curved()) && sized(m.x(), m.top(), m.right(), m.bottom())) {
                index = index == null ? new GlyphIndex(glyphs) : index;
                Glyph g = bulletFor(m.x(), m.top(), m.right(), m.bottom(), m.curved() ? "•" : "▪", m.rgb(), index);
                if (g != null) {
                    out.add(g);
                    used.add(m);
                }
            }
        }
        for (Fill f : fills) {
            if (!sized(f.x(), f.top(), f.right(), f.bottom())) {
                continue;
            }
            index = index == null ? new GlyphIndex(glyphs) : index;
            Glyph g = bulletFor(f.x(), f.top(), f.right(), f.bottom(), "▪", f.rgb(), index);
            if (g != null) {
                out.add(g);
                used.add(f);
            }
        }
        return out;
    }

    private static boolean sized(float x, float top, float right, float bottom) {
        float w = right - x;
        float h = bottom - top;
        return !(w < 1.5f || h < 1.5f || w > 9f || h > 9f || w / h > 1.6f || h / w > 1.6f);
    }

    private static Glyph bulletFor(float x, float top, float right, float bottom, String symbol, int rgb, GlyphIndex index) {
        float w = right - x;
        Glyph next = index.textAfter(right, (top + bottom) / 2f);
        if (next == null || w > next.size * 0.8f || index.between(x, next)) {
            return null;
        }
        return new Glyph(symbol, x, w, next.baseline, next.size, next.ascent, next.descent, next.font, rgb,
                next.seq - 1, next.spaceWidth, false, false);
    }

    private static final class GlyphIndex {

        private final List<Glyph> glyphs;
        private final int[] byMid;
        private final float[] mids;
        private final int[] byBaseline;
        private final float[] baselines;
        private final List<Integer> odd = new ArrayList<>();
        private float maxSize;

        GlyphIndex(List<Glyph> all) {
            glyphs = all;
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                Glyph g = all.get(i);
                if (g.isSpace()) {
                    continue;
                }
                if (Float.isNaN(mid(g)) || Float.isNaN(g.size)) {
                    odd.add(i);
                } else {
                    ids.add(i);
                    maxSize = Math.max(maxSize, g.size);
                }
            }
            byMid = ids.stream().sorted(Comparator.comparingDouble(i -> mid(all.get(i)))).mapToInt(Integer::intValue).toArray();
            mids = new float[byMid.length];
            for (int k = 0; k < byMid.length; k++) {
                mids[k] = mid(all.get(byMid[k]));
            }
            byBaseline = ids.stream().sorted(Comparator.comparingDouble(i -> all.get(i).baseline)).mapToInt(Integer::intValue).toArray();
            baselines = new float[byBaseline.length];
            for (int k = 0; k < byBaseline.length; k++) {
                baselines[k] = all.get(byBaseline[k]).baseline;
            }
        }

        private static float mid(Glyph g) {
            return g.baseline - g.size * 0.35f;
        }

        Glyph textAfter(float right, float cy) {
            float reach = maxSize * 0.45f + 1;
            int best = -1;
            for (int k = lowerBound(mids, cy - reach); k < mids.length && mids[k] <= cy + reach; k++) {
                best = better(best, byMid[k], right, cy);
            }
            for (int i : odd) {
                best = better(best, i, right, cy);
            }
            return best < 0 ? null : glyphs.get(best);
        }

        private int better(int best, int i, float right, float cy) {
            Glyph g = glyphs.get(i);
            if (Math.abs(mid(g) - cy) > g.size * 0.45f || g.x < right || g.x - right > g.size * 3f) {
                return best;
            }
            if (best < 0) {
                return i;
            }
            Glyph b = glyphs.get(best);
            return g.x < b.x || g.x == b.x && i < best ? i : best;
        }

        boolean between(float x, Glyph next) {
            for (int k = lowerBound(baselines, next.baseline - 1.001f); k < baselines.length && baselines[k] <= next.baseline + 1.001f; k++) {
                if (sitsBetween(glyphs.get(byBaseline[k]), x, next)) {
                    return true;
                }
            }
            for (int i : odd) {
                if (sitsBetween(glyphs.get(i), x, next)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean sitsBetween(Glyph g, float x, Glyph next) {
            return g != next && Math.abs(g.baseline - next.baseline) < 1f && g.x >= x - 1 && g.right() <= next.x;
        }

        private static int lowerBound(float[] sorted, float value) {
            int lo = 0;
            int hi = sorted.length;
            while (lo < hi) {
                int m = (lo + hi) >>> 1;
                if (sorted[m] < value) {
                    lo = m + 1;
                } else {
                    hi = m;
                }
            }
            return lo;
        }
    }
}

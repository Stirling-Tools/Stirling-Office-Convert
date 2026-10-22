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
        Neighbours neighbours = new Neighbours(marks, fills);
        for (VectorMark m : marks) {
            if ((m.filled() || m.curved()) && sized(m.x(), m.top(), m.right(), m.bottom())
                    && !neighbours.trailing(m.x(), m.top(), m.bottom())) {
                index = index == null ? new GlyphIndex(glyphs) : index;
                boolean after = index.textBefore(m.x(), (m.top() + m.bottom()) / 2f);
                if (after && !m.round() && !m.boxy()) {
                    continue;
                }
                Glyph g = bulletFor(m.x(), m.top(), m.right(), m.bottom(), m.curved() ? "•" : "▪", m.rgb(), index);
                if (g != null && after && m.bottom() >= g.baseline - 0.06f * g.size) {
                    continue;
                }
                if (g != null) {
                    out.add(g);
                    used.add(m);
                }
            }
        }
        for (Fill f : fills) {
            if (!sized(f.x(), f.top(), f.right(), f.bottom()) || neighbours.trailing(f.x(), f.top(), f.bottom())) {
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

    private static boolean leftNeighbour(float x, float top, float bottom, float h, float ox, float otop, float oright,
            float obottom) {
        float shared = Math.min(bottom, obottom) - Math.max(top, otop);
        float oh = obottom - otop;
        return oright <= x + 0.5f && x - oright <= LETTER_GAP * h && shared >= 0.5f * h && oh <= 2.5f * h
                && oright - ox >= 0.3f * h;
    }

    private static final float LETTER_GAP = 0.5f;

    private static final class Neighbours {

        private static final int MAX_COMPARISONS = 2_000_000;

        private final float[][] boxes;
        private final float[] rights;
        private int comparisons;

        Neighbours(List<VectorMark> marks, List<Fill> fills) {
            List<float[]> all = new ArrayList<>(marks.size() + fills.size());
            for (VectorMark o : marks) {
                all.add(new float[] {o.x(), o.top(), o.right(), o.bottom()});
            }
            for (Fill o : fills) {
                all.add(new float[] {o.x(), o.top(), o.right(), o.bottom()});
            }
            all.sort(Comparator.comparingDouble(b -> b[2]));
            boxes = all.toArray(new float[0][]);
            rights = new float[boxes.length];
            for (int i = 0; i < boxes.length; i++) {
                rights[i] = boxes[i][2];
            }
        }

        boolean trailing(float x, float top, float bottom) {
            float h = bottom - top;
            for (int i = GlyphIndex.lowerBound(rights, x - LETTER_GAP * h - 0.01f); i < rights.length && rights[i] <= x + 0.5f; i++) {
                if (++comparisons > MAX_COMPARISONS) {
                    return false;
                }
                float[] o = boxes[i];
                if (leftNeighbour(x, top, bottom, h, o[0], o[1], o[2], o[3])) {
                    return true;
                }
            }
            return false;
        }
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

        boolean textBefore(float x, float cy) {
            float reach = maxSize * 0.45f + 1;
            for (int k = lowerBound(mids, cy - reach); k < mids.length && mids[k] <= cy + reach; k++) {
                if (touches(glyphs.get(byMid[k]), x, cy)) {
                    return true;
                }
            }
            for (int i : odd) {
                if (touches(glyphs.get(i), x, cy)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean touches(Glyph g, float x, float cy) {
            return Math.abs(mid(g) - cy) <= g.size * 0.45f && g.right() <= x + 0.5f && x - g.right() <= g.size * 0.5f;
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

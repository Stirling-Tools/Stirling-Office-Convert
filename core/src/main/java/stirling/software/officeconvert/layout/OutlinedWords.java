package stirling.software.officeconvert.layout;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.IconShape;
import stirling.software.officeconvert.extract.PageGraphics;
import stirling.software.officeconvert.extract.PageGraphics.VectorMark;

final class OutlinedWords {

    private static final float REACH = 1.2f;

    private static final float WORD_GAP = 0.2f;

    private static final float CLIPPED = 0.5f;

    private static final int CHAIN_ROUNDS = 8;

    private static final float ROOM = 0.3f;

    private static final int MIN_LETTERS = 3;

    private static final float CAP = 0.72f;

    private static final float PAPER = 0.6f;

    private static final float CHAR_EM = 0.5f;

    private static final String STAND_IN = "\uFFFC";

    private OutlinedWords() {}

    static List<Glyph> synthesize(List<Glyph> glyphs, PageGraphics gfx, Set<Object> used, float pageArea) {
        List<VectorMark> candidates = new ArrayList<>();
        for (VectorMark m : gfx.marks()) {
            if (m.filled() && !m.stroked() && !used.contains(m) && gfx.lettering(m) != null) {
                candidates.add(m);
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Glyph> ink = new ArrayList<>();
        for (Glyph g : glyphs) {
            if (!g.isSpace() && g.icon == null && !Float.isNaN(g.baseline) && g.size > 0) {
                ink.add(g);
            }
        }
        if (ink.isEmpty()) {
            return List.of();
        }
        ink.sort(Comparator.comparingDouble(g -> g.baseline));
        float[] baselines = new float[ink.size()];
        float maxSize = 0;
        for (int i = 0; i < baselines.length; i++) {
            baselines[i] = ink.get(i).baseline;
            maxSize = Math.max(maxSize, ink.get(i).size);
        }
        Set<VectorMark> lettered = Collections.newSetFromMap(new IdentityHashMap<>());
        lettered.addAll(candidates);
        float reach = maxSize;
        candidates.removeIf(m -> overText(m, ink, baselines, reach) || !clear(m, gfx, lettered, ink, baselines, pageArea));
        Map<VectorMark, Glyph> anchors = new IdentityHashMap<>();
        for (VectorMark m : candidates) {
            Glyph best = null;
            float bestGap = Float.MAX_VALUE;
            Glyph plain = null;
            float plainGap = Float.MAX_VALUE;
            for (int k = lowerBound(baselines, m.top()); k < baselines.length && baselines[k] <= m.bottom() + maxSize; k++) {
                Glyph g = ink.get(k);
                float gap = gap(m, g.x, g.right());
                if (!fits(m, g)) {
                    continue;
                }
                if (gap <= REACH * g.size && gap < bestGap) {
                    best = g;
                    bestGap = gap;
                }
                if (!g.font.mono() && gap < plainGap) {
                    plain = g;
                    plainGap = gap;
                }
            }
            if (best != null) {
                anchors.put(m, plain != null ? plain : best);
            }
        }
        for (Map.Entry<VectorMark, Glyph> e : anchors.entrySet()) {
            if (e.getValue().font.mono()) {
                e.setValue(restyled(e.getValue(), ink, baselines));
            }
        }
        for (VectorMark m : candidates) {
            if (!anchors.containsKey(m)) {
                Glyph g = standalone(m, gfx.lettering(m), ink, baselines);
                if (g != null) {
                    anchors.put(m, g);
                }
            }
        }
        for (int round = 0; round < CHAIN_ROUNDS && anchors.size() < candidates.size(); round++) {
            boolean grew = false;
            for (VectorMark m : candidates) {
                if (anchors.containsKey(m)) {
                    continue;
                }
                for (Map.Entry<VectorMark, Glyph> e : new ArrayList<>(anchors.entrySet())) {
                    Glyph g = e.getValue();
                    VectorMark n = e.getKey();
                    if (fits(m, g) && gap(m, n.x(), n.right()) <= REACH * g.size) {
                        anchors.put(m, g);
                        grew = true;
                        break;
                    }
                }
            }
            if (!grew) {
                break;
            }
        }
        List<Glyph> out = new ArrayList<>();
        for (VectorMark m : candidates) {
            Glyph anchor = anchors.get(m);
            if (anchor == null) {
                continue;
            }
            List<Glyph> words = words(m, gfx.lettering(m), anchor);
            if (!words.isEmpty()) {
                out.addAll(words);
                used.add(m);
            }
        }
        return out;
    }

    private static boolean clear(VectorMark m, PageGraphics gfx, Set<VectorMark> lettered, List<Glyph> ink, float[] baselines,
            float pageArea) {
        float pad = ROOM * m.height();
        float x0 = m.x() - pad;
        float y0 = m.top() - pad;
        float x1 = m.right() + pad;
        float y1 = m.bottom() + pad;
        for (VectorMark o : gfx.marks()) {
            boolean near = o.x() < x1 && o.right() > x0 && o.top() < y1 && o.bottom() > y0;
            if (near && !lettered.contains(o) && !paper(o.width() * o.height(), pageArea) && !boxesText(o, ink, baselines)) {
                return false;
            }
        }
        for (PageGraphics.Fill f : gfx.fills()) {
            if (f.x() < x1 && f.right() > x0 && f.top() < y1 && f.bottom() > y0 && !paper(f.area(), pageArea)) {
                return false;
            }
        }
        for (PageGraphics.Rule r : gfx.rules()) {
            float lo = r.pos() - r.thickness() / 2f;
            float hi = r.pos() + r.thickness() / 2f;
            boolean hit = r.horizontal() ? r.start() < x1 && r.end() > x0 && lo < y1 && hi > y0
                    : r.start() < y1 && r.end() > y0 && lo < x1 && hi > x0;
            if (hit) {
                return false;
            }
        }
        for (PageGraphics.ImageDraw i : gfx.images()) {
            if (i.clipX() < x1 && i.clipRight() > x0 && i.clipTop() < y1 && i.clipBottom() > y0) {
                return false;
            }
        }
        return true;
    }

    private static boolean paper(float area, float pageArea) {
        return area >= PAPER * pageArea;
    }

    private static boolean boxesText(VectorMark o, List<Glyph> ink, float[] baselines) {
        for (int k = lowerBound(baselines, o.top()); k < baselines.length && baselines[k] <= o.bottom(); k++) {
            Glyph g = ink.get(k);
            if (g.centreX() > o.x() && g.centreX() < o.right() && o.height() <= 2.5f * g.size) {
                return true;
            }
        }
        return false;
    }

    private static Glyph standalone(VectorMark m, Shape shape, List<Glyph> ink, float[] baselines) {
        List<Rectangle2D> letters = letters(pieces(shape));
        if (letters.size() < MIN_LETTERS || m.width() < 1.5f * m.height()) {
            return null;
        }
        float[] bottoms = new float[letters.size()];
        for (int i = 0; i < bottoms.length; i++) {
            bottoms[i] = (float) letters.get(i).getMaxY();
        }
        Arrays.sort(bottoms);
        float baseline = bottoms[bottoms.length / 2];
        float size = (baseline - m.top()) / CAP;
        Glyph row = new Glyph(STAND_IN, m.x(), m.width(), baseline, size, 0f, 0f, null, m.rgb(), 0, 0f, false, false);
        Glyph style = restyled(row, ink, baselines, 0.6f, 3f);
        if (style == row || m.bottom() > baseline + 0.4f * size) {
            return null;
        }
        float k = size / style.size;
        return new Glyph(STAND_IN, m.x(), m.width(), baseline, size, style.ascent * k, 0f, style.font, m.rgb(), style.seq,
                style.spaceWidth * k, false, false);
    }

    private static List<Rectangle2D> letters(List<Path2D.Float> pieces) {
        List<Rectangle2D> out = new ArrayList<>();
        for (Path2D.Float p : pieces) {
            Rectangle2D b = p.getBounds2D();
            Rectangle2D last = out.isEmpty() ? null : out.getLast();
            if (last != null && b.getMinX() < last.getMaxX()) {
                last.add(b);
            } else {
                out.add(b);
            }
        }
        return out;
    }

    private static Glyph restyled(Glyph row, List<Glyph> ink, float[] baselines) {
        return restyled(row, ink, baselines, 0.8f, 1.5f);
    }

    private static Glyph restyled(Glyph row, List<Glyph> ink, float[] baselines, float low, float high) {
        int at = lowerBound(baselines, row.baseline);
        for (int d = 1; d < ink.size(); d++) {
            for (int k : new int[] {at - d, at + d - 1}) {
                if (k < 0 || k >= ink.size()) {
                    continue;
                }
                Glyph g = ink.get(k);
                if (!g.font.mono() && g.size * high >= row.size && g.size * low <= row.size) {
                    return new Glyph(row.text, row.x, row.width, row.baseline, g.size, g.ascent, g.descent, g.font, g.rgb,
                            row.seq, g.spaceWidth, false, false);
                }
            }
        }
        return row;
    }

    private static boolean fits(VectorMark m, Glyph g) {
        float size = g.size;
        float h = m.height();
        boolean sized = h >= 0.25f * size || Math.abs(m.bottom() - g.baseline) <= 0.1f * size;
        return sized && h <= 1.5f * size && m.bottom() <= g.baseline + 0.4f * size
                && m.bottom() >= g.baseline - 0.2f * size && m.top() >= g.baseline - 1.2f * size;
    }

    private static float gap(VectorMark m, float x, float right) {
        return Math.max(0, Math.max(x - m.right(), m.x() - right));
    }

    private static boolean overText(VectorMark m, List<Glyph> ink, float[] baselines, float reach) {
        for (int k = lowerBound(baselines, m.top()); k < baselines.length && baselines[k] <= m.bottom() + reach; k++) {
            Glyph g = ink.get(k);
            boolean level = g.baseline - 0.7f * g.size < m.bottom() && g.baseline + 0.2f * g.size > m.top();
            if (level && Math.min(g.right(), m.right()) - Math.max(g.x, m.x()) > 0.3f * g.width) {
                return true;
            }
        }
        return false;
    }

    private static List<Glyph> words(VectorMark m, Shape shape, Glyph anchor) {
        Rectangle2D whole = shape.getBounds2D();
        if (whole.getMinX() < m.x() - CLIPPED || whole.getMaxX() > m.right() + CLIPPED
                || whole.getMinY() < m.top() - CLIPPED || whole.getMaxY() > m.bottom() + CLIPPED) {
            return List.of();
        }
        List<Path2D.Float> pieces = pieces(shape);
        if (pieces.isEmpty()) {
            return List.of();
        }
        int rule = pieces.getFirst().getWindingRule();
        float split = WORD_GAP * anchor.size;
        List<Glyph> out = new ArrayList<>();
        Path2D.Float word = new Path2D.Float(rule);
        double left = Double.NaN;
        double right = -Double.MAX_VALUE;
        for (Path2D.Float p : pieces) {
            Rectangle2D b = p.getBounds2D();
            if (!Double.isNaN(left) && b.getMinX() - right > split) {
                out.add(glyph(word, left, right, m, anchor));
                word = new Path2D.Float(rule);
                left = Double.NaN;
            }
            word.append(p, false);
            left = Double.isNaN(left) ? b.getMinX() : left;
            right = Math.max(right, b.getMaxX());
        }
        out.add(glyph(word, left, right, m, anchor));
        return out;
    }

    private static List<Path2D.Float> pieces(Shape shape) {
        List<Path2D.Float> pieces = new ArrayList<>();
        int rule = shape instanceof Path2D p ? p.getWindingRule() : Path2D.WIND_NON_ZERO;
        Path2D.Float current = null;
        float[] c = new float[6];
        for (PathIterator it = shape.getPathIterator(null); !it.isDone(); it.next()) {
            int seg = it.currentSegment(c);
            if (seg == PathIterator.SEG_MOVETO) {
                current = new Path2D.Float(rule);
                pieces.add(current);
                current.moveTo(c[0], c[1]);
            } else if (current == null) {
                continue;
            } else if (seg == PathIterator.SEG_LINETO) {
                current.lineTo(c[0], c[1]);
            } else if (seg == PathIterator.SEG_QUADTO) {
                current.quadTo(c[0], c[1], c[2], c[3]);
            } else if (seg == PathIterator.SEG_CUBICTO) {
                current.curveTo(c[0], c[1], c[2], c[3], c[4], c[5]);
            } else {
                current.closePath();
            }
        }
        pieces.removeIf(p -> p.getBounds2D().isEmpty());
        pieces.sort(Comparator.comparingDouble(p -> p.getBounds2D().getMinX()));
        return pieces;
    }

    private static Glyph glyph(Path2D.Float word, double left, double right, VectorMark m, Glyph anchor) {
        GeneralPath outline = new GeneralPath(word);
        outline.transform(AffineTransform.getTranslateInstance(-left, -anchor.baseline));
        int chars = Math.max(1, Math.round((float) (right - left) / (CHAR_EM * anchor.size)));
        Glyph g = new Glyph(STAND_IN.repeat(chars), (float) left, (float) (right - left), anchor.baseline, anchor.size,
                anchor.ascent, 0f, anchor.font, m.rgb(), anchor.seq, anchor.spaceWidth, false, false);
        g.icon = new IconShape(outline, m.rgb(), "lettering|" + Integer.toHexString(m.rgb()) + "|" + digest(outline));
        return g;
    }

    private static String digest(Shape s) {
        long h = 17;
        int n = 0;
        float[] c = new float[6];
        for (PathIterator it = s.getPathIterator(null); !it.isDone(); it.next()) {
            int seg = it.currentSegment(c);
            h = h * 31 + seg;
            for (int i = 0; i < 6; i++) {
                h = h * 31 + Math.round(c[i] * 50f);
            }
            n++;
        }
        return Long.toHexString(h) + "|" + n;
    }

    private static int lowerBound(float[] sorted, float value) {
        int lo = 0;
        int hi = sorted.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] < value) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}

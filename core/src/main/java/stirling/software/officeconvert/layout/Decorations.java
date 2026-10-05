package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics.Rule;
import stirling.software.officeconvert.extract.PageGraphics.VectorMark;

final class Decorations {

    private Decorations() {}

    static void underlines(List<Line> segments, List<Rule> rules) {
        Iterator<Rule> it = rules.iterator();
        while (it.hasNext()) {
            Rule r = it.next();
            if (!r.horizontal() || r.length() < 2f) {
                continue;
            }
            if (apply(r, segments)) {
                it.remove();
            }
        }
    }

    private static boolean spansWords(Rule r, Line seg) {
        boolean starts = false;
        boolean ends = false;
        for (Word w : seg.words) {
            starts |= Math.abs(w.x - r.start()) <= 1.5f;
            ends |= Math.abs(w.right - r.end()) <= 1.5f;
        }
        return starts && ends;
    }

    private static boolean apply(Rule r, List<Line> segments) {
        for (Line seg : segments) {
            float size = seg.size;
            if (r.thickness() > Math.max(1.6f, size * 0.14f)) {
                continue;
            }
            float below = r.pos() - seg.baseline;
            boolean under = below >= -0.06f * size && below <= 0.28f * size
                    || below > 0 && below <= 0.45f * size && spansWords(r, seg);
            boolean strike = -below >= 0.12f * size && -below <= 0.5f * size;
            if (!under && !strike) {
                continue;
            }
            if (r.end() < seg.x || r.start() > seg.right) {
                continue;
            }
            float coveredLeft = Float.MAX_VALUE;
            float coveredRight = -Float.MAX_VALUE;
            int hits = 0;
            for (Word w : seg.words) {
                for (Glyph g : w.glyphs) {
                    float overlap = Math.min(g.right(), r.end()) - Math.max(g.x, r.start());
                    if (overlap >= g.width * 0.5f) {
                        hits++;
                        coveredLeft = Math.min(coveredLeft, g.x);
                        coveredRight = Math.max(coveredRight, g.right());
                    }
                }
            }
            if (hits == 0
                    || r.length() > (coveredRight - coveredLeft) + 1.2f * size
                    || r.start() < coveredLeft - 0.8f * size) {
                continue;
            }
            for (Word w : seg.words) {
                for (Glyph g : w.glyphs) {
                    float overlap = Math.min(g.right(), r.end()) - Math.max(g.x, r.start());
                    if (overlap >= g.width * 0.5f) {
                        if (under) {
                            g.underline = true;
                        } else {
                            g.strike = true;
                        }
                    }
                }
            }
            return true;
        }
        return false;
    }

    static void highlights(List<Line> segments, List<Fill> fills) {
        Iterator<Fill> it = fills.iterator();
        while (it.hasNext()) {
            Fill f = it.next();
            if (isWhite(f.rgb())) {
                continue;
            }
            boolean used = false;
            for (Line seg : segments) {
                float h = f.height();
                if (h < seg.size * 0.7f || h > seg.size * 2.2f) {
                    continue;
                }
                float cy = seg.baseline - seg.size * 0.3f;
                if (cy < f.top() || cy > f.bottom()) {
                    continue;
                }
                float covered = 0;
                for (Word w : seg.words) {
                    for (Glyph g : w.glyphs) {
                        if (g.centreX() >= f.x() && g.centreX() <= f.right()) {
                            covered += g.width;
                        }
                    }
                }
                if (covered <= 0 || f.width() > covered + 3f * seg.size) {
                    continue;
                }
                for (Word w : seg.words) {
                    for (Glyph g : w.glyphs) {
                        if (g.centreX() >= f.x() && g.centreX() <= f.right()) {
                            g.highlightRgb = f.rgb();
                        }
                    }
                }
                used = true;
            }
            if (used) {
                it.remove();
            }
        }
    }

    static void pills(List<Line> segments, List<VectorMark> marks, Set<Object> used) {
        List<VectorMark> boxes = new ArrayList<>();
        List<Integer> hosts = new ArrayList<>();
        for (VectorMark m : marks) {
            if (used.contains(m) || m.shading() || m.round() || !m.stroked() && !m.filled()
                    || !m.stroked() && isWhite(m.rgb())) {
                continue;
            }
            int at = -1;
            for (int i = 0; i < segments.size(); i++) {
                Line seg = segments.get(i);
                if (seg.baseline >= m.top() && seg.baseline - seg.size <= m.bottom() && seg.x < m.right() && seg.right > m.x()) {
                    if (at >= 0 || !holdsPill(m, seg)) {
                        at = -1;
                        break;
                    }
                    at = i;
                }
            }
            if (at >= 0) {
                boxes.add(m);
                hosts.add(at);
            }
        }
        for (int k = 0; k < boxes.size(); k++) {
            VectorMark m = boxes.get(k);
            int at = hosts.get(k);
            Line host = segments.get(at);
            List<Glyph> row = row(segments, host);
            if (!besideProse(row, m, boxes, host.size) && !(code(host, m) && (m.stroked() || light(m.rgb())))) {
                continue;
            }
            int border = m.stroked() ? m.strokeRgb() : -1;
            int fill = m.filled() && !isWhite(m.rgb()) ? m.rgb() : -1;
            for (Word w : host.words) {
                for (Glyph g : w.glyphs) {
                    if (inside(g, m)) {
                        g.boxRgb = border;
                        if (fill >= 0) {
                            g.highlightRgb = fill;
                        }
                    }
                }
            }
            if (m.stroked()) {
                segments.set(at, closeUp(host, m));
            }
            used.add(m);
        }
    }

    private static boolean inside(Glyph g, VectorMark m) {
        return g.centreX() >= m.x() && g.centreX() <= m.right();
    }

    private static List<Glyph> row(List<Line> segments, Line host) {
        List<Glyph> out = new ArrayList<>();
        for (Line seg : segments) {
            if (Math.abs(seg.baseline - host.baseline) <= 0.3f * host.size) {
                for (Word w : seg.words) {
                    out.addAll(w.glyphs);
                }
            }
        }
        return out;
    }

    private static boolean light(int rgb) {
        return ((rgb >> 16) & 0xFF) >= LIGHT && ((rgb >> 8) & 0xFF) >= LIGHT && (rgb & 0xFF) >= LIGHT;
    }

    private static final int LIGHT = 0xD0;

    private static boolean code(Line host, VectorMark m) {
        boolean any = false;
        for (Word w : host.words) {
            for (Glyph g : w.glyphs) {
                if (!g.isSpace() && inside(g, m)) {
                    if (!g.font.mono()) {
                        return false;
                    }
                    any = true;
                }
            }
        }
        return any;
    }

    private static boolean besideProse(List<Glyph> row, VectorMark m, List<VectorMark> boxes, float size) {
        Glyph before = null;
        Glyph after = null;
        for (Glyph g : row) {
            if (g.isSpace() || inside(g, m)) {
                continue;
            }
            if (g.right() <= m.x() + 0.5f && (before == null || g.right() > before.right())) {
                before = g;
            }
            if (g.x >= m.right() - 0.5f && (after == null || g.x < after.x)) {
                after = g;
            }
        }
        float reach = 2f * size;
        return before != null && m.x() - before.right() <= reach && free(before, boxes)
                || after != null && after.x - m.right() <= reach && free(after, boxes);
    }

    private static boolean free(Glyph g, List<VectorMark> boxes) {
        return boxes.stream().noneMatch(b -> inside(g, b) && g.baseline >= b.top() && g.baseline <= b.bottom());
    }

    private static Line closeUp(Line host, VectorMark m) {
        List<Word> words = new ArrayList<>();
        boolean joined = false;
        for (Word w : host.words) {
            Word prev = words.isEmpty() ? null : words.getLast();
            if (prev != null && touches(prev.last(), w.first(), m, host.size)) {
                List<Glyph> glyphs = new ArrayList<>(prev.glyphs);
                glyphs.addAll(w.glyphs);
                words.set(words.size() - 1, new Word(glyphs));
                joined = true;
            } else {
                words.add(w);
            }
        }
        if (!joined) {
            return host;
        }
        Line out = new Line(words, new byte[words.size()]);
        out.drawnSpace = host.drawnSpace;
        return out;
    }

    private static boolean touches(Glyph before, Glyph after, VectorMark m, float size) {
        boolean in = inside(before, m);
        boolean next = inside(after, m);
        if (in == next) {
            return false;
        }
        float gap = in ? after.x - m.right() : m.x() - before.right();
        float pad = in ? m.right() - before.right() : after.x - m.x();
        return gap < PILL_TOUCH * size && pad > PILL_TOUCH * size;
    }

    private static final float PILL_TOUCH = 0.12f;

    private static boolean holdsPill(VectorMark m, Line seg) {
        float size = seg.size;
        float h = m.height();
        if (h < size * 0.9f || h > size * 2.2f || m.top() > seg.baseline - 0.6f * size || m.bottom() < seg.baseline) {
            return false;
        }
        float left = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        for (Word w : seg.words) {
            for (Glyph g : w.glyphs) {
                if (g.isSpace() || !inside(g, m)) {
                    continue;
                }
                if (g.x < m.x() - 0.5f || g.right() > m.right() + 0.5f || g.icon != null) {
                    return false;
                }
                left = Math.min(left, g.x);
                right = Math.max(right, g.right());
            }
        }
        return right > left && m.width() <= right - left + 1.5f * size;
    }

    private static final float MAX_DOT = 24f;

    static List<PageLayout.Decoration> leftovers(List<Fill> fills, List<Rule> rules, List<VectorMark> dots,
            List<Box> figures, List<Box> tables, boolean tinted, ToIntFunction<Object> order) {
        List<PageLayout.Decoration> out = new ArrayList<>();
        for (Fill f : fills) {
            Box b = Regions.of(f);
            if (isWhite(f.rgb()) && !tinted || Regions.insideAny(b, figures, 1f) || Regions.insideAny(b, tables, 1f)) {
                continue;
            }
            out.add(new PageLayout.Decoration(b, f.rgb(), -1, 0, false, false, order.applyAsInt(f)));
        }
        for (Rule r : rules) {
            Box b = Regions.of(r);
            if (r.length() < 6 || Regions.insideAny(b, figures, 1f) || Regions.insideAny(b, tables, 2f) || isWhite(r.rgb())) {
                continue;
            }
            out.add(new PageLayout.Decoration(b, r.rgb(), -1, 0, false, false, order.applyAsInt(r)));
        }
        if (out.size() > 150) {
            out.clear();
        }
        out.addAll(dots(dots, figures, tables, order));
        return out;
    }

    private static List<PageLayout.Decoration> dots(
            List<VectorMark> dots, List<Box> figures, List<Box> tables, ToIntFunction<Object> order) {
        List<PageLayout.Decoration> out = new ArrayList<>();
        for (VectorMark m : dots) {
            if (Math.max(m.width(), m.height()) > MAX_DOT) {
                continue;
            }
            Box b = new Box(m.x(), m.top(), m.right(), m.bottom());
            int fill = m.filled() ? m.rgb() : -1;
            int line = m.stroked() ? m.strokeRgb() : -1;
            if (Regions.insideAny(b, figures, 1f) || Regions.insideAny(b, tables, 1f) || fill < 0 && line < 0) {
                continue;
            }
            out.add(new PageLayout.Decoration(b, fill, line, line < 0 ? 0 : m.lineWidth(), false, true, order.applyAsInt(m)));
        }
        return out.size() > 150 ? new ArrayList<>() : out;
    }

    public static boolean isWhite(int rgb) {
        return ((rgb >> 16) & 0xFF) >= 0xFB && ((rgb >> 8) & 0xFF) >= 0xFB && (rgb & 0xFF) >= 0xFB;
    }
}

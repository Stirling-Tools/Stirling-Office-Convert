package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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

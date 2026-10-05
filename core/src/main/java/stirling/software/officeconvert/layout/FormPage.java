package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import stirling.software.officeconvert.extract.PageData;
import stirling.software.officeconvert.extract.PageGraphics;
import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics.Rule;

final class FormPage {

    private static final float MIN_RULE = 6f;

    private FormPage() {}

    static boolean looksLikeForm(PageData page, List<Line> segments, List<Rule> rules) {
        if (page.widgets() < MIN_WIDGETS) {
            return false;
        }
        int across = 0;
        int down = 0;
        for (Rule r : rules) {
            if (r.length() >= MIN_RULE && !Decorations.isWhite(r.rgb())) {
                if (r.horizontal()) {
                    across++;
                } else if (r.length() < SHORT_UPRIGHT) {
                    down++;
                }
            }
        }
        if (across < MIN_ACROSS || down < MIN_DOWN || segments.size() < MIN_SEGMENTS) {
            return false;
        }
        int chars = 0;
        int small = 0;
        int boxed = 0;
        for (Line l : segments) {
            chars += l.chars;
            if (l.size <= SMALL_TEXT) {
                small += l.chars;
            }
            if (ruledAround(l, rules)) {
                boxed++;
            }
        }
        return small >= SMALL_SHARE * chars && boxed >= BOXED_SHARE * segments.size();
    }

    private static final int MIN_WIDGETS = 5;
    private static final float SHORT_UPRIGHT = 60f;
    private static final int MIN_ACROSS = 40;
    private static final int MIN_DOWN = 30;
    private static final int MIN_SEGMENTS = 30;
    private static final float SMALL_TEXT = 10.5f;
    private static final float SMALL_SHARE = 0.7f;
    private static final float BOXED_SHARE = 0.5f;

    private static boolean ruledAround(Line l, List<Rule> rules) {
        float reach = 2.5f * l.size;
        for (Rule r : rules) {
            if (r.horizontal() && r.start() <= l.x + 1 && r.end() >= l.x + 1 && r.length() >= MIN_RULE
                    && (r.pos() < l.top + 0.5f && r.pos() > l.top - reach || r.pos() > l.bottom - 0.5f && r.pos() < l.bottom + reach)) {
                return true;
            }
        }
        return false;
    }

    static List<Line> pieces(List<Line> segments, List<Rule> rules, List<Fill> fills, boolean tabLeaders) {
        List<Line> out = new ArrayList<>();
        for (Line seg : segments) {
            float mid = Regions.wordY(seg);
            List<Word> run = new ArrayList<>();
            boolean cut = false;
            for (Word w : seg.words) {
                if (!run.isEmpty() && (divided(run.getLast().right, w.x, mid, rules, fills) || resized(run.getLast(), w))) {
                    out.add(ended(LineGroups.fromWords(run), tabLeaders));
                    run = new ArrayList<>();
                    cut = true;
                }
                run.add(w);
            }
            out.add(ended(cut ? LineGroups.fromWords(run) : seg, tabLeaders));
        }
        return out;
    }

    private static Line ended(Line piece, boolean tabLeaders) {
        return tabLeaders ? Leaders.trailing(piece) : piece;
    }

    private static boolean resized(Word a, Word b) {
        return Math.max(a.size(), b.size()) > RESIZED * Math.min(a.size(), b.size());
    }

    private static final float RESIZED = 1.25f;

    private static boolean divided(float from, float to, float y, List<Rule> rules, List<Fill> fills) {
        for (Rule r : rules) {
            if (!r.horizontal() && r.pos() > from - 0.5f && r.pos() < to + 0.5f && r.start() <= y && r.end() >= y) {
                return true;
            }
        }
        for (Fill f : fills) {
            if (f.top() <= y && f.bottom() >= y && (edge(f.x(), from, to) || edge(f.right(), from, to))) {
                return true;
            }
        }
        return false;
    }

    private static boolean edge(float x, float from, float to) {
        return x > from + 0.5f && x < to - 0.5f;
    }

    static List<List<Line>> blocks(List<Line> pieces, List<Rule> rules) {
        List<Line> sorted = new ArrayList<>(pieces);
        sorted.sort(Comparator.comparingDouble((Line l) -> l.baseline).thenComparingDouble(l -> l.x));
        List<List<Line>> out = new ArrayList<>();
        for (Line p : sorted) {
            List<Line> best = null;
            for (List<Line> b : out) {
                Line last = b.getLast();
                if (continues(last, p, rules) && (best == null || last.baseline > best.getLast().baseline)) {
                    best = b;
                }
            }
            if (best == null) {
                best = new ArrayList<>();
                out.add(best);
            }
            best.add(p);
        }
        return out;
    }

    private static boolean continues(Line above, Line below, List<Rule> rules) {
        float size = Math.max(above.size, below.size);
        float pitch = below.baseline - above.baseline;
        if (Math.abs(above.x - below.x) > 1.5f || Math.abs(above.size - below.size) > 0.6f || pitch < 0.95f * size
                || pitch > PROSE_PITCH * size) {
            return false;
        }
        float left = Math.max(above.x, below.x);
        float right = Math.min(above.right, below.right);
        for (Rule r : rules) {
            if (r.horizontal() && r.pos() > above.baseline + 0.5f && r.pos() < below.top + 0.5f && r.start() < right
                    && r.end() > left) {
                return false;
            }
        }
        return true;
    }

    private static final float PROSE_PITCH = 1.4f;

    static float room(List<Line> block, List<Line> pieces, List<Rule> rules, float pageWidth) {
        float top = Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        for (Line l : block) {
            top = Math.min(top, l.top);
            bottom = Math.max(bottom, l.bottom);
            right = Math.max(right, l.right);
        }
        float room = pageWidth - 1;
        for (Line q : pieces) {
            if (!block.contains(q) && q.top < bottom && q.bottom > top && q.x >= right - 0.5f) {
                room = Math.min(room, q.x - 1);
            }
        }
        for (Rule r : rules) {
            if (!r.horizontal() && r.pos() >= right - 0.5f && r.start() < bottom && r.end() > top) {
                room = Math.min(room, r.pos() - 0.5f);
            }
        }
        return Math.max(room, right);
    }

    static List<PageLayout.Decoration> shapes(List<Rule> rules, List<Fill> fills, PageGraphics gfx) {
        List<PageLayout.Decoration> out = new ArrayList<>();
        List<Fill> tints = fills.stream().filter(f -> !Decorations.isWhite(f.rgb())).toList();
        for (Fill f : fills) {
            if (!Decorations.isWhite(f.rgb()) || over(Regions.of(f), gfx.order(f), tints, gfx)) {
                out.add(new PageLayout.Decoration(Regions.of(f), f.rgb(), -1, 0, false, false, gfx.order(f)));
            }
        }
        for (boolean horizontal : new boolean[] {true, false}) {
            List<Rule> line = new ArrayList<>(rules.stream().filter(r -> r.horizontal() == horizontal).toList());
            line.sort(Comparator.comparingInt((Rule r) -> Math.round(r.pos() * 4)).thenComparingDouble(Rule::start));
            Rule run = null;
            int order = Integer.MAX_VALUE;
            for (Rule r : line) {
                if (run != null && joins(run, r)) {
                    run = new Rule(horizontal, run.pos(), run.start(), Math.max(run.end(), r.end()), run.thickness(), run.rgb());
                    order = Math.min(order, gfx.order(r));
                    continue;
                }
                add(out, run, order, tints, gfx);
                run = r;
                order = gfx.order(r);
            }
            add(out, run, order, tints, gfx);
        }
        return out;
    }

    private static boolean joins(Rule run, Rule r) {
        return Math.abs(run.pos() - r.pos()) <= 0.3f && Math.abs(run.thickness() - r.thickness()) <= 0.15f
                && run.rgb() == r.rgb() && r.start() <= run.end() + 0.75f;
    }

    private static void add(List<PageLayout.Decoration> out, Rule r, int order, List<Fill> tints, PageGraphics gfx) {
        if (r == null || r.length() < 1f) {
            return;
        }
        Box b = Regions.of(r);
        if (!Decorations.isWhite(r.rgb()) || over(b, order, tints, gfx)) {
            out.add(new PageLayout.Decoration(b, r.rgb(), -1, 0, false, false, order));
        }
    }

    private static boolean over(Box b, int order, List<Fill> tints, PageGraphics gfx) {
        for (Fill t : tints) {
            if (gfx.order(t) < order && Regions.of(t).overlapArea(b) > 0) {
                return true;
            }
        }
        return false;
    }
}

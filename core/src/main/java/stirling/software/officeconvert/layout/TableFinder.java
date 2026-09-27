package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageData;
import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics.Rule;
import stirling.software.officeconvert.table.PageContent.FillBox;
import stirling.software.officeconvert.table.PageContent.PdfWord;
import stirling.software.officeconvert.table.PageContent.Ruling;
import stirling.software.officeconvert.table.PageContent;
import stirling.software.officeconvert.table.TableDetection;

final class TableFinder {

    private record RuleFrame(Box box, boolean gridded) {}

    private static final float OUTGROW = 24f;

    private final DocStats stats;
    private final ParagraphBuilder paragraphs;

    TableFinder(DocStats stats, ParagraphBuilder paragraphs) {
        this.stats = stats;
        this.paragraphs = paragraphs;
    }

    List<TableDetection.Found> find(
            PageData page, float frameLeft, float frameRight, List<Line> segments, List<Rule> rules, List<Fill> fills,
            List<Box> strong) {
        List<TableDetection.Found> found = new ArrayList<>();
        List<Box> claimed = new ArrayList<>();
        List<TableDetection.Found> pageWide = null;
        for (RuleFrame f : ruleFrames(rules)) {
            if (f.gridded() && pageWide == null) {
                pageWide = tablesIn(page, Regions.EVERYWHERE, segments, rules, fills, strong);
            }
            for (TableDetection.Found t : f.gridded() ? pageWide : tablesIn(page, f.box(), segments, rules, fills, strong)) {
                Box b = boxOf(t);
                if (f.gridded() && !Regions.overlapsMostly(b, List.of(f.box()))) {
                    continue;
                }
                if (!Regions.overlapsMostly(b, claimed)) {
                    found.add(f.gridded() ? t : TableShaper.addFrameHeader(t, f.box(), segments));
                    claimed.add(b);
                }
            }
        }
        List<Line> rest = new ArrayList<>();
        for (Line l : segments) {
            if (!Regions.insideAny(l.centre(), l.baseline - 1, claimed, 1f)) {
                rest.add(l);
            }
        }
        for (Box region : columnRegions(page, frameLeft, frameRight, segments)) {
            for (TableDetection.Found t : tablesIn(page, region, rest, rules, fills, strong)) {
                Box b = boxOf(t);
                boolean outgrows = b.x() < region.x() - OUTGROW || b.right() > region.right() + OUTGROW
                        || b.top() < region.top() - OUTGROW || b.bottom() > region.bottom() + OUTGROW;
                TableDetection.Found kept = outgrows ? rejudged(page, b, rest, rules, fills, strong) : t;
                if (kept != null && !Regions.overlapsMostly(boxOf(kept), claimed)) {
                    found.add(kept);
                    claimed.add(boxOf(kept));
                }
            }
        }
        return found;
    }

    private TableDetection.Found rejudged(PageData page, Box b, List<Line> segments, List<Rule> rules, List<Fill> fills,
            List<Box> strong) {
        Box whole = new Box(b.x() - 1, b.top() - 1, b.right() + 1, b.bottom() + 1);
        for (TableDetection.Found t : tablesIn(page, whole, segments, rules, fills, strong)) {
            if (Regions.overlapsMostly(boxOf(t), List.of(b))) {
                return t;
            }
        }
        return null;
    }

    static Box boxOf(TableDetection.Found t) {
        return new Box(t.left(), t.top(), t.right(), t.bottom());
    }

    private List<Box> columnRegions(PageData page, float frameLeft, float frameRight, List<Line> segments) {
        List<ColumnFinder.Item> items = new ArrayList<>();
        for (Line l : segments) {
            items.add(new ColumnFinder.Item(l.x, l.top, l.right, l.bottom, l.baseline, true, l.chars));
        }
        List<ColumnFinder.Band> bands = ColumnFinder.find(items, frameLeft, frameRight, page.height(), stats.bodySize);
        if (bands.stream().noneMatch(ColumnFinder.Band::multiColumn)) {
            return List.of(Regions.EVERYWHERE);
        }
        List<Box> regions = new ArrayList<>();
        for (ColumnFinder.Band b : bands) {
            for (int i = 0; i < b.columns().size(); i++) {
                float[] c = b.columns().get(i);
                float left = i == 0 ? 0 : c[0] - 1;
                float right = i == b.columns().size() - 1 ? page.width() : c[1] + 1;
                regions.add(new Box(left, b.top(), right, b.bottom()));
            }
        }
        return regions;
    }

    private static List<RuleFrame> ruleFrames(List<Rule> rules) {
        List<Rule> hs = new ArrayList<>();
        for (Rule r : RuleJoin.horizontals(rules)) {
            if (r.length() >= 60) {
                hs.add(r);
            }
        }
        hs.sort(Comparator.comparingDouble(Rule::pos));
        List<RuleFrame> out = new ArrayList<>();
        boolean[] used = new boolean[hs.size()];
        for (int i = 0; i < hs.size(); i++) {
            if (used[i]) {
                continue;
            }
            Rule a = hs.get(i);
            List<Rule> stack = new ArrayList<>(List.of(a));
            for (int j = i + 1; j < hs.size(); j++) {
                Rule b = hs.get(j);
                if (b.pos() - stack.getLast().pos() >= 400) {
                    break;
                }
                if (Math.abs(b.start() - a.start()) < 3 && Math.abs(b.end() - a.end()) < 3) {
                    stack.add(b);
                    used[j] = true;
                }
            }
            if (stack.size() < 2) {
                continue;
            }
            List<Rule> verticals = verticalsAcross(a, rules);
            boolean gridded = hasInnerVertical(a, stack.getFirst().pos(), stack.getLast().pos(), verticals);
            int from = 0;
            for (int k = 1; k <= stack.size(); k++) {
                if (k < stack.size() && (!gridded || bridged(a, stack.get(k - 1).pos(), stack.get(k).pos(), verticals))) {
                    continue;
                }
                if (k - from >= 2) {
                    float[] span = {stack.get(from).pos(), stack.get(k - 1).pos()};
                    if (gridded) {
                        growToVerticals(a, span, verticals);
                    }
                    if (span[1] - span[0] > 20) {
                        out.add(new RuleFrame(new Box(a.start() - 2, span[0] - 1, a.end() + 2, span[1] + 1), gridded));
                    }
                }
                from = k;
            }
        }
        return out;
    }

    private static List<Rule> verticalsAcross(Rule h, List<Rule> rules) {
        List<Rule> out = new ArrayList<>();
        for (Rule r : rules) {
            if (!r.horizontal() && r.pos() > h.start() - 3 && r.pos() < h.end() + 3) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingDouble(Rule::start));
        return out;
    }

    private static void growToVerticals(Rule h, float[] span, List<Rule> verticals) {
        boolean down = false;
        for (boolean grew = true; grew; down = !down) {
            grew = false;
            for (int n = 0; n < verticals.size(); n++) {
                Rule r = verticals.get(down ? verticals.size() - 1 - n : n);
                if (!r.horizontal() && r.pos() > h.start() - 3 && r.pos() < h.end() + 3
                        && r.start() < span[1] + 1 && r.end() > span[0] - 1
                        && (r.start() < span[0] - 0.1f || r.end() > span[1] + 0.1f)) {
                    span[0] = Math.min(span[0], r.start());
                    span[1] = Math.max(span[1], r.end());
                    grew = true;
                }
            }
        }
    }

    private static boolean hasInnerVertical(Rule h, float top, float bottom, List<Rule> rules) {
        for (Rule r : rules) {
            if (!r.horizontal() && r.pos() > h.start() + 6 && r.pos() < h.end() - 6
                    && r.start() < bottom && r.end() > top && r.length() > 6) {
                return true;
            }
        }
        return false;
    }

    private static boolean bridged(Rule h, float top, float bottom, List<Rule> rules) {
        float mid = (top + bottom) / 2f;
        for (Rule r : rules) {
            if (!r.horizontal() && r.pos() > h.start() - 3 && r.pos() < h.end() + 3 && r.start() < mid && r.end() > mid) {
                return true;
            }
        }
        return false;
    }

    private List<TableDetection.Found> tablesIn(
            PageData page, Box region, List<Line> segments, List<Rule> rules, List<Fill> fills, List<Box> strong) {
        List<PdfWord> words = new ArrayList<>();
        for (Line seg : segments) {
            for (Word w : seg.words) {
                float cx = Regions.wordX(w);
                float cy = Regions.wordY(seg);
                if (region.contains(cx, cy) && !Regions.insideAny(cx, cy, strong, 0.5f)) {
                    words.add(pdfWord(w, seg));
                }
            }
        }
        if (words.isEmpty()) {
            return List.of();
        }
        List<Ruling> hs = new ArrayList<>();
        List<Ruling> vs = new ArrayList<>();
        for (Rule r : rules) {
            Box rb = Regions.of(r);
            if (Regions.insideAny(rb, strong, 1f) || !region.contains(rb.centreX(), rb.centreY())) {
                continue;
            }
            (r.horizontal() ? hs : vs).add(new Ruling(r.pos(), r.start(), r.end(), r.thickness(), r.rgb()));
        }
        List<FillBox> fb = new ArrayList<>();
        for (Fill f : fills) {
            if (region.contains((f.x() + f.right()) / 2f, (f.top() + f.bottom()) / 2f)) {
                fb.add(new FillBox(f.x(), f.top(), f.right(), f.bottom(), f.rgb()));
            }
        }
        PageContent content = new PageContent(page.index() + 1, page.width(), page.height(), words, hs, vs, fb);
        List<TableDetection.Found> found = new ArrayList<>();
        try {
            for (TableDetection.Found t : TableDetection.detect(content)) {
                TableDetection.Found trimmed = TableShaper.trimToRules(t, rules, words);
                if (TablePlausibility.plausible(trimmed, words)) {
                    found.add(trimmed);
                }
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return found;
    }

    private static PdfWord pdfWord(Word w, Line seg) {
        int n = w.glyphs.size();
        float[] left = new float[n];
        float[] right = new float[n];
        String[] texts = new String[n];
        int bold = 0;
        int italic = 0;
        for (int i = 0; i < n; i++) {
            Glyph g = w.glyphs.get(i);
            left[i] = g.x;
            right[i] = g.right();
            texts[i] = g.text;
            bold += g.bold ? 1 : 0;
            italic += g.italic ? 1 : 0;
        }
        Glyph f = w.first();
        float size = w.size();
        return new PdfWord(w.text, w.x, w.right, seg.baseline - size * 0.8f, seg.baseline + size * 0.2f, seg.baseline,
                size, bold * 2 > n, italic * 2 > n, f.rgb, f.spaceWidth, left, right, texts);
    }

    static List<Word> wordsInside(List<Line> segments, TableDetection.Found t) {
        List<Word> out = new ArrayList<>();
        for (Line seg : segments) {
            for (Word w : seg.words) {
                if (contains(t, Regions.wordX(w), Regions.wordY(seg))) {
                    out.add(w);
                }
            }
        }
        return out;
    }

    static boolean contains(TableDetection.Found t, float x, float y) {
        return x >= t.left() - 1 && x <= t.right() + 1 && y >= t.top() - 1 && y <= t.bottom() + 1;
    }

    PageLayout.TableItem item(TableDetection.Found found, List<Word> words) {
        List<List<Word>> perCell = TableShaper.assign(found, words);
        TableDetection.Found t = TableShaper.splitStacked(found, perCell);
        if (t != found) {
            perCell = TableShaper.assign(t, words);
        }
        if (!t.ruled()) {
            TableShaper.keepPhrasesWhole(t, perCell);
        }
        float pad = cellPadding(t, perCell);
        List<List<ParaDraft>> cellParas = new ArrayList<>();
        for (int i = 0; i < t.cells().size(); i++) {
            TableDetection.FoundCell c = t.cells().get(i);
            float[] e = t.colEdges();
            float left = e[Math.min(c.col(), t.cols())];
            float right = e[Math.min(c.col() + c.colSpan(), t.cols())];
            cellParas.add(paragraphs.build(LineGroups.linesOf(perCell.get(i)), left + pad, right - pad));
        }
        return new PageLayout.TableItem(t, cellParas, rowEdges(t), pad);
    }

    private static float cellPadding(TableDetection.Found t, List<List<Word>> perCell) {
        if (!t.ruled()) {
            return 0f;
        }
        float tightest = Float.MAX_VALUE;
        for (int i = 0; i < t.cells().size(); i++) {
            List<Word> inCell = perCell.get(i);
            if (inCell.isEmpty()) {
                continue;
            }
            TableDetection.FoundCell c = t.cells().get(i);
            float lo = Float.MAX_VALUE;
            float hi = -Float.MAX_VALUE;
            for (Word w : inCell) {
                lo = Math.min(lo, w.x);
                hi = Math.max(hi, w.right);
            }
            tightest = Math.min(tightest, Math.min(lo - c.left(), c.right() - hi));
        }
        return tightest == Float.MAX_VALUE ? 0f : Math.clamp(tightest - 0.6f, 0f, 3f);
    }

    private static float[] rowEdges(TableDetection.Found t) {
        float[] tops = new float[t.rows()];
        float[] bottoms = new float[t.rows()];
        Arrays.fill(tops, Float.NaN);
        Arrays.fill(bottoms, Float.NaN);
        for (TableDetection.FoundCell c : t.cells()) {
            if (Float.isNaN(tops[c.row()]) || c.top() < tops[c.row()]) {
                tops[c.row()] = c.top();
            }
            int last = c.row() + c.rowSpan() - 1;
            if (Float.isNaN(bottoms[last]) || c.bottom() > bottoms[last]) {
                bottoms[last] = c.bottom();
            }
        }
        float[] edges = new float[t.rows() + 1];
        edges[0] = t.top();
        for (int r = 1; r < t.rows(); r++) {
            float a = bottoms[r - 1];
            float b = tops[r];
            edges[r] = Float.isNaN(a) ? b : Float.isNaN(b) ? a : (a + b) / 2f;
            if (Float.isNaN(edges[r])) {
                edges[r] = edges[r - 1];
            }
        }
        edges[t.rows()] = t.bottom();
        return edges;
    }
}

package stirling.software.officeconvert.topdf.docx;

import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PageFlow extends Region {

    static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Stop() {
            super("page limit", null, false, false);
        }
    }

    private final Ctx ctx;

    private final DocxPackage pkg;

    private final BlockFlow flow;

    final List<PageBox> pages = new ArrayList<>();

    private PageBox page;

    private SectionProps sect;

    private int sectionIndex;

    private float[] colX = {0};

    private float[] colW = {1};

    private int col;

    private float frameTop;

    private boolean frameEmpty = true;

    private boolean soft;

    private boolean sectionFirstPage;

    private boolean hardColumn;

    private float balanceBottom;

    private final List<Inline.NoteRef> endnotes = new ArrayList<>();

    private final FootnoteFlow notes;

    private final PageDecor decor;

    record Seed(Drawing drawing, Para para, PageBox.Exclusion exclusion) {}

    private static final int PASSES = 4;

    private final Map<Integer, List<Seed>> seeds;

    private final Set<Para> breakBefore;

    private final Map<Integer, List<Seed>> late = new HashMap<>();

    private final Map<Drawing, Integer> anchoredOn = new IdentityHashMap<>();

    private boolean stopped;

    private boolean breakTop;

    PageFlow(Ctx ctx) {
        this(ctx, Map.of(), Set.of());
    }

    private PageFlow(Ctx ctx, Map<Integer, List<Seed>> seeds, Set<Para> breakBefore) {
        this.ctx = ctx;
        this.pkg = ctx.pkg;
        this.flow = new BlockFlow(ctx);
        this.notes = new FootnoteFlow(ctx);
        this.decor = new PageDecor(ctx);
        this.seeds = seeds;
        this.breakBefore = breakBefore;
    }

    static PageFlow layout(Ctx ctx) {
        PageFlow flow = new PageFlow(ctx);
        flow.run();
        Set<Para> breaks = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int pass = 1; pass < PASSES && !flow.stopped; pass++) {
            Map<Integer, List<Seed>> next = new HashMap<>();
            boolean changed = !flow.late.isEmpty();
            for (Map.Entry<Integer, List<Seed>> e : flow.seeds.entrySet()) {
                for (Seed s : e.getValue()) {
                    Integer on = flow.anchoredOn.get(s.drawing());
                    if (on != null && on.intValue() == e.getKey()) {
                        next.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(s);
                        continue;
                    }
                    changed = true;
                    if (on != null && on > e.getKey()) {
                        breaks.add(s.para());
                    }
                }
            }
            for (Map.Entry<Integer, List<Seed>> e : flow.late.entrySet()) {
                next.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
            }
            if (!changed) {
                break;
            }
            flow = new PageFlow(ctx, next, breaks);
            flow.run();
        }
        if (ctx.bodyTotals && !flow.stopped) {
            ctx.knownPages = flow.pages.size();
            ctx.knownSectionPages = sectionCounts(flow.pages);
            PageFlow again = new PageFlow(ctx, flow.seeds, flow.breakBefore);
            again.run();
            flow = again;
        }
        return flow;
    }

    private static Map<Integer, Integer> sectionCounts(List<PageBox> pages) {
        Map<Integer, Integer> perSection = new HashMap<>();
        for (PageBox p : pages) {
            perSection.merge(p.section, 1, Integer::sum);
        }
        return perSection;
    }

    boolean breaksBefore(Para p) {
        return breakBefore.contains(p);
    }

    void run() {
        ctx.resetNotes();
        List<Section> sections = pkg.sections;
        try {
            for (int i = 0; i < sections.size(); i++) {
                startSection(sections.get(i), i);
                prepareBalance(sections, i);
                newPageAfter = i + 1 < sections.size() && newPage(sections.get(i + 1).props(), sect);
                flow.place(sections.get(i).blocks(), this);
            }
            newPageAfter = false;
            placeEndnotes();
            for (int guard = 0; notes.carrying() && guard < 10_000; guard++) {
                newPage(true);
            }
        } catch (Stop stop) {
            stopped = true;
            ctx.job.truncate();
            if (!pages.isEmpty() && pages.size() > ctx.job.maxPages() && ctx.job.maxPages() > 0) {
                pages.subList(ctx.job.maxPages(), pages.size()).clear();
            }
        }
        if (page != null) {
            finishPage();
        }
        finishHeaders();
    }

    private boolean newPageAfter;

    // An empty paragraph that ends a section takes no line
    boolean breaksToNewPage() {
        return newPageAfter;
    }

    private static boolean newPage(SectionProps next, SectionProps current) {
        String type = next.type == null ? "nextPage" : next.type;
        return !((type.equals("continuous") || type.equals("nextColumn")) && next.samePage(current));
    }

    float gridPitch() {
        if (sect == null || sect.gridType == null || sect.linePitch <= 1) {
            return 0;
        }
        return switch (sect.gridType) {
            case "lines", "linesAndChars", "snapToChars" -> sect.linePitch;
            default -> 0;
        };
    }

    private void startSection(Section s, int index) {
        SectionProps props = s.props();
        SectionProps previous = sect;
        int previousIndex = sectionIndex;
        sectionIndex = index;
        if (page == null) {
            sect = props;
            sectionFirstPage = true;
            newPage(true);
            return;
        }
        boolean hard = hardColumn;
        hardColumn = false;
        String type = props.type == null ? "nextPage" : props.type;
        if ((type.equals("continuous") || type.equals("nextColumn")) && props.samePage(previous)) {
            if (type.equals("continuous")) {
                if (balanceBottom > 0 || hard) {
                    y = lowest(previousIndex);
                } else {
                    balance(previous, previousIndex);
                }
                balanceBottom = 0;
                sect = props;
                columns(sect);
                frameTop = y;
                col = 0;
                frameEmpty = true;
                soft = false;
                return;
            }
            sect = props;
            columns(sect);
            if (col + 1 < colX.length) {
                col++;
                y = frameTop;
                frameEmpty = true;
                return;
            }
            sectionFirstPage = true;
            newPage(true);
            return;
        }
        sect = props;
        if (type.equals("nextPage") && breakTop && frameEmpty && page.placed.isEmpty() && page.floats.isEmpty()
                && !notes.carrying()) {
            // A page break just before a next-page section break does not leave an empty page
            pages.remove(pages.size() - 1);
            page = null;
            sectionFirstPage = true;
            newPage(true);
            return;
        }
        if (type.equals("oddPage") || type.equals("evenPage")) {
            int next = props.pageNumberStart != null ? props.pageNumberStart : page.number + 1;
            boolean wantOdd = type.equals("oddPage");
            if ((next % 2 == 1) != wantOdd) {
                sectionFirstPage = false;
                newPage(true);
            }
        } else if (ctx.settings.evenAndOddHeaders && props.pageNumberStart != null
                && Math.floorMod(props.pageNumberStart, 2) == Math.floorMod(page.number, 2)) {
            // With odd and even pages, a restart that would put two odd or two even pages together gets a blank one
            sectionFirstPage = false;
            newPage(true);
            page.blank = true;
        }
        sectionFirstPage = true;
        newPage(true);
    }

    private void columns(SectionProps s) {
        colX = s.columnLefts();
        colW = s.columnWidths();
        float shift = page == null ? 0 : page.shift;
        for (int i = 0; i < colX.length; i++) {
            colX[i] += shift;
        }
        if (col >= colX.length) {
            col = 0;
        }
    }

    private void newPage(boolean hard) {
        if (page != null) {
            finishPage();
        }
        if (ctx.job.maxPages() > 0 && pages.size() >= ctx.job.maxPages()) {
            throw new Stop();
        }
        PageBox p = new PageBox(sect, sectionIndex);
        ctx.notePage = pages.size();
        ctx.noteSection = sectionIndex;
        ctx.noteRestart = sect.footnoteRestart;
        p.firstOfSection = sectionFirstPage;
        if (sectionFirstPage && sect.pageNumberStart != null) {
            p.number = sect.pageNumberStart;
        } else {
            p.number = pages.isEmpty() ? 1 : pages.get(pages.size() - 1).number + 1;
        }
        sectionFirstPage = false;
        hardColumn = false;
        breakTop = false;
        balanceBottom = 0;
        if (ctx.settings.mirrorMargins && p.number % 2 == 0) {
            p.shift = sect.right - sect.left - sect.gutter;
        }
        pages.add(p);
        page = p;
        headerFooterBounds(p);
        columns(sect);
        col = 0;
        frameTop = p.bodyTop;
        y = frameTop;
        frameEmpty = true;
        soft = !hard;
        for (Seed s : seeds.getOrDefault(pages.size() - 1, List.of())) {
            p.exclusions.add(s.exclusion());
            p.seeded.put(s.drawing(), s.exclusion());
        }
        notes.continueOn(p);
        continueEndnotes();
        try {
            ctx.job.checkpoint();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void headerFooterBounds(PageBox p) {
        SectionProps s = p.sect;
        p.bodyTop = s.top;
        p.bodyBottom = s.pageH - s.bottom;
        ctx.numbers = numbers(p, ctx.knownPages > 0 ? Integer.toString(ctx.knownPages) : "1",
                Integer.toString(ctx.knownSectionPages.getOrDefault(p.section, 1)));
        StackLayout.Result header = headerLayout(p, true);
        StackLayout.Result footer = headerLayout(p, false);
        if (header != null && !s.topFixed) {
            p.bodyTop = Math.max(p.bodyTop, s.header + header.height());
        }
        if (footer != null && !s.bottomFixed) {
            p.bodyBottom = Math.min(p.bodyBottom, s.pageH - s.footer - footer.height());
        }
        if (p.bodyBottom - p.bodyTop < 36) {
            p.bodyBottom = Math.min(s.pageH, p.bodyTop + 36);
        }
        float left = s.bodyLeft() + p.shift;
        if (header != null) {
            headerWrap(p, header, left, s.header);
        }
        if (footer != null) {
            headerWrap(p, footer, left, s.pageH - s.footer - footer.height());
        }
    }

    // Body text wraps around the floating objects of the header and footer, as it does around its own
    private void headerWrap(PageBox p, StackLayout.Result r, float x, float y) {
        for (Strip.Anchor a : r.anchors()) {
            Drawing d = a.drawing();
            if (!d.wraps() || emptyPolygon(d)) {
                continue;
            }
            boolean cell = inCell(a);
            PageBox.FloatBox f = floatBox(p, d, x + a.paraX(), y + a.paraTop(), cell ? x + a.cellX() : x,
                    cell ? a.cellW() : p.sect.textWidth(), cell ? cellBox(a, x, y) : null);
            // A header picture as tall as half the body is a background, not something text flows around
            if (f == null || f.box().height > (p.bodyBottom - p.bodyTop) / 2) {
                continue;
            }
            PageBox.Exclusion e = new PageBox.Exclusion(wrapBox(d, f.box()), d.wrapSide, d.wrap.equals("topAndBottom"),
                    null, wrapShape(d, f.box()));
            p.exclusions.add(e);
            p.marginal.add(e);
        }
    }

    private Ctx.PageNumbers numbers(PageBox p, String pages, String sectionPages) {
        String page = p.display();
        int number = p.number;
        return new Ctx.PageNumbers() {
            @Override
            public String page() {
                numbered = true;
                return page;
            }

            @Override
            public int pageNumber() {
                numbered = true;
                return number;
            }

            @Override
            public String pages() {
                numbered = true;
                return pages;
            }

            @Override
            public String sectionPages() {
                numbered = true;
                return sectionPages;
            }
        };
    }

    private record HeaderKey(String rel, SectionProps sect, float shift, boolean header) {}

    // A header without page fields is the same on every page of its section, so it is laid out once
    private final Map<HeaderKey, StackLayout.Result> headers = new HashMap<>();

    private boolean numbered;

    private StackLayout.Result headerLayout(PageBox p, boolean header) {
        SectionProps s = p.sect;
        Map<String, String> refs = header ? s.headers : s.footers;
        String type = "default";
        if (p.firstOfSection && s.titlePg) {
            type = "first";
        } else if (ctx.settings.evenAndOddHeaders && p.number % 2 == 0) {
            type = "even";
        }
        String rel = refs.get(type);
        if (rel == null) {
            return null;
        }
        List<Block> blocks = pkg.headerFooter(rel);
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        HeaderKey key = new HeaderKey(rel, s, p.shift, header);
        StackLayout.Result known = headers.get(key);
        if (known != null) {
            return known;
        }
        ctx.headerDepth++;
        numbered = false;
        try {
            StackLayout.Result r = StackLayout.header(blocks, s, p.shift, header ? s.header : Float.NaN, ctx);
            if (!numbered) {
                headers.put(key, r);
            }
            return r;
        } finally {
            ctx.headerDepth--;
        }
    }

    private void finishHeaders() {
        int total = pages.size();
        Map<Integer, Integer> perSection = sectionCounts(pages);
        for (PageBox p : pages) {
            if (p.blank) {
                continue;
            }
            ctx.numbers = numbers(p, Integer.toString(total), Integer.toString(perSection.get(p.section)));
            StackLayout.Result header = headerLayout(p, true);
            StackLayout.Result footer = headerLayout(p, false);
            SectionProps s = p.sect;
            float left = s.bodyLeft() + p.shift;
            if (header != null) {
                p.header = withPageOps(new Op.Group(left, s.header, null, null, header.ops()), header);
                headerFloats(p, header, left, s.header);
            }
            if (footer != null) {
                float top = s.pageH - s.footer - footer.height();
                p.footer = withPageOps(new Op.Group(left, top, null, null, footer.ops()), footer);
                headerFloats(p, footer, left, top);
            }
        }
    }

    private static List<Op> withPageOps(Op stack, StackLayout.Result r) {
        if (r.pageOps().isEmpty()) {
            return List.of(stack);
        }
        List<Op> out = new ArrayList<>();
        out.add(stack);
        out.addAll(r.pageOps());
        return out;
    }

    private void headerFloats(PageBox p, StackLayout.Result r, float x, float y) {
        for (Strip.Anchor a : r.anchors()) {
            boolean cell = inCell(a);
            PageBox.FloatBox f = floatBox(p, a.drawing(), x + a.paraX(), y + a.paraTop(), cell ? x + a.cellX() : x,
                    cell ? a.cellW() : p.sect.textWidth(), cell ? cellBox(a, x, y) : null);
            if (f != null) {
                p.headerFloats.add(f);
            }
        }
    }

    // Word 2013 and later always lay out objects anchored in a table cell against that cell
    private static Rectangle2D.Float cellBox(Strip.Anchor a, float x, float y) {
        Rectangle2D.Float c = a.cell();
        return c == null ? null : new Rectangle2D.Float(x + c.x, y + c.y, c.width, c.height);
    }

    private boolean inCell(Strip.Anchor a) {
        return a.inCell() && (a.drawing().layoutInCell || ctx.settings.compatibilityMode >= 15);
    }

    private void finishPage() {
        PageBox p = page;
        if (p == null) {
            return;
        }
        verticalAlign(p);
        notes.layout(p);
        decor.columnRules(p);
        decor.lineNumbers(p);
    }

    private void verticalAlign(PageBox p) {
        String va = p.sect.vAlign;
        if (va == null || va.equals("top") || p.placed.isEmpty()) {
            return;
        }
        float bottom = 0;
        for (Placed pl : p.placed) {
            if (pl.section != p.section) {
                return;
            }
            if (!pl.fixed) {
                bottom = Math.max(bottom, pl.y + pl.strip.height);
            }
        }
        float free = p.bodyBottom - p.noteHeight - bottom;
        if (free <= 0) {
            return;
        }
        float shift = va.equals("center") ? free / 2 : va.equals("bottom") ? free : 0;
        if (shift <= 0) {
            return;
        }
        for (Placed pl : p.placed) {
            if (!pl.fixed) {
                pl.y += shift;
            }
        }
    }

    private float bodyBottom() {
        float b = page.bodyTop;
        for (Placed pl : page.placed) {
            if (!pl.fixed) {
                b = Math.max(b, pl.y + pl.strip.height);
            }
        }
        return Math.max(b, y);
    }

    private void placeEndnotes() {
        if (endnotes.isEmpty()) {
            return;
        }
        List<Block> sepBlocks = null;
        for (DocxPackage.Note n : pkg.endnotes.values()) {
            if ("separator".equals(n.type())) {
                sepBlocks = n.blocks();
            } else if ("continuationSeparator".equals(n.type())) {
                endnoteContinuation = n.blocks();
            }
        }
        if (sepBlocks != null) {
            flow.place(sepBlocks, this);
        }
        for (Inline.NoteRef ref : new ArrayList<>(endnotes)) {
            DocxPackage.Note n = pkg.endnotes.get(ref.id());
            if (n == null) {
                continue;
            }
            String saved = ctx.currentNoteMark;
            ctx.currentNoteMark = ref.customMark() != null ? "" : ctx.noteNumber(ref);
            try {
                flow.place(n.blocks(), this);
            } finally {
                ctx.currentNoteMark = saved;
            }
        }
        endnoteContinuation = null;
    }

    private List<Block> endnoteContinuation;

    // Endnotes that run on to a new page start below the continuation separator, as Word draws it
    private void continueEndnotes() {
        if (endnoteContinuation == null) {
            return;
        }
        StackLayout.Result sep = StackLayout.layout(endnoteContinuation, width(), ctx);
        Strip s = new Strip();
        s.height = sep.height();
        s.ops.addAll(sep.ops());
        Placed p = new Placed(s, left(), y, col, sectionIndex, 0);
        p.fixed = true;
        page.placed.add(p);
        y += s.height;
    }

    private void prepareBalance(List<Section> sections, int i) {
        balanceBottom = 0;
        if (sect.cols < 2 || i + 1 >= sections.size()) {
            return;
        }
        SectionProps next = sections.get(i + 1).props();
        if (!"continuous".equals(next.type) || !next.samePage(sect) || columnBreak(sections.get(i).blocks())) {
            return;
        }
        StackLayout trial = new StackLayout(colW[0]);
        List<Block> blocks = sections.get(i).blocks();
        if (!blocks.isEmpty() && blocks.get(blocks.size() - 1) instanceof Para last && last.sectionMark()
                && ParaFlow.listed(last)) {
            // A numbered paragraph that only carries the break shows nothing, and Word balances without it
            blocks = blocks.subList(0, blocks.size() - 1);
        }
        flow.place(blocks, trial);
        List<Placed> list = trial.placed;
        if (list.isEmpty()) {
            return;
        }
        int cols = colX.length;
        float total = 0;
        float tallest = 0;
        boolean topSuppressed = frameTop <= page.bodyTop + 0.5f;
        float[] gaps = gaps(list, 0);
        for (int k = 0; k < list.size(); k++) {
            Placed p = list.get(k);
            total += p.strip.height + gaps[k];
            tallest = Math.max(tallest, p.strip.height);
        }
        float avail = page.bodyBottom - page.noteHeight - y;
        if (!fits(list, gaps, avail, cols, topSuppressed)) {
            return;
        }
        float lo = Math.max(tallest, total / cols - 1);
        float hi = avail;
        for (int it = 0; it < 30 && hi - lo > 0.05f; it++) {
            float mid = (lo + hi) / 2;
            if (fits(list, gaps, mid, cols, topSuppressed)) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        balanceBottom = y + hi + 0.5f;
    }

    private static boolean columnBreak(List<Block> blocks) {
        for (Block b : blocks) {
            if (b instanceof Para p) {
                for (Inline in : p.items) {
                    if (in instanceof Inline.Break br && "column".equals(br.type())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void balance(SectionProps previous, int previousIndex) {
        if (previous == null || previous.cols < 2 || page == null) {
            return;
        }
        List<Placed> list = new ArrayList<>();
        for (Placed p : page.placed) {
            if (p.section == previousIndex && !p.fixed) {
                list.add(p);
            }
        }
        if (list.isEmpty()) {
            return;
        }
        int cols = colX.length;
        float total = 0;
        float tallest = 0;
        boolean topSuppressed = frameTop <= page.bodyTop + 0.5f;
        float[] gaps = gaps(list, frameTop);
        for (int i = 0; i < list.size(); i++) {
            total += list.get(i).strip.height + gaps[i];
            tallest = Math.max(tallest, list.get(i).strip.height);
        }
        float avail = page.bodyBottom - page.noteHeight - frameTop;
        float lo = Math.max(tallest, total / cols - 1);
        float hi = Math.min(avail, total);
        if (!fits(list, gaps, hi, cols, topSuppressed)) {
            return;
        }
        for (int it = 0; it < 30 && hi - lo > 0.05f; it++) {
            float mid = (lo + hi) / 2;
            if (fits(list, gaps, mid, cols, topSuppressed)) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        float height = hi;
        int c = 0;
        float at = 0;
        float max = 0;
        for (int k = 0; k < list.size(); k++) {
            Placed p = list.get(k);
            float g = gaps[k];
            if (at > 0 && at + g + p.strip.height > height + 0.05f) {
                c++;
                at = 0;
                g = topSuppressed ? 0 : gaps[k];
            }
            c = Math.min(c, cols - 1);
            float dx = colX[c] - colX[Math.min(p.column, colX.length - 1)];
            p.x += dx;
            p.y = frameTop + at + g;
            p.column = c;
            at += g + p.strip.height;
            max = Math.max(max, at);
        }
        y = frameTop + max;
    }

    private float lowest(int section) {
        float max = y;
        for (Placed p : page.placed) {
            if (p.section == section) {
                max = Math.max(max, p.y + p.strip.height);
            }
        }
        return max;
    }

    private static float[] gaps(List<Placed> list, float origin) {
        float[] g = new float[list.size()];
        g[0] = Math.max(0, list.get(0).y - origin);
        for (int k = 1; k < list.size(); k++) {
            Placed a = list.get(k - 1);
            Placed b = list.get(k);
            float bottom = a.y + a.strip.height;
            g[k] = a.column == b.column && b.y >= bottom - 0.01f ? b.y - bottom : b.gap;
        }
        return g;
    }

    private static boolean fits(List<Placed> list, float[] gaps, float height, int cols, boolean topSuppressed) {
        int c = 0;
        float at = 0;
        for (int k = 0; k < list.size(); k++) {
            Placed p = list.get(k);
            float g = gaps[k];
            if (at > 0 && at + g + p.strip.height > height + 0.05f) {
                c++;
                at = 0;
                g = topSuppressed ? 0 : gaps[k];
                if (c >= cols) {
                    return false;
                }
            }
            at += g + p.strip.height;
        }
        return true;
    }

    @Override
    float left() {
        return colX[Math.min(col, colX.length - 1)];
    }

    @Override
    float width() {
        return colW[Math.min(col, colW.length - 1)];
    }

    @Override
    float limit() {
        float l = page.bodyBottom - page.noteHeight;
        if (balanceBottom > 0 && col < colX.length - 1) {
            l = Math.min(l, balanceBottom);
        }
        return l;
    }

    @Override
    float frameHeight() {
        return page.bodyBottom - page.bodyTop;
    }

    @Override
    boolean paginated() {
        return true;
    }

    float frameTop() {
        return frameTop;
    }

    @Override
    boolean atTop() {
        return frameEmpty && frameTop <= page.bodyTop + 0.5f;
    }

    @Override
    boolean frameStart() {
        return atTop();
    }

    @Override
    boolean softTop() {
        return soft;
    }

    @Override
    void place(Strip s, float x, float atY, float gap) {
        page.placed.add(new Placed(s, x, atY, col, sectionIndex, gap));
        frameEmpty = false;
        breakTop = false;
        anchorStrip(s, x, atY);
        if (s.notes != null) {
            for (Inline.NoteRef n : s.notes) {
                if (n.endnote() && !endnotes.contains(n)) {
                    endnotes.add(n);
                }
            }
        }
    }

    SectionProps section() {
        return sect;
    }

    void pageBreakTop() {
        breakTop = frameEmpty;
    }

    float[] floatingOrigin(String horz, String vert) {
        float bx = switch (horz == null ? "text" : horz) {
            case "page" -> 0;
            case "margin" -> sect.bodyLeft() + page.shift;
            default -> left();
        };
        float by = switch (vert == null ? "text" : vert) {
            case "page" -> 0;
            case "margin" -> sect.top;
            default -> y;
        };
        return new float[] {bx, by};
    }

    private void anchorStrip(Strip s, float x, float atY) {
        if (s.anchors == null) {
            return;
        }
        for (Strip.Anchor a : s.anchors) {
            boolean cell = inCell(a);
            PageBox.FloatBox f = floatBox(page, a.drawing(), x + a.paraX(), atY + a.paraTop(),
                    cell ? x + a.cellX() : left(), cell ? a.cellW() : width(), cell ? cellBox(a, x, atY) : null);
            if (f != null) {
                addFloat(f);
            }
        }
    }

    // A floating table's rows carry the objects anchored in their cells, as rows in the flow do
    void placeFixed(Strip s, float x, float atY) {
        Placed p = new Placed(s, x, atY, col, sectionIndex, 0);
        p.fixed = true;
        page.placed.add(p);
        anchorStrip(s, x, atY);
        if (s.notes != null) {
            addNotes(s.notes, 0);
        }
    }

    void exclude(Rectangle2D.Float box) {
        tableExclusion = new PageBox.Exclusion(box, "bothSides", false, null);
        tablePage = page;
        page.exclusions.add(tableExclusion);
    }

    private PageBox.Exclusion tableExclusion;

    private PageBox tablePage;

    // Word leaves an empty paragraph right after a floating table where it falls, even under the table
    boolean suspendTableExclusion() {
        return tableExclusion != null && tablePage == page && page.exclusions.remove(tableExclusion);
    }

    void restoreTableExclusion() {
        if (tablePage != null) {
            tablePage.exclusions.add(tableExclusion);
        }
    }

    void excludeBand(Rectangle2D.Float box) {
        page.exclusions.add(new PageBox.Exclusion(box, "bothSides", true, null));
    }

    @Override
    void newFrame(boolean pageBreak, boolean hard) {
        if (!pageBreak && col + 1 < colX.length) {
            if (hard) {
                hardColumn = true;
            }
            col++;
            y = frameTop;
            frameEmpty = true;
            soft = !hard;
            return;
        }
        newPage(hard);
    }

    @Override
    List<Object> anchor(Drawing d, Para p, float paraX, float paraTop) {
        PageBox.FloatBox f = floatBox(page, d, left() + paraX, paraTop, left(), width(), null);
        if (f == null) {
            return List.of();
        }
        PageBox.Exclusion seed = page.seeded.remove(d);
        if (seed != null) {
            page.exclusions.remove(seed);
        }
        int index = pages.size() - 1;
        anchoredOn.put(d, index);
        addFloat(f);
        if (seed == null && p != null && d.wraps() && !follows(d)) {
            for (PageBox.Exclusion e : page.exclusions) {
                if (e.owner() == f && overlapsEarlier(e.box())) {
                    late.computeIfAbsent(index, k -> new ArrayList<>())
                            .add(new Seed(d, p, new PageBox.Exclusion(e.box(), e.side(), e.band(), null, e.shape())));
                    break;
                }
            }
        }
        return List.of(f);
    }

    private static boolean follows(Drawing d) {
        return d.vRel == null || d.vRel.equals("paragraph") || d.vRel.equals("line");
    }

    private boolean overlapsEarlier(Rectangle2D.Float b) {
        for (Placed pl : page.placed) {
            if (pl.fixed || pl.y + pl.strip.height <= b.y + 0.5f || pl.y >= b.y + b.height - 0.5f) {
                continue;
            }
            int c = Math.min(pl.column, colX.length - 1);
            float x0 = Math.min(pl.x, colX[c]);
            float x1 = Math.max(pl.x, colX[c]) + colW[c];
            if (x1 > b.x && x0 < b.x + b.width) {
                return true;
            }
        }
        return false;
    }

    @Override
    void unanchor(List<Object> handles) {
        for (Object h : handles) {
            page.floats.remove(h);
            page.exclusions.removeIf(e -> e.owner() == h);
            if (h instanceof PageBox.FloatBox f) {
                anchoredOn.remove(f.drawing());
                List<Seed> l = late.get(pages.size() - 1);
                if (l != null) {
                    l.removeIf(s -> s.drawing() == f.drawing());
                    if (l.isEmpty()) {
                        late.remove(pages.size() - 1);
                    }
                }
            }
        }
    }

    private void addFloat(PageBox.FloatBox f) {
        page.floats.add(f);
        Drawing d = f.drawing();
        if (d.wraps() && !emptyPolygon(d)) {
            boolean band = d.wrap.equals("topAndBottom");
            page.exclusions.add(new PageBox.Exclusion(wrapBox(d, f.box()), d.wrapSide, band, f, wrapShape(d, f.box())));
        }
    }

    // The area text keeps clear of: the object and its distances, or the bounds of a tight or through wrap polygon
    static Rectangle2D.Float wrapBox(Drawing d, Rectangle2D.Float b) {
        float x0 = b.x - d.effL;
        float y0 = b.y - d.effT;
        float x1 = b.x + b.width + d.effR;
        float y1 = b.y + b.height + d.effB;
        float[] p = d.wrapBounds;
        if (p != null && (d.wrap.equals("tight") || d.wrap.equals("through"))) {
            x0 = b.x + p[0] * b.width - d.effL;
            y0 = b.y + p[1] * b.height - d.effT;
            x1 = b.x + p[2] * b.width + d.effR;
            y1 = b.y + p[3] * b.height + d.effB;
        }
        return new Rectangle2D.Float(x0 - d.distL, y0 - d.distT, x1 - x0 + d.distL + d.distR,
                y1 - y0 + d.distT + d.distB);
    }

    // A tight or through wrap polygon with no area leaves nothing for text to wrap around
    static boolean emptyPolygon(Drawing d) {
        return d.wrapPoints != null && d.wrapBounds == null && (d.wrap.equals("tight") || d.wrap.equals("through"));
    }

    // Text follows the outline of a tight or through wrap polygon, not only its bounds
    static PageBox.WrapShape wrapShape(Drawing d, Rectangle2D.Float b) {
        float[] p = d.wrapPoints;
        if (p == null || d.wrapBounds == null || !(d.wrap.equals("tight") || d.wrap.equals("through"))) {
            return null;
        }
        float[] xy = new float[p.length];
        for (int i = 0; i + 1 < p.length; i += 2) {
            xy[i] = b.x + p[i] * b.width;
            xy[i + 1] = b.y + p[i + 1] * b.height;
        }
        return new PageBox.WrapShape(xy, d.effL + d.distL, d.effR + d.distR, d.effT + d.distT, d.effB + d.distB);
    }

    private PageBox.FloatBox floatBox(PageBox p, Drawing d, float paraX, float paraTop, float cx, float cw,
            Rectangle2D.Float cell) {
        SectionProps s = p.sect;
        FloatLayout.Frame frame = new FloatLayout.Frame(s.pageW, s.pageH, s.bodyLeft() + p.shift, s.right - p.shift,
                s.top, s.bottom, cx, cw, cell);
        Rectangle2D.Float box = FloatLayout.position(d, frame, paraX - cx, paraTop);
        List<Op> ops = new ArrayList<>();
        DrawingPainter.paint(d, box.x, box.y, ops, ctx);
        return new PageBox.FloatBox(d, box, ops, d.behind, d.z);
    }

    static final float MIN_SEGMENT = 18;

    @Override
    float[] spans(float top, float h, float x0, float x1, float minWidth) {
        return spans(page.exclusions, top, h, x0, x1, minWidth);
    }

    static float[] spans(List<PageBox.Exclusion> ex, float top, float h, float x0, float x1, float minWidth) {
        float t = top;
        if (ex.isEmpty()) {
            return new float[] {t, x0, x1};
        }
        for (int guard = 0; guard < 200; guard++) {
            List<float[]> free = new ArrayList<>();
            free.add(new float[] {x0, x1});
            float lowest = Float.NaN;
            boolean band = false;
            for (PageBox.Exclusion e : ex) {
                Rectangle2D.Float r = e.box();
                if (r.y >= t + h || r.y + r.height <= t || r.x >= x1 || r.x + r.width <= x0) {
                    continue;
                }
                float ex0 = r.x;
                float ex1 = r.x + r.width;
                float below = r.y + r.height;
                if (e.shape() != null && !e.band()) {
                    float[] reach = e.shape().extent(t, t + h);
                    if (reach == null || reach[0] >= x1 || reach[1] <= x0) {
                        continue;
                    }
                    ex0 = reach[0];
                    ex1 = reach[1];
                    // Word tries a line that does not fit beside a wrap polygon again one line lower
                    below = t + Math.max(h, 1);
                }
                lowest = Float.isNaN(lowest) ? below : Math.min(lowest, below);
                if (e.band()) {
                    band = true;
                    break;
                }
                String side = e.side() == null ? "bothSides" : e.side();
                if (side.equals("largest")) {
                    side = ex0 - x0 >= x1 - ex1 ? "left" : "right";
                }
                List<float[]> next = new ArrayList<>();
                for (float[] iv : free) {
                    if (ex1 <= iv[0] || ex0 >= iv[1]) {
                        next.add(iv);
                        continue;
                    }
                    if (!side.equals("right") && ex0 > iv[0]) {
                        next.add(new float[] {iv[0], ex0});
                    }
                    if (!side.equals("left") && ex1 < iv[1]) {
                        next.add(new float[] {ex1, iv[1]});
                    }
                }
                free = next;
            }
            if (!band) {
                if (minWidth > x1 - x0 + 0.5f) {
                    // Word sets what cannot fit even the full width at the top, over side-wrapped objects
                    return new float[] {t, x0, x1};
                }
                float need = Math.max(MIN_SEGMENT, minWidth);
                free.removeIf(iv -> iv[1] - iv[0] < need);
                if (!free.isEmpty()) {
                    float[] out = new float[1 + free.size() * 2];
                    out[0] = t;
                    for (int i = 0; i < free.size(); i++) {
                        out[1 + i * 2] = free.get(i)[0];
                        out[2 + i * 2] = free.get(i)[1];
                    }
                    return out;
                }
            }
            if (Float.isNaN(lowest) || lowest <= t) {
                break;
            }
            t = lowest;
        }
        return new float[] {t, x0, x1};
    }

    @Override
    boolean anchorsFit(List<Object> handles) {
        for (Object h : handles) {
            if (h instanceof PageBox.FloatBox f) {
                String rel = f.drawing().vRel;
                boolean follows = rel == null || rel.equals("paragraph") || rel.equals("line");
                if (follows && f.drawing().wraps() && f.box().y + f.box().height > page.bodyBottom + 1) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    float clearFloats(float top, float h, float x0, float x1) {
        return clear(top, h, x0, x1, false);
    }

    // A row moves below a header object it meets, but a footer object below its top ends the space instead
    @Override
    float clearRow(float top, float h, float x0, float x1) {
        return clear(top, h, x0, x1, true);
    }

    @Override
    float rowLimit(float top, float x0, float x1) {
        float lim = limit();
        for (PageBox.Exclusion e : page.marginal) {
            Rectangle2D.Float r = e.box();
            if (r.y > top && r.x < x1 && r.x + r.width > x0) {
                lim = Math.min(lim, r.y);
            }
        }
        return lim;
    }

    private float clear(float top, float h, float x0, float x1, boolean rows) {
        float t = top;
        for (int guard = 0; guard < 50; guard++) {
            boolean moved = false;
            for (PageBox.Exclusion e : page.exclusions) {
                Rectangle2D.Float r = e.box();
                if (rows && r.y > t && page.marginal.contains(e) || r.y >= t + h || r.y + r.height <= t
                        || r.x >= x1 || r.x + r.width <= x0) {
                    continue;
                }
                t = r.y + r.height;
                moved = true;
            }
            if (!moved) {
                break;
            }
        }
        return t;
    }

    @Override
    float clearBox(float top, float h, float boxLeft, float boxRight) {
        float x0 = left();
        float x1 = left() + width();
        float t = top;
        for (int guard = 0; guard < 50; guard++) {
            List<float[]> free = new ArrayList<>();
            free.add(new float[] {x0, x1});
            float lowest = Float.NaN;
            for (PageBox.Exclusion e : page.exclusions) {
                Rectangle2D.Float r = e.box();
                if (page.marginal.contains(e) || r.y >= t + h || r.y + r.height <= t
                        || r.x >= Math.max(x1, boxRight) || r.x + r.width <= Math.min(x0, boxLeft)) {
                    continue;
                }
                lowest = Float.isNaN(lowest) ? r.y + r.height : Math.min(lowest, r.y + r.height);
                List<float[]> next = new ArrayList<>();
                for (float[] iv : free) {
                    if (e.band() || r.x < iv[1] && r.x + r.width > iv[0]) {
                        if (!e.band() && r.x > iv[0]) {
                            next.add(new float[] {iv[0], r.x});
                        }
                        if (!e.band() && r.x + r.width < iv[1]) {
                            next.add(new float[] {r.x + r.width, iv[1]});
                        }
                    } else {
                        next.add(iv);
                    }
                }
                free = next;
            }
            if (Float.isNaN(lowest)) {
                return t;
            }
            for (float[] iv : free) {
                if (boxLeft >= iv[0] - 0.01f && boxRight <= iv[1] + 0.01f) {
                    return t;
                }
            }
            if (lowest <= t) {
                return t;
            }
            t = lowest;
        }
        return t;
    }

    @Override
    boolean notesFit(List<Inline.NoteRef> refs, float bottom, boolean split) {
        return notes.fits(page, sect, refs, bottom, split);
    }

    // Footnotes and the bottom of a balanced column end the space where the page margin would not
    @Override
    boolean hardLimit() {
        return page.noteHeight > 0 || balanceBottom > 0 && col < colX.length - 1;
    }

    @Override
    void addNotes(List<Inline.NoteRef> refs, float after) {
        for (Inline.NoteRef n : refs) {
            if (n.endnote()) {
                if (!endnotes.contains(n)) {
                    endnotes.add(n);
                }
            } else {
                notes.add(page, sect, n, bodyBottom() + after);
            }
        }
    }
}

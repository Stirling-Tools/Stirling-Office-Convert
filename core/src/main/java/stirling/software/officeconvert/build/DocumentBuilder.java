package stirling.software.officeconvert.build;

import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.Pictures;
import stirling.software.officeconvert.extract.PageData;
import stirling.software.officeconvert.layout.Box;
import stirling.software.officeconvert.layout.DocStats;
import stirling.software.officeconvert.layout.LabelGround;
import stirling.software.officeconvert.layout.PageLayout;
import stirling.software.officeconvert.layout.ParaDraft;
import stirling.software.officeconvert.model.Block;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph.Align;
import stirling.software.officeconvert.model.Paragraph.LineRule;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.model.Table;

public final class DocumentBuilder {

    private final PDDocument document;
    private final DocStats stats;
    private final DocSink sink;
    private final StyleSheet styles;
    private final Numbering numbering = new Numbering();
    private final RunBuilder runs;
    private final ParagraphFactory paragraphs;
    private final TableBuilder tables;
    private final MediaPlacer placer;
    private final RunningContent running;
    private final ColumnFlow flow;
    private final NoteWriter notes;

    private Section section;
    private Block pending;
    private int pendingPage;
    private Carry carry;
    private int pageCount;
    private String pageBookmark;
    private PageLayout pageLayout;
    private final PageFloats floats = new PageFloats();
    private final Set<PageLayout.Item> placedEarly = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final float BACKDROP = 0.6f;
    private static final float SLIDING_SHAPE = 0.02f;
    private Paragraph joined;
    private final Map<ParaDraft, PageLayout> homePage = new IdentityHashMap<>();
    private final Map<Paragraph, Object> shadedOn = new IdentityHashMap<>();
    private final List<Block> held = new ArrayList<>();
    private final List<Spaced> spaced = new ArrayList<>();
    private final List<Integer> bandColumns = new ArrayList<>();
    private int bandAt = -1;
    private int columnAt;
    private float pageShift;
    private float footerHeight;
    private float firstFooterHeight;
    private float noteHeight;

    private record Spaced(Paragraph p, int band, int column) {}

    private record Carry(ParaDraft draft, float colLeft, float colRight, float spaceBefore, boolean pageBreak, float ref,
            boolean notes) {}

    public DocumentBuilder(PDDocument document, DocStats stats, DocSink sink, float figureDpi, boolean dropHyphens) {
        this(document, stats, sink, figureDpi, dropHyphens, Pictures.COMPACT);
    }

    public DocumentBuilder(PDDocument document, DocStats stats, DocSink sink, float figureDpi, boolean dropHyphens,
            Pictures pictures) {
        this.document = document;
        this.stats = stats;
        this.sink = sink;
        this.runs = new RunBuilder(dropHyphens);
        runs.icons(new IconPictures(sink));
        RunStyle normal =
                new RunStyle(stats.bodyFont.family(), round(stats.bodySize), false, false, false, false, 0, -1, 0, false);
        this.styles = new StyleSheet(normal);
        styles.scripts = stats.scripts;
        this.paragraphs = new ParagraphFactory(stats, runs, styles, numbering);
        this.tables = new TableBuilder(paragraphs);
        this.placer = new MediaPlacer(document, figureDpi, pictures, sink, paragraphs);
        this.running = new RunningContent(stats, runs);
        this.flow = new ColumnFlow(stats);
        this.notes = new NoteWriter(sink, paragraphs, runs, styles);
    }

    public StyleSheet styles() {
        return styles;
    }

    private static final class Cursor {
        boolean pageTop = true;
        boolean breakOwed;
        float refBottom;
    }

    private record Slot(float ref, boolean top, boolean pageBreak) {}

    public void page(PageLayout layout, AffineTransform toDisplay) throws IOException {
        PageData page = layout.page();
        pageLayout = layout;
        if (pageCount == 0) {
            DocSink.HeaderFooterSet set = running.runningContent(page.width(), page.height());
            sink.begin(styles, set);
            footerHeight = Math.max(height(set.footer()), height(set.evenFooter()));
            firstFooterHeight = set.titlePage() ? SectionPlanner.runningLineHeight(stats.bodySize) : footerHeight;
        }
        release();
        if (carry != null && !carriedOn(layout)) {
            Carry c = carry;
            carry = null;
            emit(flowParagraph(c.draft(), c.colLeft(), c.colRight(), c.spaceBefore(), c.pageBreak()));
        }
        pageCount++;
        pageBookmark = "_Pg" + (page.index() + 1);
        Section base = SectionPlanner.baseSection(page, stats);
        Cursor at = new Cursor();
        at.breakOwed = section != null;
        at.refBottom = base.marginTop;
        boolean sizeChanged = section != null && !section.samePage(base);
        List<ParaDraft> strayNoteLines = notes.startPage(layout);
        noteHeight = layout.notes().isEmpty() ? 0 : stats.bodySize;
        for (PageLayout.Note note : layout.notes()) {
            for (ParaDraft d : note.paras()) {
                noteHeight += paragraphs.lineHeight(d) * d.lines.size();
            }
        }
        floats.queue(layout, paragraphs, placer, stats.bodySize);
        placeBackgrounds(layout, toDisplay);
        placeVeils(layout);
        float bodyBottom = stats.frame(page.width(), page.height()).bodyBottom();
        boolean pageFlows = layout.bands().stream().anyMatch(DocumentBuilder::flows);
        for (PageLayout.Band band : layout.bands()) {
            if (band.columns().stream().allMatch(c -> c.items().isEmpty())) {
                continue;
            }
            bandAt = -1;
            if (footBand(layout, band)) {
                for (PageLayout.Column c : band.columns()) {
                    for (PageLayout.Item item : c.items()) {
                        floats.add(placer.textBox(placed((PageLayout.ParaItem) item, c)));
                    }
                }
                continue;
            }
            if (pageFlows && !flows(band)) {
                for (PageLayout.Column c : band.columns()) {
                    for (PageLayout.Item item : c.items()) {
                        placeOutOfFlow(item, layout, toDisplay);
                    }
                }
                continue;
            }
            openSection(band, base, sizeChanged, at);
            float bandBottom = at.refBottom;
            bandColumns.add(band.columns().size());
            for (int ci = 0; ci < band.columns().size(); ci++) {
                boolean flowOn = ci > 0 && flow.flowsOn(layout, band, ci, bodyBottom);
                if (ci > 0 && !flowOn) {
                    columnBreak();
                }
                bandAt = bandColumns.size() - 1;
                columnAt = ci;
                float columnBottom = column(layout, band, ci, flowOn, base, at, toDisplay);
                fit(columnBottom);
                bandBottom = Math.max(bandBottom, columnBottom);
            }
            at.refBottom = bandBottom;
        }
        bandAt = -1;
        if (section == null) {
            section = base.copy();
            section.pageNumberStart = running.pageNumberStart();
        }
        finishPage(strayNoteLines, at);
        placer.endPage();
    }

    private void openSection(PageLayout.Band band, Section base, boolean sizeChanged, Cursor at) throws IOException {
        List<float[]> cols = SectionPlanner.columnsOf(band, base);
        if (section != null && !(sizeChanged && at.pageTop) && SectionPlanner.sameColumns(section, cols)) {
            return;
        }
        if (section != null) {
            flushCarry();
            closeSection();
        }
        Section s = base.copy();
        if (section == null) {
            s.pageNumberStart = running.pageNumberStart();
        }
        s.columns.clear();
        s.columns.addAll(cols);
        s.continuous = section != null && !at.pageTop;
        if (at.pageTop) {
            at.breakOwed = false;
        }
        section = s;
    }

    private float column(PageLayout layout, PageLayout.Band band, int ci, boolean flowOn, Section base, Cursor at,
            AffineTransform toDisplay) throws IOException {
        PageLayout.Column col = band.columns().get(ci);
        float prevBottom = at.refBottom;
        boolean columnTop = ci > 0 && !flowOn;
        for (int ii = 0; ii < col.items().size(); ii++) {
            PageLayout.Item item = col.items().get(ii);
            if (placeOutOfFlow(item, layout, toDisplay)) {
                continue;
            }
            if (item instanceof PageLayout.TableItem) {
                pinnedOver(item, col, ii, layout, toDisplay);
            }
            Slot slot = new Slot(at.pageTop ? base.marginTop : prevBottom, at.pageTop || columnTop, at.pageTop && at.breakOwed);
            if (flowOn && ii == 0) {
                flowingSpacer(item, at.pageTop ? base.marginTop : at.refBottom);
                slot = new Slot(Float.MAX_VALUE, false, slot.pageBreak());
            }
            if (item instanceof PageLayout.ParaItem pi && at.pageTop && carry != null && Continuation.continues(carry.draft(), pi.para())) {
                mergeCarry(pi.para());
                prevBottom = paragraphs.wordBottom(pi.para());
            } else {
                prevBottom = place(item, slot, col, isLast(layout, band, ci, ii), layout, toDisplay);
            }
            at.breakOwed &= !at.pageTop;
            at.pageTop = false;
            columnTop = false;
        }
        return prevBottom;
    }

    private void pinnedOver(PageLayout.Item table, PageLayout.Column col, int at, PageLayout layout, AffineTransform toDisplay)
            throws IOException {
        for (int k = at + 1; k < col.items().size(); k++) {
            PageLayout.Item it = col.items().get(k);
            float middle = (it.top() + it.bottom()) / 2f;
            if (it instanceof PageLayout.FloatItem f && f.overlay() && middle > table.top() && middle < table.bottom()
                    && placeOutOfFlow(it, layout, toDisplay)) {
                placedEarly.add(it);
            }
        }
    }

    private boolean carriedOn(PageLayout layout) {
        for (PageLayout.Band band : layout.bands()) {
            if (!flows(band)) {
                continue;
            }
            for (PageLayout.Item item : band.columns().getFirst().items()) {
                if (ColumnFlow.inFlow(item)) {
                    return item instanceof PageLayout.ParaItem pi && Continuation.continues(carry.draft(), pi.para());
                }
            }
            return false;
        }
        return false;
    }

    private static boolean footBand(PageLayout layout, PageLayout.Band band) {
        List<PageLayout.Band> bands = layout.bands();
        if (band.columns().size() < 2 || band.top() < FOOT_ZONE * layout.page().height()) {
            return false;
        }
        for (int i = bands.indexOf(band) + 1; i < bands.size(); i++) {
            if (flows(bands.get(i))) {
                return false;
            }
        }
        for (PageLayout.Column c : band.columns()) {
            int lines = 0;
            for (PageLayout.Item it : c.items()) {
                if (!(it instanceof PageLayout.ParaItem pi)) {
                    return false;
                }
                lines += pi.para().lines.size();
            }
            if (lines > FOOT_LINES) {
                return false;
            }
        }
        return true;
    }

    private static PageLayout.TextBoxItem placed(PageLayout.ParaItem pi, PageLayout.Column c) {
        ParaDraft d = pi.para();
        Box box = new Box(c.left() - 1, pi.top() - 1, c.right() + 1, pi.bottom() + 2);
        return new PageLayout.TextBoxItem(box, -1, d.colLeft, d.colRight, List.of(d), 0f, -1, 0f, false, true);
    }

    private static final float FOOT_ZONE = 0.85f;

    private static final int FOOT_LINES = 3;

    private static boolean flows(PageLayout.Band band) {
        return band.columns().stream().anyMatch(c -> c.items().stream().anyMatch(ColumnFlow::inFlow));
    }

    private boolean placeOutOfFlow(PageLayout.Item item, PageLayout layout, AffineTransform toDisplay) throws IOException {
        if (placedEarly.contains(item)) {
            return true;
        }
        if (item instanceof PageLayout.TextBoxItem tb) {
            floats.add(placer.textBox(tb));
        } else if (item instanceof PageLayout.FloatItem fi) {
            placeFloat(fi, layout, toDisplay);
        } else if (item.behindText()) {
            placeBehind(item, layout, toDisplay);
        } else {
            return false;
        }
        return true;
    }

    private static float growth(Paragraph p) {
        if (p.lineRule != LineRule.AT_LEAST) {
            return 0;
        }
        float most = 0;
        for (Inline in : p.inlines) {
            if (in instanceof Inline.Text t && t.style().vertAlign() == 0) {
                most = Math.max(most, t.style().size());
            }
        }
        return Math.max(0, GROWN_LINE * most - p.lineHeight);
    }

    private static final float GROWN_LINE = 1.15f;

    private static boolean besideWrap(PageLayout.Item item, PageLayout.Column col) {
        for (PageLayout.Item it : col.items()) {
            boolean wraps = it instanceof PageLayout.TextBoxItem tb && !tb.overlay();
            boolean level = it.top() < item.bottom() - 1 && it.bottom() > item.top() + 1;
            boolean aside = it.right() <= item.x() + 1 || it.x() >= item.right() - 1;
            if (wraps && level && aside) {
                return true;
            }
        }
        return false;
    }

    private void flowingSpacer(PageLayout.Item item, float from) throws IOException {
        float itemTop = item instanceof PageLayout.ParaItem pi ? paragraphs.wordTop(pi.para()) : item.top();
        float gap = itemTop - from;
        if (gap > 0.6f) {
            flushCarry();
            emit(paragraphs.spacer(gap));
        }
    }

    private float place(PageLayout.Item item, Slot slot, PageLayout.Column col, boolean lastOnPage, PageLayout layout,
            AffineTransform toDisplay) throws IOException {
        flushCarry();
        float sourceTop = item instanceof PageLayout.ParaItem pi ? paragraphs.wordTop(pi.para()) : item.top();
        float offset = sourceTop - slot.ref();
        float spaceBefore = Math.max(0, offset);
        boolean pageBreak = slot.pageBreak();
        Table table = item instanceof PageLayout.TableItem ti ? tables.build(ti, layout, col.left(), col.right()) : null;
        if (table != null && besideWrap(item, col)) {
            if (table.rightToLeft) {
                table.mirror();
            }
            table.floatX = col.left() + table.indent;
            table.floatY = item.top();
            table.floatRoom = Math.max(0, col.right() - item.right()) + 2f;
        }
        if (table != null && pageBreak && pending instanceof Table prev && !prev.floating() && !table.floating()
                && TableContinuation.continues(prev, table)) {
            if (TableContinuation.join(prev, table, pageBookmark)) {
                pageBookmark = null;
                return item.bottom();
            }
            offset += TableContinuation.dropLeftover(table);
            spaceBefore = Math.max(0, offset);
        }
        if (slot.top()) {
            boolean opens = table != null && pageBreak && offset <= 1f && !table.floating() && opener(table) != null;
            pageBreak = opens || topSpacer(offset, pageBreak);
            spaceBefore = 0;
        }
        float bottom;
        if (table != null) {
            emitTable(table, spaceBefore, pageBreak, item.bottom());
            bottom = item.bottom();
        } else if (item instanceof PageLayout.ParaItem pi) {
            ParaDraft d = pi.para();
            homePage.put(d, pageLayout);
            bottom = paragraphs.wordBottom(d);
            if (lastOnPage && Continuation.looksUnfinished(d, stats.bodySize)) {
                carry = new Carry(d, col.left(), col.right(), spaceBefore, pageBreak, slot.top() ? Float.NaN : slot.ref(),
                        !layout.notes().isEmpty());
            } else {
                List<Inline.Shape> under = shapesUnder(d, bottom, nextInFlow(col, item));
                Paragraph p = flowParagraph(d, col.left(), col.right(), spaceBefore, pageBreak);
                emit(p);
                bottom += growth(p);
                if (!under.isEmpty()) {
                    bottom = underRow(under, bottom);
                }
            }
        } else {
            emitItem(item, layout, toDisplay, col.left(), col.right(), spaceBefore, pageBreak);
            bottom = item.bottom();
        }
        return bottom + overlap(offset, slot.ref());
    }

    private void placeFloat(PageLayout.FloatItem fi, PageLayout layout, AffineTransform toDisplay) throws IOException {
        Picture pic = placer.picture(fi.picture(), layout, toDisplay);
        if (pic == null) {
            return;
        }
        floats.stack(pic);
        pic.wrap = fi.overlay() ? Picture.Wrap.NONE : Picture.Wrap.SQUARE;
        pic.behind = fi.overlay();
        pic.x = fi.x();
        pic.y = fi.top();
        pic.wrapGap = fi.gap();
        if (pending instanceof Paragraph p && pendingPage == pageCount && fi.top() >= p.sourceTop - 3
                && fi.top() < p.sourceBottom) {
            pic.fromParagraph = true;
            pic.y = fi.top() - PageFloats.anchorTop(p);
            p.inlines.addFirst(new Inline.Image(pic));
        } else {
            floats.add(pic);
        }
    }

    private void placeBackgrounds(PageLayout layout, AffineTransform toDisplay) throws IOException {
        placedEarly.clear();
        float most = BACKDROP * layout.page().width() * layout.page().height();
        for (PageLayout.Band band : layout.bands()) {
            for (PageLayout.Column c : band.columns()) {
                for (PageLayout.Item it : c.items()) {
                    if (it.behindText() && (it.right() - it.x()) * (it.bottom() - it.top()) >= most) {
                        placeBehind(it, layout, toDisplay);
                        placedEarly.add(it);
                    }
                }
            }
        }
    }

    private void placeVeils(PageLayout layout) throws IOException {
        if (layout.veils().isEmpty()) {
            return;
        }
        List<Box> shown = Rendered.regions(layout);
        for (PageLayout.Veil veil : layout.veils()) {
            Picture pic = placer.veil(veil, layout.page(), shown);
            if (pic == null) {
                continue;
            }
            floats.stack(pic);
            pic.wrap = Picture.Wrap.NONE;
            pic.behind = true;
            pic.x = veil.box().x();
            pic.y = veil.box().top();
            floats.add(pic);
        }
    }

    private void placeBehind(PageLayout.Item item, PageLayout layout, AffineTransform toDisplay) throws IOException {
        Picture pic = placer.picture(item, layout, toDisplay);
        if (pic == null) {
            return;
        }
        floats.stack(pic);
        pic.wrap = Picture.Wrap.NONE;
        pic.behind = true;
        pic.x = item.x();
        pic.y = item.top();
        PageData page = layout.page();
        pic.backdrop = (item.right() - item.x()) * (item.bottom() - item.top()) >= BACKDROP * page.width() * page.height();
        floats.add(pic);
    }

    private void finishPage(List<ParaDraft> strayNoteLines, Cursor at) throws IOException {
        for (ParaDraft d : strayNoteLines) {
            flushCarry();
            emit(paragraphs.paragraph(d, d.colLeft, d.colRight, Math.max(0, paragraphs.wordTop(d) - at.refBottom),
                    at.pageTop && at.breakOwed));
            at.refBottom = paragraphs.wordBottom(d);
            at.breakOwed = false;
            at.pageTop = false;
        }
        notes.endPage();
        floats.unseat(carry == null ? null : carry.draft());
        if (at.breakOwed || at.pageTop && pageCount == 1) {
            flushCarry();
            Paragraph empty = paragraphs.spacer(1);
            empty.pageBreakBefore = at.breakOwed;
            emit(empty);
        }
        if (floats.waiting()) {
            flushCarry();
            if (pageBookmark != null) {
                emit(paragraphs.spacer(1));
            }
            if (pending instanceof Paragraph p) {
                floats.anchorLeftovers(p, p == joined ? p.inlines.size() : 0);
            } else {
                Paragraph carrier = paragraphs.spacer(1);
                emit(carrier);
                floats.anchorLeftovers(carrier, 0);
            }
        }
    }

    private static float overlap(float offset, float ref) {
        return ref == Float.MAX_VALUE ? 0 : Math.max(0, -offset);
    }

    private boolean topSpacer(float offset, boolean pageBreak) throws IOException {
        if (offset <= 0.6f) {
            return pageBreak;
        }
        flushCarry();
        Paragraph sp = paragraphs.spacer(offset);
        sp.pageBreakBefore = pageBreak;
        emit(sp);
        return false;
    }

    private static boolean isLast(PageLayout layout, PageLayout.Band band, int ci, int ii) {
        List<PageLayout.Band> bands = layout.bands();
        if (bands.getLast() != band || ci != band.columns().size() - 1) {
            return false;
        }
        return ii == band.columns().get(ci).items().size() - 1;
    }

    private void emit(Block b) throws IOException {
        if (b instanceof Paragraph anchor) {
            floats.anchorOn(anchor);
        }
        if (pageBookmark != null && b instanceof Paragraph p && p.bookmark == null) {
            p.bookmark = pageBookmark;
            pageBookmark = null;
        }
        if (pending != null) {
            held.add(pending);
        }
        pending = b;
        pendingPage = pageCount;
        if (bandAt >= 0 && b instanceof Paragraph p) {
            spaced.add(new Spaced(p, bandAt, columnAt));
        }
    }

    private void release() throws IOException {
        for (Block b : held) {
            sink.block(b);
        }
        held.clear();
        spaced.clear();
        bandColumns.clear();
        pageShift = 0;
    }

    private static float height(List<Paragraph> ps) {
        float h = 0;
        for (Paragraph p : ps) {
            h += p.spaceBefore + p.spaceAfter + Math.max(p.lineHeight, 1f) * Math.max(1, p.sourceLines);
        }
        return h;
    }

    private void fit(float bottom) {
        if (section == null || bottom == Float.MAX_VALUE) {
            return;
        }
        float footer = pageCount == 1 ? firstFooterHeight : footerHeight;
        float limit = section.pageHeight - Math.max(section.marginBottom, footer > 0 ? section.footerDistance + footer : 0);
        float excess = bottom + noteHeight - pageShift + FIT_SAFETY * stats.pitchFor(stats.bodySize) - limit;
        if (excess <= 0) {
            return;
        }
        float room = 0;
        for (Spaced s : spaced) {
            room += eligible(s) ? reducible(s.p()) : 0;
        }
        if (room <= 0) {
            return;
        }
        float share = Math.min(1f, excess / room);
        for (Spaced s : spaced) {
            if (eligible(s)) {
                float took = shrink(s.p(), share);
                if (s.band() != bandAt) {
                    pageShift += took;
                }
            }
        }
    }

    private boolean eligible(Spaced s) {
        return s.band() == bandAt && s.column() == columnAt || s.band() < bandAt && bandColumns.get(s.band()) == 1;
    }

    private static boolean spacer(Paragraph p) {
        return p.inlines.isEmpty() && p.lineRule == LineRule.EXACT && p.lineHeight > 1 && p.endsSection == null;
    }

    private static float reducible(Paragraph p) {
        return FIT_SHRINK * (p.spaceBefore + p.spaceAfter + (spacer(p) ? p.lineHeight - 1 : 0));
    }

    private static float shrink(Paragraph p, float share) {
        float took = share * reducible(p);
        float cut = share * FIT_SHRINK;
        if (spacer(p)) {
            p.lineHeight -= cut * (p.lineHeight - 1);
        }
        p.spaceBefore -= cut * p.spaceBefore;
        p.spaceAfter -= cut * p.spaceAfter;
        return took;
    }

    private static final float FIT_SAFETY = 1f;

    private static final float FIT_SHRINK = 0.75f;

    private void closeSection() throws IOException {
        if (pending instanceof Paragraph p && p.endsSection == null) {
            p.endsSection = section;
        } else {
            Paragraph carrier = hairline(1);
            carrier.endsSection = section;
            emit(carrier);
        }
    }

    private void columnBreak() throws IOException {
        flushCarry();
        Paragraph carrier = hairline(1);
        carrier.inlines.add(new Inline.ColumnBreak());
        emit(carrier);
    }

    public void finish(String title, String author) throws IOException {
        flushCarry();
        notes.finish();
        if (section == null) {
            section = new Section();
            section.pageWidth = 612;
            section.pageHeight = 792;
            section.marginTop = section.marginBottom = section.marginLeft = section.marginRight = 72;
            section.columns.add(new float[] {468, 0});
        }
        if (pending == null || pending instanceof Table) {
            emit(hairline(1));
        }
        release();
        if (pending != null) {
            sink.block(pending);
            pending = null;
        }
        sink.finish(section, null, numbering, styles, title, author);
    }

    private void flushCarry() throws IOException {
        if (carry != null) {
            Carry c = carry;
            carry = null;
            String mark = pageBookmark;
            pageBookmark = null;
            emitFromLastPage(flowParagraph(c.draft(), c.colLeft(), c.colRight(), c.spaceBefore(), c.pageBreak()));
            pageBookmark = mark;
        }
    }

    private void emitFromLastPage(Paragraph p) throws IOException {
        PageFloats held = floats.setAside();
        emit(p);
        pendingPage = pageCount - 1;
        floats.restore(held);
    }

    private boolean keepsPage(Carry carried, ParaDraft rest) {
        ParaDraft cut = carried.draft();
        if (!carried.notes() || Continuation.exactFonts(cut) && Continuation.exactFonts(rest)) {
            return true;
        }
        float page = pageLayout.page().width() * pageLayout.page().height();
        for (PageLayout.Decoration d : pageLayout.decorations()) {
            if (d.box().area() >= SLIDING_SHAPE * page) {
                return true;
            }
        }
        for (PageLayout.Band band : pageLayout.bands()) {
            for (PageLayout.Column c : band.columns()) {
                for (PageLayout.Item it : c.items()) {
                    if (it instanceof PageLayout.TextBoxItem || it instanceof PageLayout.FloatItem || it.behindText()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void mergeCarry(ParaDraft next) throws IOException {
        Carry c = carry;
        carry = null;
        ParaDraft merged = Continuation.join(c.draft(), next, keepsPage(c, next));
        floats.rehost(c.draft(), merged);
        floats.unhost(next);
        float spaceBefore = Float.isNaN(c.ref()) ? c.spaceBefore() : Math.max(0, paragraphs.wordTop(merged) - c.ref());
        joined = flowParagraph(merged, c.colLeft(), c.colRight(), spaceBefore, c.pageBreak());
        emitFromLastPage(joined);
    }

    private Paragraph flowParagraph(ParaDraft d, float colLeft, float colRight, float spaceBefore, boolean pageBreak) {
        Paragraph p = paragraphs.paragraph(d, colLeft, colRight, spaceBefore, pageBreak);
        floats.ride(d, p);
        shade(p, d, homePage.remove(d), colLeft, colRight);
        return p;
    }

    private void shade(Paragraph p, ParaDraft d, PageLayout home, float colLeft, float colRight) {
        if (home == null || p.bidi || Float.isNaN(p.sourceTop) || Float.isNaN(p.sourceBottom) || d.lines.size() != 1) {
            return;
        }
        float left = colLeft + Math.min(p.indentLeft, p.indentLeft + p.indentFirst) - SHADE_OUT;
        float right = colRight + SectionPlanner.RIGHT_SLACK - p.indentRight + SHADE_OUT;
        LabelGround.Ground g = under(home, p, left - PANEL_ROOM, right + PANEL_ROOM, PANEL_ROOM, PANEL_ROOM);
        PageLayout.Decoration panel = g == null || g.paint() == null ? null : drawnPanel(g.paint(), home);
        if (panel == null || floats.host(panel) != d || !ShapeAnchors.alone(home, panel.box(), d, paragraphs)) {
            return;
        }
        int rgb = g.rgb();
        if (pending instanceof Paragraph prev && prev.shading == rgb && shadedOn.get(prev) != g.paint()) {
            rgb ^= 1;
        }
        p.shading = rgb;
        shadedOn.put(p, g.paint());
    }

    private static final float PANEL_ROOM = 1.5f;

    private static PageLayout.Decoration drawnPanel(Object paint, PageLayout home) {
        int order = home.page().graphics().order(paint);
        for (PageLayout.Decoration panel : home.decorations()) {
            if (panel.order() == order && panel.rgb() >= 0) {
                return panel;
            }
        }
        return null;
    }

    private static LabelGround.Ground under(PageLayout home, Paragraph p, float left, float right, float above, float below) {
        return LabelGround.ground(new Box(left, p.sourceTop - above, right, p.sourceBottom + below), home.page().graphics(),
                home.textPaint());
    }

    private static final float SHADE_OUT = 1.5f;

    private List<Inline.Shape> shapesUnder(ParaDraft d, float bottom, PageLayout.Item next) {
        return next instanceof PageLayout.ParaItem np ? floats.takeUnder(d, bottom, paragraphs.wordTop(np.para())) : List.of();
    }

    private float underRow(List<Inline.Shape> shapes, float top) throws IOException {
        float bottom = top;
        for (Inline.Shape s : shapes) {
            bottom = Math.max(bottom, s.y() + s.height());
        }
        Paragraph row = hairline(Math.max(1f, bottom - top));
        row.sourceTop = top;
        row.sourceBottom = top + row.lineHeight;
        for (Inline.Shape s : shapes) {
            row.inlines.add(s.riding(s.y() - top));
        }
        emit(row);
        return row.sourceBottom;
    }

    private Paragraph hairline(float height) {
        Paragraph p = new Paragraph();
        p.markStyle = styles.normal.withSize(1);
        p.lineRule = LineRule.EXACT;
        p.lineHeight = height;
        return p;
    }

    private static PageLayout.Item nextInFlow(PageLayout.Column col, PageLayout.Item item) {
        List<PageLayout.Item> items = col.items();
        for (int i = items.indexOf(item) + 1; i > 0 && i < items.size(); i++) {
            if (ColumnFlow.inFlow(items.get(i))) {
                return items.get(i);
            }
        }
        return null;
    }

    private void emitItem(PageLayout.Item item, PageLayout layout, AffineTransform toDisplay, float colLeft, float colRight,
            float spaceBefore, boolean pageBreak) throws IOException {
        if (item instanceof PageLayout.TableItem ti) {
            emitTable(tables.build(ti, layout, colLeft, colRight), spaceBefore, pageBreak, item.bottom());
        } else if (item instanceof PageLayout.PictureRow row) {
            emitPictureRow(row, layout, toDisplay, colLeft, colRight, spaceBefore, pageBreak);
        } else {
            emitPicture(item, layout, toDisplay, colLeft, colRight, spaceBefore, pageBreak);
        }
    }

    private void emitTable(Table t, float spaceBefore, boolean pageBreak, float bottom) throws IOException {
        Paragraph opener = pageBreak && !t.floating() && spaceBefore <= 1 ? opener(t) : null;
        if (opener != null) {
            opener.pageBreakBefore = true;
            t.pageBreakBefore = true;
            if (pageBookmark != null && opener.bookmark == null) {
                opener.bookmark = pageBookmark;
                pageBookmark = null;
            }
        } else if (pageBreak) {
            Paragraph sp = paragraphs.spacer(1);
            sp.pageBreakBefore = true;
            emit(sp);
        }
        if (t.floating()) {
            if (pending instanceof Table) {
                emit(hairline(1));
            }
        } else if (spaceBefore > 1) {
            if (pending instanceof Paragraph prev && prev.endsSection == null && !hasBreak(prev)) {
                prev.spaceAfter += spaceBefore;
            } else {
                emit(paragraphs.spacer(spaceBefore));
            }
        } else if (pending instanceof Table) {
            emit(hairline(1));
        }
        boolean joinedHere = pending == joined && pendingPage == pageCount - 1;
        if (floats.waiting() && pending instanceof Paragraph prev && (pendingPage == pageCount || joinedHere)) {
            floats.anchorAbove(prev, joinedHere ? prev.inlines.size() : 0, bottom);
        }
        emit(t);
    }

    private static Paragraph opener(Table t) {
        if (t.rows.isEmpty() || t.rows.getFirst().cells.isEmpty()) {
            return null;
        }
        List<Paragraph> ps = t.rows.getFirst().cells.getFirst().paragraphs;
        return ps.isEmpty() ? null : ps.getFirst();
    }

    private void emitPictureRow(PageLayout.PictureRow row, PageLayout layout, AffineTransform toDisplay, float colLeft,
            float colRight, float spaceBefore, boolean pageBreak) throws IOException {
        Paragraph p = new Paragraph();
        p.pageBreakBefore = pageBreak;
        p.markStyle = styles.normal.withSize(1);
        p.lineRule = LineRule.AUTO;
        p.spaceBefore = spaceBefore;
        p.indentLeft = Math.max(-colLeft, row.x() - colLeft);
        p.indentRight = Math.min(0, colRight - row.right()) - 2f;
        boolean firstPic = true;
        for (PageLayout.Item it : row.pictures()) {
            Picture pic = placer.picture(it, layout, toDisplay);
            if (pic == null) {
                continue;
            }
            if (!firstPic) {
                p.inlines.add(new Inline.Tab(null));
                p.tabs.add(new Paragraph.TabStop(Math.max(0, it.x() - colLeft), Paragraph.TabStop.Kind.LEFT, (char) 0));
            }
            p.inlines.add(new Inline.Image(pic));
            firstPic = false;
        }
        emit(p);
    }

    private void emitPicture(PageLayout.Item item, PageLayout layout, AffineTransform toDisplay, float colLeft,
            float colRight, float spaceBefore, boolean pageBreak) throws IOException {
        Picture pic = placer.picture(item, layout, toDisplay);
        if (pic == null) {
            return;
        }
        Paragraph p = new Paragraph();
        p.pageBreakBefore = pageBreak;
        p.markStyle = styles.normal.withSize(1);
        p.lineRule = LineRule.AUTO;
        p.lineHeight = 0;
        p.spaceBefore = spaceBefore;
        float width = colRight - colLeft;
        float centre = (item.x() + item.right()) / 2f;
        if (Math.abs(centre - (colLeft + colRight) / 2f) < width * 0.04f && item.x() > colLeft + 4) {
            p.align = Align.CENTER;
        } else if (Math.abs(colRight - item.right()) < 4 && item.x() > colLeft + width * 0.2f) {
            p.align = Align.RIGHT;
        } else {
            p.indentLeft = Math.max(0, item.x() - colLeft);
        }
        if (item.right() - item.x() > width + 2) {
            p.indentLeft = item.x() - colLeft;
            p.indentRight = colRight - item.right();
        }
        p.inlines.add(new Inline.Image(pic));
        emit(p);
    }

    private static boolean hasBreak(Paragraph p) {
        return !p.inlines.isEmpty() && p.inlines.getLast() instanceof Inline.ColumnBreak;
    }

    static float round(float v) {
        return Math.round(v * 2f) / 2f;
    }

}

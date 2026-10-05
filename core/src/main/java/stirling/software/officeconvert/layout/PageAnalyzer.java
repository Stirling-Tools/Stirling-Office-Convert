package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageData;
import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics.ImageDraw;
import stirling.software.officeconvert.extract.PageGraphics.Rule;
import stirling.software.officeconvert.extract.PageGraphics;
import stirling.software.officeconvert.model.Table;
import stirling.software.officeconvert.table.TableDetection;

public final class PageAnalyzer {

    private static final int OUTLINED_LETTERS = 80;
    private static final float PROSE_OVER_DRAWING = 0.3f;

    private final DocStats stats;
    private final ParagraphBuilder paragraphs;
    private final PlacedText placed;
    private final TextBoxFinder textBoxFinder;
    private final TableFinder tableFinder;
    private final boolean detectTables;
    private final boolean forms;
    private final boolean tabLeaders;
    private int noteCounter;

    private record Notes(List<PageLayout.Note> notes, List<ParaDraft> continuation) {}

    public PageAnalyzer(DocStats stats, boolean detectTables) {
        this(stats, detectTables, false, false);
    }

    public PageAnalyzer(DocStats stats, boolean detectTables, boolean forms, boolean tabLeaders) {
        this.stats = stats;
        this.forms = forms;
        this.tabLeaders = tabLeaders;
        this.paragraphs = new ParagraphBuilder(stats);
        this.placed = new PlacedText(paragraphs);
        this.textBoxFinder = new TextBoxFinder(paragraphs);
        this.tableFinder = new TableFinder(stats, paragraphs);
        this.detectTables = detectTables;
    }

    public PageLayout analyze(PageData page) {
        boolean ocr = OcrText.dominates(page);
        List<Glyph> glyphs = OcrText.pageGlyphs(page);
        PageGraphics gfx = page.graphics();
        if (glyphs.isEmpty() && page.rotated().isEmpty() && outlinedText(gfx)) {
            return FallbackPage.of(page);
        }
        Set<Object> usedMarks = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Glyph> bullets = VectorBullets.synthesize(glyphs, gfx.marks(), gfx.fills(), usedMarks);
        if (!bullets.isEmpty()) {
            glyphs = new ArrayList<>(glyphs);
            glyphs.addAll(bullets);
        }
        markLinks(glyphs, page.links());
        List<Line> segments = new ArrayList<>(LineBuilder.build(glyphs));
        segments.removeIf(s -> stats.headerFooter.match(page, s) != null);
        List<Line> furnitureLines = new ArrayList<>(stats.headerFooter.furniture(page, segments));
        float flowBottom = stats.frame(page.width(), page.height()).flowBottom(page.height());
        furnitureLines.addAll(PlacedText.belowFlow(segments, furnitureLines, flowBottom));
        segments.removeAll(furnitureLines);

        List<Rule> rules = new ArrayList<>(gfx.rules());
        List<Fill> fills = new ArrayList<>(gfx.fills());
        fills.removeIf(usedMarks::contains);
        Decorations.underlines(segments, rules);
        Decorations.highlights(segments, fills);
        Set<Object> textPaint = Collections.newSetFromMap(new IdentityHashMap<>());
        textPaint.addAll(gfx.rules());
        textPaint.addAll(gfx.fills());
        rules.forEach(textPaint::remove);
        fills.forEach(textPaint::remove);
        if (forms && FormPage.looksLikeForm(page, segments, rules)) {
            return formPage(page, ocr, concat(segments, furnitureLines), rules, fills, textPaint);
        }

        boolean tinted = tinted(page, gfx);
        Veils.Split veiled = Veils.split(gfx.marks(), gfx);
        List<PageGraphics.VectorMark> art = veiled.art();
        List<TextBoxFinder.Container> containers = TextBoxFinder.containers(unused(art, usedMarks), segments, tinted);
        for (TextBoxFinder.Container c : containers) {
            usedMarks.add(c.mark());
        }
        PageFrame frame = stats.frame(page.width(), page.height());
        TextBoxFinder.Result boxes = textBoxFinder.find(fills, containers, segments, frame.textLeft(), frame.textRight());
        for (PageLayout.TextBoxItem tb : boxes.boxes()) {
            fills.removeIf(f -> Math.abs(f.x() - tb.box().x()) < 0.5f && Math.abs(f.top() - tb.box().top()) < 0.5f
                    && Math.abs(f.right() - tb.box().right()) < 0.5f);
        }
        List<Box> shaped = new ArrayList<>();
        containers.removeIf(c -> {
            if (boxes.claimed().contains(c) || c.mark().boxy()) {
                return false;
            }
            if (labelsOnly(c.box(), segments)) {
                usedMarks.remove(c.mark());
            } else {
                shaped.add(c.box());
            }
            return true;
        });

        List<PageGraphics.VectorMark> papers = new ArrayList<>();
        for (PageGraphics.VectorMark m : unused(art, usedMarks)) {
            if (panel(m, page, segments)) {
                usedMarks.add(m);
                papers.add(m);
            }
        }
        List<Box> strong = new ArrayList<>(MathFinder.displayMath(segments, stats.bodySize));
        for (Box b : FigureFinder.strongRegions(unused(art, usedMarks), page.width(), page.height())) {
            if (!FigureFinder.isProse(b, segments)) {
                strong.add(b);
            }
        }
        List<Box> drawings = new ArrayList<>();
        List<Box> drawnBehind = new ArrayList<>();
        for (PageGraphics.Area a : gfx.pastBudget()) {
            Box b = new Box(a.x(), a.top(), a.right(), a.bottom());
            if (b.width() >= FigureFinder.MIN_SIDE && b.height() >= FigureFinder.MIN_SIDE) {
                (FigureFinder.runningShare(b, segments) >= PROSE_OVER_DRAWING ? drawnBehind : drawings).add(b);
            }
        }
        for (PageGraphics.Area a : gfx.masked()) {
            drawnBehind.add(new Box(a.x(), a.top(), a.right(), a.bottom()));
        }
        strong.addAll(drawings);

        ImageDraw scanned = ocr ? fullPageImage(page, gfx.images()) : null;
        if (scanned != null) {
            rules.addAll(ScanRules.find(scanned));
        }
        List<TableDetection.Found> tables = detectTables
                ? tableFinder.find(page, frame.textLeft(), frame.textRight(), segments, rules, fills, strong)
                : new ArrayList<>();
        tables.removeIf(t -> TableShaper.looksLikeList(t, TableShaper.assign(t, TableFinder.wordsInside(segments, t))));
        if (detectTables) {
            addKeyValueTables(tables, segments, frame);
        }
        List<Box> tableBoxes = tables.stream().map(TableFinder::boxOf).toList();
        List<Rule> freeRules = new ArrayList<>(rules.stream()
                .filter(r -> !Regions.insideAny(Regions.of(r), tableBoxes, 2f) && !bordersCell(r, tables)).toList());
        List<Fill> freeFills = new ArrayList<>(fills.stream().filter(f -> !Regions.insideAny(Regions.of(f), tableBoxes, 2f)).toList());
        List<Box> figures = new ArrayList<>();
        List<Box> backdrops = new ArrayList<>(FigureFinder.cluster(concat(shaped, drawnBehind), 1f));
        List<PageGraphics.VectorMark> marks = unused(art, usedMarks);
        for (Box f : FigureFinder.figures(strong, freeFills, freeRules, segments, marks, page.width(), page.height())) {
            if (!Regions.overlapsMostly(f, tableBoxes) || cellArt(f, tableBoxes)) {
                (Backdrops.isBackdrop(f, segments) ? backdrops : figures).add(f);
            }
        }
        List<Box> wholeDrawings = new ArrayList<>();
        if (!drawings.isEmpty()) {
            figures.addAll(drawings);
            figures = new ArrayList<>(FigureFinder.cluster(figures, 1f));
            wholeDrawings.addAll(figures.stream().filter(f -> drawings.stream().anyMatch(d -> f.overlapArea(d) > 0)).toList());
            backdrops.removeIf(b -> Regions.insideAny(b, wholeDrawings, 2f));
            furnitureLines.removeIf(l -> Regions.insideAny(l.centre(), Regions.wordY(l), wholeDrawings, 0.5f));
        }
        List<RotatedText.Block> turned = RotatedText.blocks(page.rotated(), page.width(), page.height());
        figures = new ArrayList<>(FigureLabels.grow(figures, labels(segments, turned, tableBoxes), stats.bodySize));
        figures.addAll(Pictograms.find(segments, stats));
        if (ocr) {
            ImageDraw scan = fullPageImage(page, gfx.images());
            if (scan != null) {
                figures.addAll(ScanRegions.find(scan, segments, page.width(), page.height()));
            }
        }

        List<PageLayout.Item> blocks = new ArrayList<>(boxes.boxes());
        blocks.removeIf(b -> Regions.insideAny(new Box(b.x(), b.top(), b.right(), b.bottom()), wholeDrawings, 2f));
        diagrams(figures, backdrops, gfx, segments, blocks, page.width());
        List<Box> pictured = figures;
        blocks.removeIf(b -> b instanceof PageLayout.TextBoxItem tb && boxes.boxes().contains(tb) && labels(tb)
                && Regions.insideAny(tb.box(), pictured, 2f));
        List<Box> drawn = concat(figures, backdrops);
        addImages(page, gfx.images(), drawn, ocr, segments, blocks);
        for (Box f : figures) {
            blocks.add(new PageLayout.FigureItem(f, false));
        }
        for (Box b : backdrops) {
            blocks.add(new PageLayout.FigureItem(b, true));
        }
        PageGraphics.VectorMark paper = paintedPaper(page, art);
        if (paper != null) {
            papers.addFirst(paper);
        }
        for (PageGraphics.VectorMark p : papers) {
            Box on = new Box(Math.max(0, p.x()), Math.max(0, p.top()), Math.min(page.width(), p.right()),
                    Math.min(page.height(), p.bottom()));
            blocks.add(new PageLayout.FigureItem(on, true, true, gfx.order(p)));
        }
        List<Line> flow = claimWords(segments, tables, figures, blocks);
        Notes notes = footnotes(flow, freeRules, frame, page);
        List<Line> sideNotes = new ArrayList<>();
        List<PageLayout.Band> bands = bands(page, flow, blocks, frame, ocr, sideNotes);

        List<PageGraphics.VectorMark> dots = marks.stream().filter(PageGraphics.VectorMark::round).toList();
        List<PageLayout.Decoration> decorations =
                new ArrayList<>(Decorations.leftovers(freeFills, freeRules, dots, drawn, tableBoxes, tinted, gfx::order));
        for (TextBoxFinder.Container c : containers) {
            if (!boxes.claimed().contains(c) && (c.line() >= 0 || c.fill() >= 0) && !Regions.insideAny(c.box(), wholeDrawings, 2f)) {
                decorations.add(new PageLayout.Decoration(c.box(), c.fill(), c.line(), c.lineWidth(), c.rounded(), false,
                        gfx.order(c.mark())));
            }
        }
        unfillHolders(bands, boxes, decorations, gfx);
        List<PageLayout.TextBoxItem> furniture = placed.rows(concat(furnitureLines, sideNotes), page.width());
        for (RotatedText.Block b : turned) {
            if (!Regions.insideAny(b.onPage().centreX(), b.onPage().centreY(), figures, 0.5f)) {
                furniture.add(placed.turned(b, page.width(), page.height()));
            }
        }
        groundTextBoxes(bands, furniture, decorations, gfx);
        return new PageLayout(page, bands, ocr, decorations, notes.notes(), notes.continuation(), furniture,
                veiled.veils(), textPaint);
    }

    private PageLayout formPage(PageData page, boolean ocr, List<Line> lines, List<Rule> rules, List<Fill> fills,
            Set<Object> textPaint) {
        PageGraphics gfx = page.graphics();
        List<PageLayout.TextBoxItem> boxes = new ArrayList<>();
        List<Line> pieces = FormPage.pieces(lines, rules, fills, tabLeaders);
        for (List<Line> block : FormPage.blocks(pieces, rules)) {
            boxes.add(placed.form(block, FormPage.room(block, pieces, rules, page.width()), page.width()));
        }
        for (RotatedText.Block b : RotatedText.blocks(page.rotated(), page.width(), page.height())) {
            boxes.add(placed.turned(b, page.width(), page.height()));
        }
        List<PageLayout.Item> pictures = new ArrayList<>();
        for (ImageDraw img : gfx.images()) {
            if (img.clipRight() - img.clipX() >= 3 && img.clipBottom() - img.clipTop() >= 3) {
                pictures.add(new PageLayout.FloatItem(new PageLayout.ImageItem(img, true), 0f, true));
            }
        }
        for (PageGraphics.VectorMark m : gfx.marks()) {
            Box b = new Box(Math.max(0, m.x()), Math.max(0, m.top()), Math.min(page.width(), m.right()),
                    Math.min(page.height(), m.bottom()));
            if (b.width() > 0.5f && b.height() > 0.5f) {
                pictures.add(new PageLayout.FloatItem(new PageLayout.FigureItem(b.grow(STROKE_MARGIN), true), 0f, true));
            }
        }
        List<PageLayout.Band> bands = pictures.isEmpty()
                ? List.of()
                : List.of(new PageLayout.Band(0, page.height(), List.of(new PageLayout.Column(0, page.width(), pictures))));
        return new PageLayout(page, bands, ocr, FormPage.shapes(rules, fills, gfx), List.of(), List.of(), boxes, List.of(),
                textPaint);
    }

    private static List<FigureLabels.Label> labels(List<Line> segments, List<RotatedText.Block> turned, List<Box> tables) {
        List<FigureLabels.Label> out = new ArrayList<>();
        for (Line l : segments) {
            if (!Regions.insideAny(l.centre(), Regions.wordY(l), tables, 0.5f)) {
                out.add(FigureLabels.of(l));
            }
        }
        for (RotatedText.Block b : turned) {
            out.add(new FigureLabels.Label(b.onPage(), b.size(), b.text()));
        }
        return out;
    }

    private static List<PageGraphics.VectorMark> unused(List<PageGraphics.VectorMark> marks, Set<Object> used) {
        List<PageGraphics.VectorMark> out = new ArrayList<>(marks);
        out.removeIf(used::contains);
        return out;
    }

    private static ImageDraw fullPageImage(PageData page, List<ImageDraw> images) {
        for (ImageDraw img : images) {
            Box b = new Box(img.clipX(), img.clipTop(), img.clipRight(), img.clipBottom());
            if (b.area() > page.width() * page.height() * 0.6f) {
                return img;
            }
        }
        return null;
    }

    private static boolean cellArt(Box f, List<Box> tableBoxes) {
        for (Box t : tableBoxes) {
            if (f.overlapArea(t) >= 0.5f * f.area()) {
                return f.width() < CELL_ART * t.width();
            }
        }
        return false;
    }

    private static final float CELL_ART = 0.4f;

    private static final float BORDER_OVERSHOOT = 6f;

    private static boolean bordersCell(Rule r, List<TableDetection.Found> tables) {
        if (!r.horizontal()) {
            return false;
        }
        for (TableDetection.Found t : tables) {
            float overshoot = Math.max(BORDER_OVERSHOOT, 0.05f * (t.right() - t.left()));
            if (r.start() < t.left() - overshoot || r.end() > t.right() + overshoot) {
                continue;
            }
            for (TableDetection.FoundCell c : t.cells()) {
                float mid = (c.left() + c.right()) / 2f;
                boolean edge = c.borderTop() && Math.abs(r.pos() - c.top()) < Table.BORDER_REACH
                        || c.borderBottom() && Math.abs(r.pos() - c.bottom()) < Table.BORDER_REACH;
                if (edge && r.start() <= mid && r.end() >= mid) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void addKeyValueTables(List<TableDetection.Found> tables, List<Line> segments, PageFrame frame) {
        for (TableDetection.Found kv : KeyValueTables.find(segments, frame.textLeft(), frame.textRight())) {
            Box box = TableFinder.boxOf(kv);
            if (tables.stream().anyMatch(t -> t.ruled() && Regions.overlapsMostly(TableFinder.boxOf(t), List.of(box)))) {
                continue;
            }
            tables.removeIf(t -> intersects(TableFinder.boxOf(t), box));
            tables.add(kv);
        }
    }

    private static boolean intersects(Box a, Box b) {
        return a.x() < b.right() && b.x() < a.right() && a.top() < b.bottom() && b.top() < a.bottom();
    }

    private static void addImages(PageData page, List<ImageDraw> images, List<Box> figures, boolean ocr,
            List<Line> segments, List<PageLayout.Item> blocks) {
        for (ImageDraw img : images) {
            Box b = new Box(img.clipX(), img.clipTop(), img.clipRight(), img.clipBottom());
            if (b.width() < 3 || b.height() < 3 || Regions.insideAny(b, figures, 2f)) {
                continue;
            }
            boolean fullPage = b.area() > page.width() * page.height() * 0.6f;
            if (fullPage && ocr) {
                continue;
            }
            boolean behind = fullPage ? !segments.isEmpty() : Backdrops.isBackdrop(b, segments);
            blocks.add(new PageLayout.ImageItem(img, behind));
        }
    }

    private List<Line> claimWords(
            List<Line> segments, List<TableDetection.Found> tables, List<Box> figures, List<PageLayout.Item> blocks) {
        List<Line> flow = new ArrayList<>();
        Map<TableDetection.Found, List<Word>> tableWords = new IdentityHashMap<>();
        for (TableDetection.Found t : tables) {
            tableWords.put(t, new ArrayList<>());
        }
        for (Line seg : segments) {
            List<Word> keep = new ArrayList<>();
            for (Word w : seg.words) {
                float cx = Regions.wordX(w);
                float cy = Regions.wordY(seg);
                TableDetection.Found owner = tables.stream().filter(t -> TableFinder.contains(t, cx, cy)).findFirst().orElse(null);
                if (owner != null) {
                    tableWords.get(owner).add(w);
                } else if (!Regions.insideAny(cx, cy, figures, 0.5f)) {
                    keep.add(w);
                }
            }
            if (keep.size() == seg.words.size()) {
                flow.add(seg);
            } else if (!keep.isEmpty()) {
                flow.add(LineGroups.fromWords(keep));
            }
        }
        for (TableDetection.Found t : tables) {
            blocks.add(tableFinder.item(t, tableWords.get(t)));
        }
        return flow;
    }

    private Notes footnotes(List<Line> flow, List<Rule> rules, PageFrame frame, PageData page) {
        List<PageLayout.Note> notes = new ArrayList<>();
        Footnotes.Found fn = Footnotes.detect(flow, rules, frame.textLeft(), frame.textRight(), page.height(), stats.bodySize);
        if (fn == null) {
            return new Notes(notes, List.of());
        }
        List<ParaDraft> continuation = List.of();
        if (!fn.continuation().isEmpty()) {
            continuation = paragraphs.build(LineGroups.joinRows(new ArrayList<>(fn.continuation())), frame.textLeft(), frame.textRight());
        }
        for (Footnotes.Note n : fn.notes()) {
            int id = ++noteCounter;
            for (Glyph g : n.reference()) {
                g.footnote = id;
            }
            notes.add(new PageLayout.Note(id, n.marker(),
                    paragraphs.build(LineGroups.joinRows(new ArrayList<>(n.lines())), frame.textLeft(), frame.textRight())));
        }
        return new Notes(notes, continuation);
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private List<PageLayout.Band> bands(
            PageData page, List<Line> flow, List<PageLayout.Item> blocks, PageFrame frame, boolean ocr,
            List<Line> sideNotes) {
        List<ColumnFinder.Item> items = new ArrayList<>();
        for (Line l : flow) {
            items.add(new ColumnFinder.Item(l.x, l.top, l.right, l.bottom, l.baseline, true, l.chars));
        }
        for (PageLayout.Item b : blocks) {
            if (!b.behindText() && !PictureArranger.isMark(b)) {
                boolean picture = b instanceof PageLayout.FigureItem || b instanceof PageLayout.ImageItem
                        || b instanceof PageLayout.PictureRow;
                items.add(new ColumnFinder.Item(b.x(), b.top(), b.right(), b.bottom(), b.bottom(), false, 0, picture));
            }
        }
        List<ColumnFinder.Band> found = ColumnFinder.find(items, frame.textLeft(), frame.textRight(), page.height(), stats.bodySize);
        boolean pinPictures = ocr || PictureArranger.pictureDominated(blocks, page);
        List<PageLayout.Band> bands = new ArrayList<>();
        for (ColumnFinder.Band band : found) {
            boolean last = band == found.getLast();
            List<List<Line>> colLines = new ArrayList<>();
            List<List<PageLayout.Item>> colBlocks = new ArrayList<>();
            for (int i = 0; i < band.columns().size(); i++) {
                colLines.add(new ArrayList<>());
                colBlocks.add(new ArrayList<>());
            }
            for (Line l : flow) {
                if (inBand(band, l.baseline, last)) {
                    colLines.get(band.columnAt(l.centre())).add(l);
                }
            }
            for (PageLayout.Item b : blocks) {
                if (inBand(band, (b.top() + b.bottom()) / 2f, last)) {
                    colBlocks.get(band.columnAt((b.x() + b.right()) / 2f)).add(b);
                }
            }
            SideNotes.Result margin = band.columns().size() == 1 ? SideNotes.extract(colLines.getFirst()) : SideNotes.Result.NONE;
            sideNotes.addAll(margin.notes());
            List<PageLayout.Column> columns = new ArrayList<>();
            for (int i = 0; i < band.columns().size(); i++) {
                columns.add(column(band.columns().get(i), colLines.get(i), colBlocks.get(i), pinPictures, margin.textLeft(),
                        new Box(0, 0, page.width(), page.height())));
            }
            bands.add(new PageLayout.Band(band.top(), band.bottom(), columns));
        }
        return bands;
    }

    private static final float STROKE_MARGIN = 1f;

    private void diagrams(List<Box> figures, List<Box> backdrops, PageGraphics gfx, List<Line> segments,
            List<PageLayout.Item> blocks, float pageWidth) {
        for (Iterator<Box> it = figures.iterator(); it.hasNext(); ) {
            Box f = it.next();
            List<BoxDiagrams.Label> labels = BoxDiagrams.text(f, gfx.fills(), segments);
            if (labels.isEmpty()) {
                continue;
            }
            it.remove();
            backdrops.add(f.grow(STROKE_MARGIN));
            for (BoxDiagrams.Label label : labels) {
                PageLayout.TextBoxItem tb = label.frame() == null
                        ? placed.box(label.lines(), pageWidth)
                        : placed.framed(label.lines(), label.frame());
                blocks.add(tb);
                segments.removeAll(label.lines());
            }
        }
    }

    private static void unfillHolders(List<PageLayout.Band> bands, TextBoxFinder.Result found,
            List<PageLayout.Decoration> decorations, PageGraphics gfx) {
        List<Box> inner = new ArrayList<>(decorations.stream().map(PageLayout.Decoration::box).toList());
        for (PageLayout.Band band : bands) {
            for (PageLayout.Column c : band.columns()) {
                for (PageLayout.Item it : c.items()) {
                    if (!(it instanceof PageLayout.TextBoxItem) && !(it instanceof PageLayout.ParaItem)
                            && !(it instanceof PageLayout.TableItem)) {
                        inner.add(new Box(it.x(), it.top(), it.right(), it.bottom()));
                    }
                }
            }
        }
        Set<PageLayout.Item> laidOut = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PageLayout.Band band : bands) {
            for (PageLayout.Column c : band.columns()) {
                laidOut.addAll(c.items());
            }
        }
        Map<PageLayout.Item, PageLayout.Item> swap = new IdentityHashMap<>();
        for (int i = 0; i < found.boxes().size(); i++) {
            PageLayout.TextBoxItem tb = found.boxes().get(i);
            if (tb.fillRgb() < 0 && tb.lineRgb() < 0 || tb.turn() != null || !laidOut.contains(tb)) {
                continue;
            }
            List<Box> holder = List.of(tb.box());
            if (!tb.rounded() && inner.stream().noneMatch(b -> b.area() < 0.9f * tb.box().area() && Regions.insideAny(b, holder, 1f))) {
                continue;
            }
            decorations.add(new PageLayout.Decoration(tb.box(), tb.fillRgb(), tb.lineRgb(), tb.lineWidth(), tb.rounded(), false,
                    paintOrder(found.claimed().get(i), gfx)));
            swap.put(tb, new PageLayout.TextBoxItem(tb.box(), -1, tb.textLeft(), tb.textRight(), tb.paras(), tb.gap(), -1, 0,
                    false, tb.overlay()));
        }
        if (!swap.isEmpty()) {
            for (PageLayout.Band band : bands) {
                for (PageLayout.Column c : band.columns()) {
                    c.items().replaceAll(it -> swap.getOrDefault(it, it));
                }
            }
        }
    }

    private static void groundTextBoxes(List<PageLayout.Band> bands, List<PageLayout.TextBoxItem> furniture,
            List<PageLayout.Decoration> decorations, PageGraphics gfx) {
        List<Box> painted = new ArrayList<>();
        for (PageLayout.Decoration d : decorations) {
            if (d.rgb() >= 0) {
                painted.add(d.box());
            }
        }
        for (PageLayout.Band band : bands) {
            for (PageLayout.Column c : band.columns()) {
                for (PageLayout.Item it : c.items()) {
                    if (it instanceof PageLayout.FigureItem || it instanceof PageLayout.ImageItem || it instanceof PageLayout.PictureRow
                            || it instanceof PageLayout.FloatItem) {
                        painted.add(new Box(it.x(), it.top(), it.right(), it.bottom()));
                    }
                }
            }
        }
        if (painted.isEmpty()) {
            return;
        }
        for (PageLayout.Band band : bands) {
            for (PageLayout.Column c : band.columns()) {
                c.items().replaceAll(it -> it instanceof PageLayout.TextBoxItem tb && tb.fillRgb() < 0 ? grounded(tb, painted, gfx) : it);
            }
        }
        furniture.replaceAll(tb -> tb.fillRgb() < 0 ? grounded(tb, painted, gfx) : tb);
    }

    private static PageLayout.TextBoxItem grounded(PageLayout.TextBoxItem tb, List<Box> painted, PageGraphics gfx) {
        Box on = tb.turn() == null ? tb.box() : tb.turn().onPage();
        if (paintedShare(on, painted) < MOSTLY) {
            return tb;
        }
        LabelGround.Ground g = LabelGround.ground(on, gfx);
        return tb.withGround(g != null && g.paint() != null ? g.rgb() : readsOn(tb));
    }

    private static final float MOSTLY = 0.5f;

    private static float paintedShare(Box b, List<Box> painted) {
        int over = 0;
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                float x = b.x() + b.width() * (i + 0.5f) / 5;
                float y = b.top() + b.height() * (j + 0.5f) / 5;
                over += Regions.insideAny(x, y, painted, 0f) ? 1 : 0;
            }
        }
        return over / 25f;
    }

    private static int readsOn(PageLayout.TextBoxItem tb) {
        double sum = 0;
        int chars = 0;
        for (ParaDraft d : tb.paras()) {
            for (Line l : d.lines) {
                int rgb = Math.max(0, l.rgb);
                sum += l.chars * (0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF)) / 255;
                chars += l.chars;
            }
        }
        return chars > 0 && sum / chars > 0.6 ? 0x000000 : 0xFFFFFF;
    }

    private static int paintOrder(TextBoxFinder.Container c, PageGraphics gfx) {
        if (c.mark() != null) {
            return gfx.order(c.mark());
        }
        for (Fill f : gfx.fills()) {
            if (Regions.of(f).equals(c.box())) {
                return gfx.order(f);
            }
        }
        return Integer.MAX_VALUE;
    }

    private static PageGraphics.VectorMark paintedPaper(PageData page, List<PageGraphics.VectorMark> marks) {
        for (PageGraphics.VectorMark m : marks) {
            if (m.shading() && m.width() >= page.width() * 0.9f && m.height() >= page.height() * 0.9f) {
                return m;
            }
        }
        return null;
    }

    private static boolean panel(PageGraphics.VectorMark m, PageData page, List<Line> segments) {
        if (!m.shading() || !m.filled() || m.curved() || m.diagonal() || m.segments() > PANEL_SEGMENTS
                || m.width() < PANEL_WIDTH * page.width() || m.height() < PANEL_HEIGHT * page.height()
                || m.width() >= 0.9f * page.width() && m.height() >= 0.9f * page.height()) {
            return false;
        }
        Box b = new Box(m.x(), m.top(), m.right(), m.bottom());
        return segments.stream().anyMatch(l -> b.contains(l.centre(), Regions.wordY(l)));
    }

    private static final int PANEL_SEGMENTS = 5;
    private static final float PANEL_WIDTH = 0.4f;
    private static final float PANEL_HEIGHT = 0.1f;

    private static boolean labels(PageLayout.TextBoxItem tb) {
        int chars = 0;
        for (ParaDraft p : tb.paras()) {
            for (Line l : p.lines) {
                chars += l.chars;
            }
        }
        return chars < LABEL_CHARS;
    }

    private static final int LABEL_CHARS = 120;

    private static boolean labelsOnly(Box box, List<Line> segments) {
        int n = 0;
        for (Line l : segments) {
            if (box.contains(l.centre(), (l.top + l.bottom) / 2f) && (++n > 2 || l.right - l.x > box.width() / 2)) {
                return false;
            }
        }
        return true;
    }

    private static boolean outlinedText(PageGraphics gfx) {
        int letters = 0;
        for (PageGraphics.VectorMark m : gfx.marks()) {
            if (m.filled() && m.height() >= 3 && m.height() <= 40 && m.width() <= 60 && ++letters >= OUTLINED_LETTERS) {
                return true;
            }
        }
        return false;
    }

    private static boolean tinted(PageData page, PageGraphics gfx) {
        float most = 0.6f * page.width() * page.height();
        for (ImageDraw img : gfx.images()) {
            if ((img.right() - img.x()) * (img.bottom() - img.top()) >= most) {
                return true;
            }
        }
        for (Fill f : gfx.fills()) {
            if (!Decorations.isWhite(f.rgb()) && f.width() * f.height() >= most) {
                return true;
            }
        }
        return false;
    }

    private static boolean inBand(ColumnFinder.Band band, float y, boolean last) {
        return y >= band.top() && y < band.bottom() || last && y >= band.bottom();
    }

    private PageLayout.Column column(
            float[] c, List<Line> colLines, List<PageLayout.Item> colBlocks, boolean pinPictures, float marginEdge,
            Box sheet) {
        List<Line> lines = LineGroups.joinRows(colLines);
        List<PageLayout.Item> colItems = new ArrayList<>();
        List<ParagraphBuilder.Obstacle> obstacles = new ArrayList<>();
        PictureArranger.arrange(colBlocks, lines, colItems, obstacles, pinPictures, marginEdge, sheet, c);
        List<ParaDraft> paras = paragraphs.build(lines, c[0], c[1], obstacles);
        Set<ParaDraft> beside = SideBySide.fragments(paras);
        for (ParaDraft p : paras) {
            colItems.add(beside.contains(p) ? placed.box(p.lines, sheet.right()) : new PageLayout.ParaItem(p));
        }
        colItems.sort(Comparator.comparingDouble(PageLayout.Item::top));
        return new PageLayout.Column(c[0], c[1], colItems);
    }

    private static void markLinks(List<Glyph> glyphs, List<PageData.Link> links) {
        if (links.isEmpty()) {
            return;
        }
        for (Glyph g : glyphs) {
            float cx = g.centreX();
            float cy = g.baseline - g.size * 0.3f;
            for (PageData.Link l : links) {
                if (cx >= l.x() - 0.5f && cx <= l.right() + 0.5f && cy >= l.top() - 1 && cy <= l.bottom() + 1) {
                    g.link = l.uri() != null ? l.uri() : "#page" + l.targetPage();
                    break;
                }
            }
        }
    }
}

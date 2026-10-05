package stirling.software.officeconvert.slides;

import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.apache.pdfbox.pdmodel.PDDocument;

import stirling.software.officeconvert.Pictures;
import stirling.software.officeconvert.build.PlacedContent;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageData;
import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.layout.Box;
import stirling.software.officeconvert.layout.DocStats;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.LineBuilder;
import stirling.software.officeconvert.layout.OcrText;
import stirling.software.officeconvert.layout.PageLayout;
import stirling.software.officeconvert.layout.ParaDraft;
import stirling.software.officeconvert.layout.ParagraphBuilder;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.slides.PaintOrder.Kind;

final class SlideBuilder {

    private final PlacedContent content;
    private final Art art;
    private final DocStats stats;
    private final ParagraphBuilder paragraphs;
    private final TextFrames frames;
    private final FigureText figureText;
    private final SlantedText slantedText;
    private final float width;
    private final float height;

    SlideBuilder(PDDocument document, DocStats stats, SlideSink sink, float figureDpi, Pictures pictures,
            boolean dropHyphens, float width, float height) {
        boolean lossless = pictures == Pictures.LOSSLESS;
        PlacedContent.MediaStore media = new SlideMedia(sink, lossless);
        this.content = new PlacedContent(document, stats, media, figureDpi, dropHyphens, pictures);
        this.art = new Art(document, media, figureDpi, lossless);
        this.stats = stats;
        this.paragraphs = new ParagraphBuilder(stats);
        this.frames = new TextFrames(content, width);
        this.figureText = new FigureText(paragraphs);
        this.slantedText = new SlantedText(paragraphs, frames);
        this.width = width;
        this.height = height;
    }

    private static final class Stack {
        private static final double BOTTOM = -1e9;
        private static final double TOP = 1e9;

        private record Placed(SlideShape shape, double z, int seq) {}

        final PaintOrder paint;
        final Set<Glyph> text;
        final List<Placed> placed = new ArrayList<>();
        final Set<Integer> represented = new HashSet<>();
        final List<Box> textPictures = new ArrayList<>();
        int background = -1;
        int backgroundOrder = -1;

        Stack(PaintOrder paint, Set<Glyph> text) {
            this.paint = paint;
            this.text = text;
        }

        void add(SlideShape shape, int order, boolean text) {
            double z = order >= 0 ? order : text ? TOP : BOTTOM;
            placed.add(new Placed(shape, z, placed.size()));
            if (order >= 0 && !text) {
                represented.add(order);
            }
        }

        void addAt(SlideShape shape, double z) {
            placed.add(new Placed(shape, z, placed.size()));
        }

        void cover(Box box) {
            paint.inside(box, represented);
        }

        void text(TextFrames.Framed t) {
            add(t.shape(), t.order(), true);
            represented.addAll(t.drawn());
        }

        List<SlideShape> ordered() {
            List<Placed> sorted = new ArrayList<>(placed);
            sorted.sort(Comparator.comparingDouble(Placed::z).thenComparingInt(Placed::seq));
            return sorted.stream().map(Placed::shape).toList();
        }

        boolean under(Frame box, int order) {
            if (background >= 0 && backgroundOrder < order) {
                return true;
            }
            for (Placed p : placed) {
                Frame f = frameOf(p.shape());
                if (p.z() < order && overlap(f, box) > 0.3f * Math.min(f.area(), box.area())) {
                    return true;
                }
            }
            return false;
        }
    }

    Slide build(PageLayout layout, AffineTransform toDisplay, PaintOrder paint, List<SlantedText.Group> slanted)
            throws IOException {
        try {
            PageData page = layout.page();
            Stack stack = new Stack(paint, FigureText.placed(layout));
            bulletDots(page, stack);
            List<SlideShape> icons = new ArrayList<>();
            for (PageLayout.Band band : layout.bands()) {
                for (PageLayout.Column col : band.columns()) {
                    column(col, layout, toDisplay, stack, icons);
                }
            }
            decorations(layout, stack);
            for (PageLayout.TextBoxItem tb : layout.furniture()) {
                textBox(tb, stack, icons);
            }
            List<ParaDraft> notes = new ArrayList<>(layout.noteContinuation());
            for (PageLayout.Note note : layout.notes()) {
                notes.addAll(note.paras());
            }
            texts(notes, stack, icons);
            runningLines(page, stack, icons);
            for (TextFrames.Framed t : slantedText.frames(slanted, stack.textPictures)) {
                stack.text(t);
            }
            whiteCards(layout, stack);
            if (stack.backgroundOrder >= 0) {
                hiddenByBackground(stack);
            }
            for (Art.Piece piece : art.pieces(stack.paint, stack.represented, page.index(), toDisplay, width, height)) {
                stack.add(piece.picture(), piece.order(), false);
            }
            for (SlideShape icon : icons) {
                stack.add(icon, stack.paint.textOver(box(frameOf(icon))), true);
            }
            showUncovered(stack);
            List<SlideShape> shapes = new ArrayList<>(stack.ordered());
            Titles.mark(shapes, height);
            return new Slide(page.index(), stack.background, shapes);
        } finally {
            content.endPage();
        }
    }

    private void column(PageLayout.Column col, PageLayout layout, AffineTransform toDisplay, Stack stack,
            List<SlideShape> icons) throws IOException {
        List<ParaDraft> run = new ArrayList<>();
        for (PageLayout.Item item : col.items()) {
            if (item instanceof PageLayout.ParaItem pi) {
                run.add(pi.para());
                continue;
            }
            if (takesRoom(item)) {
                texts(run, stack, icons);
                run.clear();
            }
            item(item, layout, toDisplay, stack, icons);
        }
        texts(run, stack, icons);
    }

    private void texts(List<ParaDraft> paras, Stack stack, List<SlideShape> icons) throws IOException {
        for (TextFrames.Framed t : frames.frames(paras, icons, stack.paint)) {
            stack.text(t);
        }
    }

    private static boolean takesRoom(PageLayout.Item item) {
        return !(item instanceof PageLayout.FloatItem) && !item.behindText();
    }

    private void item(PageLayout.Item item, PageLayout layout, AffineTransform toDisplay, Stack stack,
            List<SlideShape> icons) throws IOException {
        switch (item) {
            case PageLayout.TableItem ti -> table(ti, layout, toDisplay, stack);
            case PageLayout.FloatItem fi -> item(fi.picture(), layout, toDisplay, stack, icons);
            case PageLayout.PictureRow row -> {
                for (PageLayout.Item p : row.pictures()) {
                    item(p, layout, toDisplay, stack, icons);
                }
            }
            case PageLayout.TextBoxItem tb -> textBox(tb, stack, icons);
            case PageLayout.ImageItem im -> picture(im, layout, toDisplay, stack, icons);
            case PageLayout.FigureItem fi -> {
                if (!fi.paper()) {
                    picture(fi, layout, toDisplay, stack, icons);
                }
            }
            case PageLayout.ParaItem pi -> texts(List.of(pi.para()), stack, icons);
        }
    }

    private void table(PageLayout.TableItem ti, PageLayout layout, AffineTransform toDisplay, Stack stack) {
        TableShape t = new TableShape(ti.x(), ti.top(), content.table(ti, layout));
        Box area = box(frameOf(t));
        int order = stack.paint.lastInside(area, Kind.FILL, Kind.STROKE, Kind.TEXT);
        stack.add(t, order, false);
        List<Box> shaded = TableCells.shaded(t);
        Set<Integer> inside = new HashSet<>();
        stack.paint.inside(area, inside);
        List<PaintOrder.Mark> missed = new ArrayList<>();
        for (PaintOrder.Mark m : stack.paint.marks()) {
            if (!inside.contains(m.order()) || stack.represented.contains(m.order())) {
                continue;
            }
            if (m.kind() == Kind.FILL && !TableCells.covered(m.box(), shaded)) {
                missed.add(m);
            } else if (m.kind() == Kind.FILL || m.kind() == Kind.STROKE) {
                stack.represented.add(m.order());
            }
        }
        if (!missed.isEmpty() && order >= 0) {
            Art.Piece piece = art.together(missed, layout.page().index(), toDisplay, width, height);
            for (PaintOrder.Mark m : missed) {
                stack.represented.add(m.order());
            }
            if (piece != null) {
                stack.addAt(piece.picture(), order - 0.5);
            }
        }
    }

    private void textBox(PageLayout.TextBoxItem tb, Stack stack, List<SlideShape> icons) throws IOException {
        TextFrames.Framed shape = frames.boxed(tb, icons, stack.paint);
        if (shape != null) {
            stack.text(shape);
        }
    }

    private void picture(PageLayout.Item item, PageLayout layout, AffineTransform toDisplay, Stack stack,
            List<SlideShape> icons) throws IOException {
        Box on = new Box(item.x(), item.top(), item.right(), item.bottom());
        boolean wholePage = on.area() >= 0.95f * width * height;
        List<ParaDraft> live = item instanceof PageLayout.FigureItem fi && !fi.backdrop() && !layout.ocrText() && !wholePage
                ? figureText.live(layout.page(), on, stack.text, stack.paint)
                : null;
        Picture pic = live != null ? content.region(on, layout.page(), toDisplay, false) : content.picture(item, layout, toDisplay);
        if (pic == null) {
            return;
        }
        Frame frame;
        if (pic.rotation != 0 || pic.flipH) {
            Box drawn = item instanceof PageLayout.ImageItem im
                    ? new Box(im.draw().x(), im.draw().top(), im.draw().right(), im.draw().bottom())
                    : new Box(item.x(), item.top(), item.right(), item.bottom());
            frame = new Frame(drawn.centreX() - pic.width / 2f, drawn.centreY() - pic.height / 2f, pic.width, pic.height,
                    Math.floorMod(pic.rotation, 360));
        } else {
            frame = new Frame(item.x(), item.top(), pic.width, pic.height);
        }
        int order = item instanceof PageLayout.ImageItem
                ? stack.paint.drew(on, 2f, Kind.IMAGE)
                : item.behindText() || live != null
                        ? stack.paint.lastInside(on, Kind.FILL, Kind.STROKE, Kind.IMAGE, Kind.SHADING)
                        : stack.paint.lastInside(on, Kind.FILL, Kind.STROKE, Kind.IMAGE, Kind.SHADING, Kind.TEXT);
        int firstText = item.behindText() || live != null ? stack.paint.firstInside(on, Kind.TEXT) : -1;
        if (firstText >= 0 && firstText <= order) {
            stack.addAt(new PictureShape(frame, pic), firstText - 0.5);
            stack.represented.add(order);
        } else {
            stack.add(new PictureShape(frame, pic), order, false);
        }
        if (item instanceof PageLayout.FigureItem fi) {
            stack.cover(on);
            if (live == null && !fi.backdrop()) {
                stack.textPictures.add(on);
            }
        }
        if (live != null) {
            texts(live, stack, icons);
        }
    }

    private void decorations(PageLayout layout, Stack stack) {
        for (PageLayout.Decoration d : layout.decorations()) {
            Box b = d.box();
            float thin = Math.min(b.width(), b.height());
            float along = Math.max(b.width(), b.height());
            if (stack.background < 0 && d.rgb() >= 0 && d.lineRgb() < 0 && covers(b)) {
                stack.background = d.rgb();
                stack.backgroundOrder = stack.paint.drew(b, 2f, Kind.FILL);
            } else if (d.lineRgb() < 0 && d.rgb() >= 0 && thin <= 3f && along >= 4 * thin) {
                boolean horizontal = b.width() >= b.height();
                float t = Math.max(0.25f, thin);
                LineShape line = horizontal
                        ? new LineShape(b.x(), b.centreY(), b.right(), b.centreY(), d.rgb(), t)
                        : new LineShape(b.centreX(), b.top(), b.centreX(), b.bottom(), d.rgb(), t);
                int order = stack.paint.drew(b, 2f, Kind.STROKE, Kind.FILL);
                stack.add(line, order >= 0 ? order : ruleOrder(b, horizontal, stack.paint), false);
            } else {
                PaintOrder.Mark m = stack.paint.drewMark(b, 2f, Kind.FILL, Kind.SHADING);
                if (m == null) {
                    m = stack.paint.drewMark(b, 2f, Kind.STROKE);
                }
                PaintOrder.Mark around = m == null ? stack.paint.drewAround(b, 1f, Kind.FILL) : null;
                if (around != null) {
                    stack.add(new RectShape(new Frame(b.x(), b.top(), Math.max(0.25f, b.width()), Math.max(0.25f, b.height())),
                            d.rgb(), d.lineRgb(), d.lineWidth(), 0, 1f), around.order(), false);
                    continue;
                }
                if (m != null && m.outline() == PaintOrder.Outline.OTHER && m.kind() != Kind.SHADING) {
                    continue;
                }
                float radius = m != null && m.outline() == PaintOrder.Outline.ROUNDED ? m.radius() : d.rounded() ? 6f : 0;
                RectShape rect = new RectShape(new Frame(b.x(), b.top(), Math.max(0.25f, b.width()),
                        Math.max(0.25f, b.height())), d.rgb(), d.lineRgb(), d.lineWidth(), radius,
                        m != null && m.kind() == Kind.FILL ? m.alpha() : 1f);
                stack.add(rect, m == null ? -1 : m.order(), false);
                if (d.lineRgb() >= 0) {
                    stack.paint.drewAll(b, 2f, stack.represented);
                }
            }
        }
    }

    private static int ruleOrder(Box b, boolean horizontal, PaintOrder paint) {
        int order = paint.outlineWith(b, 3f);
        if (order >= 0) {
            return order;
        }
        order = paint.lastInside(b, Kind.STROKE, Kind.FILL);
        float along = horizontal ? b.width() : b.height();
        for (int i = 0; i <= RULE_SAMPLES; i++) {
            float at = (horizontal ? b.x() : b.top()) + along * i / RULE_SAMPLES;
            Box piece = horizontal ? new Box(at - 0.5f, b.top(), at + 0.5f, b.bottom())
                    : new Box(b.x(), at - 0.5f, b.right(), at + 0.5f);
            order = Math.max(order, paint.outlineWith(piece, 1.5f));
        }
        return order;
    }

    private static final int RULE_SAMPLES = 8;

    private static void showUncovered(Stack stack) {
        for (int i = 0; i < stack.placed.size(); i++) {
            Stack.Placed p = stack.placed.get(i);
            if (p.shape() instanceof TextShape t && t.invisible() && !onPicture(onPage(t.frame()), stack)) {
                stack.placed.set(i, new Stack.Placed(t.visible(), p.z(), p.seq()));
            }
        }
    }

    private static boolean onPicture(Frame text, Stack stack) {
        for (Stack.Placed p : stack.placed) {
            if (p.shape() instanceof PictureShape pic && overlap(text, onPage(pic.frame())) >= 0.9f * text.area()) {
                return true;
            }
        }
        return false;
    }

    private static void hiddenByBackground(Stack stack) {
        stack.represented.add(stack.backgroundOrder);
        PaintOrder.Mark fill = null;
        for (PaintOrder.Mark m : stack.paint.marks()) {
            if (m.order() == stack.backgroundOrder) {
                fill = m;
            }
        }
        if (fill == null || fill.alpha() < 1f) {
            return;
        }
        for (PaintOrder.Mark m : stack.paint.marks()) {
            if (m.order() < stack.backgroundOrder && m.kind() != Kind.TEXT) {
                stack.represented.add(m.order());
            }
        }
    }

    private static void bulletDots(PageData page, Stack stack) {
        Set<Glyph> drawn = null;
        for (Glyph g : stack.text) {
            if (!g.text.equals("\u2022") && !g.text.equals("\u25aa")) {
                continue;
            }
            if (drawn == null) {
                drawn = Collections.newSetFromMap(new IdentityHashMap<>());
                drawn.addAll(page.glyphs());
            }
            if (drawn.contains(g)) {
                continue;
            }
            for (PaintOrder.Mark m : stack.paint.marks()) {
                Box b = m.box();
                if (m.kind() == Kind.FILL && Math.abs(b.x() - g.x) <= 0.75f && Math.abs(b.width() - g.width) <= 0.75f
                        && b.centreY() <= g.baseline + 0.2f * g.size && b.centreY() >= g.baseline - g.size) {
                    stack.represented.add(m.order());
                }
            }
        }
    }

    private boolean covers(Box b) {
        return b.x() <= 1 && b.top() <= 1 && b.right() >= width - 1 && b.bottom() >= height - 1;
    }

    private void whiteCards(PageLayout layout, Stack stack) {
        for (Fill f : layout.page().graphics().fills()) {
            if (!white(f.rgb()) || f.area() < 200 || f.width() < 6 || f.height() < 6) {
                continue;
            }
            Frame card = new Frame(f.x(), f.top(), f.width(), f.height());
            if (card.area() >= 0.95f * width * height) {
                continue;
            }
            int order = stack.paint.drew(new Box(f.x(), f.top(), f.right(), f.bottom()), 1.5f, Kind.FILL);
            if (order >= 0 && stack.under(card, order) && !insideFigure(card, order, stack)) {
                stack.add(new RectShape(card, f.rgb(), -1, 0, 0, 1f), order, false);
            }
        }
    }

    private static boolean insideFigure(Frame card, int order, Stack stack) {
        for (Stack.Placed p : stack.placed) {
            if (p.shape() instanceof PictureShape pic && p.z() >= order
                    && overlap(card, pic.frame()) >= 0.9f * card.area()) {
                return true;
            }
        }
        return false;
    }

    private static boolean white(int rgb) {
        return ((rgb >> 16) & 0xFF) >= 0xFB && ((rgb >> 8) & 0xFF) >= 0xFB && (rgb & 0xFF) >= 0xFB;
    }

    private void runningLines(PageData page, Stack stack, List<SlideShape> icons) throws IOException {
        if (!stats.headerFooterInfo().any()) {
            return;
        }
        for (Line l : LineBuilder.build(OcrText.pageGlyphs(page))) {
            if (stats.headerFooterInfo().match(page, l) != null) {
                texts(paragraphs.build(List.of(l), 0, page.width()), stack, icons);
            }
        }
    }

    static Frame onPage(Frame f) {
        if (f.rotation() == 90 || f.rotation() == 270) {
            float cx = f.x() + f.width() / 2f;
            float cy = f.y() + f.height() / 2f;
            return new Frame(cx - f.height() / 2f, cy - f.width() / 2f, f.height(), f.width());
        }
        return f;
    }

    static Box box(Frame f) {
        Frame on = onPage(f);
        return new Box(on.x(), on.y(), on.right(), on.bottom());
    }

    static Frame frameOf(SlideShape s) {
        return switch (s) {
            case TextShape t -> onPage(t.frame());
            case PictureShape p -> onPage(p.frame());
            case RectShape r -> r.frame();
            case TableShape t -> new Frame(t.x(), t.y(), t.width(), t.height());
            case LineShape l -> new Frame(Math.min(l.x1(), l.x2()), Math.min(l.y1(), l.y2()), Math.abs(l.x2() - l.x1()),
                    Math.abs(l.y2() - l.y1()));
        };
    }

    private static float overlap(Frame a, Frame b) {
        float w = Math.min(a.right(), b.right()) - Math.max(a.x(), b.x());
        float h = Math.min(a.bottom(), b.bottom()) - Math.max(a.y(), b.y());
        return w > 0 && h > 0 ? w * h : 0;
    }
}

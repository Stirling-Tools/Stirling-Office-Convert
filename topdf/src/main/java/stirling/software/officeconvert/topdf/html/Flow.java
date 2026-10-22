package stirling.software.officeconvert.topdf.html;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import stirling.software.officeconvert.model.Block;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.Table;
import stirling.software.officeconvert.sink.Links;

/** Lays nested HTML blocks out as the flat run of paragraphs and tables a page holds: margins collapse into the space
 * before the next paragraph, and padding and nesting add up to its indents. */
final class Flow {

    @FunctionalInterface
    interface Out {
        void add(Block block) throws IOException;
    }

    /** Where a block's lines go: its indents, the room between them and its background. */
    record Frame(float left, float right, float width, int shading) {

        Frame inner(float addLeft, float addRight, int fill) {
            float w = Math.max(36, width - addLeft - addRight);
            return new Frame(left + addLeft, right + addRight, w, fill >= 0 ? fill : shading);
        }
    }

    private record ListLevel(Numbering.Instance list, int level, boolean bullets) {}

    static final int MAX_DEPTH = 200;

    // Tabs past this many in a row are past the edge of any page, and each costs the layout memory
    private static final int MAX_TABS_IN_A_ROW = 32;

    static final Set<String> SKIPPED = Set.of("head", "script", "style", "template", "title", "meta", "link",
            "base", "iframe", "object", "embed", "applet", "frame", "frameset", "video", "audio", "canvas",
            "map", "area", "input", "select", "option", "datalist", "param", "source", "track", "noembed", "noframes",
            "dialog", "colgroup", "col");

    private static final Set<String> BLOCKS = Set.of("address", "article", "aside", "blockquote", "body", "center",
            "dd", "details", "div", "dl", "dt", "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3",
            "h4", "h5", "h6", "header", "hgroup", "hr", "html", "legend", "li", "main", "menu", "nav", "ol", "p", "pre",
            "section", "summary", "ul", "listing", "xmp", "plaintext", "caption", "table", "tr", "td", "th", "tbody",
            "thead", "tfoot", "noscript", "search");

    private final HtmlContext ctx;
    private final Out out;
    private final boolean inCell;
    private final Deque<ListLevel> lists = new ArrayDeque<>();
    private Paragraph para;
    private Paragraph lastParagraph;
    private Look blockLook;
    private Frame blockFrame;
    private String headingStyle;
    private float pendingSpace;
    private boolean pendingBreak;
    private Paragraph.ListRef pendingMarker;
    private boolean lastSpace = true;
    private String link;
    private int forceInline;
    private Inline pendingGap;
    private final List<Inline> pendingFloats = new ArrayList<>();
    private String floatOf = "none";
    private boolean sectionStarted;
    // the height left in a box that clips its overflow, as a browser shows only what fits
    private float clipLeft = Float.MAX_VALUE;

    Flow(HtmlContext ctx, Out out, boolean inCell) {
        this.ctx = ctx;
        this.out = out;
        this.inCell = inCell;
    }

    void root(Element body, Look look, Frame frame) throws IOException {
        blockLook = look;
        blockFrame = frame;
        if (ctx.positions()) {
            List<Positioned.Item> items = Positioned.absolutes(ctx, body, look, frame.width());
            for (Positioned.Item item : items) {
                ctx.place(item.element());
            }
            Section s = ctx.defaultSection();
            Positioned.Hung hung = Positioned.hang(ctx, items, s.marginLeft, s.marginTop,
                    s.pageWidth - s.marginLeft - s.marginRight, ctx.pageRoom(), 0);
            if (!hung.top().isEmpty()) {
                anchor(Positioned.moved(hung.top(), 0, 0, false), frame);
            }
        }
        children(body, look, frame);
    }

    private static final int MAX_FIXED = 64;

    private static final int MAX_FIXED_TEXT = 8_000;

    static List<Inline> fixedLayer(HtmlContext ctx, Element body, Look look, Section page) throws IOException {
        List<Positioned.Item> items = new ArrayList<>();
        int text = 0;
        for (Positioned.Item item : Positioned.fixed(ctx, body, look, page.pageWidth - page.marginLeft
                - page.marginRight)) {
            text += Layout.text(item.element(), MAX_FIXED_TEXT + 1).length();
            if (items.size() >= MAX_FIXED || text > MAX_FIXED_TEXT) {
                ctx.warn("Only the first boxes fixed to the page are repeated on every page; the rest are drawn once,"
                        + " where they stand");
                break;
            }
            items.add(item);
            ctx.place(item.element());
        }
        Positioned.Hung hung = Positioned.hang(ctx, items, page.marginLeft, page.marginTop,
                page.pageWidth - page.marginLeft - page.marginRight, ctx.pageRoom(), 0);
        return Positioned.moved(hung.top(), 0, 0, false);
    }

    void children(Element parent, Look look, Frame frame) throws IOException {
        children(parent, look, frame, 0);
    }

    /** Lays out the children of {@code parent}, which sits {@code depth} elements deep in the page. */
    void children(Element parent, Look look, Frame frame, int depth) throws IOException {
        blockLook = look;
        blockFrame = frame;
        walkChildren(parent, look, frame, depth);
        flush();
        if (!pendingFloats.isEmpty()) {
            // floats with no text after them still need a paragraph to hold them
            start();
            flush();
        }
    }

    private void walkChildren(Element parent, Look look, Frame frame, int depth) throws IOException {
        for (Node child : parent.childNodes()) {
            if (ctx.stopped()) {
                return;
            }
            if (child instanceof TextNode t) {
                text(t.getWholeText(), look);
            } else if (child instanceof Element e) {
                element(e, look, frame, depth + 1);
            }
        }
    }

    private void element(Element e, Look parentLook, Frame frame, int depth) throws IOException {
        String tag = e.normalName();
        if (SKIPPED.contains(tag) || e.hasAttr("hidden")) {
            return;
        }
        if (depth > MAX_DEPTH) {
            text(e.text(), parentLook);
            return;
        }
        Map<String, String> d = ctx.declared(e, parentLook);
        if (tag.equals("table") && parentLook.centerBlocks && !d.containsKey("text-align")) {
            // <center> places a table in the middle but its text starts at the left, as Chrome does
            d.put("text-align", "start");
        }
        String display = d.getOrDefault("display", BLOCKS.contains(tag) ? "block" : "inline").strip().toLowerCase(Locale.ROOT);
        if (display.equals("none")) {
            return;
        }
        boolean inline = (display.startsWith("inline") || forceInline > 0) && !tag.equals("table")
                || display.equals("contents");
        Look look = parentLook.child(d, frame.width(), inline);
        if (look.hidden || Hidden.visually(d, look.size)) {
            return;
        }
        if (Positioned.outOfFlow(d) && !tag.equals("body") && !tag.equals("html")
                && (ctx.positions() || ctx.placed(e))) {
            if (!ctx.placed(e)) {
                ctx.place(e);
                staticBox(e, d, look, frame, depth);
            }
            return;
        }
        if (tag.equals("details") && !e.hasAttr("open")) {
            Element summary = e.selectFirst("> summary");
            if (summary != null) {
                element(summary, look, frame, depth + 1);
            }
            return;
        }
        switch (tag) {
            case "br" -> inline(new Inline.Break(), look);
            case "img" -> image(e, d, look, frame);
            case "svg" -> {
                Picture pic = ctx.images().inlineSvg(e, d, look, frame.width(), pictureRoom());
                if (pic != null) {
                    turn(pic, d, look);
                    place(pic, d, look, frame);
                }
            }
            case "hr" -> rule(d, look, frame);
            case "table" -> table(e, d, look, frame, parentLook.centerBlocks, depth);
            case "a" -> anchor(e, look, frame, depth);
            case "wbr" -> { }
            case "q" -> {
                text("\u201c", look);
                walkChildren(e, look, frame, depth);
                text("\u201d", look);
            }
            default -> {
                if (inline) {
                    walkChildren(e, look, frame, depth);
                } else {
                    // overflow on html or body belongs to the window, which prints whole; fixed boxes never widen it
                    boolean scrolls = !tag.equals("body") && !tag.equals("html") && scrolls(d)
                            || "fixed".equalsIgnoreCase(d.getOrDefault("position", "").strip());
                    ctx.scrolling(scrolls ? 1 : 0);
                    try {
                        block(e, tag, d, look, frame, depth);
                    } finally {
                        ctx.scrolling(scrolls ? -1 : 0);
                    }
                }
            }
        }
    }

    private void anchor(Element e, Look look, Frame frame, int depth) throws IOException {
        String before = link;
        String href = e.attr("href").strip();
        String safe = href.isEmpty() ? null : Links.safeUrl(href);
        if (safe != null) {
            link = safe;
        }
        walkChildren(e, look, frame, depth);
        link = before;
    }

    private void block(Element e, String tag, Map<String, String> d, Look look, Frame frame, int depth)
            throws IOException {
        flush();
        page(d.get("page"), frame);
        float em = look.size;
        Transform turn = forceInline > 0 ? Transform.NONE : Transform.of(d.get("transform"), em, frame.width(), 0);
        if (!turn.shiftOnly() && ctx.positions()) {
            lifted(e, d, look, frame, depth);
            return;
        }
        float[] shift = relativeShift(d, look, frame, turn);
        float lowered = 0;
        if (shift[0] != 0 || shift[1] != 0) {
            frame = new Frame(frame.left() + shift[0], frame.right() - shift[0], frame.width(), frame.shading());
            float before = pendingSpace;
            pendingSpace = Math.max(0, pendingSpace + shift[1]);
            lowered = pendingSpace - before;
        }
        List<Positioned.Item> absolutes = Positioned.contains(d) && ctx.positions() && !tag.equals("body")
                && !tag.equals("html") ? Positioned.absolutes(ctx, e, look, frame.width()) : List.of();
        for (Positioned.Item item : absolutes) {
            ctx.place(item.element());
        }
        try {
            contained(e, tag, d, look, frame, depth, absolutes);
        } finally {
            if (lowered != 0) {
                pendingSpace = Math.max(0, pendingSpace - lowered);
            }
        }
    }

    private void contained(Element e, String tag, Map<String, String> d, Look look, Frame frame, int depth,
            List<Positioned.Item> absolutes) throws IOException {
        float em = look.size;
        float top = length(d.get("margin-top"), em, frame.width());
        float bottom = length(d.get("margin-bottom"), em, frame.width());
        if ((tag.equals("ul") || tag.equals("ol")) && !lists.isEmpty()) {
            top = 0;
            bottom = 0;
        }
        float left = Math.max(0, length(d.get("margin-left"), em, frame.width()) + length(d.get("padding-left"), em,
                frame.width()) + border(d, "left"));
        float right = Math.max(0, length(d.get("margin-right"), em, frame.width()) + length(d.get("padding-right"), em,
                frame.width()) + border(d, "right"));
        int fill = fill(d, look);
        measureFixed(e, d, look, frame, left, right);
        Frame inner = frame.inner(left, right, fill);
        Layout.Plan plan = forceInline > 0 ? null : Layout.of(e, d, look, inner.width(), ctx);
        if (plan != null && !plan.inline() && widerInside(plan, look, inner.width())) {
            // a cell cannot hold a row, so a longer one inside, like a carousel, keeps its columns instead
            plan = null;
        }
        if (plan != null && inCell && !plan.inline()) {
            // a cell cannot hold a table of columns, but a row of short items still fits on one line
            plan = null;
        }
        boolean tall = !absolutes.isEmpty() && height(d, look) > 0 && e.selectFirst("table") == null;
        if (plan == null && (tall || boxed(e, tag, d, fill)) && !ctx.holdsRow(e, look, inner.width())) {
            box(e, d, look, frame, depth, fill, top, bottom, absolutes);
            return;
        }
        Positioned.Hung hung = null;
        if (!absolutes.isEmpty()) {
            float marginLeft = length(d.get("margin-left"), em, frame.width());
            float marginRight = length(d.get("margin-right"), em, frame.width());
            float cbWidth = Math.max(12, frame.width() - marginLeft - marginRight - border(d, "left") - border(d, "right"));
            Float own = Css.length(d.getOrDefault("width", "auto"), em, frame.width());
            if (own != null && own > 0) {
                boolean borderBox = "border-box".equalsIgnoreCase(d.getOrDefault("box-sizing", "").strip());
                float pads = padding(d.get("padding-left"), em, frame.width())
                        + padding(d.get("padding-right"), em, frame.width());
                cbWidth = Math.min(cbWidth, borderBox ? own - border(d, "left") - border(d, "right") : own + pads);
            }
            float h = height(d, look);
            float cbHeight = h > 0 ? h + padding(d.get("padding-top"), em, frame.width())
                    + padding(d.get("padding-bottom"), em, frame.width()) : Float.NaN;
            float x0 = originX() + frame.left() + marginLeft + border(d, "left");
            hung = Positioned.hang(ctx, absolutes, x0, ANCHOR + Math.max(pendingSpace, top) + border(d, "top"), cbWidth, cbHeight, depth);
            if (!hung.top().isEmpty()) {
                anchor(hung.top(), frame);
            }
        }
        pendingSpace = Math.max(pendingSpace, top + padding(d.get("padding-top"), em, frame.width()));
        if (isPageBreak(d.get("break-before"))) {
            pendingBreak = true;
        }
        Look outerLook = blockLook;
        Frame outerFrame = blockFrame;
        String outerHeading = headingStyle;
        blockLook = look;
        blockFrame = inner;
        if (tag.matches("h[1-6]")) {
            headingStyle = "Heading" + tag.charAt(1);
        }
        boolean list = tag.equals("ul") || tag.equals("ol") || tag.equals("menu");
        if (list) {
            openList(e, tag, look);
        }
        if (tag.equals("li")) {
            pendingMarker = marker(e, look);
        }
        String outerFloat = floatOf;
        Element only = e.children().size() == 1 ? e.child(0) : null;
        if (only != null && e.ownText().isBlank() && (only.normalName().equals("img") || only.normalName().equals("svg"))) {
            // a floated box around just a picture floats the picture
            floatOf = d.getOrDefault("float", "none");
        }
        float outerClip = clipLeft;
        String shown = d.getOrDefault("display", "block").strip().toLowerCase(Locale.ROOT);
        boolean stacked = (shown.endsWith("flex") || shown.endsWith("grid")) && plan == null
                && !d.getOrDefault("flex-direction", "row").strip().toLowerCase(Locale.ROOT).startsWith("column");
        // a row stacked here is taller than the browser's, so its height says nothing about what shows
        float clip = stacked ? -1 : clipHeight(d, look);
        if (clip >= 0) {
            flush();
            clipLeft = Math.min(clipLeft, clip);
        }
        float inside = clipLeft;
        if (plan == null) {
            walkChildren(e, look, inner, depth);
        } else if (plan.inline()) {
            inlineRow(plan, look, inner, depth);
        } else {
            row(plan, look, inner, depth);
        }
        floatOf = outerFloat;
        flush();
        if (hung != null && !hung.bottom().isEmpty()) {
            float padBottom = padding(d.get("padding-bottom"), em, frame.width());
            float below = padBottom + (padBottom > 0 || border(d, "bottom") > 0 ? pendingSpace : 0);
            anchor(Positioned.moved(hung.bottom(), 0, below, true), frame);
        }
        if (clip >= 0) {
            clipLeft = outerClip == Float.MAX_VALUE ? outerClip : outerClip - (inside - clipLeft);
        }
        if (list) {
            lists.pop();
        }
        if (tag.equals("li")) {
            pendingMarker = null;
        }
        float borderBottom = border(d, "bottom");
        if (borderBottom > 0 && lastParagraph != null && !inCell) {
            lastParagraph.borderBottom = borderBottom;
            Integer c = Css.color(d.getOrDefault("border-bottom-color", colorOf(d.get("border-bottom"))));
            lastParagraph.borderBottomRgb = c == null || c < 0 ? 0 : c;
        }
        blockLook = outerLook;
        blockFrame = outerFrame;
        headingStyle = outerHeading;
        pendingSpace = Math.max(pendingSpace, bottom + padding(d.get("padding-bottom"), em, frame.width()));
        if (isPageBreak(d.get("break-after"))) {
            pendingBreak = true;
        }
    }

    // These shade their own lines, or carry list and heading structure a box would lose
    private static final Set<String> NOT_BOXES = Set.of("p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li",
            "menu", "dl", "dt", "dd", "table", "tr", "td", "th", "caption", "body", "html");

    private static final int BOX_TEXT = 4_000;

    // The background a block paints: its colour, or for a picture we never load under light text, a dark stand-in
    private int fill(Map<String, String> d, Look look) {
        Integer c = Css.color(d.get("background-color"));
        if (c != null && c >= 0) {
            return c;
        }
        if ("picture".equals(d.get("background-image")) && light(look.color)
                && !(Backgrounds.url(d) != null && Backgrounds.drawable(d, ctx.images()))) {
            return 0x2B2B2B;
        }
        return -1;
    }

    private static boolean light(int rgb) {
        int r = rgb >> 16 & 0xFF;
        int g = rgb >> 8 & 0xFF;
        int b = rgb & 0xFF;
        return 0.299 * r + 0.587 * g + 0.114 * b > 200;
    }

    // A container drawn as a box (a coloured panel, a bordered card) keeps its background and edges in one piece
    private boolean boxed(Element e, String tag, Map<String, String> d, int fill) {
        if (inCell || forceInline > 0 || NOT_BOXES.contains(tag)) {
            return false;
        }
        int edges = 0;
        for (String side : new String[] {"top", "bottom", "left", "right"}) {
            edges += border(d, side) > 0 ? 1 : 0;
        }
        boolean painted = fill >= 0 && fill != 0xFFFFFF && fill != blockFrame.shading()
                || Backgrounds.url(d) != null && Backgrounds.drawable(d, ctx.images());
        return (painted || edges >= 3) && Layout.text(e, BOX_TEXT).length() < BOX_TEXT;
    }

    // Percentage padding above or below makes an aspect-ratio box for absolute content, which flows here instead
    private static float padding(String v, float em, float width) {
        return v == null || v.contains("%") ? 0 : length(v, em, width);
    }

    private boolean widerInside(Layout.Plan plan, Look look, float room) {
        for (Element item : plan.items()) {
            if (ctx.widestRow(item, look, room) > plan.items().size()) {
                return true;
            }
        }
        return false;
    }

    private void box(Element e, Map<String, String> d, Look look, Frame frame, int depth, int fill, float top,
            float bottom, List<Positioned.Item> absolutes) throws IOException {
        float em = look.size;
        float marginLeft = Math.max(0, length(d.get("margin-left"), em, frame.width()));
        float marginRight = Math.max(0, length(d.get("margin-right"), em, frame.width()));
        float padLeft = Math.max(0, length(d.get("padding-left"), em, frame.width()));
        float padRight = Math.max(0, length(d.get("padding-right"), em, frame.width()));
        float width = Math.max(36, frame.width() - marginLeft - marginRight);
        Table.Cell cell = new Table.Cell();
        cell.shading = fill >= 0 && fill != 0xFFFFFF ? fill : -1;
        cell.top = HtmlTables.border(d, "top", 0);
        cell.bottom = HtmlTables.border(d, "bottom", 0);
        cell.left = HtmlTables.border(d, "left", 0);
        cell.right = HtmlTables.border(d, "right", 0);
        Flow inner = new Flow(ctx, b -> cell.paragraphs.add((Paragraph) b), true);
        inner.pendingSpace = Math.max(0, padding(d.get("padding-top"), em, frame.width()));
        inner.children(e, look, new Frame(0, 0, Math.max(24, width - padLeft - padRight), -1), depth);
        float padBottom = Math.max(0, padding(d.get("padding-bottom"), em, frame.width()));
        float tall = height(d, look);
        boolean sized = tall > 0 && !"border-box".equalsIgnoreCase(d.getOrDefault("box-sizing", "").strip());
        float boxHeight = tall <= 0 ? -1 : sized ? tall + padding(d.get("padding-top"), em, frame.width()) + padBottom
                + cell.top.width() + cell.bottom.width() : tall;
        float innerWidth = Math.max(24, width - padLeft - padRight);
        float drawnHeight = boxHeight > 0 ? boxHeight
                : Measure.height(cell.paragraphs, innerWidth) + padBottom + cell.top.width() + cell.bottom.width();
        List<Inline> behind = Backgrounds.url(d) == null ? List.of()
                : Backgrounds.paint(d, look, ctx.images(), -padLeft, 0, width, drawnHeight, true);
        Positioned.Hung hung = Positioned.hang(ctx, absolutes, -padLeft, 0, width, boxHeight > 0 ? boxHeight : Float.NaN,
                depth);
        if (cell.paragraphs.isEmpty() && behind.isEmpty() && hung.top().isEmpty() && hung.bottom().isEmpty()
                && boxHeight <= 0) {
            pendingSpace = Math.max(pendingSpace, top + bottom);
            return;
        }
        if (!behind.isEmpty() && (covers(d) || boxHeight > 0) && cell.shading >= 0) {
            behind = new ArrayList<>(behind);
            behind.add(0, new Inline.Shape(-padLeft, 0, width, drawnHeight, cell.shading, -1, 0, false, false, true, 0));
            cell.shading = -1;
        }
        if (!hung.bottom().isEmpty()) {
            cell.paragraphs.add(anchorParagraph(Positioned.moved(hung.bottom(), 0, padBottom, true), 0));
        }
        if (cell.paragraphs.isEmpty()) {
            cell.paragraphs.add(anchorParagraph(List.of(), 0));
        }
        cell.paragraphs.get(0).inlines.addAll(0, hung.top());
        cell.paragraphs.get(0).inlines.addAll(0, behind);
        if (padBottom >= 1) {
            inner.spacer(padBottom, new Frame(0, 0, width, -1));
        }
        Table t = new Table();
        t.columnWidths.add(width);
        t.indent = frame.left() + marginLeft;
        t.cellMarginLeft = padLeft;
        t.cellMarginRight = padRight;
        Table.Row row = new Table.Row();
        row.splits = true;
        row.cells.add(cell);
        if (boxHeight > 0) {
            row.height = Math.min(boxHeight, ctx.pageRoom());
        }
        t.rows.add(row);
        pendingSpace = Math.max(pendingSpace, top);
        if (pendingSpace > 0) {
            spacer(pendingSpace, frame);
        }
        t.pageBreakBefore = pendingBreak && !sectionStarted;
        pendingBreak = false;
        sectionStarted = false;
        ctx.count();
        out.add(t);
        pendingSpace = bottom;
    }

    // A block on a page of another name starts a new section on that page's size and margins
    private void page(String name, Frame frame) throws IOException {
        if (name == null || inCell || name.isBlank() || name.strip().equalsIgnoreCase("auto")) {
            return;
        }
        String next = name.strip();
        if (next.equals(ctx.pageName()) || ctx.section(next) == null) {
            return;
        }
        if (ctx.nothingWritten()) {
            ctx.pageName(next);
            return;
        }
        Section ending = ctx.section(ctx.pageName());
        Paragraph mark = new Paragraph();
        mark.indentLeft = frame.left();
        mark.lineRule = Paragraph.LineRule.EXACT;
        mark.lineHeight = 1;
        mark.endsSection = ending != null ? ending : ctx.defaultSection();
        out.add(mark);
        ctx.pageName(next);
        // the new section starts a page already, so a forced break right after it is the same break
        sectionStarted = true;
    }

    private void inlineRow(Layout.Plan plan, Look look, Frame frame, int depth) throws IOException {
        for (int i = 0; i < plan.items().size(); i++) {
            float space = i == 0 ? 0 : plan.gap() + sideSpace(plan.items().get(i - 1), look, "right")
                    + sideSpace(plan.items().get(i), look, "left");
            // Items that touch stay touching (a word split into letters); spaced ones keep a gap
            pendingGap = space >= 1 ? new Inline.Text(space >= 6 ? "\u2002" : " ", look.run(), link, -1) : null;
            forceInline++;
            try {
                element(plan.items().get(i), look, frame, depth + 1);
            } finally {
                forceInline--;
            }
            lastSpace = false;
        }
        pendingGap = null;
    }

    private float sideSpace(Element item, Look look, String side) {
        Map<String, String> d = ctx.declared(item, look);
        return Math.max(0, length(d.get("margin-" + side), look.size, 0)) + Math.max(0, length(d.get("padding-" + side),
                look.size, 0));
    }

    private Table.Cell cell(Element item, Look look, Frame frame, float width, float gap, int depth) throws IOException {
        Table.Cell cell = new Table.Cell();
        if (item != null) {
            Integer fill = Css.color(ctx.declared(item, look).get("background-color"));
            // an item with no background of its own shows the row's
            cell.shading = fill == null || fill < 0 ? frame.shading() : fill;
            Flow inner = new Flow(ctx, b -> cell.paragraphs.add((Paragraph) b), true);
            inner.clipLeft = clipLeft;
            inner.single(item, look, new Frame(0, 0, Math.max(24, width - gap), -1), depth + 1);
        }
        return cell;
    }

    private void row(Layout.Plan plan, Look look, Frame frame, int depth) throws IOException {
        flush();
        Table t = new Table();
        float[] widths = plan.widths();
        for (float w : widths) {
            t.columnWidths.add(w);
        }
        t.indent = frame.left();
        t.cellMarginLeft = plan.gap() / 2;
        t.cellMarginRight = plan.gap() / 2;
        List<Element> items = plan.items();
        int columns = plan.columns();
        if (plan.areas() != null) {
            for (List<int[]> cells : plan.areas()) {
                Table.Row row = new Table.Row();
                row.splits = true;
                for (int[] a : cells) {
                    Element item = a[0] < 0 ? null : items.get(a[0]);
                    Table.Cell cell = cell(item, look, frame, widths[0] * a[1], plan.gap(), depth);
                    cell.gridSpan = a[1];
                    cell.vMerge = a[2];
                    row.cells.add(cell);
                }
                t.rows.add(row);
            }
        }
        for (int start = 0; plan.areas() == null && start < items.size(); start += columns) {
            Table.Row row = new Table.Row();
            row.splits = true;
            for (int c = 0; c < columns; c++) {
                Element item = start + c < items.size() ? items.get(start + c) : null;
                row.cells.add(cell(item, look, frame, widths[c], plan.gap(), depth));
            }
            t.rows.add(row);
        }
        if (plan.areas() == null) {
            dropEmptyColumns(t);
        }
        if (t.columnWidths.size() < 2) {
            // what is left is one column: its paragraphs flow as if there had been no row
            for (Table.Row row : t.rows) {
                for (Table.Cell cell : row.cells) {
                    for (Paragraph p : cell.paragraphs) {
                        p.indentLeft += frame.left();
                        ctx.count();
                        out.add(p);
                    }
                }
            }
            return;
        }
        if (pendingSpace > 0) {
            spacer(pendingSpace, frame);
            pendingSpace = 0;
        }
        t.pageBreakBefore = pendingBreak;
        pendingBreak = false;
        ctx.count();
        out.add(t);
    }

    // Items that drew nothing (a picture that could not be read, an empty wrapper) leave no column behind
    private static void dropEmptyColumns(Table t) {
        float room = 0;
        for (float w : t.columnWidths) {
            room += w;
        }
        for (int c = t.columnWidths.size() - 1; c >= 0; c--) {
            boolean empty = true;
            for (Table.Row row : t.rows) {
                empty &= row.cells.get(c).paragraphs.isEmpty();
            }
            if (empty) {
                t.columnWidths.remove(c);
                for (Table.Row row : t.rows) {
                    row.cells.remove(c);
                }
            }
        }
        float left = 0;
        for (float w : t.columnWidths) {
            left += w;
        }
        for (int c = 0; c < t.columnWidths.size() && left > 0; c++) {
            t.columnWidths.set(c, t.columnWidths.get(c) * room / left);
        }
    }

    /** One element laid out on its own, as a cell of a side-by-side row. */
    void single(Element item, Look look, Frame frame, int depth) throws IOException {
        blockLook = look;
        blockFrame = frame;
        element(item, look, frame, depth);
        flush();
    }

    private static boolean isPageBreak(String v) {
        if (v == null) {
            return false;
        }
        String t = v.strip().toLowerCase(Locale.ROOT);
        return t.equals("page") || t.equals("always") || t.equals("left") || t.equals("right");
    }

    private static String colorOf(String border) {
        if (border == null) {
            return null;
        }
        for (String token : Css.tokens(border)) {
            if (Css.color(token) != null) {
                return token;
            }
        }
        return null;
    }

    static float border(Map<String, String> d, String side) {
        String style = d.get("border-" + side + "-style");
        String all = d.get("border-" + side);
        String width = d.get("border-" + side + "-width");
        if (all != null) {
            for (String token : Css.tokens(all)) {
                String t = token.toLowerCase(Locale.ROOT);
                if (t.equals("none") || t.equals("hidden")) {
                    style = style == null ? "none" : style;
                } else if (Css.length(t, 12, 0) != null) {
                    width = width == null ? t : width;
                } else if (Css.color(t) == null) {
                    style = style == null ? t : style;
                }
            }
            if (style == null) {
                style = "solid";
            }
        }
        if (style == null || style.equalsIgnoreCase("none") || style.equalsIgnoreCase("hidden")) {
            return 0;
        }
        Float w = width == null ? Float.valueOf(0.75f) : Css.length(keyword(width), 12, 0);
        return w == null ? 0.75f : Math.max(0, Math.min(12, w));
    }

    private static String keyword(String width) {
        return switch (width.strip().toLowerCase(Locale.ROOT)) {
            case "thin" -> "1px";
            case "medium" -> "3px";
            case "thick" -> "5px";
            default -> width;
        };
    }

    static float length(String v, float em, float width) {
        if (v == null || v.equalsIgnoreCase("auto")) {
            return 0;
        }
        Float pt = Css.length(v, em, width);
        return pt == null ? 0 : pt;
    }

    private void openList(Element e, String tag, Look look) {
        boolean top = lists.isEmpty();
        Numbering.Instance inst = top ? ctx.numbering().create() : lists.peek().list();
        int level = top ? 0 : Math.min(8, lists.peek().level() + 1);
        String type = listType(e, tag, look);
        boolean bullets = !type.matches("decimal|decimal-leading-zero|lower-alpha|lower-latin|upper-alpha|upper-latin"
                + "|lower-roman|upper-roman|1|a|A|i|I");
        if (inst.definition.levels[level] == null) {
            inst.definition.levels[level] = level(type, level);
            if (tag.equals("ol") && e.hasAttr("start")) {
                try {
                    inst.starts[level] = Math.max(0, Math.min(32_767, Integer.parseInt(e.attr("start").strip())));
                } catch (NumberFormatException ignored) {
                    // the list starts at 1
                }
            }
        }
        lists.push(new ListLevel(inst, level, bullets));
    }

    private String listType(Element e, String tag, Look look) {
        String attr = e.attr("type").strip();
        if (!attr.isEmpty()) {
            return attr;
        }
        if (look.listStyle != null) {
            return look.listStyle;
        }
        if (tag.equals("ol")) {
            return "decimal";
        }
        return switch (lists.size() % 3) {
            case 0 -> "disc";
            case 1 -> "circle";
            default -> "square";
        };
    }

    private static Numbering.Level level(String type, int level) {
        String number = "%" + (level + 1) + ".";
        return switch (type) {
            case "decimal", "1" -> new Numbering.Level("decimal", number, null, 36f * (level + 1), 18f);
            case "decimal-leading-zero" -> new Numbering.Level("decimalZero", number, null, 36f * (level + 1), 18f);
            case "lower-alpha", "lower-latin", "a" -> new Numbering.Level("lowerLetter", number, null, 36f * (level + 1), 18f);
            case "upper-alpha", "upper-latin", "A" -> new Numbering.Level("upperLetter", number, null, 36f * (level + 1), 18f);
            case "lower-roman", "i" -> new Numbering.Level("lowerRoman", number, null, 36f * (level + 1), 18f);
            case "upper-roman", "I" -> new Numbering.Level("upperRoman", number, null, 36f * (level + 1), 18f);
            case "circle" -> new Numbering.Level("bullet", "\u25e6", null, 36f * (level + 1), 18f);
            case "square" -> new Numbering.Level("bullet", "\u25aa", null, 36f * (level + 1), 18f);
            case "none" -> new Numbering.Level("none", "", null, 36f * (level + 1), 18f);
            default -> new Numbering.Level("bullet", "\u2022", null, 36f * (level + 1), 18f);
        };
    }

    private Paragraph.ListRef marker(Element li, Look look) {
        ListLevel l = lists.peek();
        if (l == null || "none".equals(look.listStyle)) {
            return null;
        }
        return new Paragraph.ListRef(l.list().numId, l.level());
    }

    private void text(String raw, Look look) throws IOException {
        if (raw.isEmpty()) {
            return;
        }
        String ws = look.whiteSpace;
        boolean keepSpaces = ws.startsWith("pre") && !ws.equals("pre-line") || ws.equals("break-spaces");
        boolean keepLines = ws.startsWith("pre") || ws.equals("break-spaces");
        if (!keepSpaces) {
            String collapsed = raw.replaceAll(keepLines ? "[ \\t\\f\\r]+" : "[ \\t\\n\\f\\r]+", " ");
            if (lastSpace && collapsed.startsWith(" ")) {
                collapsed = collapsed.substring(1);
            }
            if (collapsed.isEmpty()) {
                return;
            }
            raw = collapsed;
        }
        String text = look.transform(raw);
        RunStyle style = look.run();
        int from = 0;
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : '\n';
            boolean lineEnd = c == '\n' && keepLines && i < text.length();
            boolean tab = c == '\t' && keepSpaces;
            if (i == text.length() || lineEnd || tab) {
                if (i > from) {
                    inline(new Inline.Text(text.substring(from, i), style, link, -1), look);
                }
                if (lineEnd) {
                    inline(new Inline.Break(), look);
                    lastSpace = true;
                } else if (tab && tabsInARow() < MAX_TABS_IN_A_ROW) {
                    inline(new Inline.Tab(style), look);
                }
                from = i + 1;
            }
        }
        if (!text.isEmpty()) {
            lastSpace = text.endsWith(" ") || text.endsWith("\n");
        }
    }

    private int tabsInARow() {
        int n = 0;
        if (para != null) {
            for (int i = para.inlines.size() - 1; i >= 0 && para.inlines.get(i) instanceof Inline.Tab; i--) {
                n++;
            }
        }
        return n;
    }

    private void inline(Inline in, Look look) throws IOException {
        if (para == null) {
            start();
        }
        if (pendingGap != null) {
            // the gap between two items is written only once both have something to show
            if (!para.inlines.isEmpty()) {
                para.inlines.add(pendingGap);
            }
            pendingGap = null;
        }
        para.inlines.add(in);
    }

    private void start() {
        Look l = blockLook;
        Frame f = blockFrame;
        Paragraph p = new Paragraph();
        p.align = l.align;
        p.indentLeft = f.left();
        p.indentRight = f.right();
        p.indentFirst = l.textIndent;
        p.spaceBefore = Math.min(pendingSpace, 400);
        pendingSpace = 0;
        p.pageBreakBefore = pendingBreak && !inCell && !sectionStarted;
        pendingBreak = false;
        sectionStarted = false;
        p.bidi = l.rightToLeft;
        p.shading = f.shading();
        if (l.linePoints > 0) {
            p.lineRule = Paragraph.LineRule.AT_LEAST;
            p.lineHeight = l.linePoints;
        } else if (l.lineMultiple > 0) {
            p.lineRule = Paragraph.LineRule.AT_LEAST;
            p.lineHeight = l.lineMultiple * l.size;
        }
        if (headingStyle != null && !inCell) {
            p.style = ctx.heading(headingStyle, l.run());
            p.keepNext = true;
        }
        if (pendingMarker != null) {
            p.list = pendingMarker;
            p.indentFirst = -18;
            pendingMarker = null;
        }
        para = p;
        p.inlines.addAll(pendingFloats);
        pendingFloats.clear();
        lastSpace = true;
    }

    void flush() throws IOException {
        if (para == null) {
            return;
        }
        Paragraph p = para;
        para = null;
        boolean blankLine = !p.inlines.isEmpty();
        trimEnd(p.inlines);
        if (p.inlines.isEmpty() && blankLine) {
            // <p><br></p> still takes a line
            p.inlines.add(new Inline.Text("\u00a0", blockLook.run(), null, -1));
        }
        if (p.inlines.isEmpty()) {
            return;
        }
        if (clipLeft != Float.MAX_VALUE && !fits(p)) {
            return;
        }
        measureUnwrapped(p);
        lastParagraph = p;
        lastSpace = true;
        ctx.count();
        out.add(p);
    }

    // A box that scrolls or clips keeps what is too wide inside it, so the page does not grow
    private static boolean scrolls(Map<String, String> d) {
        String o = d.getOrDefault("overflow-x", d.getOrDefault("overflow", "visible")).strip().toLowerCase(Locale.ROOT);
        return !o.isEmpty() && !o.startsWith("visible");
    }

    // Preformatted and no-wrap lines run as wide as they are, past the page if need be
    private void measureUnwrapped(Paragraph p) {
        String ws = blockLook.whiteSpace;
        if (!ws.equals("pre") && !ws.equals("nowrap")) {
            return;
        }
        float line = 0;
        float widest = 0;
        int column = 0;
        for (Inline i : p.inlines) {
            if (i instanceof Inline.Text t) {
                line += t.text().length() * t.style().size() * charWidth(t.style().font());
                column += t.text().length();
            } else if (i instanceof Inline.Tab t) {
                // a tab moves to the next stop eight characters along
                int step = 8 - column % 8;
                line += step * t.style().size() * charWidth(t.style().font());
                column += step;
            } else if (i instanceof Inline.Break) {
                widest = Math.max(widest, line);
                line = 0;
                column = 0;
            }
        }
        widest = Math.max(widest, line);
        // the paragraph's indent already counts from the text area's left edge
        ctx.wants(p.indentLeft + widest);
    }

    // An average character's advance in ems: fixed-pitch faces are wider
    private static float charWidth(String font) {
        String f = font == null ? "" : font.toLowerCase(Locale.ROOT);
        boolean mono = f.contains("mono") || f.contains("courier") || f.contains("consol") || f.contains("menlo")
                || f.contains("code");
        return mono ? 0.6f : 0.5f;
    }

    // A block given a width wider than its frame pushes past the page in a browser
    private void measureFixed(Element e, Map<String, String> d, Look look, Frame frame, float left, float right) {
        String position = d.getOrDefault("position", "").strip().toLowerCase(Locale.ROOT);
        if (position.equals("absolute") || position.equals("fixed") || shrinksInRow(e, d, look)) {
            return;
        }
        Float w = absolute(d.get("min-width"), look);
        Float width = absolute(d.get("width"), look);
        if (width != null && (w == null || width > w)) {
            Float max = absolute(d.get("max-width"), look);
            boolean capped = max != null || d.getOrDefault("max-width", "none").contains("%");
            w = capped ? (max == null ? null : Math.min(width, max)) : width;
        }
        if (w != null && w > frame.width() - left - right) {
            ctx.wants(frame.left() + left + w + right);
        }
    }

    // A flex item gives up width to fit its row unless it is told not to shrink
    private boolean shrinksInRow(Element e, Map<String, String> d, Look look) {
        Element parent = e.parent();
        if (parent == null) {
            return false;
        }
        String display = ctx.declared(parent, look).getOrDefault("display", "").strip().toLowerCase(Locale.ROOT);
        if (!display.endsWith("flex")) {
            return false;
        }
        String shrink = d.getOrDefault("flex-shrink", "").strip();
        String flex = d.getOrDefault("flex", "").strip().toLowerCase(Locale.ROOT);
        return !shrink.equals("0") && !flex.equals("none") && !flex.matches("[0-9.]+\\s+0(\\s.*)?");
    }

    private static Float absolute(String v, Look look) {
        return v == null || v.contains("%") || v.contains("vw") ? null : Css.length(v, look.size, 0);
    }

    // Overflow hidden under a fixed or maximum height, or a line clamp, shows only what fits; -1 when it does not clip
    private static float clipHeight(Map<String, String> d, Look look) {
        String overflow = d.getOrDefault("overflow-y", d.getOrDefault("overflow", "")).strip().toLowerCase(Locale.ROOT);
        if (!overflow.startsWith("hidden") && !overflow.startsWith("clip")) {
            return -1;
        }
        String clamp = d.getOrDefault("-webkit-line-clamp", d.getOrDefault("line-clamp", "")).strip();
        if (clamp.matches("[0-9]{1,3}") && Integer.parseInt(clamp) > 0) {
            return Integer.parseInt(clamp) * lineHeight(look);
        }
        String h = d.getOrDefault("max-height", d.get("height"));
        Float pt = h == null || h.contains("%") ? null : Css.length(h, look.size, 0);
        return pt == null || pt <= 1 ? -1 : pt;
    }

    private static float lineHeight(Look look) {
        if (look.linePoints > 0) {
            return look.linePoints;
        }
        return look.size * (look.lineMultiple > 0 ? look.lineMultiple : 1.2f);
    }

    // Spends the clip on a paragraph by its estimated height: whole lines that fit stay, the rest is cut
    private boolean fits(Paragraph p) {
        // a column here is often narrower than the browser's, which then fits more words in the same height
        Section page = ctx.defaultSection();
        float wide = (page.pageWidth - page.marginLeft - page.marginRight) * 0.75f;
        float width = Math.max(wide, Math.max(24, blockFrame.width() - p.indentLeft - p.indentRight));
        float lines = 0;
        float tallest = 0;
        int chars = 0;
        float size = blockLook.size;
        for (Inline i : p.inlines) {
            if (i instanceof Inline.Text t) {
                size = t.style().size();
                chars += t.text().length();
            } else if (i instanceof Inline.Image img) {
                tallest = Math.max(tallest, img.picture().height);
            } else if (i instanceof Inline.Break) {
                lines += Math.max(1, (float) Math.ceil(chars * size * 0.5f / width));
                chars = 0;
            }
        }
        lines += Math.max(chars > 0 || tallest == 0 ? 1 : 0, (float) Math.ceil(chars * size * 0.5f / width));
        float line = Math.max(p.lineHeight, size * 1.15f);
        // margins are left out: the first collapses into the box, and the cut is approximate anyway
        float height = Math.max(lines * line, tallest);
        if (height <= clipLeft + 0.5f) {
            clipLeft -= height;
            return true;
        }
        int keep = (int) Math.floor(clipLeft / line);
        clipLeft = 0;
        if (keep < 1 || tallest > 0) {
            return false;
        }
        cut(p, (int) (keep * width / (size * 0.5f)));
        return true;
    }

    // Keeps the first chars characters of a paragraph's text, ending at a word
    private static void cut(Paragraph p, int chars) {
        int left = chars;
        for (int i = 0; i < p.inlines.size(); i++) {
            if (!(p.inlines.get(i) instanceof Inline.Text t)) {
                continue;
            }
            if (t.text().length() <= left) {
                left -= t.text().length();
                continue;
            }
            int at = t.text().lastIndexOf(' ', left);
            String kept = t.text().substring(0, Math.max(0, at > 0 ? at : left)).stripTrailing();
            p.inlines.set(i, new Inline.Text(kept + "\u2026", t.style(), t.link(), t.anchorPage()));
            p.inlines.subList(i + 1, p.inlines.size()).clear();
            return;
        }
    }

    private static void trimEnd(List<Inline> inlines) {
        while (!inlines.isEmpty()) {
            Inline last = inlines.getLast();
            if (last instanceof Inline.Break) {
                inlines.removeLast();
                continue;
            }
            if (last instanceof Inline.Text t) {
                String s = t.text().stripTrailing();
                if (s.length() != t.text().length() && !t.text().endsWith("\u00a0")) {
                    inlines.removeLast();
                    if (!s.isEmpty()) {
                        inlines.add(new Inline.Text(s, t.style(), t.link(), t.anchorPage()));
                        return;
                    }
                    continue;
                }
            }
            return;
        }
    }

    private void image(Element e, Map<String, String> d, Look look, Frame frame) throws IOException {
        String width = d.getOrDefault("width", "").strip();
        float room = width.endsWith("%") ? percentBase(e, look, frame.width()) : frame.width();
        measureImage(e, d, look, frame);
        Picture pic = ctx.images().picture(e, d, look, room, pictureRoom());
        if (pic == null) {
            String alt = e.attr("alt").strip();
            if (!alt.isEmpty()) {
                text("[" + alt + "]", look);
            }
            return;
        }
        turn(pic, d, look);
        place(pic, d, look, frame);
    }

    private static void turn(Picture pic, Map<String, String> d, Look look) {
        Transform t = Transform.of(d.get("transform"), look.size, pic.width, pic.height);
        if (t.shiftOnly()) {
            return;
        }
        pic.width *= t.scaleX();
        pic.height *= t.scaleY();
        pic.rotation = Math.round(t.rotation());
    }

    // A row in a cell cannot break, so a picture there leaves room for its caption on the same page
    private float pictureRoom() {
        return inCell ? ctx.pageRoom() * 0.75f : ctx.pageRoom();
    }

    // A picture with a fixed width and no max-width shows at that width, past the page if it is wider
    private void measureImage(Element e, Map<String, String> d, Look look, Frame frame) {
        if (d.containsKey("max-width") && !d.get("max-width").strip().equalsIgnoreCase("none")) {
            return;
        }
        Float w = absolute(d.get("width"), look);
        if (w == null && !d.containsKey("width") && e.attr("width").matches("[0-9]{1,5}")) {
            w = Integer.parseInt(e.attr("width")) * 0.75f;
        }
        if (w != null && w > frame.width()) {
            ctx.wants(frame.left() + w);
        }
    }

    // A percentage width is of the nearest box with a fixed width, like a map marker's icon, not of the page
    private float percentBase(Element e, Look look, float room) {
        float scale = 1;
        Element p = e.parent();
        for (int i = 0; p != null && i < 12; i++, p = p.parent()) {
            String w = ctx.declared(p, look).getOrDefault("width", "auto").strip().toLowerCase(Locale.ROOT);
            if (w.equals("auto")) {
                continue;
            }
            if (w.matches("[0-9]{1,6}([.][0-9]{1,6})?%")) {
                scale *= Math.max(0, Math.min(1, Float.parseFloat(w.substring(0, w.length() - 1)) / 100f));
                continue;
            }
            Float pt = w.contains("%") ? null : Css.length(w, look.size, room);
            return pt == null || pt <= 0 ? room : Math.min(room, pt * scale);
        }
        return room;
    }

    private void place(Picture pic, Map<String, String> d, Look look, Frame frame) throws IOException {
        String f = d.getOrDefault("float", floatOf).strip().toLowerCase(Locale.ROOT);
        if ((f.equals("left") || f.equals("right")) && forceInline == 0 && !inCell && pic.width < frame.width() * 0.7f) {
            // A floated picture sits at the side of the text, which wraps around it as in a browser
            Section page = ctx.defaultSection();
            pic.wrap = Picture.Wrap.SQUARE;
            pic.fromParagraph = true;
            pic.wrapGap = 6;
            pic.x = page.marginLeft + frame.left() + (f.equals("right") ? frame.width() - pic.width : 0);
            pic.y = 0;
            if (para != null) {
                para.inlines.add(new Inline.Image(pic));
            } else {
                pendingFloats.add(new Inline.Image(pic));
            }
            return;
        }
        boolean alone = forceInline == 0
                && (d.getOrDefault("display", "").strip().equalsIgnoreCase("block") || !f.equals("none"));
        if (alone) {
            flush();
        }
        inline(new Inline.Image(pic), look);
        lastSpace = false;
        if (alone) {
            if (para != null && f.equals("right")) {
                para.align = Paragraph.Align.RIGHT;
            }
            flush();
        }
    }

    private void rule(Map<String, String> d, Look look, Frame frame) throws IOException {
        flush();
        float em = look.size;
        pendingSpace = Math.max(pendingSpace, length(d.get("margin-top"), em, frame.width()));
        Paragraph p = new Paragraph();
        p.indentLeft = frame.left();
        p.indentRight = frame.right();
        p.spaceBefore = Math.min(pendingSpace, 400);
        p.lineRule = Paragraph.LineRule.EXACT;
        p.lineHeight = 2;
        p.borderBottom = 0.75f;
        Integer c = Css.color(d.getOrDefault("border-top-color", d.getOrDefault("color", d.get("background-color"))));
        p.borderBottomRgb = c == null || c < 0 ? 0xA0A0A0 : c;
        p.inlines.add(new Inline.Text("\u00a0", look.run().withSize(1), null, -1));
        pendingSpace = length(d.get("margin-bottom"), em, frame.width());
        ctx.count();
        out.add(p);
    }

    private void table(Element e, Map<String, String> d, Look look, Frame frame, boolean centred, int depth)
            throws IOException {
        flush();
        if (inCell) {
            nestedTable(e, look, frame, depth);
            return;
        }
        if (HtmlTables.layoutOnly(e, d, ctx, look)) {
            // a one-column table with no borders is only there to stack its cells: they flow as blocks
            Integer fill = Css.color(d.get("background-color"));
            Frame inside = frame.inner(0, 0, fill == null ? -1 : fill);
            for (Element tr : e.select(HtmlTables.ROWS)) {
                for (Element cell : tr.select("> td, > th")) {
                    element(cell, look, inside, depth + 1);
                }
            }
            flush();
            return;
        }
        float em = look.size;
        pendingSpace = Math.max(pendingSpace, length(d.get("margin-top"), em, frame.width()));
        if (isPageBreak(d.get("break-before"))) {
            pendingBreak = true;
        }
        Table t = ctx.tables().table(e, d, look, frame, centred, depth);
        if (t == null) {
            return;
        }
        t.pageBreakBefore = pendingBreak && !sectionStarted;
        pendingBreak = false;
        sectionStarted = false;
        if (pendingSpace > 0) {
            spacer(pendingSpace, frame);
        }
        pendingSpace = Math.max(0, length(d.get("margin-bottom"), em, frame.width()));
        ctx.count();
        out.add(t);
    }

    // A table inside a cell: each of its rows becomes a line, its cells side by side with their own formatting
    private void nestedTable(Element table, Look look, Frame frame, int depth) throws IOException {
        for (Element tr : table.select(HtmlTables.ROWS)) {
            boolean first = true;
            for (Element td : tr.select("> td, > th")) {
                pendingGap = first ? null : new Inline.Text("  ", look.run(), null, -1);
                first = false;
                forceInline++;
                try {
                    element(td, look, frame, depth + 1);
                } finally {
                    forceInline--;
                }
                lastSpace = false;
            }
            pendingGap = null;
            flush();
        }
    }

    private static final float ANCHOR = 1;

    private void staticBox(Element e, Map<String, String> d, Look look, Frame frame, int depth) throws IOException {
        Positioned.Placed p = Positioned.lay(ctx, new Positioned.Item(e, d, look), frame.width(), Float.NaN, false,
                depth);
        if (p.drawings().isEmpty()) {
            return;
        }
        float x0 = originX() + frame.left();
        float lift = p.fromBottom() ? p.height() : 0;
        if (para != null) {
            para.inlines.addAll(Positioned.moved(p.drawings(), x0, lift, true));
        } else {
            anchor(Positioned.moved(p.drawings(), x0, ANCHOR + pendingSpace + lift, true), frame);
        }
    }

    private void lifted(Element e, Map<String, String> d, Look look, Frame frame, int depth) throws IOException {
        float em = look.size;
        float top = length(d.get("margin-top"), em, frame.width());
        float bottom = length(d.get("margin-bottom"), em, frame.width());
        Positioned.Placed p = Positioned.lay(ctx, new Positioned.Item(e, d, look), frame.width(), Float.NaN, true,
                depth);
        Transform t = Transform.of(d.get("transform"), em, p.width(), p.height());
        float roomHeight = p.height() / Math.max(0.05f, t.scaleY());
        pendingSpace = Math.max(pendingSpace, top);
        Paragraph hold = anchorParagraph(Positioned.moved(p.drawings(), originX() + frame.left(), pendingSpace, true),
                frame.left());
        hold.lineHeight = Math.max(1, Math.min(roomHeight, ctx.pageRoom()));
        hold.spaceBefore = Math.min(pendingSpace, 400);
        hold.keepNext = false;
        hold.pageBreakBefore = pendingBreak && !inCell && !sectionStarted;
        pendingBreak = false;
        sectionStarted = false;
        pendingSpace = 0;
        ctx.count();
        out.add(hold);
        pendingSpace = bottom;
    }

    private void anchor(List<Inline> drawings, Frame frame) throws IOException {
        Paragraph p = anchorParagraph(drawings, frame.left());
        p.pageBreakBefore = pendingBreak && !inCell && !sectionStarted;
        pendingBreak = false;
        sectionStarted = false;
        ctx.count();
        out.add(p);
    }

    private Paragraph anchorParagraph(List<Inline> drawings, float indent) {
        return anchorParagraph(drawings, indent, blockLook.run());
    }

    static Paragraph anchorParagraph(List<Inline> drawings, float indent, RunStyle style) {
        Paragraph p = new Paragraph();
        p.indentLeft = indent;
        p.lineRule = Paragraph.LineRule.EXACT;
        p.lineHeight = ANCHOR;
        p.keepNext = true;
        p.inlines.addAll(drawings);
        p.inlines.add(new Inline.Text("\u00a0", style.withSize(1), null, -1));
        return p;
    }

    private float originX() {
        if (inCell) {
            return 0;
        }
        Section s = ctx.section(ctx.pageName());
        return (s != null ? s : ctx.defaultSection()).marginLeft;
    }

    private static float[] relativeShift(Map<String, String> d, Look look, Frame frame, Transform turn) {
        float dx = turn.dx();
        float dy = turn.dy();
        if (Positioned.position(d).equals("relative")) {
            Float left = shiftBy(d.get("left"), look, frame.width());
            Float right = shiftBy(d.get("right"), look, frame.width());
            Float top = shiftBy(d.get("top"), look, 0);
            Float bottom = shiftBy(d.get("bottom"), look, 0);
            dx += left != null ? left : right != null ? -right : 0;
            dy += top != null ? top : bottom != null ? -bottom : 0;
        }
        return new float[] {Math.max(-frame.left(), Math.min(dx, frame.width() / 2)), Math.max(-400, Math.min(dy, 400))};
    }

    private static Float shiftBy(String v, Look look, float base) {
        if (v == null || v.strip().equalsIgnoreCase("auto") || base <= 0 && v.contains("%")) {
            return null;
        }
        return Css.length(v, look.size, base);
    }

    private static float height(Map<String, String> d, Look look) {
        String h = d.get("height");
        if (h == null || h.contains("%") || h.strip().equalsIgnoreCase("auto")) {
            return 0;
        }
        Float pt = Css.length(h, look.size, 0);
        return pt == null || pt <= 1 ? 0 : Math.min(pt, 14_400);
    }

    private static boolean covers(Map<String, String> d) {
        String size = d.getOrDefault("background-size", "auto").strip().toLowerCase(Locale.ROOT);
        boolean[] repeat = Backgrounds.repeat(d.get("background-repeat"));
        return size.equals("cover") || size.equals("100% 100%") || repeat[0] && repeat[1];
    }

    private void spacer(float space, Frame frame) throws IOException {
        Paragraph p = new Paragraph();
        p.indentLeft = frame.left();
        p.lineRule = Paragraph.LineRule.EXACT;
        p.lineHeight = Math.max(1, Math.min(space, 400));
        p.inlines.add(new Inline.Text("\u00a0", blockLook.run().withSize(1), null, -1));
        out.add(p);
    }

}

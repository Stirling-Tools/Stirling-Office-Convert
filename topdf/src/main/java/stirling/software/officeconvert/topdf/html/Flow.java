package stirling.software.officeconvert.topdf.html;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.model.Block;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.Table;
import stirling.software.officeconvert.sink.Links;
import stirling.software.officeconvert.topdf.docx.BlockFlow;

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

    static final int MAX_DEPTH = 200;

    // Tabs past this many in a row are past the edge of any page, and each costs the layout memory
    private static final int MAX_TABS_IN_A_ROW = 32;

    private static final float MAX_BORDER_SPACE = 31;

    private static final float BROKEN_PICTURE = 12;

    static final Set<String> SKIPPED = Set.of("head", "script", "style", "template", "title", "meta", "link",
            "base", "iframe", "object", "embed", "applet", "frame", "frameset", "video", "audio", "canvas",
            "map", "area", "input", "select", "option", "datalist", "param", "source", "track", "noembed", "noframes",
            "dialog", "colgroup", "col");

    static final Set<String> BLOCKS = Set.of("address", "article", "aside", "blockquote", "body", "center",
            "dd", "details", "div", "dl", "dt", "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3",
            "h4", "h5", "h6", "header", "hgroup", "hr", "html", "legend", "li", "main", "menu", "nav", "ol", "p", "pre",
            "section", "summary", "ul", "listing", "xmp", "plaintext", "caption", "table", "tr", "td", "th", "tbody",
            "thead", "tfoot", "noscript", "search");

    private final HtmlContext ctx;
    private final Out out;
    private final boolean paragraphsOnly;
    private final boolean inCell;
    private final HtmlLists lists;
    private Paragraph para;
    private Look blockLook;
    private Frame blockFrame;
    private String headingStyle;
    private float pendingSpace;
    private float pendingMargin;
    private boolean pendingBreak;
    private Paragraph.ListRef pendingMarker;
    private boolean lastSpace = true;
    private String link;
    private final List<String> pendingAnchors = new ArrayList<>();
    private int anchors;
    private int forceInline;
    private Inline pendingGap;
    private final List<Inline> pendingEdges = new ArrayList<>();
    private long edgeBoundary;
    private int rowItemStart = -1;
    private int rowNested;
    private int avoiding;
    private boolean inColumns;
    private final List<Block> held = new ArrayList<>();
    private Block waiting;
    private boolean outrun;
    private Element sized;
    private Element aroundOf;
    private Element stackedCell;
    private Paragraph.Align aroundAlign;
    private float besideLeft;
    private float besideRight;
    private final List<Inline> pendingFloats = new ArrayList<>();
    private String floatOf = "none";
    private boolean sectionStarted;
    private Paragraph rightOf;
    private String pendingBookmark;
    private Paragraph leaderOf;
    private int leaderFrom;
    private char leaderChar;
    private RunStyle leaderStyle;
    private final List<Inline> right = new ArrayList<>();
    private RunStyle rightStyle;
    // the height left in a box that clips its overflow, as a browser shows only what fits
    private float clipLeft = Float.MAX_VALUE;

    Flow(HtmlContext ctx, Out out, boolean inCell) {
        this(ctx, out, inCell, false);
    }

    Flow(HtmlContext ctx, Out out, boolean inCell, boolean paragraphsOnly) {
        this.ctx = ctx;
        this.out = out;
        this.inCell = inCell;
        this.paragraphsOnly = paragraphsOnly;
        this.lists = new HtmlLists(ctx);
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
            Positioned.Hung hung = Positioned.hang(ctx, items, s.marginLeft - ctx.insetLeft(), s.marginTop,
                    s.pageWidth - s.marginLeft - s.marginRight + ctx.insetLeft() + ctx.insetRight(), ctx.pageRoom(),
                    0);
            if (!hung.top().isEmpty()) {
                anchor(Positioned.moved(hung.top(), 0, 0, false), frame);
            }
        }
        Map<String, String> d = ctx.declared(body, look);
        String top = d.get("margin-top");
        margin(top == null ? HtmlDocument.BODY_MARGIN : Math.max(0, BoxCss.length(top, look.size, frame.width())));
        pad(BoxCss.padding(d.get("padding-top"), look.size, frame.width()));
        children(body, look, frame);
        if (waiting != null) {
            out.add(waiting);
            waiting = null;
        }
    }

    private static final int MAX_FIXED = 64;

    private static final int MAX_FIXED_TEXT = 8_000;

    static List<Inline> fixedLayer(HtmlContext ctx, Element body, Look look, Section page) throws IOException {
        List<Positioned.Item> items = new ArrayList<>();
        int text = 0;
        float left = page.marginLeft - ctx.insetLeft();
        float room = page.pageWidth - left - page.marginRight + ctx.insetRight();
        for (Positioned.Item item : Positioned.fixed(ctx, body, look, room)) {
            text += Layout.text(item.element(), MAX_FIXED_TEXT + 1).length();
            if (items.size() >= MAX_FIXED || text > MAX_FIXED_TEXT) {
                ctx.warn(HtmlDocument.FIXED_LAYER_LIMIT_WARNING);
                break;
            }
            items.add(item);
            ctx.place(item.element());
        }
        Positioned.Hung hung = Positioned.hang(ctx, items, left, page.marginTop, room, ctx.pageRoom(), 0);
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
        Element skip = null;
        for (Node child : parent.childNodes()) {
            Admission.checkpoint();
            if (ctx.stopped()) {
                return;
            }
            if (child == skip) {
                continue;
            }
            if (child instanceof TextNode t) {
                text(t.getWholeText(), look);
            } else if (child instanceof Element e) {
                Element beside = besideFloat(e, look, frame);
                if (beside != null) {
                    floatRow(e, beside, look, frame, depth + 1);
                    skip = beside;
                } else {
                    element(e, look, frame, depth + 1);
                }
            }
        }
    }

    private Element besideFloat(Element e, Look look, Frame frame) {
        if (inCell || forceInline > 0 || look.rightToLeft) {
            return null;
        }
        Map<String, String> d = ctx.declared(e, look);
        String side = d.getOrDefault("float", "none").strip().toLowerCase(Locale.ROOT);
        String shown = d.getOrDefault("display", BLOCKS.contains(e.normalName()) ? "block" : "inline").strip();
        if (!side.equals("left") && !side.equals("right") || !shown.equals("block") || e.ownText().isBlank()
                && e.children().size() == 1 && Set.of("img", "svg").contains(e.child(0).normalName())) {
            return null;
        }
        float width = floatWidth(e, d, look, frame.width());
        if (width < frame.width() * 0.1f || width > frame.width() * 0.7f) {
            return null;
        }
        Node n = e.nextSibling();
        while (n instanceof TextNode t && t.isBlank()) {
            n = n.nextSibling();
        }
        if (!(n instanceof Element next) || SKIPPED.contains(next.normalName()) || !BLOCKS.contains(next.normalName())) {
            return null;
        }
        Map<String, String> nd = ctx.declared(next, look);
        boolean flows = nd.getOrDefault("float", "none").strip().equalsIgnoreCase("none")
                && nd.getOrDefault("display", "block").strip().equalsIgnoreCase("block")
                && !nd.getOrDefault("position", "").strip().matches("(?i)absolute|fixed");
        return flows ? next : null;
    }

    private float floatWidth(Element e, Map<String, String> d, Look look, float room) {
        Float w = BoxCss.boxLength(d.get("width"), look, room);
        if (w == null) {
            return 0;
        }
        if (!"border-box".equalsIgnoreCase(d.getOrDefault("box-sizing", "").strip())) {
            w += Math.max(0, BoxCss.length(d.get("padding-left"), look.size, room))
                    + Math.max(0, BoxCss.length(d.get("padding-right"), look.size, room)) + BoxCss.border(d, "left")
                    + BoxCss.border(d, "right");
        }
        return w;
    }

    private void floatRow(Element side, Element beside, Look look, Frame frame, int depth) throws IOException {
        flush();
        Map<String, String> d = ctx.declared(side, look);
        boolean left = d.get("float").strip().equalsIgnoreCase("left");
        float width = floatWidth(side, d, look, frame.width());
        float rest = frame.width() - width;
        Table.Cell floated = cell(side, look, frame, width, 0, depth, 0, 0);
        Table.Cell flowing = cell(beside, look, frame, rest, 0, depth, left ? width : 0, left ? 0 : width);
        Table t = new Table();
        t.indent = frame.left();
        t.cellMarginLeft = 0;
        t.cellMarginRight = 0;
        t.columnWidths.add(left ? width : rest);
        t.columnWidths.add(left ? rest : width);
        Table.Row row = new Table.Row();
        row.splits = true;
        row.cells.add(left ? floated : flowing);
        row.cells.add(left ? flowing : floated);
        Float tall = BoxCss.boxLength(d.get("min-height"), look, 0);
        row.height = tall == null ? 0 : Math.min(tall, ctx.pageRoom());
        t.rows.add(row);
        if (pendingSpace > 0) {
            spacer(pendingSpace, frame);
            space(0);
        }
        breakBefore(t, pendingBreak && !sectionStarted, frame);
        sectionStarted = false;
        ctx.count();
        emit(t);
    }

    private void element(Element e, Look parentLook, Frame frame, int depth) throws IOException {
        String tag = e.normalName();
        if (tag.equals(Generated.LEADER)) {
            bookmark(e);
            leader(e, parentLook);
            return;
        }
        if (Controls.TAGS.contains(tag)) {
            if (!e.hasAttr("hidden") && Controls.drawn(e)) {
                control(e, parentLook, frame);
            }
            return;
        }
        if (SKIPPED.contains(tag) || e.hasAttr("hidden")) {
            return;
        }
        Map<String, String> d = ctx.declared(e, parentLook);
        if (tag.equals("table") && parentLook.legacyAlign && !d.containsKey("text-align")) {
            // <center> or align moves a table across but not its text, which starts at the start edge, as Chrome does
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
                bookmark(e);
                anchorAt(e, tag, false);
                ctx.place(e);
                staticBox(e, d, look, frame, depth);
            }
            return;
        }
        String bookmark = ctx.bookmarkFor(e);
        boolean opensBlock = !inline && !tag.equals("br");
        if (bookmark != null) {
            if (opensBlock) {
                flush();
            }
            bookmark(bookmark);
        }
        anchorAt(e, tag, opensBlock && bookmark == null);
        if (depth > MAX_DEPTH) {
            text(e.text(), look);
            return;
        }
        if (tag.equals("details") && !e.hasAttr("open")) {
            Element summary = e.selectFirst("> summary");
            if (summary != null) {
                element(summary, look, frame, depth + 1);
            }
            return;
        }
        boolean stacks = rowItemStart >= 0 && Layout.stacks(e, tag, d, parentLook, ctx);
        boolean nests = rowItemStart >= 0 && (display.endsWith("flex") || display.endsWith("grid"))
                && !d.getOrDefault("flex-direction", "row").strip().toLowerCase(Locale.ROOT).startsWith("column");
        if (stacks) {
            breakInRow(look);
        }
        rowNested += nests ? 1 : 0;
        if (display.equals("inline-block") && padded(e, d, look)) {
            String own = e.normalName().equals("a") ? Links.safeUrl(e.attr("href").strip()) : null;
            inline(Controls.button(e, d, look, frame.width(), e.text().strip(), own != null ? own : link), look);
            lastSpace = false;
            rowNested -= nests ? 1 : 0;
            if (stacks) {
                breakInRow(look);
            }
            return;
        }
        switch (tag) {
            case "br" -> inline(new Inline.Break(), look);
            case "img" -> image(e, d, look, frame);
            case "svg" -> {
                if (onOwnLine(e, d, look) && Transform.of(d.get("transform"), look.size, 0, 0).shiftOnly()) {
                    HtmlImages.Labelled drawn = ctx.images().labelledSvg(e, d, look, frame.width(), pictureRoom());
                    if (drawn != null) {
                        labelled(drawn, d, look, frame);
                    }
                } else {
                    Picture pic = ctx.images().inlineSvg(e, d, look, frame.width(), pictureRoom());
                    if (pic != null) {
                        FlowImages.turn(pic, d, look);
                        place(pic, d, look, frame);
                    }
                }
            }
            case "hr" -> rule(d, look, frame);
            case "table" -> table(e, d, look, frame, parentLook.legacyAlign ? parentLook.align : null, depth);
            case "a" -> {
                long boundary = edgeBoundary;
                if (inline) {
                    leadingGap(edge(d, "left", look, frame), look);
                }
                anchor(e, look, frame, depth);
                if (inline) {
                    trailingGap(edge(d, "right", look, frame), look, boundary);
                }
            }
            case "wbr" -> { }
            case "q" -> {
                text("\u201c", look);
                walkChildren(e, look, frame, depth);
                text("\u201d", look);
            }
            default -> {
                if (inline && forceInline == 0 && rowItemStart < 0 && !display.equals("contents")
                        && d.getOrDefault("float", "none").strip().equalsIgnoreCase("right")) {
                    floatRight(e, look, frame, depth);
                } else if (inline) {
                    long boundary = edgeBoundary;
                    leadingGap(edge(d, "left", look, frame), look);
                    if (forceInline > 0 && display.endsWith("flex")) {
                        flexInline(e, look, frame, depth, Layout.gap(d, look));
                    } else {
                        walkChildren(e, look, frame, depth);
                    }
                    trailingGap(edge(d, "right", look, frame), look, boundary);
                } else {
                    // overflow on html or body belongs to the window, which prints whole; fixed boxes never widen it
                    boolean scrolls = !tag.equals("body") && !tag.equals("html") && BoxCss.scrolls(d)
                            || "fixed".equalsIgnoreCase(d.getOrDefault("position", "").strip());
                    ctx.scrolling(scrolls ? 1 : 0);
                    aroundOf = e;
                    aroundAlign = parentLook.legacyAlign ? parentLook.align : null;
                    try {
                        block(e, tag, d, look, frame, depth);
                    } finally {
                        ctx.scrolling(scrolls ? -1 : 0);
                    }
                }
            }
        }
        rowNested -= nests ? 1 : 0;
        if (stacks) {
            breakInRow(look);
        }
    }

    private void bookmark(Element e) {
        bookmark(ctx.bookmarkFor(e));
    }

    private void bookmark(String bookmark) {
        if (bookmark != null) {
            if (pendingBookmark == null) {
                pendingBookmark = bookmark;
            } else {
                ctx.alias(bookmark, pendingBookmark);
            }
        }
    }

    private static final int BUTTON_TEXT = 80;

    private static final float MAX_INLINE_GAP = 144;

    private float edge(Map<String, String> d, String side, Look look, Frame frame) {
        return Math.max(0, BoxCss.length(d.get("margin-" + side), look.size, frame.width()))
                + Math.max(0, BoxCss.length(d.get("padding-" + side), look.size, frame.width()));
    }

    private Inline gapInline(float pt, Look look) {
        if (pt < 0.5f) {
            return null;
        }
        RunStyle s = look.run();
        float space = s.size() * 0.25f;
        RunStyle g = new RunStyle(s.font(), s.size(), false, false, false, false, s.rgb(), -1, 0, false,
                Math.max(0, Math.min(MAX_INLINE_GAP, pt) - space), s.scale(), false, -1);
        return new Inline.Text("\u00a0", g, null, -1);
    }

    private void gap(float pt, Look look) throws IOException {
        Inline gap = gapInline(pt, look);
        if (gap != null) {
            inline(gap, look);
        }
    }

    private void leadingGap(float pt, Look look) {
        Inline gap = gapInline(pt, look);
        if (gap != null) {
            pendingEdges.add(gap);
        }
    }

    private void trailingGap(float pt, Look look, long boundary) throws IOException {
        if (para != null || boundary == edgeBoundary) {
            if (!pendingEdges.isEmpty()) {
                List<Inline> edges = new ArrayList<>(pendingEdges);
                pendingEdges.clear();
                for (Inline edge : edges) {
                    inline(edge, look);
                }
            }
            gap(pt, look);
        }
    }

    private void flexInline(Element e, Look look, Frame frame, int depth, float between) throws IOException {
        boolean first = true;
        for (Node n : e.childNodes()) {
            if (n instanceof Element c) {
                if (!first) {
                    gap(between, look);
                }
                first = false;
                element(c, look, frame, depth + 1);
            } else if (n instanceof TextNode t && !t.isBlank()) {
                text(t.getWholeText(), look);
            }
        }
    }

    private static boolean padded(Element e, Map<String, String> d, Look look) {
        Integer fill = Css.color(d.get("background-color"));
        boolean filled = fill != null && fill >= 0 && fill != 0xFFFFFF;
        boolean wide = BoxCss.length(d.get("width"), look.size, 0) > 0;
        if (!filled && !(wide && e.normalName().equals("a")) || Layout.text(e, BUTTON_TEXT + 1).strip().isEmpty()
                || Layout.text(e, BUTTON_TEXT + 1).length() > BUTTON_TEXT) {
            return false;
        }
        for (Element c : e.getAllElements()) {
            if (c != e && !Set.of("b", "strong", "i", "em", "span", "small", "u", "font").contains(c.normalName())) {
                return false;
            }
        }
        for (String side : new String[] {"top", "right", "bottom", "left"}) {
            if (BoxCss.length(d.get("padding-" + side), look.size, 0) > 0) {
                return true;
            }
        }
        return wide || BoxCss.length(d.get("height"), look.size, 0) > 0 || Controls.linePitch(look) > 1.5f * look.size;
    }

    private void control(Element e, Look parentLook, Frame frame) throws IOException {
        Map<String, String> d = Controls.withDefaults(e, ctx.declared(e, parentLook));
        String display = d.getOrDefault("display", "inline-block").strip().toLowerCase(Locale.ROOT);
        if (display.equals("none")) {
            return;
        }
        Look look = parentLook.child(d, frame.width(), true);
        if (look.hidden || Hidden.visually(d, look.size)) {
            return;
        }
        Inline.FormControl control = Controls.control(e, d, look, frame.width(), Controls.label(e, ctx.labelFor(e)));
        boolean alone = forceInline == 0 && display.equals("block");
        if (alone) {
            flush();
        }
        bookmark(e);
        anchorAt(e, e.normalName(), false);
        inline(control, look);
        lastSpace = false;
        if (alone) {
            flush();
        }
    }

    private void leader(Element e, Look look) {
        if (para == null) {
            start();
        }
        String c = e.attr("data-char");
        leaderOf = para;
        leaderFrom = para.inlines.size();
        leaderChar = c.isEmpty() ? '.' : c.charAt(0) == ' ' ? 0 : c.charAt(0);
        leaderStyle = look.run();
        lastSpace = true;
    }

    private void floatRight(Element e, Look look, Frame frame, int depth) throws IOException {
        if (para == null) {
            start();
        }
        Paragraph p = para;
        int from = p.inlines.size();
        boolean spaced = lastSpace;
        walkChildren(e, look, frame, depth);
        if (para != p || p.inlines.size() == from || p.inlines.subList(from, p.inlines.size()).stream()
                .anyMatch(i -> i instanceof Inline.Break)) {
            return;
        }
        List<Inline> moved = new ArrayList<>(p.inlines.subList(from, p.inlines.size()));
        p.inlines.subList(from, p.inlines.size()).clear();
        if (rightOf != p) {
            rightOf = p;
            right.clear();
        }
        right.addAll(moved);
        rightStyle = look.run();
        lastSpace = spaced;
    }

    private void anchorAt(Element e, String tag, boolean boundary) throws IOException {
        String id = e.id().isEmpty() && tag.equals("a") ? e.attr("name") : e.id();
        if (!id.isEmpty() && anchors < MAX_ANCHORS) {
            if (boundary) {
                flush();
            }
            anchors++;
            pendingAnchors.add(anchorName(id));
        }
    }

    static String anchorName(String id) {
        String clean = id.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return "a_" + (clean.length() > 190 ? clean.substring(0, 190) : clean);
    }

    private void anchor(Element e, Look look, Frame frame, int depth) throws IOException {
        String before = link;
        String href = e.attr("href").strip();
        String safe = href.isEmpty() ? null
                : href.startsWith("#") && href.length() > 1
                        ? "#" + anchorName(HtmlRefs.percentDecoded(href.substring(1))) : Links.safeUrl(href);
        if (safe != null) {
            link = safe;
        }
        walkChildren(e, look, frame, depth);
        link = before;
    }

    private void block(Element e, String tag, Map<String, String> d, Look look, Frame frame, int depth)
            throws IOException {
        flush();
        frame = page(d.get("page"), frame);
        float em = look.size;
        Transform turn = forceInline > 0 ? Transform.NONE : Transform.of(d.get("transform"), em, frame.width(), 0);
        if (!turn.shiftOnly() && ctx.positions()) {
            lifted(e, d, look, frame, depth);
            return;
        }
        float[] shift = BoxCss.relativeShift(d, look, frame, turn);
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
        float top = BoxCss.length(d.get("margin-top"), em, frame.width());
        float bottom = BoxCss.length(d.get("margin-bottom"), em, frame.width());
        if ((tag.equals("ul") || tag.equals("ol")) && lists.nested()) {
            top = 0;
            bottom = 0;
        }
        float besideL = e == sized ? besideLeft : 0;
        float besideR = e == sized ? besideRight : 0;
        float left = Math.max(0, Math.max(0, BoxCss.length(d.get("margin-left"), em, frame.width() + besideL) - besideL)
                + BoxCss.length(d.get("padding-left"), em, frame.width()) + BoxCss.border(d, "left"));
        float right = Math.max(0, Math.max(0, BoxCss.length(d.get("margin-right"), em, frame.width() + besideR)
                - besideR)
                + BoxCss.length(d.get("padding-right"), em, frame.width()) + BoxCss.border(d, "right"));
        float[] narrow = narrowed(e, d, look, frame.width());
        left += narrow[0];
        right += narrow[1];
        int fill = fill(d, look);
        boolean paddedCell = e == stackedCell && BoxCss.hasPadding(d) && holdsContent(e);
        if (paddedCell && fill < 0) {
            fill = frame.shading();
        }
        if (columns(e, d, look, frame, depth, left, right, top, bottom, fill)) {
            return;
        }
        TextFit.measureFixed(ctx, e, d, look, frame, left, right);
        Frame inner = frame.inner(left, right, fill);
        Layout.Plan plan = forceInline > 0 ? null : Layout.of(e, d, look, inner.width(), ctx, inCell);
        if (plan != null && !plan.inline() && widerInside(plan, look, inner.width())) {
            // a cell cannot hold a row, so a longer one inside, like a carousel, keeps its columns instead
            plan = null;
        }
        if (plan != null && inCell && !plan.inline() && (paragraphsOnly || ctx.cells() >= Table.MAX_NESTING)) {
            // a cell nested too deep cannot hold a table of columns, but a row of short items still fits on one line
            plan = null;
        }
        boolean tall = !absolutes.isEmpty() && BoxCss.height(d, look) > 0 && e.selectFirst("table") == null;
        if (plan == null && (tall || boxed(e, tag, d, fill, paddedCell)) && !ctx.holdsRow(e, look, inner.width())) {
            box(e, d, look, frame, depth, fill, top, bottom, absolutes);
            return;
        }
        boolean avoid = !inCell && BoxCss.avoidsBreaks(d);
        avoiding += avoid ? 1 : 0;
        Positioned.Hung hung = null;
        if (!absolutes.isEmpty()) {
            float marginLeft = BoxCss.length(d.get("margin-left"), em, frame.width());
            float marginRight = BoxCss.length(d.get("margin-right"), em, frame.width());
            float cbWidth = Math.max(12, frame.width() - marginLeft - marginRight - BoxCss.border(d, "left")
                    - BoxCss.border(d, "right"));
            Float own = Css.length(d.getOrDefault("width", "auto"), em, frame.width());
            if (own != null && own > 0) {
                boolean borderBox = "border-box".equalsIgnoreCase(d.getOrDefault("box-sizing", "").strip());
                float pads = BoxCss.padding(d.get("padding-left"), em, frame.width())
                        + BoxCss.padding(d.get("padding-right"), em, frame.width());
                cbWidth = Math.min(cbWidth,
                        borderBox ? own - BoxCss.border(d, "left") - BoxCss.border(d, "right") : own + pads);
            }
            float h = BoxCss.height(d, look);
            float cbHeight = h > 0 ? h + BoxCss.padding(d.get("padding-top"), em, frame.width())
                    + BoxCss.padding(d.get("padding-bottom"), em, frame.width()) : Float.NaN;
            float x0 = originX() + frame.left() + marginLeft + BoxCss.border(d, "left");
            hung = Positioned.hang(ctx, absolutes, x0, ANCHOR + pendingSpace + Math.max(0, top - pendingMargin) + BoxCss.border(d, "top"), cbWidth, cbHeight, depth);
            if (!hung.top().isEmpty()) {
                anchor(hung.top(), frame);
            }
        }
        margin(top);
        pad(BoxCss.padding(d.get("padding-top"), em, frame.width()));
        if (BoxCss.isPageBreak(d.get("break-before"))) {
            pendingBreak = true;
        }
        Block before = lastBlock();
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
            lists.open(e, tag, look);
        }
        if (tag.equals("li")) {
            pendingMarker = lists.marker(look);
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
        float clip = stacked ? -1 : BoxCss.clipHeight(d, look);
        if (clip >= 0) {
            flush();
            clipLeft = Math.min(clipLeft, clip);
        }
        float inside = clipLeft;
        if (plan == null) {
            walkChildren(e, look, inner, depth);
        } else if (plan.inline()) {
            inlineRow(plan, d, look, inner, depth);
        } else {
            row(plan, look, inner, depth);
        }
        floatOf = outerFloat;
        flush();
        if (hung != null && !hung.bottom().isEmpty()) {
            float padBottom = BoxCss.padding(d.get("padding-bottom"), em, frame.width());
            float below = padBottom + (padBottom > 0 || BoxCss.border(d, "bottom") > 0 ? pendingSpace : 0);
            anchor(Positioned.moved(hung.bottom(), 0, below, true), frame);
        }
        if (clip >= 0) {
            clipLeft = outerClip == Float.MAX_VALUE ? outerClip : outerClip - (inside - clipLeft);
        }
        if (list) {
            lists.close();
        }
        if (tag.equals("li")) {
            pendingMarker = null;
        }
        float borderBottom = BoxCss.border(d, "bottom");
        float padBottom = BoxCss.padding(d.get("padding-bottom"), em, frame.width());
        Block last = lastBlock();
        if (borderBottom > 0 && last != null && last != before && !inCell
                && (!held.isEmpty() || BoxCss.border(d, "left") <= 0 && BoxCss.border(d, "right") <= 0)) {
            Integer c = Css.color(d.getOrDefault("border-bottom-color", BoxCss.colorOf(d.get("border-bottom"))));
            int rgb = c == null || c < 0 ? 0 : c;
            boolean doubled = BoxCss.doubled(d, "bottom");
            if (last instanceof Paragraph p) {
                p.borderBottom = borderBottom;
                p.borderBottomRgb = rgb;
                p.borderBottomDouble = doubled;
                if (padBottom > 0 && padBottom <= MAX_BORDER_SPACE) {
                    p.borderBottomSpace = padBottom;
                    padBottom = 0;
                }
            } else if (last instanceof Table t && !t.rows.isEmpty()) {
                boolean padded = false;
                for (Table.Cell cell : t.rows.getLast().cells) {
                    if (!cell.bottom.visible()) {
                        cell.bottom = new Table.Border(borderBottom, rgb, doubled);
                        if (padBottom > 0 && !cell.paragraphs.isEmpty()) {
                            cell.paragraphs.getLast().spaceAfter += padBottom;
                            padded = true;
                        }
                    }
                }
                padBottom = padded ? 0 : padBottom;
            }
        }
        blockLook = outerLook;
        blockFrame = outerFrame;
        headingStyle = outerHeading;
        pad(padBottom);
        margin(bottom);
        if (BoxCss.isPageBreak(d.get("break-after"))) {
            pendingBreak = true;
        }
        avoiding -= avoid ? 1 : 0;
        if (avoiding == 0) {
            keepTogether();
        }
    }

    private boolean columns(Element e, Map<String, String> d, Look look, Frame frame, int depth, float left,
            float right, float top, float bottom, int fill) throws IOException {
        if (inColumns || inCell || forceInline > 0 || rowItemStart >= 0 || avoiding > 0 || !held.isEmpty()
                || clipLeft != Float.MAX_VALUE || look.rightToLeft) {
            return false;
        }
        float width = frame.width() - left - right;
        float gap = BoxCss.columnGap(d, look);
        int n = BoxCss.columnCount(d, look, width, gap);
        float each = n < 2 ? 0 : (width - gap * (n - 1)) / n;
        if (n < 2 || each < 36) {
            return false;
        }
        float em = look.size;
        Section base = ctx.currentSection();
        margin(top);
        float before = pendingSpace;
        boolean newPage = pendingBreak && !sectionStarted;
        if (before > 0 || newPage) {
            Paragraph space = sectionMark(frame);
            space.lineHeight = Math.max(1, Math.min(before, 400));
            space.inlines.add(new Inline.Text("\u00a0", look.run().withSize(1), null, -1));
            space.pageBreakBefore = newPage;
            emit(space);
        }
        Paragraph opening = sectionMark(frame);
        opening.endsSection = ctx.ending(base);
        space(0);
        pad(BoxCss.padding(d.get("padding-top"), em, frame.width()));
        pendingBreak = false;
        sectionStarted = false;
        ctx.count();
        emit(opening);
        ctx.continuing(true);
        Section set = base.copy();
        set.marginLeft += frame.left() + left;
        set.marginRight += frame.right() + right;
        for (int i = 0; i < n; i++) {
            set.columns.add(new float[] {each, i + 1 < n ? gap : 0});
        }
        set.columnRule = BoxCss.columnRule(d) > 0;
        Look outerLook = blockLook;
        Frame outerFrame = blockFrame;
        Frame inner = new Frame(0, 0, each, fill >= 0 && fill != 0xFFFFFF ? fill : -1);
        blockLook = look;
        blockFrame = inner;
        inColumns = true;
        walkChildren(e, look, inner, depth);
        flush();
        inColumns = false;
        Paragraph closing = sectionMark(inner);
        closing.endsSection = ctx.ending(set);
        ctx.count();
        emit(closing);
        blockLook = outerLook;
        blockFrame = outerFrame;
        space(0);
        pad(BoxCss.padding(d.get("padding-bottom"), em, frame.width()));
        margin(bottom);
        return true;
    }

    private Paragraph sectionMark(Frame frame) {
        Paragraph mark = new Paragraph();
        mark.indentLeft = frame.left();
        mark.lineRule = Paragraph.LineRule.EXACT;
        mark.lineHeight = 1;
        return mark;
    }

    private void margin(float m) {
        float collapsed = Math.max(pendingMargin, m);
        pendingSpace += collapsed - pendingMargin;
        pendingMargin = collapsed;
    }

    private void pad(float p) {
        if (p > 0) {
            pendingSpace += p;
            pendingMargin = 0;
        }
    }

    private void space(float s) {
        pendingSpace = s;
        pendingMargin = s;
    }

    private void emit(Block b) throws IOException {
        if (pendingBookmark != null) {
            Paragraph first = switch (b) {
                case Paragraph p -> p;
                case Table t -> t.rows.isEmpty() || t.rows.getFirst().cells.isEmpty()
                        || t.rows.getFirst().cells.getFirst().paragraphs.isEmpty() ? null
                        : t.rows.getFirst().cells.getFirst().paragraphs.getFirst();
            };
            if (first != null && first.bookmark == null) {
                first.bookmark = pendingBookmark;
                pendingBookmark = null;
            }
        }
        if (avoiding == 0 || outrun) {
            send(b);
            return;
        }
        held.add(b);
        if (held.size() > BlockFlow.CHAIN_LIMIT) {
            for (Block h : held) {
                send(h);
            }
            held.clear();
            outrun = true;
        }
    }

    private void keepTogether() throws IOException {
        for (int i = 0; i < held.size(); i++) {
            if (held.get(i) instanceof Paragraph p) {
                p.keepLines = true;
                p.keepNext |= i < held.size() - 1;
            }
            send(held.get(i));
        }
        held.clear();
        outrun = false;
    }

    private void send(Block b) throws IOException {
        if (inCell) {
            out.add(b);
            return;
        }
        if (waiting != null) {
            out.add(waiting);
        }
        waiting = b;
    }

    private Block lastBlock() {
        return held.isEmpty() ? waiting : held.getLast();
    }

    // These shade their own lines, or carry list and heading structure a box would lose
    private static final Set<String> NOT_BOXES = Set.of("p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li",
            "menu", "dl", "dt", "dd", "table", "tr", "td", "th", "caption", "body", "html");

    private static final int BOX_TEXT = 4_000;

    private static final int MAX_ANCHORS = 100_000;

    // The background a block paints: its colour, or for a picture we never load under light text, a dark stand-in
    private int fill(Map<String, String> d, Look look) {
        Integer c = Css.color(d.get("background-color"));
        if (c != null && c >= 0) {
            return c;
        }
        if ("picture".equals(d.get("background-image")) && BoxCss.light(look.color)
                && !(Backgrounds.url(d) != null && Backgrounds.drawable(d, ctx.images()))) {
            return 0x2B2B2B;
        }
        return -1;
    }

    // A container drawn as a box (a coloured panel, a bordered card) keeps its background and edges in one piece
    private boolean boxed(Element e, String tag, Map<String, String> d, int fill, boolean paddedCell) {
        if (inCell || forceInline > 0 || NOT_BOXES.contains(tag) && !paddedCell) {
            return false;
        }
        int edges = 0;
        for (String side : new String[] {"top", "bottom", "left", "right"}) {
            edges += BoxCss.border(d, side) > 0 ? 1 : 0;
        }
        boolean painted = fill >= 0 && (fill != 0xFFFFFF || paddedCell && ctx.canvas() >= 0)
                && fill != blockFrame.shading()
                || Backgrounds.url(d) != null && Backgrounds.drawable(d, ctx.images());
        boolean bar = BoxCss.border(d, "left") > 0 || BoxCss.border(d, "right") > 0;
        boolean shadow = !BoxPaint.shadows(d.get("box-shadow"), Look.ROOT_SIZE, 0).isEmpty();
        return (painted || edges >= 3 || bar || shadow) && Layout.text(e, BOX_TEXT).length() < BOX_TEXT;
    }

    private float[] narrowed(Element e, Map<String, String> d, Look look, float room) {
        float[] none = {0, 0};
        if (e == sized) {
            return none;
        }
        Element parent = e.parent();
        if (parent == null || e.normalName().equals("body") || e.normalName().equals("html")) {
            return none;
        }
        String around = ctx.declared(parent, look).getOrDefault("display", "").strip().toLowerCase(Locale.ROOT);
        if (around.endsWith("flex") || around.endsWith("grid")) {
            return none;
        }
        Float width = BoxCss.boxLength(d.get("width"), look, room);
        Float max = BoxCss.boxLength(d.get("max-width"), look, room);
        if (width == null && max == null) {
            return none;
        }
        float em = look.size;
        float outer = width == null ? max : max == null ? width : Math.min(width, max);
        if (!"border-box".equalsIgnoreCase(d.getOrDefault("box-sizing", "").strip())) {
            outer += Math.max(0, BoxCss.length(d.get("padding-left"), em, room))
                    + Math.max(0, BoxCss.length(d.get("padding-right"), em, room)) + BoxCss.border(d, "left")
                    + BoxCss.border(d, "right");
        }
        float extra = room - Math.max(0, BoxCss.length(d.get("margin-left"), em, room))
                - Math.max(0, BoxCss.length(d.get("margin-right"), em, room)) - outer;
        if (extra < 1) {
            return none;
        }
        boolean autoLeft = "auto".equalsIgnoreCase(d.getOrDefault("margin-left", "").strip());
        boolean autoRight = "auto".equalsIgnoreCase(d.getOrDefault("margin-right", "").strip());
        Paragraph.Align legacy = e == aroundOf && !autoLeft && !autoRight ? aroundAlign : null;
        if (autoLeft && autoRight || legacy == Paragraph.Align.CENTER) {
            return new float[] {extra / 2, extra / 2};
        }
        if (legacy != null) {
            return legacy == Paragraph.Align.RIGHT ? new float[] {extra, 0} : new float[] {0, extra};
        }
        return autoLeft || look.rightToLeft && !autoRight ? new float[] {extra, 0} : new float[] {0, extra};
    }

    private static boolean holdsContent(Element cell) {
        return !Layout.text(cell, STACK_TEXT).replaceAll("[ \t\n\r\f]", "").isEmpty() || cell.selectFirst(
                "img, svg, picture, video, canvas, iframe, object, embed, input, button, select, textarea, hr")
                != null;
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
        float marginLeft = Math.max(0, BoxCss.length(d.get("margin-left"), em, frame.width()));
        float marginRight = Math.max(0, BoxCss.length(d.get("margin-right"), em, frame.width()));
        float padLeft = Math.max(0, BoxCss.length(d.get("padding-left"), em, frame.width()));
        float padRight = Math.max(0, BoxCss.length(d.get("padding-right"), em, frame.width()));
        float[] narrow = narrowed(e, d, look, frame.width());
        marginLeft += narrow[0];
        marginRight += narrow[1];
        float width = Math.max(36, frame.width() - marginLeft - marginRight);
        Table.Cell cell = new Table.Cell();
        cell.shading = fill >= 0 && (fill != 0xFFFFFF || ctx.canvas() >= 0) ? fill : -1;
        cell.top = HtmlTables.border(d, "top", 0);
        cell.bottom = HtmlTables.border(d, "bottom", 0);
        cell.left = HtmlTables.border(d, "left", 0);
        cell.right = HtmlTables.border(d, "right", 0);
        Flow inner = new Flow(ctx, cell::add, true);
        inner.pad(Math.max(0, BoxCss.padding(d.get("padding-top"), em, frame.width())));
        ctx.enterCell();
        try {
            inner.children(e, look, new Frame(0, 0, Math.max(24, width - padLeft - padRight), -1), depth);
        } finally {
            ctx.leaveCell();
        }
        float padBottom = Math.max(0, BoxCss.padding(d.get("padding-bottom"), em, frame.width()));
        float padTop = Math.max(0, BoxCss.padding(d.get("padding-top"), em, frame.width()));
        Float tall = BoxCss.boxLength(d.get("height"), look, 0);
        float fixed = BoxCss.height(d, look);
        boolean sized = fixed > 0 && !"border-box".equalsIgnoreCase(d.getOrDefault("box-sizing", "").strip());
        float boxHeight = fixed <= 0 ? -1 : sized ? fixed + padTop + padBottom + cell.top.width() + cell.bottom.width()
                : fixed;
        float innerWidth = Math.max(24, width - padLeft - padRight);
        float drawnHeight = boxHeight > 0 ? boxHeight
                : Measure.height(cell.blocks(), innerWidth) + padBottom + cell.top.width() + cell.bottom.width();
        List<Inline> behind = Backgrounds.url(d) == null ? List.of()
                : Backgrounds.paint(d, look, ctx.images(), -padLeft, 0, width, drawnHeight, true);
        Positioned.Hung hung = Positioned.hang(ctx, absolutes, -padLeft, 0, width, boxHeight > 0 ? boxHeight : Float.NaN,
                depth);
        boolean hangs = !behind.isEmpty() || !hung.top().isEmpty() || !hung.bottom().isEmpty();
        boolean drawn = cell.shading >= 0 || cell.top.visible() || cell.bottom.visible();
        if (cell.empty() && !hangs && (tall == null || tall < 1 || !drawn)) {
            margin(top + bottom);
            return;
        }
        if (!behind.isEmpty() && (BoxCss.covers(d) || boxHeight > 0) && cell.shading >= 0) {
            behind = new ArrayList<>(behind);
            behind.add(0, new Inline.Shape(-padLeft, 0, width, drawnHeight, cell.shading, -1, 0, false, false, true, 0));
            cell.shading = -1;
        }
        Table t = new Table();
        Table.Row row = new Table.Row();
        if (cell.empty() && !hangs) {
            float h = tall + padTop + padBottom;
            cell.paragraphs.add(bar(h, look));
            Element only = e.children().size() == 1 ? e.child(0) : null;
            Map<String, String> od = only == null ? Map.of() : ctx.declared(only, look);
            Integer color = Css.color(od.get("background-color"));
            String share = od.getOrDefault("width", "").strip();
            if (color != null && color >= 0 && padLeft + padRight < 1 && share.matches("[0-9]{1,3}([.][0-9]{1,4})?%")) {
                float done = Math.min(1, Float.parseFloat(share.substring(0, share.length() - 1)) / 100f) * width;
                if (width - done < 1) {
                    cell.shading = color;
                } else if (done >= 1) {
                    Table.Cell part = new Table.Cell();
                    part.shading = color;
                    part.top = cell.top;
                    part.bottom = cell.bottom;
                    part.left = cell.left;
                    cell.left = Table.Border.NONE;
                    part.paragraphs.add(bar(h, look));
                    row.cells.add(part);
                    t.columnWidths.add(done);
                    width -= done;
                }
            }
        } else {
            if (!hung.bottom().isEmpty()) {
                cell.paragraphs.add(anchorParagraph(Positioned.moved(hung.bottom(), 0, padBottom, true), 0));
            }
            if (!hung.top().isEmpty() || !behind.isEmpty()) {
                if (cell.empty() || !(cell.blocks().getFirst() instanceof Paragraph)) {
                    cell.tables.replaceAll(n -> new Table.Nested(n.before() + 1, n.table()));
                    cell.paragraphs.addFirst(anchorParagraph(List.of(), 0));
                }
                cell.paragraphs.getFirst().inlines.addAll(0, hung.top());
                cell.paragraphs.getFirst().inlines.addAll(0, behind);
            }
            if (padBottom >= 1) {
                inner.spacer(padBottom, new Frame(0, 0, width, -1));
            }
        }
        boolean painted = false;
        if (!cell.empty() && tall != null && tall >= 1) {
            boolean borderBox = "border-box".equalsIgnoreCase(d.getOrDefault("box-sizing", "").strip());
            float high = Math.min(ctx.pageRoom(),
                    tall + (borderBox ? 0
                            : Math.max(0, BoxCss.padding(d.get("padding-top"), em, frame.width())) + padBottom));
            if (gradient(cell, d.get("background-gradient"), width, high, padLeft,
                    BoxCss.corner(d, look, width, high), look)) {
                row.height = high;
                painted = true;
            }
        }
        if (!painted && behind.isEmpty() && row.cells.isEmpty()
                && decorate(cell, d, look, -padLeft, width, drawnHeight, frame.shading())) {
            row.splits = false;
            row.height = Math.max(row.height, Math.min(drawnHeight, ctx.pageRoom()));
        }
        t.columnWidths.add(width);
        t.indent = frame.left() + marginLeft;
        if (look.rightToLeft) {
            t.rightToLeft = true;
            t.indent = frame.right() + marginRight;
        }
        t.cellMarginLeft = padLeft;
        t.cellMarginRight = padRight;
        row.splits = !BoxCss.avoidsBreaks(d);
        row.cells.add(cell);
        if (boxHeight > 0 && row.height <= 0) {
            row.height = Math.min(boxHeight, ctx.pageRoom());
        }
        t.rows.add(row);
        margin(top);
        if (pendingSpace > 0) {
            spacer(pendingSpace, frame);
        }
        breakBefore(t, pendingBreak && !sectionStarted, frame);
        sectionStarted = false;
        ctx.count();
        emit(t);
        space(bottom);
    }

    private boolean gradient(Table.Cell cell, String css, float width, float height, float padLeft, float corner,
            Look look) throws IOException {
        Gradient g = Gradient.parse(css);
        Picture pic = g == null ? null : ctx.images().gradient(g, width, height, corner);
        if (pic == null) {
            return false;
        }
        pic.wrap = Picture.Wrap.NONE;
        pic.behind = true;
        pic.x = -padLeft;
        pic.y = 0;
        if (!(cell.blocks().getFirst() instanceof Paragraph)) {
            cell.tables.replaceAll(n -> new Table.Nested(n.before() + 1, n.table()));
            cell.paragraphs.addFirst(bar(1, look));
        }
        cell.paragraphs.getFirst().inlines.addFirst(new Inline.Image(pic));
        cell.shading = -1;
        return true;
    }

    private boolean decorate(Table.Cell cell, Map<String, String> d, Look look, float x, float width, float height,
            int backdrop) throws IOException {
        float corner = BoxCss.corner(d, look, width, height);
        List<BoxPaint.Shadow> shadows = ctx.shadowsLeft() ? BoxPaint.shadows(d.get("box-shadow"), look.size, look.color)
                : List.of();
        Gradient gradient = Gradient.parse(d.get("background-gradient"));
        if (corner <= 0 && shadows.isEmpty() && gradient == null || cell.empty() || !(height >= 1)
                || height > ctx.pageRoom() * BoxPaint.MAX_PAGE_SHARE) {
            return false;
        }
        Table.Border edge = BoxPaint.even(cell);
        if (corner > 0 && edge == null && BoxPaint.bordered(cell)) {
            corner = 0;
        }
        Picture pic = gradient == null ? null : ctx.images().gradient(gradient, width, height, corner);
        if (pic == null && corner <= 0 && shadows.isEmpty()) {
            return false;
        }
        int fill = cell.shading;
        int ground = backdrop >= 0 ? backdrop : ctx.canvas();
        boolean stroked = pic == null && corner > 0 && edge != null;
        Integer own = Css.color(d.get("background-color"));
        int cover = fill >= 0 ? fill : own != null && own >= 0 ? own : shadows.isEmpty() ? -1
                : ground >= 0 ? ground : 0xFFFFFF;
        if (pic == null && cover < 0 && !stroked) {
            return false;
        }
        List<Inline> drawn = new ArrayList<>(BoxPaint.shadows(shadows, x, 0, width, height, corner, ground));
        ctx.drewShadows(drawn.size());
        if (pic != null) {
            pic.wrap = Picture.Wrap.NONE;
            pic.behind = true;
            pic.x = x;
            pic.y = 0;
            drawn.add(new Inline.Image(pic));
        } else {
            float inset = stroked ? edge.width() / 2 : 0;
            drawn.add(new Inline.Shape(x + inset, inset, width - 2 * inset, height - 2 * inset, cover,
                    stroked ? edge.rgb() : -1, stroked ? edge.width() : 0, corner > 0, false, true, 0,
                    Math.max(0, corner - inset)));
            if (stroked) {
                cell.top = Table.Border.NONE;
                cell.bottom = Table.Border.NONE;
                cell.left = Table.Border.NONE;
                cell.right = Table.Border.NONE;
            }
        }
        if (cell.blocks().getFirst() instanceof Paragraph first) {
            first.inlines.addAll(0, drawn);
        } else {
            cell.tables.replaceAll(n -> new Table.Nested(n.before() + 1, n.table()));
            cell.paragraphs.addFirst(anchorParagraph(drawn, 0));
        }
        cell.shading = -1;
        for (Paragraph p : cell.paragraphs) {
            if (p.shading == fill) {
                p.shading = -1;
            }
        }
        return true;
    }

    private boolean itemGradient(Table.Row row, Table.Cell cell, Element item, Look look, float width, float margin)
            throws IOException {
        Map<String, String> d = ctx.declared(item, look);
        Float tall = BoxCss.boxLength(d.get("height"), look, 0);
        if (tall == null || tall < 1 || cell.empty() || Gradient.parse(d.get("background-gradient")) == null) {
            return false;
        }
        boolean borderBox = "border-box".equalsIgnoreCase(d.getOrDefault("box-sizing", "").strip());
        float high = Math.min(ctx.pageRoom(), tall + (borderBox ? 0
                : Math.max(0, BoxCss.padding(d.get("padding-top"), look.size, width))
                + Math.max(0, BoxCss.padding(d.get("padding-bottom"), look.size, width))));
        int fill = cell.shading;
        if (gradient(cell, d.get("background-gradient"), width, high, margin, BoxCss.corner(d, look, width, high),
                look)) {
            for (Paragraph p : cell.paragraphs) {
                if (p.shading == fill) {
                    p.shading = -1;
                }
            }
            row.height = Math.max(row.height, high);
            return true;
        }
        return false;
    }

    private static Paragraph bar(float height, Look look) {
        Paragraph p = new Paragraph();
        p.lineRule = Paragraph.LineRule.EXACT;
        p.lineHeight = Math.max(1, Math.min(height, 400));
        p.inlines.add(new Inline.Text("\u00a0", look.run().withSize(1), null, -1));
        return p;
    }

    // A block on a page of another name starts a new section on that page's size and margins
    private Frame page(String name, Frame frame) throws IOException {
        if (name == null || inCell || name.isBlank()) {
            return frame;
        }
        boolean back = name.strip().equalsIgnoreCase("auto");
        String next = back ? "" : name.strip();
        if (next.equals(ctx.pageName()) || !back && ctx.section(next) == null) {
            return frame;
        }
        if (ctx.nothingWritten()) {
            ctx.pageName(next);
            return cover(next, frame);
        }
        Section ending = ctx.section(ctx.pageName());
        Paragraph mark = new Paragraph();
        mark.indentLeft = frame.left();
        mark.lineRule = Paragraph.LineRule.EXACT;
        mark.lineHeight = 1;
        mark.endsSection = ctx.ending(ending != null ? ending : ctx.defaultSection());
        emit(mark);
        ctx.continuing(false);
        ctx.pageName(next);
        // the new section starts a page already, so a forced break right after it is the same break
        sectionStarted = true;
        return cover(next, frame);
    }

    private Frame cover(String name, Frame frame) {
        if (!HtmlDocument.BOOK_COVER.equals(name)) {
            return frame;
        }
        Section from = ctx.defaultSection();
        Section to = ctx.section(name);
        float extra = from.marginLeft + from.marginRight - to.marginLeft - to.marginRight;
        return new Frame(frame.left(), frame.right(), frame.width() + extra, frame.shading());
    }

    private void inlineRow(Layout.Plan plan, Map<String, String> d, Look look, Frame frame, int depth)
            throws IOException {
        List<Paragraph.TabStop> stops = look.rightToLeft ? null : Layout.stops(plan, d, look, frame.width(), ctx);
        if (stops != null) {
            tabbedRow(plan, stops, look, frame, depth);
            return;
        }
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

    private void tabbedRow(Layout.Plan plan, List<Paragraph.TabStop> stops, Look look, Frame frame, int depth)
            throws IOException {
        if (para == null) {
            start();
        }
        Paragraph row = para;
        row.align = Paragraph.Align.LEFT;
        int n = plan.items().size();
        int[] from = new int[n + 1];
        int outer = rowItemStart;
        try {
            for (int i = 0; i < n; i++) {
                from[i] = para.inlines.size();
                rowItemStart = from[i];
                pendingGap = null;
                lastSpace = true;
                forceInline++;
                try {
                    element(plan.items().get(i), look, frame, depth + 1);
                } finally {
                    forceInline--;
                }
                if (para != row) {
                    return;
                }
            }
        } finally {
            rowItemStart = outer;
        }
        from[n] = row.inlines.size();
        List<List<List<Inline>>> items = new ArrayList<>();
        int lines = 0;
        for (int i = 0; i < n; i++) {
            List<List<Inline>> item = TextFit.lines(row.inlines.subList(from[i], from[i + 1]));
            items.add(item);
            lines = Math.max(lines, item.size());
        }
        List<Inline> rebuilt = new ArrayList<>(row.inlines.subList(0, from[0]));
        for (int line = 0; line < lines; line++) {
            if (line > 0) {
                rebuilt.add(new Inline.Break());
            }
            for (int i = 0; i < n; i++) {
                if (stops.get(i) != null) {
                    rebuilt.add(new Inline.Tab(look.run()));
                }
                if (line < items.get(i).size()) {
                    rebuilt.addAll(items.get(i).get(line));
                }
            }
        }
        row.inlines.clear();
        row.inlines.addAll(rebuilt);
        for (Paragraph.TabStop s : stops) {
            if (s != null) {
                row.tabs.add(new Paragraph.TabStop(s.pos() + frame.left(), s.kind(), s.leader()));
            }
        }
        lastSpace = false;
    }

    private void breakInRow(Look look) {
        if (para == null || para.inlines.size() <= rowItemStart || para.inlines.getLast() instanceof Inline.Break) {
            return;
        }
        if (rowNested == 0) {
            para.inlines.add(new Inline.Break());
        } else if (!lastSpace) {
            para.inlines.add(new Inline.Text(" ", look.run(), link, -1));
        }
        lastSpace = true;
    }

    private float sideSpace(Element item, Look look, String side) {
        Map<String, String> d = ctx.declared(item, look);
        return Math.max(0, BoxCss.length(d.get("margin-" + side), look.size, 0))
                + Math.max(0, BoxCss.length(d.get("padding-" + side), look.size, 0));
    }

    private Table.Cell cell(Element item, Look look, Frame frame, float width, float gap, int depth) throws IOException {
        return cell(item, look, frame, width, gap, depth, 0, 0);
    }

    private Table.Cell cell(Element item, Look look, Frame frame, float width, float gap, int depth, float besideLeft,
            float besideRight) throws IOException {
        Table.Cell cell = new Table.Cell();
        if (item != null) {
            Map<String, String> d = ctx.declared(item, look);
            Map<String, String> box = framedChild(item, d, look);
            if (box != null) {
                d = box;
            }
            Integer fill = Css.color(d.get("background-color"));
            // an item with no background of its own shows the row's
            cell.shading = fill == null || fill < 0 ? frame.shading() : fill;
            cell.top = HtmlTables.border(d, "top", 0);
            cell.bottom = HtmlTables.border(d, "bottom", 0);
            cell.left = HtmlTables.border(d, "left", 0);
            cell.right = HtmlTables.border(d, "right", 0);
            if (BoxCss.shadowed(d) && cell.top.width() <= 0 && cell.bottom.width() <= 0 && cell.left.width() <= 0
                    && cell.right.width() <= 0) {
                Table.Border edge = new Table.Border(0.5f, BoxCss.SHADOW_EDGE);
                cell.top = edge;
                cell.bottom = edge;
                cell.left = edge;
                cell.right = edge;
            }
            Flow inner = new Flow(ctx, cell::add, true);
            inner.clipLeft = clipLeft;
            inner.sized = item;
            inner.besideLeft = besideLeft;
            inner.besideRight = besideRight;
            ctx.enterCell();
            try {
                inner.single(item, look, new Frame(0, 0, Math.max(24, width - gap), -1), depth + 1);
            } finally {
                ctx.leaveCell();
            }
            List<Block> blocks = cell.blocks();
            if (inner.pendingSpace > inner.pendingMargin && !blocks.isEmpty() && blocks.getLast() instanceof Paragraph p) {
                p.spaceAfter += inner.pendingSpace - inner.pendingMargin;
            }
        }
        return cell;
    }

    private Map<String, String> framedChild(Element item, Map<String, String> d, Look look) {
        if (Layout.framed(d) || BoxCss.shadowed(d) || item.childrenSize() != 1 || !item.ownText().isBlank()) {
            return null;
        }
        Element only = item.child(0);
        Map<String, String> cd = ctx.declared(only, look);
        String display = cd.getOrDefault("display", BLOCKS.contains(only.normalName()) ? "block" : "inline")
                .strip().toLowerCase(Locale.ROOT);
        if (!display.equals("block") || !(Layout.framed(cd) || BoxCss.shadowed(cd) || BoxCss.hasEdges(cd))) {
            return null;
        }
        return cd;
    }

    private float gutter(List<Element> items, Look look, Frame frame) {
        float widest = 0;
        for (Element item : items) {
            Map<String, String> d = ctx.declared(item, look);
            if (framedChild(item, d, look) != null) {
                widest = Math.max(widest, Math.max(0, BoxCss.length(d.get("padding-left"), look.size, frame.width()))
                        + Math.max(0, BoxCss.length(d.get("padding-right"), look.size, frame.width())));
            }
        }
        return Math.min(72, widest);
    }

    private void row(Layout.Plan plan, Look look, Frame frame, int depth) throws IOException {
        flush();
        Table t = new Table();
        float[] widths = plan.widths();
        for (float w : widths) {
            t.columnWidths.add(w);
        }
        t.indent = look.rightToLeft ? frame.right() : frame.left();
        t.rightToLeft = look.rightToLeft;
        t.cellMarginLeft = plan.gap() / 2;
        t.cellMarginRight = plan.gap() / 2;
        List<Element> items = plan.items();
        int columns = plan.columns();
        if (plan.areas() != null) {
            for (List<int[]> cells : plan.areas()) {
                Table.Row row = new Table.Row();
                row.splits = avoiding == 0;
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
        Map<Table.Cell, Element> placed = new IdentityHashMap<>();
        for (int start = 0; plan.areas() == null && start < items.size(); start += columns) {
            Table.Row row = new Table.Row();
            row.splits = avoiding == 0;
            for (int c = 0; c < columns; c++) {
                Element item = start + c < items.size() ? items.get(start + c) : null;
                Table.Cell cell = cell(item, look, frame, widths[c], plan.gap(), depth);
                if (item != null) {
                    placed.put(cell, item);
                }
                row.cells.add(cell);
            }
            t.rows.add(row);
        }
        if (plan.areas() == null) {
            dropEmptyColumns(t);
            float gutter = gutter(items, look, frame);
            if (plan.gap() >= 1 && Layout.framed(items, look, ctx)) {
                gapColumns(t, plan.gap());
            } else if (gutter >= 1) {
                gapColumns(t, gutter);
            }
            for (Table.Row row : t.rows) {
                Set<Table.Cell> painted = Collections.newSetFromMap(new IdentityHashMap<>());
                for (int c = 0; c < row.cells.size() && c < t.columnWidths.size(); c++) {
                    Element item = placed.get(row.cells.get(c));
                    if (item != null && itemGradient(row, row.cells.get(c), item, look, t.columnWidths.get(c),
                            t.cellMarginLeft)) {
                        painted.add(row.cells.get(c));
                    }
                }
                if (t.columnWidths.size() >= 2) {
                    decorate(row, t, placed, painted, look, frame);
                }
            }
        }
        if (t.columnWidths.size() < 2) {
            // what is left is one column: its blocks flow as if there had been no row
            for (Table.Row row : t.rows) {
                for (Table.Cell cell : row.cells) {
                    for (Block b : cell.blocks()) {
                        switch (b) {
                            case Paragraph p -> p.indentLeft += frame.left();
                            case Table nested -> nested.indent += frame.left();
                        }
                        ctx.count();
                        emit(b);
                    }
                }
            }
            return;
        }
        if (pendingSpace > 0) {
            spacer(pendingSpace, frame);
            space(0);
        }
        breakBefore(t, pendingBreak, frame);
        ctx.count();
        emit(t);
    }

    private void decorate(Table.Row row, Table t, Map<Table.Cell, Element> placed, Set<Table.Cell> painted, Look look,
            Frame frame) throws IOException {
        int columns = Math.min(row.cells.size(), t.columnWidths.size());
        List<Map<String, String>> styles = new ArrayList<>(Collections.nCopies(columns, null));
        boolean wanted = false;
        for (int c = 0; c < columns; c++) {
            Table.Cell cell = row.cells.get(c);
            Element item = placed.get(cell);
            if (item == null || painted.contains(cell)) {
                continue;
            }
            Map<String, String> d = ctx.declared(item, look);
            Map<String, String> box = framedChild(item, d, look);
            d = box != null ? box : d;
            styles.set(c, d);
            wanted |= d.containsKey("border-radius") || d.containsKey("box-shadow")
                    || d.containsKey("background-gradient");
        }
        if (!wanted) {
            return;
        }
        float tallest = row.height;
        for (int c = 0; c < columns; c++) {
            Table.Cell cell = row.cells.get(c);
            float w = t.columnWidths.get(c) - t.cellMarginLeft - t.cellMarginRight;
            tallest = Math.max(tallest, Measure.height(cell.blocks(), w) + spaceAfter(cell.blocks())
                    + cell.top.width() + cell.bottom.width());
        }
        boolean any = false;
        for (int c = 0; c < columns; c++) {
            Table.Cell cell = row.cells.get(c);
            Map<String, String> d = styles.get(c);
            if (d == null) {
                continue;
            }
            boolean shadowEdge = cell.top.equals(new Table.Border(0.5f, BoxCss.SHADOW_EDGE))
                    && cell.top.equals(cell.bottom) && cell.top.equals(cell.left) && cell.top.equals(cell.right);
            if (shadowEdge && !BoxPaint.shadows(d.get("box-shadow"), look.size, look.color).isEmpty()) {
                cell.top = Table.Border.NONE;
                cell.bottom = Table.Border.NONE;
                cell.left = Table.Border.NONE;
                cell.right = Table.Border.NONE;
            }
            float w = t.columnWidths.get(c) - t.cellMarginLeft - t.cellMarginRight;
            if (decorate(cell, d, look, 0, w, tallest, frame.shading())) {
                any = true;
            } else if (shadowEdge) {
                Table.Border edge = new Table.Border(0.5f, BoxCss.SHADOW_EDGE);
                cell.top = edge;
                cell.bottom = edge;
                cell.left = edge;
                cell.right = edge;
            }
        }
        if (any) {
            row.splits = false;
            row.height = Math.max(row.height, tallest);
        }
    }

    private static float spaceAfter(List<Block> blocks) {
        float total = 0;
        for (Block b : blocks) {
            if (b instanceof Paragraph p) {
                total += Math.max(0, p.spaceAfter);
            }
        }
        return total;
    }

    private static void gapColumns(Table t, float gap) {
        int n = t.columnWidths.size();
        if (n < 2) {
            return;
        }
        float give = gap * (n - 1) / n;
        for (int c = n - 1; c >= 0; c--) {
            t.columnWidths.set(c, Math.max(24, t.columnWidths.get(c) - give));
            if (c > 0) {
                t.columnWidths.add(c, gap);
                for (Table.Row row : t.rows) {
                    row.cells.add(c, new Table.Cell());
                }
            }
        }
        for (int r = t.rows.size() - 1; r > 0; r--) {
            Table.Row spacer = new Table.Row();
            spacer.height = gap;
            spacer.exactHeight = true;
            for (int c = 0; c < t.columnWidths.size(); c++) {
                spacer.cells.add(new Table.Cell());
            }
            t.rows.add(r, spacer);
        }
        t.cellMarginLeft = 0;
        t.cellMarginRight = 0;
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
                empty &= row.cells.get(c).empty();
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
        para.inlines.addAll(pendingEdges);
        pendingEdges.clear();
        if (in instanceof Inline.Text t && !t.text().isBlank() && para.lineRule != Paragraph.LineRule.EXACT) {
            float line = look.linePoints > 0 ? look.linePoints : look.lineMultiple * look.size;
            float own = para.lineRule == Paragraph.LineRule.AT_LEAST ? para.lineHeight : 0;
            if (line > own + 0.01f) {
                in = new Inline.Text(t.text(), t.style().withLineHeight(line), t.link(), t.anchorPage());
            }
        }
        para.inlines.add(in);
    }

    private void start() {
        Look l = blockLook;
        Frame f = blockFrame;
        Paragraph p = new Paragraph();
        p.align = l.visualAlign();
        p.indentLeft = f.left();
        p.indentRight = f.right();
        p.indentFirst = l.textIndent;
        p.spaceBefore = Math.min(pendingSpace, 400);
        space(0);
        p.pageBreakBefore = pendingBreak && !inCell && !sectionStarted;
        pendingBreak = false;
        sectionStarted = false;
        p.bidi = l.rightToLeft;
        p.hyphenate = l.hyphenates();
        p.autoSpaceLetters = l.autoSpaceLetters;
        p.autoSpaceDigits = l.autoSpaceDigits;
        p.widows = l.widows;
        p.orphans = l.orphans;
        p.shading = f.shading();
        if (l.linePoints > 0) {
            p.lineRule = Paragraph.LineRule.AT_LEAST;
            p.lineHeight = l.linePoints;
            p.halfLeading = true;
        } else if (l.lineMultiple > 0) {
            p.lineRule = Paragraph.LineRule.AT_LEAST;
            p.lineHeight = l.lineMultiple * l.size;
            p.halfLeading = true;
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
        edgeBoundary++;
        pendingEdges.clear();
        if (para == null) {
            return;
        }
        Paragraph p = para;
        para = null;
        if (leaderOf == p) {
            List<Inline> moved = new ArrayList<>(p.inlines.subList(leaderFrom, p.inlines.size()));
            p.inlines.subList(leaderFrom, p.inlines.size()).clear();
            TextFit.trimEnd(p.inlines);
            TextFit.trimStart(moved);
            p.inlines.add(new Inline.Tab(leaderStyle));
            p.inlines.addAll(moved);
            p.tabs.add(new Paragraph.TabStop(blockFrame.left() + blockFrame.width(),
                    Paragraph.TabStop.Kind.RIGHT, leaderChar));
            leaderOf = null;
        }
        if (rightOf == p) {
            TextFit.trimEnd(p.inlines);
            TextFit.trimStart(right);
            p.inlines.add(new Inline.Tab(rightStyle));
            p.inlines.addAll(right);
            p.tabs.add(new Paragraph.TabStop(blockFrame.left() + blockFrame.width(), Paragraph.TabStop.Kind.RIGHT,
                    (char) 0));
            rightOf = null;
            right.clear();
        }
        boolean blankLine = !p.inlines.isEmpty();
        TextFit.trimEnd(p.inlines);
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
        TextFit.measureUnwrapped(ctx, p, blockLook.whiteSpace);
        p.anchors.addAll(pendingAnchors);
        pendingAnchors.clear();
        lastSpace = true;
        ctx.count();
        if (!inCell && p.list == null && p.orphans > 1 && TextFit.boldLabel(p)) {
            p.keepNext = true;
        }
        emit(p);
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
            } else if (i instanceof Inline.FormControl f) {
                tallest = Math.max(tallest, f.height());
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
        TextFit.cut(p, (int) (keep * width / (size * 0.5f)));
        return true;
    }

    private void image(Element e, Map<String, String> d, Look look, Frame frame) throws IOException {
        String width = d.getOrDefault("width", "").strip();
        float room = width.endsWith("%") ? FlowImages.percentBase(ctx, e, look, frame.width()) : frame.width();
        FlowImages.measureImage(ctx, e, d, look, frame);
        Picture pic = ctx.images().picture(e, d, look, room, pictureRoom());
        if (pic == null) {
            String alt = e.attr("alt").strip();
            if (!alt.isEmpty()) {
                text("[" + alt + "]", look);
            } else if (!e.hasAttr("alt") || para == null || para.inlines.isEmpty()) {
                RunStyle r = look.run();
                float size = e.hasAttr("alt") ? r.size() : Math.max(r.size(), BROKEN_PICTURE);
                float box = e.hasAttr("alt") ? 0 : BROKEN_PICTURE;
                inline(new Inline.Text("\u00a0", new RunStyle(r.font(), size, false, false, false, false, r.rgb(),
                        -1, 0, false, box - size * 0.25f, r.scale(), false, -1), null, -1), look);
            }
            return;
        }
        FlowImages.turn(pic, d, look);
        place(pic, d, look, frame);
    }

    // A row in a cell cannot break, so a picture there leaves room for its caption on the same page
    private float pictureRoom() {
        return inCell ? ctx.pageRoom() * 0.75f : ctx.pageRoom();
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

    private boolean onOwnLine(Element svg, Map<String, String> d, Look look) {
        if (forceInline > 0 || look.rightToLeft || !d.getOrDefault("float", floatOf).strip().equalsIgnoreCase("none")
                || para != null && !TextFit.blank(para.inlines)) {
            return false;
        }
        for (Node n = svg.nextSibling(); n != null; n = n.nextSibling()) {
            if (n instanceof TextNode t && !t.isBlank()) {
                return false;
            }
            if (!(n instanceof Element next) || SKIPPED.contains(next.normalName())
                    && !Controls.TAGS.contains(next.normalName())) {
                continue;
            }
            String shown = ctx.declared(next, look).getOrDefault("display",
                    BLOCKS.contains(next.normalName()) ? "block" : "inline").strip().toLowerCase(Locale.ROOT);
            if (!shown.equals("none")) {
                return !shown.startsWith("inline") && !shown.equals("contents");
            }
        }
        return true;
    }

    private void labelled(HtmlImages.Labelled drawn, Map<String, String> d, Look look, Frame frame) throws IOException {
        Picture pic = drawn.picture();
        if (drawn.labels().isEmpty()) {
            place(pic, d, look, frame);
            return;
        }
        flush();
        if (pendingSpace > 0) {
            spacer(pendingSpace, blockFrame);
            space(0);
        }
        start();
        Paragraph p = para;
        p.lineRule = Paragraph.LineRule.AUTO;
        p.lineHeight = 0;
        float share = switch (p.align) {
            case CENTER -> 0.5f;
            case RIGHT -> 1f;
            default -> 0f;
        };
        float left = p.indentLeft + Math.max(0, blockFrame.width() - pic.width) * share;
        p.inlines.add(new Inline.Image(pic));
        for (Svg.Label label : drawn.labels()) {
            p.inlines.add(FlowImages.textBox(label, left, look));
        }
        lastSpace = false;
        flush();
    }

    private void rule(Map<String, String> d, Look look, Frame frame) throws IOException {
        flush();
        float em = look.size;
        margin(BoxCss.length(d.get("margin-top"), em, frame.width()));
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
        space(BoxCss.length(d.get("margin-bottom"), em, frame.width()));
        ctx.count();
        emit(p);
    }

    private void table(Element e, Map<String, String> d, Look look, Frame frame, Paragraph.Align legacy, int depth)
            throws IOException {
        flush();
        if (inCell && (paragraphsOnly || e.parents().stream().filter(p -> p.normalName().equals("table")).count() >= Table.MAX_NESTING
                || HtmlTables.least(e, look) > frame.width())) {
            nestedTable(e, look, frame, depth);
            return;
        }
        if (HtmlTables.layoutOnly(e, d, ctx, look)) {
            // a one-column table with no borders is only there to stack its cells: they flow as blocks
            Integer fill = Css.color(d.get("background-color"));
            float room = frame.width();
            float margins = Math.max(0, BoxCss.length(d.get("margin-left"), look.size, room))
                    + Math.max(0, BoxCss.length(d.get("margin-right"), look.size, room));
            float width = stackWidth(e, d, look, room);
            float slack = room - (margins < room ? Math.min(width, room - margins) : width);
            float left = stackIndent(e, d, look, legacy, room, slack);
            Frame inside = frame.inner(left, slack - left, fill == null ? -1 : fill);
            for (Element tr : e.select(HtmlTables.ROWS)) {
                for (Element cell : tr.select("> td, > th")) {
                    stackedCell = cell;
                    element(cell, look, inside, depth + 1);
                }
            }
            flush();
            return;
        }
        float em = look.size;
        margin(BoxCss.length(d.get("margin-top"), em, frame.width()));
        if (BoxCss.isPageBreak(d.get("break-before"))) {
            pendingBreak = true;
        }
        Table t = ctx.tables().table(e, d, look, frame, legacy, depth);
        if (t == null) {
            return;
        }
        boolean newPage = pendingBreak && !sectionStarted && !inCell;
        pendingBreak = false;
        sectionStarted = false;
        if (pendingSpace > 0 && !newPage) {
            spacer(pendingSpace, frame);
        }
        space(Math.max(0, BoxCss.length(d.get("margin-bottom"), em, frame.width())));
        breakBefore(t, newPage, frame);
        ctx.count();
        emit(t);
    }

    private static float stackWidth(Element table, Map<String, String> d, Look look, float room) {
        float width = room;
        for (String key : new String[] {"width", "max-width"}) {
            Float pt = d.get(key) == null || d.get(key).strip().equalsIgnoreCase("auto") ? null
                    : Css.length(d.get(key), look.size, room);
            if (pt != null && pt > 0) {
                width = Math.min(width, pt);
            }
        }
        return width < room ? Math.min(room, Math.max(width, longestWord(table, look))) : width;
    }

    private static final int STACK_TEXT = 2_000;

    private static float longestWord(Element table, Look look) {
        float most = 0;
        int seen = 0;
        ArrayDeque<Node> open = new ArrayDeque<>();
        open.push(table);
        while (!open.isEmpty() && seen < STACK_TEXT) {
            Node n = open.pop();
            seen++;
            if (n instanceof TextNode t) {
                String text = t.text();
                seen += text.length();
                for (String word : text.split("\\s+")) {
                    most = Math.max(most, Glyphs.word(word, look.size, look.bold, look.font));
                }
            } else {
                for (int i = Math.min(n.childNodeSize(), STACK_TEXT) - 1; i >= 0; i--) {
                    open.push(n.childNode(i));
                }
            }
        }
        return most;
    }

    private static float stackIndent(Element table, Map<String, String> d, Look look, Paragraph.Align legacy,
            float room, float slack) {
        return slack <= 0 ? 0 : Math.max(0, Math.min(slack, HtmlTables.offset(table, d, look, legacy, room, slack)));
    }

    // A table nested past the limit: each row becomes a line, its cells side by side with their own formatting
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
            para.inlines.addAll(columned(Positioned.moved(p.drawings(), x0, lift, true)));
        } else {
            anchor(Positioned.moved(p.drawings(), x0, ANCHOR + pendingSpace + lift, true), frame);
        }
    }

    private void lifted(Element e, Map<String, String> d, Look look, Frame frame, int depth) throws IOException {
        float em = look.size;
        float top = BoxCss.length(d.get("margin-top"), em, frame.width());
        float bottom = BoxCss.length(d.get("margin-bottom"), em, frame.width());
        Positioned.Placed p = Positioned.lay(ctx, new Positioned.Item(e, d, look), frame.width(), Float.NaN, true,
                depth);
        Transform t = Transform.of(d.get("transform"), em, p.width(), p.height());
        float roomHeight = p.height() / Math.max(0.05f, t.scaleY());
        margin(top);
        Paragraph hold = anchorParagraph(columned(Positioned.moved(p.drawings(), originX() + frame.left(), pendingSpace,
                true)), frame.left());
        hold.lineHeight = Math.max(1, Math.min(roomHeight, ctx.pageRoom()));
        hold.spaceBefore = Math.min(pendingSpace, 400);
        hold.keepNext = false;
        hold.pageBreakBefore = pendingBreak && !inCell && !sectionStarted;
        pendingBreak = false;
        sectionStarted = false;
        space(0);
        ctx.count();
        emit(hold);
        space(bottom);
    }

    private void anchor(List<Inline> drawings, Frame frame) throws IOException {
        Paragraph p = anchorParagraph(columned(drawings), frame.left());
        p.pageBreakBefore = pendingBreak && !inCell && !sectionStarted;
        pendingBreak = false;
        sectionStarted = false;
        ctx.count();
        emit(p);
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

    private List<Inline> columned(List<Inline> drawings) {
        float origin = originX();
        return origin == 0 ? drawings : Positioned.boxesShifted(drawings, -origin);
    }

    private float originX() {
        if (inCell) {
            return 0;
        }
        Section s = ctx.section(ctx.pageName());
        return (s != null ? s : ctx.defaultSection()).marginLeft;
    }

    private void breakBefore(Table t, boolean newPage, Frame frame) throws IOException {
        pendingBreak = false;
        if (!newPage) {
            return;
        }
        Paragraph opener = new Paragraph();
        opener.indentLeft = frame.left();
        opener.lineRule = Paragraph.LineRule.EXACT;
        opener.lineHeight = 1;
        opener.inlines.add(new Inline.Text("\u00a0", blockLook.run().withSize(1), null, -1));
        opener.pageBreakBefore = true;
        opener.keepNext = true;
        emit(opener);
    }

    private void spacer(float space, Frame frame) throws IOException {
        Paragraph p = new Paragraph();
        p.indentLeft = frame.left();
        p.lineRule = Paragraph.LineRule.EXACT;
        p.lineHeight = Math.max(1, Math.min(space, 400));
        p.inlines.add(new Inline.Text("\u00a0", blockLook.run().withSize(1), null, -1));
        emit(p);
    }

}

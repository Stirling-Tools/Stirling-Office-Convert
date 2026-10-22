package stirling.software.officeconvert.topdf.html;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
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

    private record ListLevel(Numbering.Instance list, int level, boolean bullets) {}

    static final int MAX_DEPTH = 200;

    // Tabs past this many in a row are past the edge of any page, and each costs the layout memory
    private static final int MAX_TABS_IN_A_ROW = 32;

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
    private final boolean inCell;
    private final Deque<ListLevel> lists = new ArrayDeque<>();
    private Paragraph para;
    private Paragraph lastParagraph;
    private Look blockLook;
    private Frame blockFrame;
    private String headingStyle;
    private float pendingSpace;
    private float pendingMargin;
    private boolean pendingBreak;
    private Paragraph.ListRef pendingMarker;
    private boolean lastSpace = true;
    private String link;
    private int forceInline;
    private Inline pendingGap;
    private int rowItemStart = -1;
    private int rowNested;
    private int avoiding;
    private final List<Block> held = new ArrayList<>();
    private boolean outrun;
    private Element sized;
    private float besideLeft;
    private float besideRight;
    private final List<Inline> pendingFloats = new ArrayList<>();
    private String floatOf = "none";
    private boolean sectionStarted;
    private Paragraph rightOf;
    private final List<Inline> right = new ArrayList<>();
    private RunStyle rightStyle;
    // the height left in a box that clips its overflow, as a browser shows only what fits
    private float clipLeft = Float.MAX_VALUE;

    Flow(HtmlContext ctx, Out out, boolean inCell) {
        this.ctx = ctx;
        this.out = out;
        this.inCell = inCell;
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
        Float w = boxLength(d.get("width"), look, room);
        if (w == null) {
            return 0;
        }
        if (!"border-box".equalsIgnoreCase(d.getOrDefault("box-sizing", "").strip())) {
            w += Math.max(0, length(d.get("padding-left"), look.size, room))
                    + Math.max(0, length(d.get("padding-right"), look.size, room)) + border(d, "left") + border(d, "right");
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
        Float tall = boxLength(d.get("min-height"), look, 0);
        row.height = tall == null ? 0 : Math.min(tall, ctx.pageRoom());
        t.rows.add(row);
        if (pendingSpace > 0) {
            spacer(pendingSpace, frame);
            space(0);
        }
        t.pageBreakBefore = pendingBreak && !sectionStarted;
        pendingBreak = false;
        sectionStarted = false;
        ctx.count();
        emit(t);
    }

    private void element(Element e, Look parentLook, Frame frame, int depth) throws IOException {
        String tag = e.normalName();
        if (Controls.TAGS.contains(tag)) {
            if (!e.hasAttr("hidden") && Controls.drawn(e)) {
                control(e, parentLook, frame);
            }
            return;
        }
        if (SKIPPED.contains(tag) || e.hasAttr("hidden")) {
            return;
        }
        if (depth > MAX_DEPTH) {
            text(e.text(), parentLook);
            return;
        }
        Map<String, String> d = ctx.declared(e, parentLook);
        if (tag.equals("table") && parentLook.centerBlocks && !d.containsKey("text-align") && e.ownerDocument() != null
                && e.ownerDocument().quirksMode() == org.jsoup.nodes.Document.QuirksMode.quirks) {
            // <center> places a table in the middle but, in quirks mode, its text starts at the left, as Chrome does
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
                if (onOwnLine(e, d, look)) {
                    HtmlImages.Labelled drawn = ctx.images().labelledSvg(e, d, look, frame.width(), pictureRoom());
                    if (drawn != null) {
                        labelled(drawn, d, look, frame);
                    }
                } else {
                    Picture pic = ctx.images().inlineSvg(e, d, look, frame.width(), pictureRoom());
                    if (pic != null) {
                        place(pic, d, look, frame);
                    }
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
                if (inline && forceInline == 0 && rowItemStart < 0 && !display.equals("contents")
                        && d.getOrDefault("float", "none").strip().equalsIgnoreCase("right")) {
                    floatRight(e, look, frame, depth);
                } else if (inline) {
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
        rowNested -= nests ? 1 : 0;
        if (stacks) {
            breakInRow(look);
        }
    }

    private static final int BUTTON_TEXT = 80;

    private static boolean padded(Element e, Map<String, String> d, Look look) {
        Integer fill = Css.color(d.get("background-color"));
        if (fill == null || fill < 0 || fill == 0xFFFFFF || Layout.text(e, BUTTON_TEXT + 1).strip().isEmpty()
                || Layout.text(e, BUTTON_TEXT + 1).length() > BUTTON_TEXT) {
            return false;
        }
        for (Element c : e.getAllElements()) {
            if (c != e && !Set.of("b", "strong", "i", "em", "span", "small", "u", "font").contains(c.normalName())) {
                return false;
            }
        }
        for (String side : new String[] {"top", "right", "bottom", "left"}) {
            if (length(d.get("padding-" + side), look.size, 0) > 0) {
                return true;
            }
        }
        return false;
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
        inline(control, look);
        lastSpace = false;
        if (alone) {
            flush();
        }
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
        float top = length(d.get("margin-top"), em, frame.width());
        float bottom = length(d.get("margin-bottom"), em, frame.width());
        if ((tag.equals("ul") || tag.equals("ol")) && !lists.isEmpty()) {
            top = 0;
            bottom = 0;
        }
        float besideL = e == sized ? besideLeft : 0;
        float besideR = e == sized ? besideRight : 0;
        float left = Math.max(0, Math.max(0, length(d.get("margin-left"), em, frame.width() + besideL) - besideL)
                + length(d.get("padding-left"), em, frame.width()) + border(d, "left"));
        float right = Math.max(0, Math.max(0, length(d.get("margin-right"), em, frame.width() + besideR) - besideR)
                + length(d.get("padding-right"), em, frame.width()) + border(d, "right"));
        float[] narrow = narrowed(e, d, look, frame.width());
        left += narrow[0];
        right += narrow[1];
        int fill = fill(d, look);
        if (columns(e, d, look, frame, depth, left, right, top, bottom, fill)) {
            return;
        }
        measureFixed(e, d, look, frame, left, right);
        Frame inner = frame.inner(left, right, fill);
        Layout.Plan plan = forceInline > 0 ? null : Layout.of(e, d, look, inner.width(), ctx, inCell);
        if (plan != null && !plan.inline() && widerInside(plan, look, inner.width())) {
            // a cell cannot hold a row, so a longer one inside, like a carousel, keeps its columns instead
            plan = null;
        }
        if (plan != null && inCell && !plan.inline()) {
            // a cell cannot hold a table of columns, but a row of short items still fits on one line
            plan = null;
        }
        if (plan == null && boxed(e, tag, d, fill) && !ctx.holdsRow(e, look, inner.width())) {
            box(e, d, look, frame, depth, fill, top, bottom);
            return;
        }
        boolean avoid = !inCell && avoidsBreaks(d);
        avoiding += avoid ? 1 : 0;
        margin(top);
        pad(padding(d.get("padding-top"), em, frame.width()));
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
            inlineRow(plan, d, look, inner, depth);
        } else {
            row(plan, look, inner, depth);
        }
        floatOf = outerFloat;
        flush();
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
        pad(padding(d.get("padding-bottom"), em, frame.width()));
        margin(bottom);
        if (isPageBreak(d.get("break-after"))) {
            pendingBreak = true;
        }
        avoiding -= avoid ? 1 : 0;
        if (avoiding == 0) {
            keepTogether();
        }
    }

    private boolean columns(Element e, Map<String, String> d, Look look, Frame frame, int depth, float left,
            float right, float top, float bottom, int fill) throws IOException {
        if (inCell || forceInline > 0 || rowItemStart >= 0 || avoiding > 0 || held != null
                || clipLeft != Float.MAX_VALUE || look.rightToLeft) {
            return false;
        }
        float width = frame.width() - left - right;
        float gap = columnGap(d, look);
        int n = columnCount(d, look, width, gap);
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
        pad(padding(d.get("padding-top"), em, frame.width()));
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
        set.columnRule = columnRule(d) > 0;
        Look outerLook = blockLook;
        Frame outerFrame = blockFrame;
        Frame inner = new Frame(0, 0, each, fill >= 0 && fill != 0xFFFFFF ? fill : -1);
        blockLook = look;
        blockFrame = inner;
        walkChildren(e, look, inner, depth);
        flush();
        Paragraph closing = sectionMark(inner);
        closing.endsSection = ctx.ending(set);
        ctx.count();
        emit(closing);
        blockLook = outerLook;
        blockFrame = outerFrame;
        space(0);
        pad(padding(d.get("padding-bottom"), em, frame.width()));
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

    private static float columnGap(Map<String, String> d, Look look) {
        String v = d.getOrDefault("column-gap", "normal").strip();
        Float pt = v.equalsIgnoreCase("normal") ? null : Css.length(v, look.size, 0);
        return pt == null ? look.size : Math.max(0, Math.min(pt, 144));
    }

    static int columnCount(Map<String, String> d, Look look, float width, float gap) {
        Integer count = null;
        Float least = null;
        for (String token : Css.tokens(d.getOrDefault("columns", ""))) {
            if (token.matches("[0-9]{1,3}")) {
                count = Integer.parseInt(token);
            } else if (!token.equalsIgnoreCase("auto")) {
                least = Css.length(token, look.size, 0);
            }
        }
        String c = d.getOrDefault("column-count", "").strip();
        if (c.matches("[0-9]{1,3}")) {
            count = Integer.parseInt(c);
        }
        String w = d.getOrDefault("column-width", "").strip();
        if (!w.isEmpty() && !w.equalsIgnoreCase("auto")) {
            least = Css.length(w, look.size, 0);
        }
        int n = count == null ? Integer.MAX_VALUE : count;
        if (least != null && least > 0) {
            n = Math.min(n, Math.max(1, (int) ((width + gap) / (least + gap))));
        }
        return n == Integer.MAX_VALUE ? 1 : Math.max(1, Math.min(n, 12));
    }

    private static float columnRule(Map<String, String> d) {
        Map<String, String> rule = new HashMap<>();
        for (String part : new String[] {"", "-style", "-width"}) {
            String v = d.get("column-rule" + part);
            if (v != null) {
                rule.put("border-rule" + part, v);
            }
        }
        return border(rule, "rule");
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

    static boolean avoidsBreaks(Map<String, String> d) {
        String v = d.getOrDefault("break-inside", d.getOrDefault("page-break-inside", "")).strip();
        return v.toLowerCase(Locale.ROOT).startsWith("avoid");
    }

    private void emit(Block b) throws IOException {
        if (avoiding == 0 || outrun) {
            out.add(b);
            return;
        }
        held.add(b);
        if (held.size() > BlockFlow.CHAIN_LIMIT) {
            for (Block h : held) {
                out.add(h);
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
            out.add(held.get(i));
        }
        held.clear();
        outrun = false;
    }

    // These shade their own lines, or carry list and heading structure a box would lose
    private static final Set<String> NOT_BOXES = Set.of("p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li",
            "menu", "dl", "dt", "dd", "table", "tr", "td", "th", "caption", "body", "html");

    private static final int BOX_TEXT = 4_000;

    // The background a block paints: its colour, or for a picture we never load under light text, a dark stand-in
    private static int fill(Map<String, String> d, Look look) {
        Integer c = Css.color(d.get("background-color"));
        if (c != null && c >= 0) {
            return c;
        }
        if ("picture".equals(d.get("background-image")) && light(look.color)) {
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
        boolean painted = fill >= 0 && fill != 0xFFFFFF && fill != blockFrame.shading();
        boolean bar = border(d, "left") > 0 || border(d, "right") > 0;
        return (painted || edges >= 3 || bar) && Layout.text(e, BOX_TEXT).length() < BOX_TEXT;
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
        Float width = boxLength(d.get("width"), look, room);
        Float max = boxLength(d.get("max-width"), look, room);
        if (width == null && max == null) {
            return none;
        }
        float em = look.size;
        float outer = width == null ? max : max == null ? width : Math.min(width, max);
        if (!"border-box".equalsIgnoreCase(d.getOrDefault("box-sizing", "").strip())) {
            outer += Math.max(0, length(d.get("padding-left"), em, room)) + Math.max(0, length(d.get("padding-right"),
                    em, room)) + border(d, "left") + border(d, "right");
        }
        float extra = room - Math.max(0, length(d.get("margin-left"), em, room))
                - Math.max(0, length(d.get("margin-right"), em, room)) - outer;
        if (extra < 1) {
            return none;
        }
        boolean autoLeft = "auto".equalsIgnoreCase(d.getOrDefault("margin-left", "").strip());
        boolean autoRight = "auto".equalsIgnoreCase(d.getOrDefault("margin-right", "").strip());
        if (autoLeft && autoRight) {
            return new float[] {extra / 2, extra / 2};
        }
        return autoLeft || look.rightToLeft && !autoRight ? new float[] {extra, 0} : new float[] {0, extra};
    }

    private static Float boxLength(String v, Look look, float room) {
        if (v == null) {
            return null;
        }
        String t = v.strip().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || t.equals("auto") || t.equals("none") || t.contains("content") || t.startsWith("calc")) {
            return null;
        }
        Float pt = Css.length(t, look.size, room);
        return pt == null || pt <= 0 ? null : pt;
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
            float bottom) throws IOException {
        float em = look.size;
        float marginLeft = Math.max(0, length(d.get("margin-left"), em, frame.width()));
        float marginRight = Math.max(0, length(d.get("margin-right"), em, frame.width()));
        float padLeft = Math.max(0, length(d.get("padding-left"), em, frame.width()));
        float padRight = Math.max(0, length(d.get("padding-right"), em, frame.width()));
        float[] narrow = narrowed(e, d, look, frame.width());
        marginLeft += narrow[0];
        marginRight += narrow[1];
        float width = Math.max(36, frame.width() - marginLeft - marginRight);
        Table.Cell cell = new Table.Cell();
        cell.shading = fill >= 0 && fill != 0xFFFFFF ? fill : -1;
        cell.top = HtmlTables.border(d, "top", 0);
        cell.bottom = HtmlTables.border(d, "bottom", 0);
        cell.left = HtmlTables.border(d, "left", 0);
        cell.right = HtmlTables.border(d, "right", 0);
        Flow inner = new Flow(ctx, b -> cell.paragraphs.add((Paragraph) b), true);
        inner.pad(Math.max(0, padding(d.get("padding-top"), em, frame.width())));
        inner.children(e, look, new Frame(0, 0, Math.max(24, width - padLeft - padRight), -1), depth);
        float padBottom = Math.max(0, padding(d.get("padding-bottom"), em, frame.width()));
        Float tall = boxLength(d.get("height"), look, 0);
        boolean drawn = cell.shading >= 0 || cell.top.visible() || cell.bottom.visible();
        if (cell.paragraphs.isEmpty() && (tall == null || tall < 1 || !drawn)) {
            margin(top + bottom);
            return;
        }
        Table t = new Table();
        Table.Row row = new Table.Row();
        if (cell.paragraphs.isEmpty()) {
            float h = tall + Math.max(0, padding(d.get("padding-top"), em, frame.width())) + padBottom;
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
        } else if (padBottom >= 1) {
            inner.spacer(padBottom, new Frame(0, 0, width, -1));
        }
        if (!cell.paragraphs.isEmpty() && tall != null && tall >= 1) {
            boolean borderBox = "border-box".equalsIgnoreCase(d.getOrDefault("box-sizing", "").strip());
            float high = tall + (borderBox ? 0 : Math.max(0, padding(d.get("padding-top"), em, frame.width())) + padBottom);
            if (gradient(cell, d.get("background-gradient"), width, high, padLeft)) {
                row.height = Math.min(high, ctx.pageRoom());
            }
        }
        t.columnWidths.add(width);
        t.indent = frame.left() + marginLeft;
        if (look.rightToLeft) {
            t.rightToLeft = true;
            t.indent = frame.right() + marginRight;
        }
        t.cellMarginLeft = padLeft;
        t.cellMarginRight = padRight;
        row.splits = !avoidsBreaks(d);
        row.cells.add(cell);
        t.rows.add(row);
        margin(top);
        if (pendingSpace > 0) {
            spacer(pendingSpace, frame);
        }
        t.pageBreakBefore = pendingBreak && !sectionStarted;
        pendingBreak = false;
        sectionStarted = false;
        ctx.count();
        emit(t);
        space(bottom);
    }

    private boolean gradient(Table.Cell cell, String css, float width, float height, float padLeft) throws IOException {
        Gradient g = Gradient.parse(css);
        Picture pic = g == null ? null : ctx.images().gradient(g, width, height);
        if (pic == null) {
            return false;
        }
        pic.wrap = Picture.Wrap.NONE;
        pic.behind = true;
        pic.x = -padLeft;
        pic.y = 0;
        cell.paragraphs.getFirst().inlines.addFirst(new Inline.Image(pic));
        cell.shading = -1;
        return true;
    }

    private static Paragraph bar(float height, Look look) {
        Paragraph p = new Paragraph();
        p.lineRule = Paragraph.LineRule.EXACT;
        p.lineHeight = Math.max(1, Math.min(height, 400));
        p.inlines.add(new Inline.Text("\u00a0", look.run().withSize(1), null, -1));
        return p;
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
            List<List<Inline>> item = lines(row.inlines.subList(from[i], from[i + 1]));
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

    private static List<List<Inline>> lines(List<Inline> inlines) {
        List<List<Inline>> lines = new ArrayList<>();
        List<Inline> line = new ArrayList<>();
        for (Inline in : inlines) {
            if (in instanceof Inline.Break) {
                lines.add(line);
                line = new ArrayList<>();
            } else {
                line.add(in);
            }
        }
        lines.add(line);
        while (!lines.isEmpty() && blank(lines.getLast())) {
            lines.removeLast();
        }
        while (!lines.isEmpty() && blank(lines.getFirst())) {
            lines.removeFirst();
        }
        for (List<Inline> l : lines) {
            trimEnd(l);
        }
        return lines;
    }

    private static boolean blank(List<Inline> line) {
        for (Inline in : line) {
            if (!(in instanceof Inline.Text t) || !t.text().isBlank()) {
                return false;
            }
        }
        return true;
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
        return Math.max(0, length(d.get("margin-" + side), look.size, 0)) + Math.max(0, length(d.get("padding-" + side),
                look.size, 0));
    }

    private Table.Cell cell(Element item, Look look, Frame frame, float width, float gap, int depth) throws IOException {
        return cell(item, look, frame, width, gap, depth, 0, 0);
    }

    private Table.Cell cell(Element item, Look look, Frame frame, float width, float gap, int depth, float besideLeft,
            float besideRight) throws IOException {
        Table.Cell cell = new Table.Cell();
        if (item != null) {
            Map<String, String> d = ctx.declared(item, look);
            Integer fill = Css.color(d.get("background-color"));
            // an item with no background of its own shows the row's
            cell.shading = fill == null || fill < 0 ? frame.shading() : fill;
            cell.top = HtmlTables.border(d, "top", 0);
            cell.bottom = HtmlTables.border(d, "bottom", 0);
            cell.left = HtmlTables.border(d, "left", 0);
            cell.right = HtmlTables.border(d, "right", 0);
            Flow inner = new Flow(ctx, b -> cell.paragraphs.add((Paragraph) b), true);
            inner.clipLeft = clipLeft;
            inner.sized = item;
            inner.besideLeft = besideLeft;
            inner.besideRight = besideRight;
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
        for (int start = 0; plan.areas() == null && start < items.size(); start += columns) {
            Table.Row row = new Table.Row();
            row.splits = avoiding == 0;
            for (int c = 0; c < columns; c++) {
                Element item = start + c < items.size() ? items.get(start + c) : null;
                row.cells.add(cell(item, look, frame, widths[c], plan.gap(), depth));
            }
            t.rows.add(row);
        }
        if (plan.areas() == null) {
            dropEmptyColumns(t);
            if (plan.gap() >= 1 && Layout.framed(items, look, ctx)) {
                gapColumns(t, plan.gap());
            }
        }
        if (t.columnWidths.size() < 2) {
            // what is left is one column: its paragraphs flow as if there had been no row
            for (Table.Row row : t.rows) {
                for (Table.Cell cell : row.cells) {
                    for (Paragraph p : cell.paragraphs) {
                        p.indentLeft += frame.left();
                        ctx.count();
                        emit(p);
                    }
                }
            }
            return;
        }
        if (pendingSpace > 0) {
            spacer(pendingSpace, frame);
            space(0);
        }
        t.pageBreakBefore = pendingBreak;
        pendingBreak = false;
        ctx.count();
        emit(t);
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
        Numbering.Level wanted = level(type, level);
        Numbering.Level defined = inst.definition.levels[level];
        if (defined != null && (!defined.format().equals(wanted.format()) || !defined.text().equals(wanted.text()))) {
            Numbering.Instance own = ctx.numbering().create();
            System.arraycopy(inst.definition.levels, 0, own.definition.levels, 0, level);
            inst = own;
        }
        if (inst.definition.levels[level] == null) {
            inst.definition.levels[level] = wanted;
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
        if (rightOf == p) {
            trimEnd(p.inlines);
            trimStart(right);
            p.inlines.add(new Inline.Tab(rightStyle));
            p.inlines.addAll(right);
            p.tabs.add(new Paragraph.TabStop(blockFrame.left() + blockFrame.width(), Paragraph.TabStop.Kind.RIGHT,
                    (char) 0));
            rightOf = null;
            right.clear();
        }
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
        emit(p);
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

    private static void trimStart(List<Inline> inlines) {
        while (!inlines.isEmpty() && inlines.getFirst() instanceof Inline.Text t && t.text().isBlank()) {
            inlines.removeFirst();
        }
        if (!inlines.isEmpty() && inlines.getFirst() instanceof Inline.Text t && t.text().startsWith(" ")) {
            inlines.set(0, new Inline.Text(t.text().stripLeading(), t.style(), t.link(), t.anchorPage()));
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
        place(pic, d, look, frame);
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

    private boolean onOwnLine(Element svg, Map<String, String> d, Look look) {
        if (forceInline > 0 || look.rightToLeft || !d.getOrDefault("float", floatOf).strip().equalsIgnoreCase("none")
                || para != null && !blank(para.inlines)) {
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
            p.inlines.add(textBox(label, left, look));
        }
        lastSpace = false;
        flush();
    }

    private static Inline.TextBox textBox(Svg.Label label, float left, Look look) {
        RunStyle base = look.run();
        String family = label.family() == null ? null : Look.family(label.family());
        float size = Math.max(1, Math.min(label.size(), 400));
        RunStyle style = new RunStyle(family == null ? base.font() : family, size, label.bold(), false, false, false,
                label.rgb(), -1, 0, false);
        float width = label.width() * 1.3f + size;
        float x = label.x() - width * switch (label.anchor()) {
            case "middle" -> 0.5f;
            case "end" -> 1f;
            default -> 0f;
        };
        Paragraph line = new Paragraph();
        line.align = switch (label.anchor()) {
            case "middle" -> Paragraph.Align.CENTER;
            case "end" -> Paragraph.Align.RIGHT;
            default -> Paragraph.Align.LEFT;
        };
        line.lineRule = Paragraph.LineRule.EXACT;
        line.lineHeight = size * LABEL_LINE;
        line.inlines.add(new Inline.Text(label.text(), style, null, -1));
        return new Inline.TextBox(left + x, label.baseline() - size * LABEL_ASCENT, width, line.lineHeight, -1, 0, 0, 0,
                List.of(line), -1, 0, false, true, 0, -1, false, true);
    }

    private static final float LABEL_LINE = 1.2f;
    private static final float LABEL_ASCENT = 0.9f;

    private void rule(Map<String, String> d, Look look, Frame frame) throws IOException {
        flush();
        float em = look.size;
        margin(length(d.get("margin-top"), em, frame.width()));
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
        space(length(d.get("margin-bottom"), em, frame.width()));
        ctx.count();
        emit(p);
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
        margin(length(d.get("margin-top"), em, frame.width()));
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
        space(Math.max(0, length(d.get("margin-bottom"), em, frame.width())));
        ctx.count();
        emit(t);
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

    private void spacer(float space, Frame frame) throws IOException {
        Paragraph p = new Paragraph();
        p.indentLeft = frame.left();
        p.lineRule = Paragraph.LineRule.EXACT;
        p.lineHeight = Math.max(1, Math.min(space, 400));
        p.inlines.add(new Inline.Text("\u00a0", blockLook.run().withSize(1), null, -1));
        emit(p);
    }

}

package stirling.software.officeconvert.topdf.html;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jsoup.nodes.Element;

import stirling.software.officeconvert.build.DocSink;
import stirling.software.officeconvert.json.JsonModel;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.StyleSheet;

/** What every part of one HTML conversion shares. */
final class HtmlContext {

    private final Cascade cascade;
    private final Numbering numbering = new Numbering();
    private final StyleSheet styles;
    private final List<String> warnings;
    private final HtmlImages images;
    private final HtmlTables tables;
    private final float pageRoom;
    private final Section page;
    private int blocks;
    // how far right of the text area's left edge any content wanted to reach, in points
    private float wanted;
    private int scrollers;
    private String pageName = "";
    private boolean continuing;
    private float insetLeft;
    private float insetRight;
    private Generated.Result generated = Generated.Result.NONE;
    private final Map<String, String> aliases = new HashMap<>();
    // the most items of any row of columns in an element or inside it, 0 when none
    private final Map<Element, Integer> rows = new IdentityHashMap<>();
    private Map<String, Element> labels;

    private static final int MAX_VISITS = 400_000;

    private static final int MAX_ALIAS_DEPTH = 64;

    private int visits;
    private final java.util.Set<Element> placed = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    boolean visit() {
        return ++visits <= MAX_VISITS;
    }

    void place(Element e) {
        placed.add(e);
    }

    boolean placed(Element e) {
        return placed.contains(e);
    }

    private static final int MAX_NESTED_BOXES = 8;

    private int boxes;

    boolean positions() {
        return boxes < MAX_NESTED_BOXES && cascade.positions();
    }

    void enterBox() {
        boxes++;
    }

    void leaveBox() {
        boxes--;
    }

    private int cells;

    int cells() {
        return cells;
    }

    void enterCell() {
        cells++;
    }

    void leaveCell() {
        cells--;
    }

    HtmlContext(Cascade cascade, StyleSheet styles, DocSink sink, List<String> warnings, Section page, Linked linked) {
        this.cascade = cascade;
        this.styles = styles;
        this.warnings = warnings;
        this.images = new HtmlImages(sink, warnings, linked);
        this.tables = new HtmlTables(this);
        this.page = page;
        this.pageRoom = page.pageHeight - page.marginTop - page.marginBottom;
    }

    Cascade cascade() {
        return cascade;
    }

    Element labelFor(Element control) {
        if (labels == null) {
            labels = new HashMap<>();
            Element body = control.ownerDocument() == null ? null : control.ownerDocument().body();
            if (body != null) {
                for (Element l : body.getElementsByTag("label")) {
                    String id = l.attr("for");
                    if (!id.isEmpty()) {
                        labels.putIfAbsent(id, l);
                    }
                }
            }
        }
        String id = control.id();
        return id.isEmpty() ? null : labels.get(id);
    }

    /** What {@code e} declares, with the custom properties it inherits from {@code parent} filled in. */
    Map<String, String> declared(Element e, Look parent) {
        Map<String, String> d = cascade.declared(e);
        Vars.resolve(d, Vars.scope(parent.vars, d));
        Css.expandShorthands(d);
        float own = Css.opacity(d.get("opacity"));
        boolean inheritsColor = !d.containsKey("color");
        Css.fade(d, parent.opacity * own);
        if (own < 1 && inheritsColor) {
            d.put("color", Css.faded(Css.hex(parent.color), own));
        }
        String direction = d.get("direction");
        boolean rtl = direction == null ? parent.rightToLeft : "rtl".equalsIgnoreCase(direction.strip());
        for (String box : new String[] {"margin", "padding"}) {
            String start = d.remove(box + "-inline-start");
            String end = d.remove(box + "-inline-end");
            if (start != null) {
                d.putIfAbsent(box + (rtl ? "-right" : "-left"), start);
            }
            if (end != null) {
                d.putIfAbsent(box + (rtl ? "-left" : "-right"), end);
            }
        }
        return d;
    }

    /** Whether a child of {@code e}, or anything inside one, lays out as a row of columns; each element is
     * judged once per document. */
    boolean holdsRow(Element e, Look look, float room) {
        for (Element c : e.children()) {
            if (widestRow(c, look, room) > 0) {
                return true;
            }
        }
        return false;
    }

    // Children first, without recursion, so a deep page cannot overflow the stack
    int widestRow(Element root, Look look, float room) {
        Integer known = rows.get(root);
        if (known != null) {
            return known;
        }
        // each element's own look, so custom properties set on the way down reach its items
        Map<Element, Look> looks = new IdentityHashMap<>();
        Map<Element, Map<String, String>> styles = new IdentityHashMap<>();
        ArrayDeque<Element> stack = new ArrayDeque<>();
        stack.push(root);
        Map<String, String> rootStyle = declared(root, look);
        styles.put(root, rootStyle);
        looks.put(root, look.child(rootStyle, room, false));
        while (!stack.isEmpty()) {
            Element e = stack.peek();
            if (rows.containsKey(e)) {
                stack.pop();
                continue;
            }
            Map<String, String> d = styles.get(e);
            String display = d.getOrDefault("display", "").strip().toLowerCase(java.util.Locale.ROOT);
            if (display.equals("none")) {
                rows.put(e, 0);
                stack.pop();
                continue;
            }
            boolean pending = false;
            for (Element c : e.children()) {
                if (!rows.containsKey(c) && !styles.containsKey(c)) {
                    Map<String, String> cd = declared(c, looks.get(e));
                    styles.put(c, cd);
                    looks.put(c, looks.get(e).child(cd, room, false));
                    stack.push(c);
                    pending = true;
                }
            }
            if (pending) {
                continue;
            }
            stack.pop();
            int row = 0;
            for (Element c : e.children()) {
                row = Math.max(row, rows.getOrDefault(c, 0));
            }
            if (display.endsWith("flex") || display.endsWith("grid")) {
                Layout.Plan p = Layout.of(e, d, looks.get(e), room, this);
                row = p != null && !p.inline() ? Math.max(row, p.items().size()) : row;
            }
            rows.put(e, row);
        }
        return rows.get(root);
    }

    /** Content that cannot wrap, like a preformatted line or a fixed width, wants to reach {@code pt} from the
     * left edge of the text area. */
    void wants(float pt) {
        if (scrollers == 0) {
            wanted = Math.max(wanted, Math.min(pt, 1e6f));
        }
    }

    /** Entering ({@code 1}) or leaving ({@code -1}) a box that scrolls or clips what is too wide for it. */
    void scrolling(int step) {
        scrollers = Math.max(0, scrollers + step);
    }

    float wanted() {
        return wanted;
    }

    Numbering numbering() {
        return numbering;
    }

    HtmlImages images() {
        return images;
    }

    HtmlTables tables() {
        return tables;
    }

    float pageRoom() {
        if (cover != null && HtmlDocument.BOOK_COVER.equals(pageName)) {
            return cover.pageHeight - cover.marginTop - cover.marginBottom;
        }
        return pageRoom;
    }

    String heading(String id, RunStyle look) {
        return styles.use(id, look).id();
    }

    void warn(String w) {
        if (!warnings.contains(w)) {
            warnings.add(w);
        }
    }

    boolean nothingWritten() {
        return blocks == 0;
    }

    void count() {
        if (++blocks == JsonModel.MAX_BLOCKS) {
            warn("Only the start of the document was converted: it has more than " + JsonModel.MAX_BLOCKS + " blocks");
        }
    }

    private Section cover;

    void cover(Section s) {
        cover = s;
    }

    /** The section a page name lays out on; null for an unknown name. */
    Section section(String name) {
        if (cover != null && HtmlDocument.BOOK_COVER.equals(name)) {
            return cover;
        }
        Map<String, String> d = cascade.page(name);
        return d == null ? null : HtmlDocument.page(d);
    }

    Section defaultSection() {
        return page;
    }

    /** The section the document ends on, for the package's last section. */
    Section lastSection() {
        return ending(currentSection());
    }

    Section currentSection() {
        Section named = section(pageName);
        return named != null ? named : page;
    }

    Section ending(Section s) {
        Section c = s.copy();
        c.continuous = continuing;
        return c;
    }

    void continuing(boolean onSamePage) {
        continuing = onSamePage;
    }

    String pageName() {
        return pageName;
    }

    void pageName(String name) {
        pageName = name;
    }

    boolean stopped() {
        return blocks >= JsonModel.MAX_BLOCKS || Thread.currentThread().isInterrupted();
    }

    boolean truncated() {
        return blocks >= JsonModel.MAX_BLOCKS;
    }

    void inset(float left, float right) {
        insetLeft = left;
        insetRight = right;
    }

    float insetLeft() {
        return insetLeft;
    }

    float insetRight() {
        return insetRight;
    }

    void generated(Generated.Result result) {
        generated = result;
    }

    String bookmarkFor(Element e) {
        String mark = generated.marks().get(e);
        String id = e.id();
        boolean target = !id.isEmpty() && generated.targets().contains(id);
        if (mark != null) {
            if (target) {
                alias(Generated.BOOKMARK_PREFIX + id, mark);
            }
            return mark;
        }
        return target ? Generated.BOOKMARK_PREFIX + id : null;
    }

    void alias(String bookmark, String written) {
        if (aliases.size() < 100_000) {
            aliases.put(bookmark, written);
        }
    }

    String written(String bookmark) {
        String current = bookmark;
        for (int i = 0; i < MAX_ALIAS_DEPTH; i++) {
            String next = aliases.get(current);
            if (next == null || next.equals(current)) {
                return current;
            }
            current = next;
        }
        return bookmark;
    }

    Map<String, String[]> runningStrings() {
        Map<String, List<String>> pairs = new java.util.LinkedHashMap<>();
        for (String[] v : generated.strings().values()) {
            List<String> list = pairs.computeIfAbsent(written(v[0]), k -> new java.util.ArrayList<>());
            list.add(v[1]);
            list.add(v[2]);
        }
        Map<String, String[]> out = new java.util.LinkedHashMap<>();
        pairs.forEach((k, v) -> out.put(k, v.toArray(String[]::new)));
        return out;
    }

    Map<String, String> targetBookmarks() {
        Map<String, String> out = new HashMap<>();
        for (String id : generated.targets()) {
            out.put(id, written(Generated.BOOKMARK_PREFIX + id));
        }
        return out;
    }
}

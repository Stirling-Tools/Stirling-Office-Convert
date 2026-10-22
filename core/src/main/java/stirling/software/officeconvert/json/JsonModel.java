package stirling.software.officeconvert.json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import stirling.software.officeconvert.build.DocSink;
import stirling.software.officeconvert.model.Block;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Numbering;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Scripts;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.model.Table;

/** Reads a document written by {@link JsonWriter} (or by hand, see {@code docs/json-format.md}) into a sink. */
public final class JsonModel {

    public static final int MAX_BLOCKS = 2_000_000;

    static final int MAX_COLUMNS = 63;

    private static final Pattern STYLE_ID = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,39}");

    // A list marker without a font of its own takes the paragraph's
    private static final RunStyle NO_FONT = new RunStyle(null, 11, false, false, false, false, 0, -1, 0, false);

    private static final RunStyle DEFAULT_RUN = new RunStyle("Calibri", 11, false, false, false, false, 0, -1, 0, false);

    private final DocSink sink;
    private final List<String> warnings;
    private int nesting;
    private final Map<String, Picture.MediaRef> media = new HashMap<>();
    private final Map<Integer, Integer> listIds = new HashMap<>();
    private StyleSheet styles;
    private Object root;
    private RunStyle normal = DEFAULT_RUN;
    private int blocks;

    private JsonModel(DocSink sink, List<String> warnings) {
        this.sink = sink;
        this.warnings = warnings;
    }

    /** Replays the parsed document into {@code sink}: begin, blocks, footnotes, finish. */
    public static void replay(Object root, DocSink sink, List<String> warnings) throws IOException {
        new JsonModel(sink, warnings).read(new JsonIn(root, "the document"));
    }

    private void read(JsonIn doc) throws IOException {
        root = doc.raw();
        String format = doc.str("format", JsonWriter.FORMAT);
        if (!JsonWriter.FORMAT.equals(format)) {
            throw new IOException("Not a Stirling Office Convert JSON document: format is \"" + format + "\"");
        }
        int version = doc.integer("version", JsonWriter.VERSION, 0, Integer.MAX_VALUE);
        if (version > JsonWriter.VERSION) {
            throw new IOException("The JSON document is version " + version + "; this converter reads up to version "
                    + JsonWriter.VERSION);
        }
        styles(doc.obj("styles"));
        for (Object m : doc.list("media")) {
            media(new JsonIn(m, "media"));
        }
        Numbering numbering = lists(doc.list("lists"));
        DocSink.HeaderFooterSet running = new DocSink.HeaderFooterSet(paragraphs(doc.list("header")),
                paragraphs(doc.list("footer")), paragraphs(doc.list("evenHeader")), paragraphs(doc.list("evenFooter")),
                doc.flag("titlePage"));
        sink.begin(styles, running);
        for (Object b : doc.list("body")) {
            Block block = block(new JsonIn(b, "a block"));
            if (block != null) {
                sink.block(block);
            }
        }
        for (Object n : doc.list("footnotes")) {
            JsonIn note = new JsonIn(n, "a footnote");
            sink.footnote(note.integer("id", 1, 1, 1_000_000), paragraphs(note.list("paragraphs")));
        }
        JsonIn page = doc.obj("page");
        sink.finish(page == null ? defaultPage() : section(page), null, numbering, styles, doc.str("title", null),
                doc.str("author", null));
    }

    private void styles(JsonIn in) throws IOException {
        if (in != null && in.has("normal")) {
            normal = run(in.obj("normal"), DEFAULT_RUN);
        }
        styles = new StyleSheet(normal);
        styles.scripts = languages(in == null ? null : in.obj("languages"));
        if (in == null) {
            return;
        }
        for (Object o : in.list("styles")) {
            JsonIn s = new JsonIn(o, "a style");
            String id = s.str("id", "");
            if (!STYLE_ID.matcher(id).matches() || id.startsWith("Heading") && !id.matches("Heading[1-9]")) {
                warnings.add("Left out the style \"" + id + "\": style ids are letters and digits, headings Heading1-9");
                continue;
            }
            styles.use(id, s.has("run") ? run(s.obj("run"), normal) : normal);
        }
    }

    private Scripts.Profile languages(JsonIn in) throws IOException {
        if (in == null) {
            Scripts.Profile counted = new Scripts.Profile();
            countText(root, counted, 0);
            return counted;
        }
        return Scripts.Profile.of(new Scripts.Profile.Answers(in.str("latin", null), in.str("eastAsian", null),
                in.str("complex", null), script(in.str("latinScript", null)), script(in.str("complexScript", null)),
                in.flag("rightToLeft")));
    }

    private static Character.UnicodeScript script(String name) throws IOException {
        if (name == null) {
            return null;
        }
        try {
            return Character.UnicodeScript.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IOException("Unknown script \"" + name + "\"");
        }
    }

    // A hand-written document without languages gets them from its own text, as a PDF does
    private static void countText(Object node, Scripts.Profile profile, int depth) {
        if (depth > Json.MAX_DEPTH) {
            return;
        }
        if (node instanceof Map<?, ?> m) {
            if (m.get("text") instanceof String s) {
                profile.add(s);
            }
            for (Object v : m.values()) {
                countText(v, profile, depth + 1);
            }
        } else if (node instanceof List<?> l) {
            for (Object v : l) {
                countText(v, profile, depth + 1);
            }
        }
    }

    private void media(JsonIn m) throws IOException {
        String id = m.str("id", null);
        String data = m.str("data", null);
        if (id == null || data == null) {
            throw new IOException("Each media item needs an \"id\" and base64 \"data\"");
        }
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new IOException("The data of media \"" + id + "\" is not base64", e);
        }
        MediaBytes pic = MediaBytes.of(bytes);
        if (pic == null) {
            warnings.add("Left out the picture \"" + id + "\": it is not a PNG, JPEG, GIF or BMP picture that can be read");
            return;
        }
        media.put(id, sink.media(pic.bytes(), pic.ext(), pic.pixelWidth(), pic.pixelHeight(), "json:" + id));
    }

    private Numbering lists(List<?> list) throws IOException {
        Numbering numbering = new Numbering();
        for (Object o : list) {
            JsonIn l = new JsonIn(o, "a list");
            Numbering.Instance inst = numbering.create();
            listIds.put(l.integer("id", inst.numId, Integer.MIN_VALUE, Integer.MAX_VALUE), inst.numId);
            List<?> levels = l.list("levels");
            for (int i = 0; i < Math.min(9, levels.size()); i++) {
                if (levels.get(i) == null) {
                    continue;
                }
                JsonIn lv = new JsonIn(levels.get(i), "a list level");
                inst.definition.levels[i] = new Numbering.Level(lv.str("format", "decimal"), lv.str("text", "%" + (i + 1) + "."),
                        lv.has("markerStyle") ? run(lv.obj("markerStyle"), NO_FONT) : null,
                        lv.num("indentLeft", 36f * (i + 1), 0, JsonIn.MAX_LENGTH), lv.num("hanging", 18f, 0, JsonIn.MAX_LENGTH));
                inst.starts[i] = lv.integer("start", 1, 0, 32_767);
            }
        }
        return numbering;
    }

    private Block block(JsonIn b) throws IOException {
        if (++blocks > MAX_BLOCKS) {
            throw new IOException("The document has more than " + MAX_BLOCKS + " blocks");
        }
        String type = b.str("type", "paragraph");
        return switch (type) {
            case "paragraph" -> paragraph(b);
            case "table" -> table(b);
            default -> throw new IOException("Unknown block type \"" + type + "\"");
        };
    }

    private List<Paragraph> paragraphs(List<?> list) throws IOException {
        List<Paragraph> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (++blocks > MAX_BLOCKS) {
                throw new IOException("The document has more than " + MAX_BLOCKS + " blocks");
            }
            out.add(paragraph(new JsonIn(o, "a paragraph")));
        }
        return out;
    }

    private Paragraph paragraph(JsonIn in) throws IOException {
        Paragraph p = new Paragraph();
        String style = in.str("style", "Normal");
        p.style = styles.get(style).id();
        p.align = in.choice("align", Paragraph.Align.class, Paragraph.Align.LEFT);
        p.indentLeft = in.length("indentLeft");
        p.indentRight = in.length("indentRight");
        p.indentFirst = in.length("indentFirst");
        p.spaceBefore = in.num("spaceBefore", 0, 0, JsonIn.MAX_LENGTH);
        p.spaceAfter = in.num("spaceAfter", 0, 0, JsonIn.MAX_LENGTH);
        p.lineHeight = in.num("lineHeight", 0, 0, JsonIn.MAX_LENGTH);
        p.lineRule = in.choice("lineRule", Paragraph.LineRule.class, Paragraph.LineRule.AUTO);
        JsonIn list = in.obj("list");
        if (list != null) {
            Integer numId = listIds.get(list.integer("id", 0, Integer.MIN_VALUE, Integer.MAX_VALUE));
            if (numId != null) {
                p.list = new Paragraph.ListRef(numId, list.integer("level", 0, 0, 8));
            }
        }
        p.pageBreakBefore = in.flag("pageBreakBefore");
        p.keepNext = in.flag("keepNext");
        p.keepLines = in.flag("keepLines");
        p.bidi = in.flag("rightToLeft");
        p.noHangingPunctuation = in.flag("noHangingPunctuation");
        p.shading = in.color("shading", -1);
        p.borderBottom = in.num("borderBottom", 0, 0, 100);
        p.borderBottomRgb = in.color("borderBottomColor", 0);
        p.bookmark = in.str("bookmark", null);
        if (in.has("markStyle")) {
            p.markStyle = run(in.obj("markStyle"), normal);
        }
        for (Object o : in.list("tabs")) {
            JsonIn t = new JsonIn(o, "a tab stop");
            String leader = t.str("leader", "");
            p.tabs.add(new Paragraph.TabStop(t.num("pos", 0, 0, JsonIn.MAX_LENGTH),
                    t.choice("kind", Paragraph.TabStop.Kind.class, Paragraph.TabStop.Kind.LEFT),
                    leader.isEmpty() ? 0 : leader.charAt(0)));
        }
        if (in.has("endsSection")) {
            p.endsSection = section(in.obj("endsSection"));
        }
        RunStyle base = styles.get(p.style).run();
        for (Object o : in.list("content")) {
            Inline inline = inline(new JsonIn(o, "paragraph content"), base);
            if (inline != null) {
                p.inlines.add(inline);
            }
        }
        return p;
    }

    private Inline inline(JsonIn in, RunStyle base) throws IOException {
        String type = in.str("type", "text");
        return switch (type) {
            case "text" -> {
                int page = in.integer("linkPage", 0, 0, 1_000_000);
                yield new Inline.Text(in.str("text", ""), style(in, base), in.str("link", null), page - 1);
            }
            case "tab" -> new Inline.Tab(style(in, base));
            case "break" -> new Inline.Break();
            case "columnBreak" -> new Inline.ColumnBreak();
            case "pageBreak" -> new Inline.PageBreak();
            case "pageNumber" -> new Inline.PageNumber(style(in, base), in.flag("total"));
            case "image" -> image(in);
            case "footnoteRef" -> new Inline.FootnoteRef(in.integer("id", 1, 1, 1_000_000), in.str("marker", ""),
                    in.flag("custom"), style(in, base));
            case "footnoteMark" -> new Inline.FootnoteMark(in.str("marker", ""), in.flag("custom"), style(in, base));
            case "textBox" -> new Inline.TextBox(in.length("x"), in.length("y"), in.num("width", 0, 0, JsonIn.MAX_LENGTH),
                    in.num("height", 0, 0, JsonIn.MAX_LENGTH), in.color("fill", -1), in.length("insetLeft"),
                    in.length("insetRight"), in.length("wrapGap"), paragraphs(in.list("paragraphs")), in.color("line", -1),
                    in.num("lineWidth", 0, 0, 100), in.flag("rounded"), in.flag("overlay"), in.integer("direction", 0, -360, 360),
                    in.color("ground", -1), in.flag("upright"));
            case "shape" -> new Inline.Shape(in.length("x"), in.length("y"), in.num("width", 0, 0, JsonIn.MAX_LENGTH),
                    in.num("height", 0, 0, JsonIn.MAX_LENGTH), in.color("fill", -1), in.color("line", -1),
                    in.num("lineWidth", 0, 0, 100), in.flag("rounded"), in.flag("ellipse"), in.flag("fromParagraph"),
                    in.integer("z", 0, 0, 0x1E000000));
            default -> throw new IOException("Unknown paragraph content type \"" + type + "\"");
        };
    }

    private Inline image(JsonIn in) throws IOException {
        String id = in.str("media", "");
        Picture.MediaRef ref = media.get(id);
        if (ref == null) {
            warnings.add("Left out a picture: no readable media with id \"" + id + "\"");
            return null;
        }
        Picture p = new Picture(ref, in.num("width", ref.pixelWidth() * 0.75f, 0.1f, JsonIn.MAX_LENGTH),
                in.num("height", ref.pixelHeight() * 0.75f, 0.1f, JsonIn.MAX_LENGTH));
        p.wrap = in.choice("wrap", Picture.Wrap.class, Picture.Wrap.INLINE);
        p.x = in.length("x");
        p.y = in.length("y");
        p.behind = in.flag("behind");
        p.backdrop = in.flag("backdrop");
        p.z = in.integer("z", 0, 0, 0x1E000000);
        p.paintOrder = in.integer("paintOrder", -1, -1, Integer.MAX_VALUE);
        p.fromParagraph = in.flag("fromParagraph");
        p.wrapGap = in.num("wrapGap", 0, 0, 1000);
        p.cropLeft = in.num("cropLeft", 0, 0, 0.99f);
        p.cropTop = in.num("cropTop", 0, 0, 0.99f);
        p.cropRight = in.num("cropRight", 0, 0, 0.99f);
        p.cropBottom = in.num("cropBottom", 0, 0, 0.99f);
        p.rotation = in.integer("rotation", 0, -360, 360);
        p.flipH = in.flag("flipHorizontal");
        p.baselineShift = in.length("baselineShift");
        p.description = in.str("description", "");
        return new Inline.Image(p);
    }

    private Table table(JsonIn in) throws IOException {
        nesting++;
        try {
            return readTable(in);
        } finally {
            nesting--;
        }
    }

    private Table readTable(JsonIn in) throws IOException {
        Table t = new Table();
        for (Object w : in.list("columns")) {
            if (t.columnWidths.size() >= MAX_COLUMNS) {
                break;
            }
            if (!(w instanceof Double d)) {
                throw new IOException("Table \"columns\" must be a list of widths");
            }
            t.columnWidths.add((float) Math.max(1, Math.min(JsonIn.MAX_LENGTH, d)));
        }
        t.indent = in.length("indent");
        t.indentEnd = in.length("indentEnd");
        t.cellMarginLeft = in.num("cellMarginLeft", 2f, 0, 1000);
        t.cellMarginRight = in.num("cellMarginRight", 2f, 0, 1000);
        t.pageBreakBefore = in.flag("pageBreakBefore");
        t.rightToLeft = in.flag("rightToLeft");
        if (in.has("floatY")) {
            t.floatX = in.has("floatX") ? in.length("floatX") : Float.NaN;
            t.floatY = in.length("floatY");
            t.floatRoom = in.length("floatRoom");
        }
        int widest = 0;
        for (Object r : in.list("rows")) {
            JsonIn ri = new JsonIn(r, "a table row");
            Table.Row row = new Table.Row();
            row.height = ri.num("height", 0, 0, JsonIn.MAX_LENGTH);
            row.header = ri.flag("header");
            row.exactHeight = ri.flag("exactHeight");
            row.splits = ri.flag("splits");
            int span = 0;
            for (Object c : ri.list("cells")) {
                if (span >= MAX_COLUMNS) {
                    warnings.add("Only the first " + MAX_COLUMNS + " columns of a table were kept");
                    break;
                }
                Table.Cell cell = cell(new JsonIn(c, "a table cell"));
                cell.gridSpan = Math.min(cell.gridSpan, MAX_COLUMNS - span);
                span += cell.gridSpan;
                row.cells.add(cell);
            }
            widest = Math.max(widest, span);
            t.rows.add(row);
        }
        t.rows.removeIf(r -> r.cells.isEmpty());
        if (t.rows.isEmpty()) {
            warnings.add("Left out a table with no cells");
            return null;
        }
        float fill = t.columnWidths.isEmpty() ? 468f / Math.max(1, widest) : t.columnWidths.getLast();
        while (t.columnWidths.size() < widest) {
            t.columnWidths.add(fill);
        }
        return t;
    }

    private Table.Cell cell(JsonIn in) throws IOException {
        Table.Cell c = new Table.Cell();
        c.gridSpan = in.integer("span", 1, 1, 63);
        String merge = in.str("verticalMerge", null);
        c.vMerge = merge == null ? 0 : switch (merge) {
            case "restart" -> 1;
            case "continue" -> 2;
            default -> throw new IOException("\"verticalMerge\" is restart or continue, was " + merge);
        };
        c.top = border(in.obj("top"));
        c.bottom = border(in.obj("bottom"));
        c.left = border(in.obj("left"));
        c.right = border(in.obj("right"));
        c.shading = in.color("shading", -1);
        c.vAlign = in.choice("verticalAlign", Table.VAlign.class, Table.VAlign.TOP);
        if (!in.has("blocks")) {
            c.paragraphs.addAll(paragraphs(in.list("paragraphs")));
            return c;
        }
        for (Object o : in.list("blocks")) {
            Block b = block(new JsonIn(o, "a block in a table cell"));
            if (b instanceof Table nested && nesting >= Table.MAX_NESTING) {
                String flat = "Tables nested more than " + Table.MAX_NESTING + " deep were written as their text";
                if (!warnings.contains(flat)) {
                    warnings.add(flat);
                }
                c.paragraphs.addAll(nested.flatParagraphs());
            } else if (b != null) {
                c.add(b);
            }
        }
        return c;
    }

    private static Table.Border border(JsonIn in) throws IOException {
        if (in == null) {
            return Table.Border.NONE;
        }
        return new Table.Border(in.num("width", 0.5f, 0, 50), in.color("color", 0));
    }

    private Section section(JsonIn in) throws IOException {
        Section s = defaultPage();
        s.pageWidth = in.num("width", s.pageWidth, 36, JsonIn.MAX_LENGTH);
        s.pageHeight = in.num("height", s.pageHeight, 36, JsonIn.MAX_LENGTH);
        // Each pair of margins leaves at least a little room for text
        s.marginTop = in.num("marginTop", s.marginTop, 0, s.pageHeight - 18);
        s.marginBottom = in.num("marginBottom", s.marginBottom, 0, s.pageHeight - 18 - s.marginTop);
        s.marginLeft = in.num("marginLeft", s.marginLeft, 0, s.pageWidth - 18);
        s.marginRight = in.num("marginRight", s.marginRight, 0, s.pageWidth - 18 - s.marginLeft);
        s.headerDistance = in.num("headerDistance", 0, 0, s.pageHeight);
        s.footerDistance = in.num("footerDistance", 0, 0, s.pageHeight);
        s.continuous = in.flag("continuous");
        s.titlePage = in.flag("titlePage");
        s.pageNumberStart = in.integer("pageNumberStart", 1, 0, 32_767);
        List<?> cols = in.list("columns");
        for (int i = 0; i < Math.min(cols.size(), 45); i++) {
            JsonIn c = new JsonIn(cols.get(i), "a column");
            s.columns.add(new float[] {c.num("width", 0, 1, JsonIn.MAX_LENGTH), c.num("gap", 0, 0, JsonIn.MAX_LENGTH)});
        }
        return s;
    }

    /** A4 with 72pt margins, the page a document without one gets. */
    public static Section defaultPage() {
        Section s = new Section();
        s.pageWidth = 595.3f;
        s.pageHeight = 841.9f;
        s.marginTop = 72;
        s.marginBottom = 72;
        s.marginLeft = 72;
        s.marginRight = 72;
        s.headerDistance = 36;
        s.footerDistance = 36;
        return s;
    }

    private RunStyle style(JsonIn in, RunStyle base) throws IOException {
        if (!in.present("style")) {
            return base;
        }
        return in.has("style") ? run(in.obj("style"), base) : null;
    }

    // Missing fields are off, except font and size which come from the paragraph style
    private static RunStyle run(JsonIn in, RunStyle base) throws IOException {
        String va = in.str("vertAlign", null);
        int vert = va == null ? 0 : switch (va) {
            case "super" -> 1;
            case "sub" -> -1;
            default -> throw new IOException("\"vertAlign\" is super or sub, was " + va);
        };
        String font = in.present("font") ? in.str("font", null) : base.font();
        return new RunStyle(font, in.num("size", base.size(), 1, 1638), in.flag("bold"),
                in.flag("italic"), in.flag("underline"), in.flag("strike"), in.color("color", 0), in.color("highlight", -1),
                vert, in.flag("symbol"), in.num("spacing", 0, -100, 100), in.integer("scale", 100, 1, 600),
                in.flag("smallCaps"), in.color("border", -1));
    }
}

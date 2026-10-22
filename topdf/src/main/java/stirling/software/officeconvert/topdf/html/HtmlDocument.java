package stirling.software.officeconvert.topdf.html;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import stirling.software.officeconvert.build.DocSink;
import stirling.software.officeconvert.docx.DocxWriter;
import stirling.software.officeconvert.json.JsonModel;
import stirling.software.officeconvert.model.Section;
import stirling.software.officeconvert.model.StyleSheet;
import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.text.Converted;

/** An HTML file as a Word package, for the Word renderer to draw. No script is run and nothing is fetched: links stay
 * links, and only pictures inside the file are drawn. */
public final class HtmlDocument {

    private HtmlDocument() {}

    public static long estimate(long bytes) {
        long v = Admission.BASE_BYTES + Math.max(0, bytes) * 20;
        return v < 0 ? Long.MAX_VALUE : v;
    }

    /** A page laid out, and the scale it should be printed at: below 1 when content was wider than the page. */
    public record Laid(Converted converted, float fit) {}

    // Chrome shrinks a page whose content is too wide by at most a third, and clips what is wider still
    static final float LEAST_FIT = 2f / 3f;

    public static Converted write(Path source, OutputStream out) throws IOException {
        return write(source, out, null);
    }

    /** {@code fonts} decides which family of each CSS font stack is used; null takes the first named. */
    public static Converted write(Path source, OutputStream out, FontLibrary fonts) throws IOException {
        return lay(source, out, fonts, 1f).converted();
    }

    /** Lays the page out on paper enlarged by {@code 1 / zoom}, as a browser lays out content too wide for the page
     * before shrinking it to fit; the result says which zoom the content asked for. */
    public static Laid lay(Path source, OutputStream out, FontLibrary fonts, float zoom) throws IOException {
        try (DocxWriter writer = new DocxWriter(out)) {
            return lay(source, writer, fonts, zoom);
        }
    }

    /** Lays the HTML out into any document sink: begin, blocks, finish. */
    public static Converted write(Path source, DocSink sink) throws IOException {
        return write(source, sink, null);
    }

    public static Converted write(Path source, DocSink sink, FontLibrary fonts) throws IOException {
        return lay(source, sink, fonts, 1f).converted();
    }

    private static Laid lay(Path source, DocSink sink, FontLibrary fonts, float zoom) throws IOException {
        try {
            if (fonts != null) {
                Map<String, Boolean> known = new HashMap<>();
                Look.drawable(f -> known.computeIfAbsent(f, k -> installed(fonts, k)));
            }
            return convert(source, sink, Math.max(LEAST_FIT, Math.min(1f, zoom)));
        } finally {
            Css.clearRootSize();
            Look.clearDrawable();
        }
    }

    // Installed, or a well-known family with a look-alike installed (Arial drawn with Liberation Sans)
    private static boolean installed(FontLibrary fonts, String family) {
        if (fonts.exact(family, false, false) != null) {
            return true;
        }
        for (String standIn : FontLibrary.lookAlikes(family)) {
            if (fonts.exact(standIn, false, false) != null) {
                return true;
            }
        }
        return false;
    }

    private static float viewportPx(Section page) {
        return (page.pageWidth - page.marginLeft - page.marginRight) / 0.75f;
    }

    private static Laid convert(Path source, DocSink writer, float zoom) throws IOException {
        // One bulk read: the parser's small reads are slow on network and container file systems
        byte[] bytes = Files.readAllBytes(source);
        Document doc = Jsoup.parse(new ByteArrayInputStream(bytes), null, "");
        List<String> warnings = new ArrayList<>();
        Section page = page(new Cascade(doc, viewportPx(JsonModel.defaultPage())).page());
        // media queries see the printed page, however wide the layout grows
        Cascade cascade = new Cascade(doc, viewportPx(page));
        boolean ownPaper = cascade.declaresPages();
        float printed = page.pageWidth - page.marginLeft - page.marginRight;
        if (zoom < 1 && !ownPaper) {
            enlarge(page, zoom);
        }
        Element html = doc.selectFirst("html");
        Element body = doc.body();
        Look root = new Look();
        float width = page.pageWidth - page.marginLeft - page.marginRight;
        Map<String, String> htmlStyle = html == null ? Map.of() : resolved(cascade, html, root);
        if (html != null) {
            root = root.child(htmlStyle, width, false);
        }
        Css.rootSize(root.size);
        Map<String, String> bodyStyle = resolved(cascade, body, root);
        Look bodyLook = root.child(bodyStyle, width, false);
        StyleSheet styles = new StyleSheet(bodyLook.run());
        writer.begin(styles, new DocSink.HeaderFooterSet(List.of(), List.of(), List.of(), List.of(), false));
        HtmlContext ctx = new HtmlContext(cascade, styles, writer, warnings, page);
        least(ctx, htmlStyle, root);
        least(ctx, bodyStyle, bodyLook);
        if (fixed(htmlStyle) || fixed(bodyStyle)) {
            // a page locked in place, as behind an open dialog, does not grow wider than the window
            ctx.scrolling(1);
        }
        new Flow(ctx, writer::block, false).children(body, bodyLook, new Flow.Frame(0, 0, width, -1));
        String author = null;
        Element meta = doc.selectFirst("meta[name=author]");
        if (meta != null) {
            author = meta.attr("content");
        }
        writer.finish(ctx.lastSection(), null, ctx.numbering(), styles, doc.title(), author);
        float fit = 1f;
        if (!ownPaper && ctx.wanted() > printed * 1.02f) {
            // widths here are estimates, so a little slack keeps a measured line from wrapping after all
            fit = Math.max(LEAST_FIT, printed / Math.min(ctx.wanted() * 1.03f, printed / LEAST_FIT));
        }
        // a second layout on larger paper is printed at the zoom it was laid out for
        float printAt = zoom < 1 ? (ownPaper ? 1f : zoom) : fit;
        return new Laid(new Converted(warnings, ctx.truncated()), printAt);
    }

    // A page that sets html or body at least so wide is laid out that wide, past the paper if need be
    private static void least(HtmlContext ctx, Map<String, String> d, Look look) {
        for (String key : new String[] {"min-width", "width"}) {
            String v = d.get(key);
            Float pt = v == null || v.contains("%") || v.contains("vw") ? null : Css.length(v, look.size, 0);
            if (pt != null) {
                ctx.wants(pt);
            }
        }
    }

    private static boolean fixed(Map<String, String> d) {
        return "fixed".equalsIgnoreCase(d.getOrDefault("position", "").strip());
    }

    private static void enlarge(Section page, float zoom) {
        page.pageWidth /= zoom;
        page.pageHeight /= zoom;
        page.marginLeft /= zoom;
        page.marginRight /= zoom;
        page.marginTop /= zoom;
        page.marginBottom /= zoom;
    }

    private static Map<String, String> resolved(Cascade cascade, Element e, Look parent) {
        Map<String, String> d = cascade.declared(e);
        Vars.resolve(d, Vars.scope(parent.vars, d));
        Css.expandShorthands(d);
        return d;
    }

    static Section page(Map<String, String> d) {
        Section s = JsonModel.defaultPage();
        String size = d.get("size");
        if (size != null) {
            size(s, size.toLowerCase(Locale.ROOT));
        }
        float half = Math.min(s.pageWidth, s.pageHeight) / 2 - 36;
        s.marginTop = margin(d.get("margin-top"), s.marginTop, half);
        s.marginBottom = margin(d.get("margin-bottom"), s.marginBottom, half);
        s.marginLeft = margin(d.get("margin-left"), s.marginLeft, half);
        s.marginRight = margin(d.get("margin-right"), s.marginRight, half);
        return s;
    }

    private static float margin(String v, float fallback, float max) {
        Float pt = v == null ? null : Css.length(v, Look.ROOT_SIZE, 0);
        return pt == null ? fallback : Math.max(0, Math.min(max, pt));
    }

    private static void size(Section s, String size) {
        List<Float> lengths = new ArrayList<>();
        boolean landscape = false;
        for (String token : Css.tokens(size)) {
            switch (token) {
                case "a3" -> set(s, 841.9f, 1190.6f);
                case "a4" -> set(s, 595.3f, 841.9f);
                case "a5" -> set(s, 419.5f, 595.3f);
                case "b5" -> set(s, 498.9f, 708.7f);
                case "letter" -> set(s, 612f, 792f);
                case "legal" -> set(s, 612f, 1008f);
                case "ledger" -> set(s, 792f, 1224f);
                case "landscape" -> landscape = true;
                default -> {
                    Float pt = Css.length(token, Look.ROOT_SIZE, 0);
                    if (pt != null && pt >= 72 && pt <= 14_400) {
                        lengths.add(pt);
                    }
                }
            }
        }
        if (lengths.size() == 1) {
            set(s, lengths.get(0), lengths.get(0));
        } else if (lengths.size() >= 2) {
            set(s, lengths.get(0), lengths.get(1));
        }
        if (landscape && s.pageWidth < s.pageHeight) {
            set(s, s.pageHeight, s.pageWidth);
        }
    }

    private static void set(Section s, float w, float h) {
        s.pageWidth = w;
        s.pageHeight = h;
    }
}

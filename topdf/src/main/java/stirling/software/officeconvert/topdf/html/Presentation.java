package stirling.software.officeconvert.topdf.html;

import java.util.Locale;
import java.util.Map;

import org.jsoup.nodes.Element;

/** A browser's default look for each tag, then the old presentational attributes; style sheets override both. */
final class Presentation {

    private static final String[] FONT_SIZES = {"10px", "13px", "16px", "18px", "24px", "32px", "48px"};

    private Presentation() {}

    static final String LANG = "-oc-lang";

    static void apply(Element e, Map<String, String> out) {
        tag(e.normalName(), out);
        if (e.hasAttr("lang") || e.hasAttr("xml:lang")) {
            String lang = e.hasAttr("lang") ? e.attr("lang") : e.attr("xml:lang");
            if (lang.length() <= 35) {
                out.put(LANG, lang);
            }
        }
        if ("table".equals(e.normalName()) && e.ownerDocument() != null
                && e.ownerDocument().quirksMode() != org.jsoup.nodes.Document.QuirksMode.noQuirks) {
            // A page without a doctype gets the old table defaults: tables do not inherit text styles
            out.put("text-align", "start");
            out.put("font-size", "medium");
            out.put("font-weight", "normal");
            out.put("font-style", "normal");
            out.put("line-height", "normal");
            out.put("white-space", "normal");
        }
        if ("a".equals(e.normalName()) && e.hasAttr("href")) {
            out.put("color", "#0000ee");
            out.put("text-decoration", "underline");
        }
        attributes(e, out);
    }

    private static void tag(String tag, Map<String, String> out) {
        switch (tag) {
            case "h1" -> heading(out, "2em", "0.67em");
            case "h2" -> heading(out, "1.5em", "0.83em");
            case "h3" -> heading(out, "1.17em", "1em");
            case "h4" -> heading(out, "1em", "1.33em");
            case "h5" -> heading(out, "0.83em", "1.67em");
            case "h6" -> heading(out, "0.67em", "2.33em");
            case "p", "dl" -> vertical(out, "1em");
            case "ul", "ol" -> {
                vertical(out, "1em");
                out.put("padding-inline-start", "40px");
            }
            case "blockquote", "figure" -> {
                vertical(out, "1em");
                out.put("margin-left", "40px");
                out.put("margin-right", "40px");
            }
            case "dd" -> out.put("margin-inline-start", "40px");
            case "pre", "listing", "xmp", "plaintext" -> {
                vertical(out, "1em");
                out.put("font-family", "monospace");
                out.put("white-space", "pre");
            }
            case "code", "kbd", "samp", "tt" -> out.put("font-family", "monospace");
            case "b", "strong" -> out.put("font-weight", "bold");
            case "i", "em", "cite", "dfn", "var", "address" -> out.put("font-style", "italic");
            case "u", "ins" -> out.put("text-decoration", "underline");
            case "s", "strike", "del" -> out.put("text-decoration", "line-through");
            case "small" -> out.put("font-size", "smaller");
            case "big" -> out.put("font-size", "larger");
            case "sub" -> out.put("vertical-align", "sub");
            case "sup" -> out.put("vertical-align", "super");
            case "mark" -> out.put("background-color", "yellow");
            case "th" -> {
                out.put("font-weight", "bold");
                out.put("text-align", "center");
            }
            case "center" -> out.put("text-align", "-webkit-center");
            case "hr" -> vertical(out, "0.5em");
            default -> { }
        }
    }

    private static void heading(Map<String, String> out, String size, String margin) {
        out.put("font-size", size);
        out.put("font-weight", "bold");
        vertical(out, margin);
    }

    private static void vertical(Map<String, String> out, String margin) {
        out.put("margin-top", margin);
        out.put("margin-bottom", margin);
    }

    private static void attributes(Element e, Map<String, String> out) {
        String align = e.attr("align").toLowerCase(Locale.ROOT);
        if (!align.isEmpty() && !"table".equals(e.normalName()) && !"img".equals(e.normalName())) {
            // align on a block centres the tables and blocks inside it too, as <center> does
            boolean block = !"td".equals(e.normalName()) && !"th".equals(e.normalName());
            out.put("text-align", block && "center".equals(align) ? "-webkit-center" : align);
        }
        String dir = e.attr("dir").strip().toLowerCase(Locale.ROOT);
        if (dir.equals("rtl") || dir.equals("ltr")) {
            out.put("direction", dir);
        }
        String valign = e.attr("valign").toLowerCase(Locale.ROOT);
        if (!valign.isEmpty()) {
            out.put("vertical-align", valign);
        }
        if (e.hasAttr("bgcolor")) {
            out.put("background-color", e.attr("bgcolor"));
        }
        for (String dim : new String[] {"width", "height"}) {
            String v = e.attr(dim).strip();
            if (!v.isEmpty()) {
                out.put(dim, v.matches("[0-9.]+") ? v + "px" : v);
            }
        }
        if ("font".equals(e.normalName())) {
            if (e.hasAttr("color")) {
                out.put("color", e.attr("color"));
            }
            if (e.hasAttr("face")) {
                out.put("font-family", e.attr("face"));
            }
            fontSize(e.attr("size"), out);
        }
        if ("body".equals(e.normalName()) && e.hasAttr("text")) {
            out.put("color", e.attr("text"));
        }
    }

    private static void fontSize(String size, Map<String, String> out) {
        String s = size.strip();
        if (s.isEmpty()) {
            return;
        }
        try {
            int n = s.startsWith("+") || s.startsWith("-") ? 3 + Integer.parseInt(s) : Integer.parseInt(s);
            out.put("font-size", FONT_SIZES[Math.max(1, Math.min(7, n)) - 1]);
        } catch (NumberFormatException ignored) {
            // not a size
        }
    }
}

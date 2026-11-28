package stirling.software.officeconvert.topdf.html;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jsoup.nodes.Element;

/** A browser's default look for each tag, then the old presentational attributes; style sheets override both. */
final class Presentation {

    private static final Set<String> BACKGROUND_ELEMENTS = Set.of("body", "table", "tr", "td", "th");

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
            case "p" -> {
                vertical(out, "1em");
                out.put(DEFAULT_BOTTOM, "1");
            }
            case "dl" -> vertical(out, "1em");
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

    static final String DEFAULT_TOP = "-oc-default-margin-top";

    static final String DEFAULT_BOTTOM = "-oc-default-margin-bottom";

    private static void vertical(Map<String, String> out, String margin) {
        out.put("margin-top", margin);
        out.put("margin-bottom", margin);
        out.put(DEFAULT_TOP, "1");
    }

    private static void attributes(Element e, Map<String, String> out) {
        String align = e.attr("align").toLowerCase(Locale.ROOT);
        if (!align.isEmpty() && !"table".equals(e.normalName()) && !"img".equals(e.normalName())) {
            // align centres the tables and blocks inside it too, as <center> does
            out.put("text-align", switch (align) {
                case "center", "middle" -> "-webkit-center";
                case "left", "right" -> "-webkit-" + align;
                default -> align;
            });
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
            putColor(out, "background-color", e.attr("bgcolor"));
        }
        String background = e.attr("background").strip();
        if (!background.isEmpty() && BACKGROUND_ELEMENTS.contains(e.normalName())) {
            out.put("background-image", "picture");
            out.put("background-url", background);
        }
        for (String dim : new String[] {"width", "height"}) {
            String v = dimension(e.attr(dim));
            if (v != null) {
                out.put(dim, v);
            }
        }
        if ("font".equals(e.normalName())) {
            if (e.hasAttr("color")) {
                putColor(out, "color", e.attr("color"));
            }
            if (e.hasAttr("face")) {
                out.put("font-family", e.attr("face"));
            }
            fontSize(e.attr("size"), out);
        }
        if ("td".equals(e.normalName()) || "th".equals(e.normalName())) {
            cellPadding(e, out);
        }
        if ("body".equals(e.normalName()) && e.hasAttr("text")) {
            putColor(out, "color", e.attr("text"));
        }
    }

    static String dimension(String value) {
        String v = value.strip();
        int i = 0;
        while (i < v.length() && i < 12 && Character.isDigit(v.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return null;
        }
        int end = i;
        if (end < v.length() && v.charAt(end) == '.') {
            int f = end + 1;
            while (f < v.length() && f < 20 && Character.isDigit(v.charAt(f))) {
                f++;
            }
            if (f > end + 1) {
                end = f;
            }
        }
        boolean percent = end < v.length() && v.charAt(end) == '%';
        return v.substring(0, end) + (percent ? "%" : "px");
    }

    private static void putColor(Map<String, String> out, String property, String value) {
        String c = legacyColor(value);
        if (c != null) {
            out.put(property, c);
        }
    }

    static String legacyColor(String value) {
        String s = value.strip();
        if (s.isEmpty() || s.equalsIgnoreCase("transparent")) {
            return null;
        }
        Integer named = NamedColors.get(s.toLowerCase(Locale.ROOT));
        if (named != null) {
            return Css.hex(named);
        }
        if (s.length() == 4 && s.charAt(0) == '#' && hexDigits(s.substring(1))) {
            int r = Character.digit(s.charAt(1), 16) * 17;
            int g = Character.digit(s.charAt(2), 16) * 17;
            int b = Character.digit(s.charAt(3), 16) * 17;
            return Css.hex(r << 16 | g << 8 | b);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 128; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            sb.append(cp > 0xFFFF ? "00" : Character.digit(cp, 16) >= 0 && cp < 128 ? String.valueOf((char) cp) : "0");
        }
        if (sb.length() > 128) {
            sb.setLength(128);
        }
        if (s.charAt(0) == '#') {
            sb.deleteCharAt(0);
        }
        while (sb.length() == 0 || sb.length() % 3 != 0) {
            sb.append('0');
        }
        int len = sb.length() / 3;
        String[] parts = {sb.substring(0, len), sb.substring(len, 2 * len), sb.substring(2 * len)};
        if (len > 8) {
            for (int i = 0; i < 3; i++) {
                parts[i] = parts[i].substring(len - 8);
            }
            len = 8;
        }
        while (len > 2 && parts[0].charAt(0) == '0' && parts[1].charAt(0) == '0' && parts[2].charAt(0) == '0') {
            for (int i = 0; i < 3; i++) {
                parts[i] = parts[i].substring(1);
            }
            len--;
        }
        int rgb = 0;
        for (String part : parts) {
            rgb = rgb << 8 | Integer.parseInt(part.substring(0, Math.min(2, len)), 16);
        }
        return Css.hex(rgb);
    }

    private static boolean hexDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0 || s.charAt(i) >= 128) {
                return false;
            }
        }
        return true;
    }

    private static void cellPadding(Element cell, Map<String, String> out) {
        Element table = cell.parent();
        for (int i = 0; i < 2 && table != null && !"table".equals(table.normalName()); i++) {
            table = table.parent();
        }
        String v = table == null || !"table".equals(table.normalName()) ? null : dimension(table.attr("cellpadding"));
        if (v != null && v.endsWith("px")) {
            for (String side : new String[] {"top", "right", "bottom", "left"}) {
                out.put("padding-" + side, v);
            }
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

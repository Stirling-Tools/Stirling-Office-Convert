package stirling.software.officeconvert.html;

import java.util.Locale;
import java.util.Map;

import stirling.software.officeconvert.model.RunStyle;

/** Escaping and the CSS for one run of text. */
final class HtmlText {

    private static final Map<String, String> SAME_WIDTHS = Map.of(
            "calibri", "'Carlito', ",
            "cambria", "'Caladea', ",
            "arial", "'Liberation Sans', 'Arimo', ",
            "helvetica", "'Liberation Sans', 'Arimo', ",
            "arial narrow", "'Liberation Sans Narrow', ",
            "times new roman", "'Liberation Serif', 'Tinos', ",
            "times", "'Liberation Serif', 'Tinos', ",
            "courier new", "'Liberation Mono', 'Cousine', ",
            "courier", "'Liberation Mono', 'Cousine', ");

    private HtmlText() {}

    static void escape(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\u00ad', '\0', '\r', '\ufffe', '\uffff' -> { }
                default -> {
                    if (c >= 0x20 || c == '\t' || c == '\n') {
                        sb.append(c);
                    }
                }
            }
        }
    }

    static String pt(float v) {
        float r = Math.round(v * 10) / 10f;
        return (r == Math.rint(r) ? Long.toString((long) r) : Float.toString(r)) + "pt";
    }

    static String ratio(float v) {
        float r = Math.round(v * 100) / 100f;
        return r == Math.rint(r) ? Long.toString((long) r) : Float.toString(r);
    }

    static String em(float size, float base) {
        return ratio(base > 0 ? size / base : 1f) + "em";
    }

    static String hex(int rgb) {
        return String.format("#%06x", rgb & 0xFFFFFF);
    }

    static String name(String font) {
        return font == null ? "" : font.replaceAll("[\"'\\\\;{}<>]", "");
    }

    static String family(String font) {
        if (font == null || font.isBlank()) {
            return "sans-serif";
        }
        String clean = name(font);
        String lower = clean.toLowerCase(Locale.ROOT);
        return "'" + clean + "', " + SAME_WIDTHS.getOrDefault(lower, "") + generic(lower);
    }

    private static String generic(String n) {
        if (n.contains("mono") || n.contains("courier") || n.contains("consol")) {
            return "monospace";
        }
        if (n.contains("sans") || n.contains("arial") || n.contains("helvet") || n.contains("calibri")) {
            return "sans-serif";
        }
        boolean serif = n.contains("times") || n.contains("serif") || n.contains("georgia") || n.contains("garamond")
                || n.contains("cambria") || n.contains("roman") || n.contains("book");
        return serif ? "serif" : "sans-serif";
    }

    /** The full look of a run, for a style class. */
    static String css(RunStyle s) {
        return css(s, 0);
    }

    static String css(RunStyle s, float base) {
        StringBuilder sb = new StringBuilder();
        sb.append("font-family:").append(family(s.font())).append(";font-size:")
                .append(base > 0 ? em(s.size(), base) : pt(s.size()))
                .append(";font-weight:").append(s.bold() ? "bold" : "normal").append(";font-style:")
                .append(s.italic() ? "italic" : "normal");
        if ((s.rgb() & 0xFFFFFF) != 0) {
            sb.append(";color:").append(hex(s.rgb()));
        }
        return sb.toString();
    }

    /** What differs from {@code base}, for a span; bold, italic, lines and offsets are tags instead. */
    static String diff(RunStyle s, RunStyle base, boolean relative) {
        StringBuilder sb = new StringBuilder();
        if (s.font() != null && !s.font().equals(base.font())) {
            sb.append("font-family:").append(family(s.font())).append(';');
        }
        if (Math.abs(s.size() - base.size()) > 0.05f && s.vertAlign() == 0) {
            sb.append("font-size:").append(relative ? em(s.size(), base.size()) : pt(s.size())).append(';');
        }
        if (!s.bold() && base.bold()) {
            sb.append("font-weight:normal;");
        }
        if (!s.italic() && base.italic()) {
            sb.append("font-style:normal;");
        }
        if ((s.rgb() & 0xFFFFFF) != (base.rgb() & 0xFFFFFF)) {
            sb.append("color:").append(hex(s.rgb())).append(';');
        }
        if (s.highlight() >= 0) {
            sb.append("background-color:").append(hex(s.highlight())).append(';');
        }
        if (s.smallCaps()) {
            sb.append("font-variant:small-caps;");
        }
        if (Math.abs(s.spacing()) >= 0.05f) {
            sb.append("letter-spacing:").append(pt(s.spacing())).append(';');
        }
        return sb.toString();
    }
}

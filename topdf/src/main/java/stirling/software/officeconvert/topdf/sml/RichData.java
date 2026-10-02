package stirling.software.officeconvert.topdf.sml;

import java.util.HashMap;
import java.util.Map;

import stirling.software.officeconvert.topdf.xls.Xml;

/** A cell's HTML-formatted Data (B, I, U, S, Sub, Sup and Font elements) as rich text runs. */
final class RichData {

    private static final int MAX_DEPTH = 32;

    private RichData() {}

    static String runs(Node data, Styles styles) {
        StringBuilder b = new StringBuilder();
        walk(data, new HashMap<>(), styles, b, 0);
        return b.isEmpty() ? "<t/>" : b.toString();
    }

    private static void walk(Node n, Map<String, String> font, Styles styles, StringBuilder out, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        for (Node k : n.kids()) {
            if (k.local().equals("#text")) {
                if (!k.text().isEmpty()) {
                    out.append("<r>").append(font.isEmpty() ? "" : styles.fontRun(withDefaults(font, styles)))
                            .append("<t xml:space=\"preserve\">").append(Xml.text(k.text())).append("</t></r>");
                }
                continue;
            }
            Map<String, String> f = new HashMap<>(font);
            switch (k.local()) {
                case "B" -> f.put("Bold", "1");
                case "I" -> f.put("Italic", "1");
                case "U" -> f.put("Underline", "Single");
                case "S" -> f.put("StrikeThrough", "1");
                case "Sub" -> f.put("VerticalAlign", "Subscript");
                case "Sup" -> f.put("VerticalAlign", "Superscript");
                case "Font" -> {
                    copy(k, "Color", "Color", f);
                    copy(k, "Face", "FontName", f);
                    copy(k, "Size", "Size", f);
                }
                default -> {
                }
            }
            walk(k, f, styles, out, depth + 1);
        }
    }

    private static void copy(Node k, String from, String to, Map<String, String> f) {
        String v = k.attr(from);
        if (v != null) {
            f.put(to, v);
        }
    }

    private static Map<String, String> withDefaults(Map<String, String> f, Styles styles) {
        Map<String, String> out = new HashMap<>(f);
        out.putIfAbsent("FontName", styles.defaultFont);
        out.putIfAbsent("Size", Double.toString(styles.defaultSize));
        return out;
    }
}

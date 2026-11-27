package stirling.software.officeconvert.topdf.html;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class SvgStyle {

    static final Set<String> INHERITED = Set.of("clip-rule", "color", "color-interpolation",
            "color-interpolation-filters", "direction", "dominant-baseline", "fill", "fill-opacity", "fill-rule",
            "font-family", "font-size", "font-size-adjust", "font-stretch", "font-style", "font-variant", "font-weight",
            "image-rendering", "kerning", "letter-spacing", "marker-start", "marker-mid", "marker-end", "paint-order",
            "shape-rendering", "stroke", "stroke-dasharray", "stroke-dashoffset", "stroke-linecap", "stroke-linejoin",
            "stroke-miterlimit", "stroke-opacity", "stroke-width", "text-anchor", "text-rendering", "visibility",
            "word-spacing", "writing-mode", "white-space", "glyph-orientation-vertical", "font-kerning",
            "font-variant-caps", "text-orientation");

    private static final Map<String, String> INITIAL = new HashMap<>();

    static {
        INITIAL.put("fill", "black");
        INITIAL.put("fill-opacity", "1");
        INITIAL.put("fill-rule", "nonzero");
        INITIAL.put("clip-rule", "nonzero");
        INITIAL.put("stroke", "none");
        INITIAL.put("stroke-width", "1");
        INITIAL.put("stroke-opacity", "1");
        INITIAL.put("stroke-linecap", "butt");
        INITIAL.put("stroke-linejoin", "miter");
        INITIAL.put("stroke-miterlimit", "4");
        INITIAL.put("stroke-dasharray", "none");
        INITIAL.put("stroke-dashoffset", "0");
        INITIAL.put("color", "black");
        INITIAL.put("font-family", "Times New Roman");
        INITIAL.put("font-style", "normal");
        INITIAL.put("font-weight", "normal");
        INITIAL.put("font-variant", "normal");
        INITIAL.put("text-anchor", "start");
        INITIAL.put("letter-spacing", "normal");
        INITIAL.put("word-spacing", "normal");
        INITIAL.put("visibility", "visible");
        INITIAL.put("marker-start", "none");
        INITIAL.put("marker-mid", "none");
        INITIAL.put("marker-end", "none");
        INITIAL.put("paint-order", "normal");
        INITIAL.put("direction", "ltr");
        INITIAL.put("writing-mode", "lr-tb");
        INITIAL.put("dominant-baseline", "auto");
        INITIAL.put("white-space", "");
        INITIAL.put("color-interpolation-filters", "linearrgb");
        INITIAL.put("image-rendering", "auto");
        INITIAL.put("shape-rendering", "auto");
    }

    final SvgStyle parent;
    private final Map<String, String> inherited;
    final Map<String, String> own;
    final float fontSize;
    final int color;

    private SvgStyle(SvgStyle parent, Map<String, String> inherited, Map<String, String> own, float fontSize,
            int color) {
        this.parent = parent;
        this.inherited = inherited;
        this.own = own;
        this.fontSize = fontSize;
        this.color = color;
    }

    static SvgStyle root() {
        return new SvgStyle(null, Map.copyOf(INITIAL), Map.of(), 16, 0);
    }

    SvgStyle child(Map<String, String> specified) {
        Map<String, String> next = null;
        for (Map.Entry<String, String> d : specified.entrySet()) {
            String k = d.getKey();
            if (!INHERITED.contains(k)) {
                continue;
            }
            String v = d.getValue();
            String lower = v.toLowerCase(Locale.ROOT);
            if (lower.equals("inherit") || lower.equals("unset")) {
                continue;
            }
            if (lower.equals("initial")) {
                v = INITIAL.getOrDefault(k, "");
            } else if ((k.equals("fill") || k.equals("stroke")) && !validPaint(lower)) {
                continue;
            }
            if (next == null) {
                next = new HashMap<>(inherited);
            }
            next.put(k, v);
        }
        Map<String, String> values = next == null ? inherited : next;
        float size = fontSize;
        String fs = specified.get("font-size");
        if (fs != null) {
            size = fontSize(fs, fontSize);
        }
        int c = color;
        String cv = specified.get("color");
        if (cv != null) {
            Integer parsed = Css.color(cv);
            if (parsed != null && parsed >= 0) {
                c = parsed;
            }
        }
        return new SvgStyle(this, values, specified, size, c);
    }

    private static float fontSize(String v, float parent) {
        String s = v.strip().toLowerCase(Locale.ROOT);
        switch (s) {
            case "xx-small": return 9;
            case "x-small": return 10;
            case "small": return 13;
            case "medium": return 16;
            case "large": return 18;
            case "x-large": return 24;
            case "xx-large": return 32;
            case "larger": return parent * 1.2f;
            case "smaller": return parent / 1.2f;
            case "inherit": return parent;
            default: break;
        }
        if (s.endsWith("%")) {
            return parent * SvgDraw.number(s.substring(0, s.length() - 1), 100) / 100f;
        }
        float px = SvgDraw.length(s, parent, 0, Float.NaN);
        return Float.isFinite(px) && px >= 0 ? px : parent;
    }

    static boolean validPaint(String v) {
        if (v.contains("icc-color")) {
            return false;
        }
        if (v.equals("none") || v.equals("currentcolor") || v.equals("context-fill") || v.equals("context-stroke")) {
            return true;
        }
        if (v.startsWith("url(")) {
            int close = v.indexOf(')');
            if (close < 0) {
                return false;
            }
            String rest = v.substring(close + 1).strip();
            return rest.isEmpty() || !rest.startsWith("url(") && validPaint(rest);
        }
        return Css.color(v) != null;
    }

    String get(String name) {
        String v = inherited.get(name);
        return v == null ? "" : v;
    }

    String own(String name, String initial) {
        String v = own.get(name);
        if (v == null) {
            return initial;
        }
        if (v.equalsIgnoreCase("inherit")) {
            return parent == null ? initial : parent.own(name, initial);
        }
        return v;
    }

    boolean visible() {
        String v = get("visibility").toLowerCase(Locale.ROOT);
        return !v.equals("hidden") && !v.equals("collapse");
    }
}

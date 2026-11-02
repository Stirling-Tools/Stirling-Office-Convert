package stirling.software.officeconvert.topdf.html;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class BoxCss {

    static final int SHADOW_EDGE = 0xD9D9D9;

    private static final float DOUBLE_LEAST = 2.25f;

    private BoxCss() {}

    static float columnGap(Map<String, String> d, Look look) {
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

    static float columnRule(Map<String, String> d) {
        Map<String, String> rule = new HashMap<>();
        for (String part : new String[] {"", "-style", "-width"}) {
            String v = d.get("column-rule" + part);
            if (v != null) {
                rule.put("border-rule" + part, v);
            }
        }
        return border(rule, "rule");
    }

    static boolean avoidsBreaks(Map<String, String> d) {
        String v = d.getOrDefault("break-inside", d.getOrDefault("page-break-inside", "")).strip();
        return v.toLowerCase(Locale.ROOT).startsWith("avoid");
    }

    static boolean light(int rgb) {
        int r = rgb >> 16 & 0xFF;
        int g = rgb >> 8 & 0xFF;
        int b = rgb & 0xFF;
        return 0.299 * r + 0.587 * g + 0.114 * b > 200;
    }

    static boolean hasPadding(Map<String, String> d) {
        for (String side : new String[] {"top", "right", "bottom", "left"}) {
            if (length(d.get("padding-" + side), Look.ROOT_SIZE, 0) > 0) {
                return true;
            }
        }
        return false;
    }

    static Float boxLength(String v, Look look, float room) {
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
    static float padding(String v, float em, float width) {
        return v == null || v.contains("%") ? 0 : length(v, em, width);
    }

    static float corner(Map<String, String> d, Look look, float width, float height) {
        return BoxPaint.corner(d, look.size, width, height);
    }

    static boolean shadowed(Map<String, String> d) {
        String s = d.getOrDefault("box-shadow", "none").strip().toLowerCase(Locale.ROOT);
        return !s.isEmpty() && !s.equals("none") && !s.startsWith("inset");
    }

    static boolean hasEdges(Map<String, String> d) {
        int edges = 0;
        for (String side : new String[] {"top", "bottom", "left", "right"}) {
            edges += border(d, side) > 0 ? 1 : 0;
        }
        return edges >= 2;
    }

    static boolean isPageBreak(String v) {
        if (v == null) {
            return false;
        }
        String t = v.strip().toLowerCase(Locale.ROOT);
        return t.equals("page") || t.equals("always") || t.equals("left") || t.equals("right");
    }

    static String colorOf(String border) {
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
        return border(d, side, false);
    }

    static boolean doubled(Map<String, String> d, String side) {
        return border(d, side, true) >= DOUBLE_LEAST;
    }

    private static float border(Map<String, String> d, String side, boolean onlyDouble) {
        String style = d.get("border-" + side + "-style");
        String all = d.get("border-" + side);
        String width = d.get("border-" + side + "-width");
        if (all != null) {
            for (String token : Css.tokens(all)) {
                String t = token.toLowerCase(Locale.ROOT);
                if (t.equals("none") || t.equals("hidden")) {
                    style = style == null ? "none" : style;
                } else if (Css.length(t, 12, 0) != null || !keyword(t).equals(t)) {
                    width = width == null ? t : width;
                } else if (Css.color(t) == null) {
                    style = style == null ? t : style;
                }
            }
            if (style == null) {
                style = "solid";
            }
        }
        if (style == null || style.equalsIgnoreCase("none") || style.equalsIgnoreCase("hidden")
                || onlyDouble && !style.equalsIgnoreCase("double")) {
            return 0;
        }
        Float w = width == null ? Float.valueOf(style.equalsIgnoreCase("double") ? DOUBLE_LEAST : 0.75f)
                : Css.length(keyword(width), 12, 0);
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

    // A box that scrolls or clips keeps what is too wide inside it, so the page does not grow
    static boolean scrolls(Map<String, String> d) {
        String o = d.getOrDefault("overflow-x", d.getOrDefault("overflow", "visible")).strip().toLowerCase(Locale.ROOT);
        return !o.isEmpty() && !o.startsWith("visible");
    }

    static Float absolute(String v, Look look) {
        return v == null || v.contains("%") || v.contains("vw") ? null : Css.length(v, look.size, 0);
    }

    // Overflow hidden under a fixed or maximum height, or a line clamp, shows only what fits; -1 when it does not clip
    static float clipHeight(Map<String, String> d, Look look) {
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

    static float[] relativeShift(Map<String, String> d, Look look, Flow.Frame frame, Transform turn) {
        float dx = turn.dx();
        float dy = turn.dy();
        if (Positioned.position(d).equals("relative")) {
            Float left = shiftBy(d.get("left"), look, frame.width());
            Float right = shiftBy(d.get("right"), look, frame.width());
            Float top = shiftBy(d.get("top"), look, 0);
            Float bottom = shiftBy(d.get("bottom"), look, 0);
            dx += left != null ? left : right != null ? -right : 0;
            dy += top != null ? top : bottom != null ? -bottom : 0;
        }
        return new float[] {Math.max(-frame.left(), Math.min(dx, frame.width() / 2)), Math.max(-400, Math.min(dy, 400))};
    }

    private static Float shiftBy(String v, Look look, float base) {
        if (v == null || v.strip().equalsIgnoreCase("auto") || base <= 0 && v.contains("%")) {
            return null;
        }
        return Css.length(v, look.size, base);
    }

    static float height(Map<String, String> d, Look look) {
        String h = d.get("height");
        if (h == null || h.contains("%") || h.strip().equalsIgnoreCase("auto")) {
            return 0;
        }
        Float pt = Css.length(h, look.size, 0);
        return pt == null || pt <= 1 ? 0 : Math.min(pt, 14_400);
    }

    static boolean covers(Map<String, String> d) {
        String size = d.getOrDefault("background-size", "auto").strip().toLowerCase(Locale.ROOT);
        boolean[] repeat = Backgrounds.repeat(d.get("background-repeat"));
        return size.equals("cover") || size.equals("100% 100%") || repeat[0] && repeat[1];
    }
}

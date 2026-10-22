package stirling.software.officeconvert.topdf.html;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Media queries as Chrome answers them when it prints: the medium is print, the viewport is the page's text width. */
final class Media {

    private static final Pattern FEATURE = Pattern.compile("\\(\\s*([a-z-]+)\\s*(?::\\s*([^)]*))?\\)");

    private static final Pattern RANGE = Pattern.compile("\\(\\s*(width|height)\\s*(<=|>=|<|>|=)\\s*([^)]*)\\)");

    private Media() {}

    static boolean matches(String query, float viewportPx) {
        String q = query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return true;
        }
        for (String one : Css.splitTopLevel(q, ',')) {
            if (one(one.strip(), viewportPx)) {
                return true;
            }
        }
        return false;
    }

    private static boolean one(String q, float viewportPx) {
        boolean not = q.startsWith("not ");
        String rest = q;
        if (not) {
            rest = q.substring(4).strip();
        } else if (q.startsWith("only ")) {
            rest = q.substring(5).strip();
        }
        String type = rest.startsWith("(") ? "all" : rest.split("[\\s(]", 2)[0];
        boolean result = "all".equals(type) || "print".equals(type);
        Matcher r = RANGE.matcher(rest);
        while (r.find()) {
            result &= range(r.group(1), r.group(2), r.group(3), viewportPx);
        }
        Matcher f = FEATURE.matcher(RANGE.matcher(rest).replaceAll(""));
        while (f.find()) {
            result &= feature(f.group(1), f.group(2) == null ? "" : f.group(2).strip(), viewportPx);
        }
        return not != result;
    }

    private static boolean range(String name, String op, String value, float viewportPx) {
        Float px = px(value);
        if (px == null || !"width".equals(name)) {
            return "height".equals(name);
        }
        return switch (op) {
            case "<=" -> viewportPx <= px;
            case ">=" -> viewportPx >= px;
            case "<" -> viewportPx < px;
            case ">" -> viewportPx > px;
            default -> Math.abs(viewportPx - px) < 0.5f;
        };
    }

    private static boolean feature(String name, String value, float viewportPx) {
        Float px = px(value);
        return switch (name) {
            case "min-width" -> px != null && viewportPx >= px;
            case "max-width" -> px != null && viewportPx <= px;
            case "width" -> px != null && Math.abs(viewportPx - px) < 0.5f;
            case "min-height", "max-height", "height", "min-resolution", "max-resolution", "resolution", "min-color",
                    "color" -> true;
            case "orientation" -> "portrait".equals(value);
            case "prefers-color-scheme" -> "light".equals(value);
            case "prefers-reduced-motion", "prefers-contrast" -> "no-preference".equals(value);
            case "hover", "any-hover", "pointer", "any-pointer", "scripting" -> "none".equals(value);
            default -> false;
        };
    }

    private static Float px(String value) {
        String v = value.strip();
        if (v.endsWith("em")) {
            try {
                return Float.parseFloat(v.substring(0, v.length() - (v.endsWith("rem") ? 3 : 2))) * 16;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        Float pt = Css.length(v, 12, 0);
        return pt == null ? null : pt / 0.75f;
    }
}

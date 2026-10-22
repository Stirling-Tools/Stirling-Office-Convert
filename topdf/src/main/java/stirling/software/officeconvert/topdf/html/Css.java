package stirling.software.officeconvert.topdf.html;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Just enough CSS: rules from a style sheet, declarations, lengths and colours. Nothing is fetched here: a
 * background's url() is kept as {@code background-url} for the caller to resolve, and @import and @font-face are
 * dropped. */
final class Css {

    static final int MAX_RULES = 200_000;

    /** Declarations marked !important are kept under their name with this in front, to win the cascade later. */
    static final String IMPORTANT_MARK = "!";

    private static final Pattern IMPORTANT = Pattern.compile("![ \t]*important[ \t]*$", Pattern.CASE_INSENSITIVE);

    record Rule(String selector, Map<String, String> declarations) {}

    private Css() {}

    static final int MAX_NESTING = 16;

    private static final Pattern MARGIN_BOX = Pattern.compile(
            "@((?:top|bottom)-(?:left|center|right))\\s*\\{([^{}]*)\\}", Pattern.CASE_INSENSITIVE);

    // The size of the root element's font, which rem lengths count in; set while one document converts
    private static final ThreadLocal<Float> ROOT_SIZE = ThreadLocal.withInitial(() -> 12f);

    static void rootSize(float pt) {
        ROOT_SIZE.set(pt);
    }

    static void clearRootSize() {
        ROOT_SIZE.remove();
    }

    static List<Rule> rules(String sheet, List<Rule> into, float viewportPx) {
        return rules(stripComments(sheet), into, viewportPx, 0);
    }

    private static List<Rule> rules(String s, List<Rule> into, float viewportPx, int depth) {
        int i = 0;
        while (i < s.length() && into.size() < MAX_RULES) {
            int open = s.indexOf('{', i);
            if (open < 0) {
                break;
            }
            String head = s.substring(i, open).strip();
            int close = matching(s, open);
            String inner = s.substring(open + 1, close < 0 ? s.length() : close);
            i = close < 0 ? s.length() : close + 1;
            // @import and other bodiless at-rules end at a semicolon
            int semi = head.lastIndexOf(';');
            if (semi >= 0) {
                head = head.substring(semi + 1).strip();
            }
            if (head.startsWith("@")) {
                String at = head.toLowerCase(Locale.ROOT);
                if (depth >= MAX_NESTING) {
                    continue;
                }
                if (at.startsWith("@media")) {
                    if (Media.matches(at.substring(6), viewportPx)) {
                        rules(inner, into, viewportPx, depth + 1);
                    }
                } else if (at.startsWith("@supports") || at.startsWith("@layer") || at.startsWith("@container")
                        || at.startsWith("@scope") || at.startsWith("@document")) {
                    rules(inner, into, viewportPx, depth + 1);
                } else if (at.startsWith("@page")) {
                    // @page, or a named page: "@page wide"; page selectors like :first are left to the default page
                    String name = head.substring(5).strip();
                    if (name.isEmpty() || name.matches("[A-Za-z_][A-Za-z0-9_-]{0,63}")) {
                        Matcher box = MARGIN_BOX.matcher(inner);
                        while (name.isEmpty() && box.find()) {
                            into.add(new Rule("@margin " + box.group(1).toLowerCase(Locale.ROOT),
                                    declarations(box.group(2))));
                        }
                        String own = MARGIN_BOX.matcher(inner).replaceAll("");
                        into.add(new Rule(name.isEmpty() ? "@page" : "@page " + name, declarations(own)));
                    }
                }
                continue;
            }
            if (!head.isEmpty() && head.length() < 1_000) {
                Map<String, String> decls = declarations(inner);
                for (String sel : splitTopLevel(head, ',')) {
                    if (!sel.isBlank()) {
                        into.add(new Rule(sel.strip(), decls));
                    }
                }
            }
        }
        return into;
    }

    static List<String> splitTopLevel(String s, char sep) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int from = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote || c == '\n') {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(' || c == '[') {
                depth++;
            } else if ((c == ')' || c == ']') && depth > 0) {
                depth--;
            } else if (c == sep && depth == 0) {
                out.add(s.substring(from, i));
                from = i + 1;
            }
        }
        out.add(s.substring(from));
        return out;
    }

    private static int matching(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    static String stripComments(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int start = s.indexOf("/*", i);
            if (start < 0) {
                sb.append(s, i, s.length());
                break;
            }
            sb.append(s, i, start);
            int end = s.indexOf("*/", start + 2);
            i = end < 0 ? s.length() : end + 2;
        }
        return sb.toString();
    }

    static Map<String, String> declarations(String block) {
        Map<String, String> out = new LinkedHashMap<>();
        if (block == null) {
            return out;
        }
        for (String d : splitTopLevel(stripComments(block), ';')) {
            int colon = d.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String raw = d.substring(0, colon).strip();
            String name = raw.startsWith("--") ? raw : raw.toLowerCase(Locale.ROOT);
            String value = d.substring(colon + 1).strip();
            Matcher bang = IMPORTANT.matcher(value);
            boolean important = bang.find();
            if (important) {
                value = value.substring(0, bang.start()).strip();
            }
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.contains("expression(")) {
                continue;
            }
            if (lower.contains("url(") || lower.contains("image-set(")) {
                // a picture is never loaded: a background only notes that one was there, anything else is dropped
                if (!name.equals("background") && !name.equals("background-image")) {
                    continue;
                }
                String url = firstUrl(value);
                value = withoutPictures(value);
                out.put(important ? IMPORTANT_MARK + "background-image" : "background-image", "picture");
                out.put(important ? IMPORTANT_MARK + "background-url" : "background-url", url == null ? "none" : url);
            } else if (name.equals("background") || name.equals("background-image")) {
                out.put(important ? IMPORTANT_MARK + "background-url" : "background-url", "none");
            }
            if (SHORTHANDS.contains(name) && lower.contains("var(")) {
                // its parts are only known once var() is filled in, so it is split up after that
                out.put(important ? IMPORTANT_MARK + name : name, value);
                continue;
            }
            if (important) {
                Map<String, String> expanded = new LinkedHashMap<>();
                expand(expanded, name, value);
                expanded.forEach((k, v) -> out.put(IMPORTANT_MARK + k, v));
            } else {
                expand(out, name, value);
            }
        }
        return out;
    }

    static final Set<String> SHORTHANDS = Set.of("margin", "padding", "border", "border-width", "border-color",
            "border-style", "background", "background-image", "font", "list-style", "page-break-before",
            "page-break-after");

    /** Splits the shorthands that held var() into their parts, once var() has been filled in. */
    static void expandShorthands(Map<String, String> d) {
        for (String name : SHORTHANDS) {
            String v = d.get(name);
            if (v != null && !"picture".equals(v)) {
                d.remove(name);
                Map<String, String> parts = new LinkedHashMap<>();
                expand(parts, name, v);
                d.putAll(parts);
            }
        }
    }

    private static void expand(Map<String, String> out, String name, String value) {
        switch (name) {
            case "margin", "padding" -> {
                String[] v = box(value);
                out.put(name + "-top", v[0]);
                out.put(name + "-right", v[1]);
                out.put(name + "-bottom", v[2]);
                out.put(name + "-left", v[3]);
            }
            case "border" -> {
                for (String side : new String[] {"top", "right", "bottom", "left"}) {
                    out.put("border-" + side, value);
                }
            }
            case "border-width", "border-color", "border-style" -> {
                String[] v = box(value);
                String part = name.substring(7);
                String[] sides = {"top", "right", "bottom", "left"};
                for (int i = 0; i < 4; i++) {
                    out.put("border-" + sides[i] + "-" + part, v[i]);
                }
            }
            case "background", "background-image" -> {
                out.put("background-gradient", "none");
                for (String token : tokens(value)) {
                    String t = token.toLowerCase(Locale.ROOT);
                    if (t.contains("gradient(")) {
                        // a gradient is drawn as its first colour, which keeps light text on it readable
                        String first = firstStop(token);
                        if (first != null && !out.containsKey("background-color")) {
                            out.put("background-color", first);
                        }
                        out.put("background-gradient", token);
                    } else if (name.equals("background") && (color(token) != null || t.equals("transparent")
                            || t.equals("none"))) {
                        out.put("background-color", token);
                    }
                }
                if (name.equals("background")) {
                    backgroundLayout(out, value);
                }
            }
            case "font" -> font(out, value);
            case "list-style" -> {
                for (String token : tokens(value)) {
                    out.put("list-style-type", token);
                }
            }
            case "text-decoration-line" -> out.put("text-decoration", value);
            case "page-break-before" -> out.put("break-before", "always".equalsIgnoreCase(value) ? "page" : value);
            case "page-break-after" -> out.put("break-after", "always".equalsIgnoreCase(value) ? "page" : value);
            default -> out.put(name, value);
        }
    }

    private static final Set<String> REPEATS = Set.of("repeat", "no-repeat", "repeat-x", "repeat-y", "space", "round");

    private static final Set<String> PLACES = Set.of("left", "right", "top", "bottom", "center");

    private static void backgroundLayout(Map<String, String> out, String value) {
        List<String> place = new ArrayList<>();
        List<String> size = new ArrayList<>();
        List<String> repeat = new ArrayList<>();
        boolean afterSlash = false;
        for (String token : tokens(value.replace("/", " / "))) {
            String t = token.toLowerCase(Locale.ROOT);
            if (t.equals("/")) {
                afterSlash = true;
            } else if (REPEATS.contains(t)) {
                repeat.add(t);
            } else if (afterSlash && (t.equals("cover") || t.equals("contain") || t.equals("auto")
                    || length(t, 12, 100) != null)) {
                size.add(t);
            } else if (!afterSlash && (PLACES.contains(t) || color(t) == null && length(t, 12, 100) != null)) {
                place.add(t);
            }
        }
        if (!place.isEmpty()) {
            out.put("background-position", String.join(" ", place));
        }
        if (!size.isEmpty()) {
            out.put("background-size", String.join(" ", size));
        }
        if (!repeat.isEmpty()) {
            out.put("background-repeat", String.join(" ", repeat));
        }
    }

    static String firstUrl(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        int at = lower.indexOf("url(");
        if (at < 0 || lower.lastIndexOf("image-set(", at) >= 0) {
            return null;
        }
        int depth = 0;
        int end = -1;
        for (int j = at + 3; j < value.length(); j++) {
            char c = value.charAt(j);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                end = j;
                break;
            }
        }
        if (end < 0) {
            return null;
        }
        String inside = value.substring(at + 4, end).strip();
        if (inside.length() >= 2 && (inside.charAt(0) == '"' || inside.charAt(0) == '\'')
                && inside.charAt(inside.length() - 1) == inside.charAt(0)) {
            inside = inside.substring(1, inside.length() - 1);
        }
        return inside.isBlank() ? null : inside.strip();
    }

    private static String withoutPictures(String value) {
        StringBuilder sb = new StringBuilder();
        String lower = value.toLowerCase(Locale.ROOT);
        int i = 0;
        while (i < value.length()) {
            int at = Math.min(index(lower, "url(", i), index(lower, "image-set(", i));
            if (at == Integer.MAX_VALUE) {
                sb.append(value, i, value.length());
                break;
            }
            sb.append(value, i, at);
            int depth = 0;
            int j = at;
            for (; j < value.length(); j++) {
                char c = value.charAt(j);
                if (c == '(') {
                    depth++;
                } else if (c == ')' && --depth == 0) {
                    break;
                }
            }
            i = j + 1;
        }
        return sb.toString();
    }

    private static int index(String s, String what, int from) {
        int at = s.indexOf(what, from);
        return at < 0 ? Integer.MAX_VALUE : at;
    }

    private static String firstStop(String gradient) {
        int open = gradient.indexOf('(');
        int close = gradient.lastIndexOf(')');
        if (open < 0 || close <= open) {
            return null;
        }
        for (String arg : splitTopLevel(gradient.substring(open + 1, close), ',')) {
            List<String> parts = tokens(arg.strip());
            if (!parts.isEmpty() && color(parts.get(0)) != null && color(parts.get(0)) >= 0) {
                return parts.get(0);
            }
        }
        return null;
    }

    private static void font(Map<String, String> out, String value) {
        List<String> t = tokens(value);
        for (int i = 0; i < t.size(); i++) {
            String token = t.get(i).toLowerCase(Locale.ROOT);
            if (token.equals("bold") || token.equals("bolder") || token.matches("[1-9]00")) {
                out.put("font-weight", token);
            } else if (token.equals("italic") || token.equals("oblique")) {
                out.put("font-style", token);
            } else if (token.equals("small-caps")) {
                out.put("font-variant", token);
            } else if (Character.isDigit(token.charAt(0)) || token.startsWith(".")) {
                String size = token;
                int slash = size.indexOf('/');
                if (slash > 0) {
                    out.put("line-height", size.substring(slash + 1));
                    size = size.substring(0, slash);
                }
                out.put("font-size", size);
                if (i + 1 < t.size()) {
                    out.put("font-family", String.join(" ", t.subList(i + 1, t.size())));
                }
                return;
            }
        }
    }

    static String[] box(String value) {
        List<String> t = tokens(value);
        String a = t.isEmpty() ? "0" : t.get(0);
        String b = t.size() > 1 ? t.get(1) : a;
        String c = t.size() > 2 ? t.get(2) : a;
        String d = t.size() > 3 ? t.get(3) : b;
        return new String[] {a, b, c, d};
    }

    static List<String> tokens(String value) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int paren = 0;
        for (char c : value.toCharArray()) {
            if (c == '(') {
                paren++;
            } else if (c == ')') {
                paren = Math.max(0, paren - 1);
            }
            if (Character.isWhitespace(c) && paren == 0) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    /** A length in points; {@code em} is the font size, {@code percent} what 100% means. Null when not a length. */
    static Float length(String v, float em, float percent) {
        if (v == null) {
            return null;
        }
        String s = v.strip().toLowerCase(Locale.ROOT);
        if (s.equals("0")) {
            return 0f;
        }
        if (s.startsWith("calc(") || s.startsWith("min(") || s.startsWith("max(") || s.startsWith("clamp(")) {
            Float pt = Calc.eval(s, em, percent);
            return pt == null ? null : Math.max(-14_400f, Math.min(14_400f, pt));
        }
        int unit = 0;
        while (unit < s.length() && (Character.isDigit(s.charAt(unit)) || "+-.".indexOf(s.charAt(unit)) >= 0)) {
            unit++;
        }
        if (unit == 0) {
            return null;
        }
        float n;
        try {
            n = Float.parseFloat(s.substring(0, unit));
        } catch (NumberFormatException e) {
            return null;
        }
        float pt = switch (s.substring(unit)) {
            case "px", "" -> n * 0.75f;
            case "pt" -> n;
            case "pc" -> n * 12;
            case "in" -> n * 72;
            case "cm" -> n * 28.3465f;
            case "mm" -> n * 2.83465f;
            case "q" -> n * 0.7087f;
            case "em" -> n * em;
            case "rem" -> n * ROOT_SIZE.get();
            case "ex", "ch" -> n * em / 2;
            case "%" -> n * percent / 100;
            case "vw", "vh", "vmin", "vmax" -> n * percent / 100;
            default -> Float.NaN;
        };
        return Float.isFinite(pt) ? Math.max(-14_400f, Math.min(14_400f, pt)) : null;
    }

    /** An RGB colour, -1 for transparent, null when not a colour. */
    static Integer color(String v) {
        if (v == null) {
            return null;
        }
        String s = v.strip().toLowerCase(Locale.ROOT);
        if (s.equals("transparent")) {
            return -1;
        }
        for (int i = 0; i < 4 && s.startsWith("light-dark("); i++) {
            s = splitTopLevel(s.substring(11, Math.max(11, s.lastIndexOf(')'))), ',').get(0).strip();
        }
        if (s.startsWith("light-dark(")) {
            return null;
        }
        if (s.startsWith("#")) {
            return hex(s.substring(1));
        }
        if (s.startsWith("rgb")) {
            return rgb(s);
        }
        if (s.startsWith("hsl")) {
            return hsl(s);
        }
        return NamedColors.get(s);
    }

    static float opacity(String v) {
        if (v == null) {
            return 1;
        }
        String s = v.strip();
        try {
            float f = s.endsWith("%") ? Float.parseFloat(s.substring(0, s.length() - 1)) / 100 : Float.parseFloat(s);
            return Float.isFinite(f) ? Math.max(0, Math.min(1, f)) : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06x", rgb & 0xFFFFFF);
    }

    static void fade(Map<String, String> d, float alpha) {
        if (alpha >= 0.99f) {
            return;
        }
        for (Map.Entry<String, String> e : d.entrySet()) {
            String key = e.getKey();
            if (key.equals("color") || key.endsWith("-color") || key.startsWith("border-") && !key.endsWith("-width")
                    && !key.endsWith("-style") && !key.endsWith("-radius") || key.equals("column-rule")) {
                e.setValue(faded(e.getValue(), alpha));
            }
        }
    }

    static String faded(String value, float alpha) {
        List<String> out = new ArrayList<>();
        for (String token : tokens(value)) {
            Integer c = color(token);
            out.add(c == null || c < 0 ? token : hex(withAlpha(c, alpha)));
        }
        return String.join(" ", out);
    }

    private static Integer hex(String h) {
        String full = h;
        if (h.length() == 3 || h.length() == 4) {
            StringBuilder sb = new StringBuilder();
            for (char c : h.toCharArray()) {
                sb.append(c).append(c);
            }
            full = sb.toString();
        }
        if (full.length() != 6 && full.length() != 8) {
            return null;
        }
        try {
            int rgb = Integer.parseInt(full.substring(0, 6), 16);
            float alpha = full.length() == 8 ? Integer.parseInt(full.substring(6), 16) / 255f : 1f;
            return withAlpha(rgb, alpha);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // A see-through colour is drawn as it shows on white paper; one nearly invisible is no colour at all
    private static int withAlpha(int rgb, float alpha) {
        if (alpha < 0.1f) {
            return -1;
        }
        if (alpha >= 0.99f) {
            return rgb;
        }
        int out = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int c = rgb >> shift & 0xFF;
            out = out << 8 | Math.round(255 - (255 - c) * alpha);
        }
        return out;
    }

    private static List<String> args(String s) {
        int open = s.indexOf('(');
        int close = s.lastIndexOf(')');
        if (open < 0 || close < open) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String p : s.substring(open + 1, close).replace('/', ',').replace(',', ' ').split(" ")) {
            if (!p.isBlank()) {
                out.add(p.strip());
            }
        }
        return out;
    }

    private static Float number(String p, float percentOf) {
        try {
            return p.endsWith("%") ? Float.parseFloat(p.substring(0, p.length() - 1)) * percentOf / 100
                    : Float.parseFloat(p.replace("deg", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static float alpha(List<String> parts) {
        if (parts.size() < 4) {
            return 1f;
        }
        Float a = number(parts.get(3), 1f);
        return a == null ? 1f : a;
    }

    private static Integer rgb(String s) {
        List<String> parts = args(s);
        if (parts.size() < 3) {
            return null;
        }
        int rgb = 0;
        for (int i = 0; i < 3; i++) {
            Float f = number(parts.get(i), 255f);
            if (f == null) {
                return null;
            }
            rgb = rgb << 8 | Math.max(0, Math.min(255, Math.round(f)));
        }
        return withAlpha(rgb, alpha(parts));
    }

    private static Integer hsl(String s) {
        List<String> parts = args(s);
        if (parts.size() < 3) {
            return null;
        }
        Float h = number(parts.get(0), 360f);
        Float sat = number(parts.get(1), 1f);
        Float light = number(parts.get(2), 1f);
        if (h == null || sat == null || light == null) {
            return null;
        }
        float hue = ((h % 360) + 360) % 360 / 360f;
        float sv = Math.max(0, Math.min(1, sat));
        float lv = Math.max(0, Math.min(1, light));
        float q = lv < 0.5f ? lv * (1 + sv) : lv + sv - lv * sv;
        float p = 2 * lv - q;
        int rgb = 0;
        for (float t : new float[] {hue + 1f / 3, hue, hue - 1f / 3}) {
            rgb = rgb << 8 | Math.round(255 * channel(p, q, t));
        }
        return withAlpha(rgb, alpha(parts));
    }

    private static float channel(float p, float q, float t) {
        float u = t;
        if (u < 0) {
            u += 1;
        } else if (u > 1) {
            u -= 1;
        }
        if (u < 1f / 6) {
            return p + (q - p) * 6 * u;
        }
        if (u < 0.5f) {
            return q;
        }
        if (u < 2f / 3) {
            return p + (q - p) * (2f / 3 - u) * 6;
        }
        return p;
    }
}

package stirling.software.officeconvert.topdf.html;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.jsoup.select.Selector;

final class SvgCss {

    static final int MAX_RULES = 20_000;

    static final int MAX_MATCHES = 2_000_000;

    static final long MAX_SELECTOR_WORK = 50_000_000L;

    static final Set<String> PRESENTATION = Set.of("alignment-baseline", "baseline-shift", "clip", "clip-path",
            "clip-rule", "color", "color-interpolation", "color-interpolation-filters", "direction", "display",
            "dominant-baseline", "fill", "fill-opacity", "fill-rule", "filter", "flood-color", "flood-opacity", "font",
            "font-family", "font-size", "font-size-adjust", "font-stretch", "font-style", "font-variant", "font-weight",
            "image-rendering", "kerning", "letter-spacing", "lighting-color", "marker-start", "marker-mid", "marker-end",
            "mask", "opacity", "overflow", "shape-rendering", "stop-color", "stop-opacity", "stroke", "stroke-dasharray",
            "stroke-dashoffset", "stroke-linecap", "stroke-linejoin", "stroke-miterlimit", "stroke-opacity",
            "stroke-width", "text-anchor", "text-decoration", "text-rendering", "unicode-bidi", "visibility",
            "word-spacing", "writing-mode", "paint-order", "mask-type",
            "transform-origin", "white-space", "glyph-orientation-vertical", "font-variant-caps",
            "text-orientation", "vector-effect");

    record Rule(String selector, Map<String, String> declarations, int specificity, int order) {}

    private final Map<Element, Map<String, String>> sheet = new IdentityHashMap<>();
    final List<Map<String, String>> fontFaces = new ArrayList<>();

    SvgCss(Element root, List<String> sheets) {
        List<Rule> rules = new ArrayList<>();
        for (String s : sheets) {
            parse(Css.stripComments(s), rules, 0);
        }
        rules.sort((a, b) -> a.specificity() != b.specificity() ? Integer.compare(a.specificity(), b.specificity())
                : Integer.compare(a.order(), b.order()));
        int matched = 0;
        long elements = root.getAllElements().size();
        long cost = 0;
        for (Rule r : rules) {
            cost += elements;
            if (cost > MAX_SELECTOR_WORK) {
                return;
            }
            String sel = r.selector().toLowerCase(java.util.Locale.ROOT);
            if (sel.contains(":matches") || sel.contains(":contains") || sel.contains(":has") || sel.contains("~=")
                    || sel.contains(":containsdata") || sel.contains(":containswholetext")) {
                continue;
            }
            Elements found;
            try {
                found = Selector.select(r.selector(), root);
            } catch (RuntimeException e) {
                continue;
            }
            for (Element e : found) {
                if (++matched > MAX_MATCHES) {
                    return;
                }
                sheet.computeIfAbsent(e, k -> new LinkedHashMap<>()).putAll(r.declarations());
            }
        }
    }

    private void parse(String s, List<Rule> into, int depth) {
        int i = 0;
        while (i < s.length() && into.size() < MAX_RULES) {
            int open = s.indexOf('{', i);
            if (open < 0) {
                return;
            }
            String head = s.substring(i, open).strip();
            int close = matching(s, open);
            String inner = s.substring(open + 1, close < 0 ? s.length() : close);
            i = close < 0 ? s.length() : close + 1;
            int semi = head.lastIndexOf(';');
            if (semi >= 0) {
                head = head.substring(semi + 1).strip();
            }
            if (head.startsWith("@")) {
                String at = head.toLowerCase(Locale.ROOT);
                if (at.equals("@font-face")) {
                    if (fontFaces.size() < WebFonts.MAX_FACES) {
                        fontFaces.add(Css.rawDeclarations(inner));
                    }
                } else if (depth < 8 && (at.startsWith("@media") && media(at.substring(6))
                        || at.startsWith("@supports") || at.startsWith("@layer"))) {
                    parse(inner, into, depth + 1);
                }
                continue;
            }
            if (head.isEmpty() || head.length() > 2000) {
                continue;
            }
            Map<String, String> decls = declarations(inner);
            for (String sel : Css.splitTopLevel(head, ',')) {
                String t = sel.strip();
                if (!t.isEmpty() && !t.contains(":hover") && !t.contains(":focus") && !t.contains(":active")
                        && !t.contains(":visited") && !t.contains("::")) {
                    into.add(new Rule(t, decls, specificity(t), into.size()));
                }
            }
        }
    }

    private static boolean media(String query) {
        String q = query.strip();
        return q.isEmpty() || q.contains("all") || q.contains("screen") || q.startsWith("(") && !q.contains("print");
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

    static int specificity(String selector) {
        int ids = 0;
        int classes = 0;
        int tags = 0;
        boolean start = true;
        for (int i = 0; i < selector.length(); i++) {
            char c = selector.charAt(i);
            if (c == '#') {
                ids++;
                start = false;
            } else if (c == '.' || c == '[' || c == ':') {
                classes++;
                start = false;
            } else if (Character.isLetter(c) && start) {
                tags++;
                start = false;
            } else if (c == ' ' || c == '>' || c == '+' || c == '~') {
                start = true;
            } else if (c == '*') {
                start = false;
            }
        }
        return ids * 10_000 + classes * 100 + tags;
    }

    static Map<String, String> declarations(String block) {
        Map<String, String> out = new LinkedHashMap<>();
        if (block == null) {
            return out;
        }
        for (String d : Css.splitTopLevel(Css.stripComments(block), ';')) {
            int colon = d.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = d.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = d.substring(colon + 1).strip();
            boolean important = false;
            int bang = value.lastIndexOf('!');
            if (bang >= 0 && value.substring(bang + 1).strip().equalsIgnoreCase("important")) {
                value = value.substring(0, bang).strip();
                important = true;
            }
            if (value.isEmpty() || value.toLowerCase(Locale.ROOT).contains("expression(")) {
                continue;
            }
            Map<String, String> expanded = new HashMap<>();
            expand(expanded, name, value);
            for (Map.Entry<String, String> e : expanded.entrySet()) {
                out.put(important ? "!" + e.getKey() : e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static void expand(Map<String, String> out, String name, String value) {
        if (name.equals("marker")) {
            out.put("marker-start", value);
            out.put("marker-mid", value);
            out.put("marker-end", value);
        } else if (name.equals("font")) {
            font(out, value);
        } else {
            out.put(name, value);
        }
    }

    private static void font(Map<String, String> out, String value) {
        List<String> parts = Css.tokens(value);
        if (parts.size() < 2) {
            return;
        }
        out.put("font-style", "normal");
        out.put("font-weight", "normal");
        out.put("font-variant", "normal");
        out.put("font-stretch", "normal");
        out.put("font-kerning", "auto");
        int i = 0;
        while (i < parts.size()) {
            String p = parts.get(i).toLowerCase(Locale.ROOT);
            if (p.equals("italic") || p.equals("oblique")) {
                out.put("font-style", p);
            } else if (p.equals("bold") || p.equals("bolder") || p.equals("lighter") || p.matches("[1-9]00")) {
                out.put("font-weight", p);
            } else if (p.equals("small-caps")) {
                out.put("font-variant", p);
            } else if (!p.equals("normal")) {
                break;
            }
            i++;
        }
        if (i < parts.size()) {
            String size = parts.get(i);
            int slash = size.indexOf('/');
            out.put("font-size", slash >= 0 ? size.substring(0, slash) : size);
            i++;
            if (i < parts.size() && parts.get(i).startsWith("/")) {
                i++;
            }
            if (i < parts.size()) {
                out.put("font-family", String.join(" ", parts.subList(i, parts.size())));
            }
        }
    }

    Map<String, String> specified(Element e) {
        Map<String, String> out = new HashMap<>();
        for (org.jsoup.nodes.Attribute a : e.attributes()) {
            String key = a.getKey().toLowerCase(Locale.ROOT);
            int colon = key.indexOf(':');
            if (colon >= 0 && !key.startsWith("xml:")) {
                continue;
            }
            if (PRESENTATION.contains(key) || key.equals("transform")) {
                out.put(key, a.getValue().strip());
            }
        }
        Map<String, String> important = new HashMap<>();
        Map<String, String> fromSheet = sheet.get(e);
        if (fromSheet != null) {
            take(fromSheet, out, important);
        }
        String style = SvgDoc.attr(e, "style");
        if (!style.isEmpty()) {
            take(declarations(style), out, important);
        }
        out.putAll(important);
        return out;
    }

    private static void take(Map<String, String> from, Map<String, String> out, Map<String, String> important) {
        for (Map.Entry<String, String> d : from.entrySet()) {
            if (d.getKey().startsWith("!")) {
                important.put(d.getKey().substring(1), d.getValue());
            } else {
                out.put(d.getKey(), d.getValue());
            }
        }
    }
}

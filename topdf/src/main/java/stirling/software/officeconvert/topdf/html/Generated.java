package stirling.software.officeconvert.topdf.html;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Evaluator;

final class Generated {

    static final int MAX_RULES = 2_000;

    static final String LEADER = "oc-leader";

    static final String BOOKMARK_PREFIX = "oc-target-";

    private static final int MAX_BOXES = 20_000;

    private static final long MAX_WORK = 5_000_000L;

    private static final int MAX_TEXT = 2_000;

    private static final Set<String> EMPTY = Set.of("img", "br", "hr", "input", "textarea", "select", "iframe",
            "video", "audio", "canvas", "svg", "object", "embed", "meta", "link", "style", "script", "head", "title",
            "option", "wbr", LEADER);

    record Rule(Evaluator evaluator, String which, int specificity, int order, Map<String, String> declarations) {}

    private record Pending(Element element, Map<String, String> declarations, boolean first) {}

    private Generated() {}

    static Set<String> apply(Document doc, Cascade cascade, Map<String, Integer> pages) {
        List<Rule> rules = cascade.pseudoRules();
        Set<String> targets = new LinkedHashSet<>();
        if (rules.isEmpty() || doc.body() == null) {
            return targets;
        }
        List<Rule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparingInt(Rule::specificity).thenComparingInt(Rule::order));
        long work = 0;
        int boxes = 0;
        List<Pending> pending = new ArrayList<>();
        for (Element e : doc.body().getAllElements()) {
            work += sorted.size();
            if (work > MAX_WORK || boxes >= MAX_BOXES) {
                break;
            }
            if (EMPTY.contains(e.normalName()) || e.hasAttr("data-oc-generated")) {
                continue;
            }
            Map<String, String> before = null;
            Map<String, String> after = null;
            for (Rule r : sorted) {
                boolean hit;
                try {
                    hit = r.evaluator().matches(doc, e);
                } catch (RuntimeException ex) {
                    hit = false;
                }
                if (!hit) {
                    continue;
                }
                if (r.which().equals("before")) {
                    before = merged(before, r.declarations());
                } else {
                    after = merged(after, r.declarations());
                }
            }
            if (before != null) {
                pending.add(new Pending(e, before, true));
                boxes++;
            }
            if (after != null) {
                pending.add(new Pending(e, after, false));
                boxes++;
            }
        }
        if (pending.isEmpty()) {
            return targets;
        }
        for (Element e : doc.getAllElements()) {
            cascade.match(e);
        }
        for (Pending p : pending) {
            insert(p.element(), p.declarations(), p.first(), cascade, pages, targets);
        }
        return targets;
    }

    private static Map<String, String> merged(Map<String, String> into, Map<String, String> declarations) {
        Map<String, String> out = into == null ? new HashMap<>() : into;
        for (Map.Entry<String, String> d : declarations.entrySet()) {
            String key = d.getKey();
            if (key.startsWith(Css.IMPORTANT_MARK)) {
                out.put(key, d.getValue());
                out.remove(key.substring(Css.IMPORTANT_MARK.length()));
            } else if (!out.containsKey(Css.IMPORTANT_MARK + key)) {
                out.put(key, d.getValue());
            }
        }
        return out;
    }

    private static boolean insert(Element e, Map<String, String> d, boolean first, Cascade cascade,
            Map<String, Integer> pages, Set<String> targets) {
        String content = d.containsKey(Css.IMPORTANT_MARK + "content") ? d.get(Css.IMPORTANT_MARK + "content")
                : d.get("content");
        String display = d.getOrDefault("display", "").strip().toLowerCase(Locale.ROOT);
        if (content == null || display.equals("none")) {
            return false;
        }
        Element span = new Element("span").attr("data-oc-generated", first ? "before" : "after");
        if (!content(span, content, e, pages, targets)) {
            return false;
        }
        Map<String, String> own = new HashMap<>(d);
        own.remove("content");
        own.remove(Css.IMPORTANT_MARK + "content");
        cascade.generated(span, own);
        if (first) {
            e.prependChild(span);
        } else {
            e.appendChild(span);
        }
        return true;
    }

    static boolean content(Element span, String value, Element owner, Map<String, Integer> pages, Set<String> targets) {
        String v = value.strip();
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.equals("none") || lower.equals("normal")) {
            return false;
        }
        StringBuilder text = new StringBuilder();
        int i = 0;
        boolean any = false;
        while (i < v.length() && text.length() < MAX_TEXT) {
            char c = v.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '"' || c == '\'') {
                int end = stringEnd(v, i);
                text.append(unescape(v.substring(i + 1, Math.max(i + 1, end - 1))));
                i = end;
            } else {
                int start = i;
                while (i < v.length() && (Character.isLetterOrDigit(v.charAt(i)) || v.charAt(i) == '-')) {
                    i++;
                }
                String name = v.substring(start, i).toLowerCase(Locale.ROOT);
                if (i == start) {
                    i++;
                    continue;
                }
                String args = null;
                if (i < v.length() && v.charAt(i) == '(') {
                    int close = close(v, i);
                    args = v.substring(i + 1, Math.max(i + 1, close - 1)).strip();
                    i = close;
                }
                switch (name) {
                    case "open-quote" -> text.append('“');
                    case "close-quote" -> text.append('”');
                    case "attr" -> text.append(args == null ? "" : owner.attr(args.strip()));
                    case "leader" -> {
                        if (args != null) {
                            flushText(span, text);
                            span.appendChild(new Element(LEADER).attr("data-char", String.valueOf(leader(args))));
                            any = true;
                        }
                    }
                    case "target-counter" -> {
                        String page = target(args, owner, pages, targets);
                        if (page != null) {
                            text.append(page);
                        }
                    }
                    default -> { }
                }
            }
        }
        if (text.length() > 0) {
            any = true;
        }
        flushText(span, text);
        return any;
    }

    private static void flushText(Element span, StringBuilder text) {
        if (text.length() > 0) {
            span.appendChild(new TextNode(text.toString()));
            text.setLength(0);
        }
    }

    private static String target(String args, Element owner, Map<String, Integer> pages, Set<String> targets) {
        if (args == null) {
            return null;
        }
        List<String> parts = Css.splitTopLevel(args, ',');
        if (parts.size() < 2 || !parts.get(1).strip().equalsIgnoreCase("page")) {
            return null;
        }
        String ref = parts.get(0).strip();
        String href;
        if (ref.regionMatches(true, 0, "attr(", 0, 5) && ref.endsWith(")")) {
            href = owner.attr(ref.substring(5, ref.length() - 1).strip());
        } else if (ref.regionMatches(true, 0, "url(", 0, 4) || ref.startsWith("\"") || ref.startsWith("'")) {
            href = ref.startsWith("url(") ? Css.firstUrl(ref) : ref.substring(1, Math.max(1, ref.length() - 1));
        } else {
            return null;
        }
        if (href == null) {
            return null;
        }
        int hash = href.indexOf('#');
        String id = hash < 0 ? null : href.substring(hash + 1);
        if (id == null || id.isEmpty() || id.length() > 200) {
            return null;
        }
        targets.add(id);
        Integer page = pages.get(id);
        int n = page == null ? 0 : page;
        String style = parts.size() > 2 ? parts.get(2).strip().toLowerCase(Locale.ROOT) : "decimal";
        return switch (style) {
            case "lower-roman" -> roman(n).toLowerCase(Locale.ROOT);
            case "upper-roman" -> roman(n);
            default -> Integer.toString(n);
        };
    }

    private static String roman(int n) {
        if (n <= 0 || n >= 4000) {
            return Integer.toString(n);
        }
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] marks = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < values.length; k++) {
            while (n >= values[k]) {
                sb.append(marks[k]);
                n -= values[k];
            }
        }
        return sb.toString();
    }

    private static char leader(String args) {
        String a = args.strip();
        if (a.length() >= 2 && (a.charAt(0) == '"' || a.charAt(0) == '\'')) {
            String s = unescape(a.substring(1, a.length() - 1)).strip();
            char c = s.isEmpty() ? ' ' : s.charAt(0);
            return c == '-' || c == '_' || c == '·' || c == ' ' ? c : '.';
        }
        return switch (a.toLowerCase(Locale.ROOT)) {
            case "solid" -> '_';
            case "space" -> ' ';
            default -> '.';
        };
    }

    private static int stringEnd(String v, int open) {
        char q = v.charAt(open);
        int i = open + 1;
        while (i < v.length()) {
            char c = v.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == q) {
                return i + 1;
            } else {
                i++;
            }
        }
        return v.length();
    }

    private static int close(String v, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < v.length(); i++) {
            char c = v.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i + 1;
            }
        }
        return v.length();
    }

    static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            int j = i + 1;
            while (j < s.length() && j < i + 7 && Character.digit(s.charAt(j), 16) >= 0) {
                j++;
            }
            if (j > i + 1) {
                int cp = Integer.parseInt(s.substring(i + 1, j), 16);
                out.appendCodePoint(cp == 0 || cp > 0x10FFFF || cp >= 0xD800 && cp <= 0xDFFF ? 0xFFFD : cp);
                if (j < s.length() && s.charAt(j) == ' ') {
                    j++;
                }
                i = j - 1;
            } else {
                out.append(s.charAt(j));
                i = j;
            }
        }
        return out.toString();
    }
}

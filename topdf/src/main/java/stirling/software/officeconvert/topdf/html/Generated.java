package stirling.software.officeconvert.topdf.html;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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

    private record Pending(Element element, Element span, Map<String, String> declarations, boolean first) {}

    private Generated() {}

    record Result(Set<String> targets, Map<Element, String> marks, Map<String, String[]> strings) {
        static final Result NONE = new Result(Set.of(), Map.of(), Map.of());
    }

    static Result apply(Document doc, Cascade cascade, Map<String, Integer> pages) {
        List<Rule> rules = cascade.pseudoRules();
        boolean strings = cascade.setsStrings();
        if (rules.isEmpty() && !strings || doc.body() == null) {
            return Result.NONE;
        }
        List<Rule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparingInt(Rule::specificity).thenComparingInt(Rule::order));
        boolean counting = sorted.stream().anyMatch(r -> counts(r.declarations()));
        Walk walk = new Walk(doc, cascade, sorted, pages, counting, strings);
        Deque<Element> stack = new ArrayDeque<>();
        Deque<Integer> next = new ArrayDeque<>();
        stack.push(doc.body());
        next.push(0);
        walk.enter(doc.body());
        while (!stack.isEmpty() && !walk.exhausted()) {
            Element top = stack.peek();
            int i = next.pop();
            if (i < top.childrenSize()) {
                next.push(i + 1);
                Element child = top.child(i);
                walk.enter(child);
                stack.push(child);
                next.push(0);
            } else {
                walk.exit(stack.pop());
            }
        }
        if (walk.pending.isEmpty() && walk.marks.isEmpty()) {
            return new Result(walk.targets, Map.of(), Map.of());
        }
        for (Element e : doc.getAllElements()) {
            cascade.match(e);
        }
        for (Pending p : walk.pending) {
            Map<String, String> own = new HashMap<>(p.declarations());
            own.remove("content");
            own.remove(Css.IMPORTANT_MARK + "content");
            cascade.generated(p.span(), own);
            if (p.first()) {
                p.element().prependChild(p.span());
            } else {
                p.element().appendChild(p.span());
            }
        }
        return new Result(walk.targets, walk.marks, walk.stringValues);
    }

    private static final class Walk {
        final Document doc;
        final Cascade cascade;
        final List<Rule> rules;
        final Map<String, Integer> pages;
        final boolean counting;
        final boolean strings;
        final Counters counters = new Counters();
        final Set<String> targets = new LinkedHashSet<>();
        final List<Pending> pending = new ArrayList<>();
        final Map<Element, String> marks = new IdentityHashMap<>();
        final Map<String, String[]> stringValues = new LinkedHashMap<>();
        final Map<Element, Map<String, String>> afters = new IdentityHashMap<>();
        final Map<Element, Map<String, String>> declared = new IdentityHashMap<>();
        long work;
        int boxes;

        Walk(Document doc, Cascade cascade, List<Rule> rules, Map<String, Integer> pages, boolean counting,
                boolean strings) {
            this.doc = doc;
            this.cascade = cascade;
            this.rules = rules;
            this.pages = pages;
            this.counting = counting;
            this.strings = strings;
        }

        boolean exhausted() {
            return work > MAX_WORK || boxes >= MAX_BOXES;
        }

        void enter(Element e) {
            work += rules.size() + 1;
            Element scope = e.parent() == null ? e : e.parent();
            if (counting || strings) {
                Map<String, String> d = cascade.declared(e);
                if (counting) {
                    counters.reset(d.get("counter-reset"), scope);
                    counters.increment(d.get("counter-increment"), scope);
                }
                if (strings && d.containsKey("string-set")) {
                    declared.put(e, d);
                }
            }
            if (rules.isEmpty() || EMPTY.contains(e.normalName()) || e.hasAttr("data-oc-generated")) {
                return;
            }
            Map<String, String> before = null;
            Map<String, String> after = null;
            for (Rule r : rules) {
                boolean hit;
                try {
                    hit = r.evaluator().matches(doc, e);
                } catch (RuntimeException ex) {
                    hit = false;
                }
                if (hit) {
                    if (r.which().equals("before")) {
                        before = merged(before, r.declarations());
                    } else {
                        after = merged(after, r.declarations());
                    }
                }
            }
            if (before != null) {
                Element span = build(before, true, e, pages, targets, counters);
                if (span != null) {
                    pending.add(new Pending(e, span, before, true));
                    boxes++;
                }
            }
            if (after != null) {
                afters.put(e, after);
            }
        }

        void exit(Element e) {
            Map<String, String> after = afters.remove(e);
            Element afterSpan = null;
            if (after != null) {
                afterSpan = build(after, false, e, pages, targets, counters);
                if (afterSpan != null) {
                    pending.add(new Pending(e, afterSpan, after, false));
                    boxes++;
                }
            }
            Map<String, String> d = declared.remove(e);
            if (d != null) {
                stringSet(e, d.get("string-set"), afterSpan);
            }
            counters.close(e);
        }

        void stringSet(Element e, String value, Element afterSpan) {
            if (value == null || value.strip().equalsIgnoreCase("none")) {
                return;
            }
            for (String part : Css.splitTopLevel(value, ',')) {
                String p = part.strip();
                int space = p.indexOf(' ');
                if (space <= 0 || stringValues.size() >= MAX_BOXES) {
                    continue;
                }
                String name = p.substring(0, space);
                String text = stringValue(e, p.substring(space + 1), afterSpan);
                String mark = marks.computeIfAbsent(e, k -> "oc-mark-" + marks.size());
                String key = mark + "#" + name;
                stringValues.put(key, new String[] {mark, name, text});
            }
        }

        String stringValue(Element e, String spec, Element afterSpan) {
            StringBuilder out = new StringBuilder();
            String v = spec.strip();
            int i = 0;
            while (i < v.length() && out.length() < MAX_TEXT) {
                char c = v.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (c == '"' || c == '\'') {
                    int end = stringEnd(v, i);
                    out.append(unescape(v.substring(i + 1, Math.max(i + 1, end - 1))));
                    i = end;
                } else {
                    int start = i;
                    while (i < v.length() && (Character.isLetterOrDigit(v.charAt(i)) || v.charAt(i) == '-')) {
                        i++;
                    }
                    if (i == start) {
                        i++;
                        continue;
                    }
                    String name = v.substring(start, i).toLowerCase(Locale.ROOT);
                    String args = "";
                    if (i < v.length() && v.charAt(i) == '(') {
                        int close = close(v, i);
                        args = v.substring(i + 1, Math.max(i + 1, close - 1)).strip().toLowerCase(Locale.ROOT);
                        i = close;
                    }
                    String own = e.text();
                    switch (name) {
                        case "content" -> {
                            if (args.equals("after")) {
                                out.append(afterSpan == null ? "" : afterSpan.text());
                            } else if (args.equals("before")) {
                                for (Pending p : pending) {
                                    if (p.element() == e && p.first()) {
                                        out.append(p.span().text());
                                    }
                                }
                            } else if (args.equals("first-letter")) {
                                out.append(own.isEmpty() ? "" : own.substring(0, own.offsetByCodePoints(0, 1)));
                            } else {
                                out.append(own);
                            }
                        }
                        case "attr" -> out.append(e.attr(args));
                        case "counter" -> out.append(counters.value(args.split(",")[0].strip()));
                        default -> { }
                    }
                }
            }
            return out.length() > MAX_TEXT ? out.substring(0, MAX_TEXT) : out.toString();
        }
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

    private static boolean counts(Map<String, String> d) {
        String c = d.getOrDefault("content", d.getOrDefault(Css.IMPORTANT_MARK + "content", ""));
        return c.toLowerCase(Locale.ROOT).contains("counter");
    }

    private static Element build(Map<String, String> d, boolean first, Element e, Map<String, Integer> pages,
            Set<String> targets, Counters counters) {
        String increment = d.containsKey(Css.IMPORTANT_MARK + "counter-increment")
                ? d.get(Css.IMPORTANT_MARK + "counter-increment") : d.get("counter-increment");
        String reset = d.containsKey(Css.IMPORTANT_MARK + "counter-reset")
                ? d.get(Css.IMPORTANT_MARK + "counter-reset") : d.get("counter-reset");
        counters.reset(reset, e);
        counters.increment(increment, e);
        String content = d.containsKey(Css.IMPORTANT_MARK + "content") ? d.get(Css.IMPORTANT_MARK + "content")
                : d.get("content");
        String display = d.getOrDefault("display", "").strip().toLowerCase(Locale.ROOT);
        if (content == null || display.equals("none")) {
            return null;
        }
        Element span = new Element("span").attr("data-oc-generated", first ? "before" : "after");
        return content(span, content, e, pages, targets, counters) ? span : null;
    }

    static boolean content(Element span, String value, Element owner, Map<String, Integer> pages, Set<String> targets,
            Counters counters) {
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
                    case "counter", "counters" -> {
                        List<String> parts = args == null ? List.of() : Css.splitTopLevel(args, ',');
                        if (!parts.isEmpty()) {
                            String style = parts.size() > (name.equals("counter") ? 1 : 2)
                                    ? parts.get(name.equals("counter") ? 1 : 2).strip() : "decimal";
                            String counter = parts.get(0).strip();
                            if (name.equals("counter")) {
                                text.append(formatted(counters.value(counter), style));
                            } else {
                                String separator = parts.size() > 1 ? unquoted(parts.get(1).strip()) : ".";
                                List<String> each = new ArrayList<>();
                                counters.values(counter).forEach(n -> each.add(formatted(n, style)));
                                text.append(String.join(separator, each));
                            }
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
        return formatted(n, parts.size() > 2 ? parts.get(2).strip() : "decimal");
    }

    private static String formatted(int n, String style) {
        return switch (style.toLowerCase(Locale.ROOT)) {
            case "lower-roman" -> roman(n).toLowerCase(Locale.ROOT);
            case "upper-roman" -> roman(n);
            case "lower-alpha", "lower-latin" -> alpha(n).toLowerCase(Locale.ROOT);
            case "upper-alpha", "upper-latin" -> alpha(n);
            case "decimal-leading-zero" -> n >= 0 && n < 10 ? "0" + n : Integer.toString(n);
            case "none" -> "";
            default -> Integer.toString(n);
        };
    }

    private static String alpha(int n) {
        if (n <= 0) {
            return Integer.toString(n);
        }
        StringBuilder sb = new StringBuilder();
        for (int v = n; v > 0; v = (v - 1) / 26) {
            sb.append((char) ('A' + (v - 1) % 26));
        }
        return sb.reverse().toString();
    }

    private static String unquoted(String s) {
        return s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'') && s.charAt(s.length() - 1) == s.charAt(0)
                ? unescape(s.substring(1, s.length() - 1)) : s;
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

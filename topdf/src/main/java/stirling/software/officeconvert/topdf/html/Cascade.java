package stirling.software.officeconvert.topdf.html;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Evaluator;
import org.jsoup.select.QueryParser;

/** The declarations each element gets from the document's style sheets, then its own style attribute. Rules are
 * indexed by the id, class or tag their last step needs, as browsers do, so an element is only tested against rules
 * that can match it. */
final class Cascade {

    // Selector tests past this many for one document stop matching: the rest of the page keeps the defaults
    private static final long MAX_MATCH_WORK = 40_000_000L;

    private static final Pattern ID = Pattern.compile("#((?:[_a-zA-Z0-9-]|\\\\.)+)");
    private static final Pattern CLASS = Pattern.compile("[.]((?:[_a-zA-Z0-9-]|\\\\.)+)");
    private static final Pattern TAG = Pattern.compile("^([a-zA-Z][a-zA-Z0-9-]*)");

    private record Hit(int specificity, int order, Map<String, String> declarations) {}

    private record Compiled(Evaluator evaluator, Hit hit) {}

    private final Document doc;
    private final Map<String, List<Compiled>> byId = new HashMap<>();
    private final Map<String, List<Compiled>> byClass = new HashMap<>();
    private final Map<String, List<Compiled>> byTag = new HashMap<>();
    private final List<Compiled> anyElement = new ArrayList<>();
    private final Map<Element, List<Hit>> hits = new IdentityHashMap<>();
    private final Map<String, String> page = new HashMap<>();
    private final Map<String, Map<String, String>> margins = new HashMap<>();
    private final Map<String, Map<String, String>> importantMargins = new HashMap<>();
    private final Map<String, Map<String, Map<String, String>>> sidedMargins = new HashMap<>();

    private static final Pattern SIDED_MARGIN = Pattern.compile("@margin-(first|left|right) (\\S+)");
    private final Map<String, Map<String, String>> named = new HashMap<>();
    private long work;
    private final Set<String> sheets = new HashSet<>();
    private final List<WebFonts.Face> faces = new ArrayList<>();
    private final List<Generated.Rule> pseudoRules = new ArrayList<>();
    private final Map<Element, Map<String, String>> generated = new IdentityHashMap<>();
    private long sheetChars;

    private static final long MAX_SHEET_CHARS = 8L << 20;

    private static final int MAX_NAMED_PAGES = 64;

    private static final Pattern GENERATED = Pattern.compile("(.*?)::?(before|after)\\s*", Pattern.CASE_INSENSITIVE
            | Pattern.DOTALL);

    /** {@code viewportPx} is the width media queries see, the page's text width in CSS pixels. */
    Cascade(Document doc, float viewportPx) {
        this(doc, viewportPx, new Linked(null));
    }

    Cascade(Document doc, float viewportPx, Linked linked) {
        this.doc = doc;
        List<Css.Rule> rules = new ArrayList<>();
        for (Element sheet : doc.select("style, link")) {
            if (!Media.matches(sheet.attr("media"), viewportPx)) {
                continue;
            }
            int before = rules.size();
            String base = linked.base();
            if (sheet.normalName().equals("style")) {
                Css.rules(imported(sheet.data(), linked.base(), linked, viewportPx, 0), rules, viewportPx);
            } else if (stylesheet(sheet) && !linked.none()) {
                String ref = linked.resolve(linked.base(), sheet.attr("href"));
                String text = sheet(ref, linked);
                if (text != null) {
                    base = ref;
                    Css.rules(imported(text, ref, linked, viewportPx, 0), rules, viewportPx);
                }
            }
            for (int k = before; k < rules.size(); k++) {
                if (rules.get(k).selector().equals("@font-face") && faces.size() < WebFonts.MAX_FACES) {
                    faces.add(new WebFonts.Face(rules.get(k).declarations(), base));
                }
            }
        }
        int order = 0;
        for (Css.Rule rule : rules) {
            order++;
            positions |= positioning(rule.declarations());
            for (Map.Entry<String, String> d : rule.declarations().entrySet()) {
                hyphenates |= d.getKey().endsWith("hyphens") && d.getValue().strip().equalsIgnoreCase("auto");
            }
            strings |= rule.declarations().containsKey("string-set")
                    || rule.declarations().containsKey(Css.IMPORTANT_MARK + "string-set");
            Matcher sided = SIDED_MARGIN.matcher(rule.selector());
            if (sided.matches()) {
                Map<String, String> box = sidedMargins.computeIfAbsent(sided.group(1), k -> new HashMap<>())
                        .computeIfAbsent(sided.group(2), k -> new HashMap<>());
                put(box, rule.declarations(), false);
                put(box, rule.declarations(), true);
                continue;
            }
            if (rule.selector().startsWith("@margin ")) {
                String box = rule.selector().substring(8);
                put(margins.computeIfAbsent(box, k -> new HashMap<>()), rule.declarations(), false);
                put(importantMargins.computeIfAbsent(box, k -> new HashMap<>()), rule.declarations(), true);
                continue;
            }
            if ("@font-face".equals(rule.selector())) {
                continue;
            }
            if ("@page".equals(rule.selector())) {
                put(page, rule.declarations(), false);
                put(page, rule.declarations(), true);
                continue;
            }
            if (rule.selector().startsWith("@page ")) {
                if (named.size() < MAX_NAMED_PAGES || named.containsKey(rule.selector().substring(6))) {
                    Map<String, String> own = named.computeIfAbsent(rule.selector().substring(6), k -> new HashMap<>());
                    put(own, rule.declarations(), false);
                    put(own, rule.declarations(), true);
                }
                continue;
            }
            Matcher pseudo = GENERATED.matcher(rule.selector());
            if (pseudo.matches()) {
                String base = pseudo.group(1).isBlank() ? "*" : usable(pseudo.group(1).strip());
                if (base != null && pseudoRules.size() < Generated.MAX_RULES) {
                    try {
                        pseudoRules.add(new Generated.Rule(QueryParser.parse(base),
                                pseudo.group(2).toLowerCase(Locale.ROOT), specificity(base), order,
                                rule.declarations()));
                    } catch (RuntimeException e) {
                        continue;
                    }
                }
                continue;
            }
            String selector = usable(rule.selector());
            if (selector == null) {
                continue;
            }
            Evaluator evaluator;
            try {
                evaluator = QueryParser.parse(selector);
            } catch (RuntimeException e) {
                continue;
            }
            index(selector, new Compiled(evaluator, new Hit(specificity(selector), order, rule.declarations())));
        }
        importantMargins.forEach((box, d) -> margins.get(box).putAll(d));
    }

    private boolean positions;

    private boolean strings;

    private boolean hyphenates;

    boolean hyphenates() {
        return hyphenates || !doc.select("[style*=hyphens]").isEmpty();
    }

    boolean setsStrings() {
        return strings;
    }

    private static boolean positioning(Map<String, String> d) {
        for (Map.Entry<String, String> e : d.entrySet()) {
            String k = e.getKey().startsWith(Css.IMPORTANT_MARK) ? e.getKey().substring(1) : e.getKey();
            if (k.equals("position") || k.equals("transform") || k.startsWith("--")
                    && e.getValue().toLowerCase(Locale.ROOT).matches(".*\\b(absolute|fixed)\\b.*")) {
                return true;
            }
        }
        return false;
    }

    boolean positions() {
        if (!positionsChecked) {
            positionsChecked = true;
            positions |= !doc.select("[style*=position], [style*=transform], [style*=POSITION]").isEmpty();
        }
        return positions;
    }

    private boolean positionsChecked;

    private static boolean stylesheet(Element link) {
        boolean sheet = false;
        for (String r : link.attr("rel").toLowerCase(Locale.ROOT).split("\\s+")) {
            if (r.equals("alternate")) {
                return false;
            }
            sheet |= r.equals("stylesheet");
        }
        return sheet;
    }

    private static final int MAX_IMPORT_DEPTH = 8;

    private static final Pattern IMPORT = Pattern.compile(
            "@import\\s*(?:url\\(\\s*(?:\"([^\"]*)\"|'([^']*)'|([^)\\s]*))\\s*\\)|\"([^\"]*)\"|'([^']*)')([^;]*);",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CHARSET = Pattern.compile("@charset\\s*\"[^\"]*\"\\s*;", Pattern.CASE_INSENSITIVE);

    private String imported(String sheet, String base, Linked linked, float viewportPx, int depth) {
        if (linked.none() || !sheet.toLowerCase(Locale.ROOT).contains("@import")) {
            return sheet;
        }
        String s = Css.stripComments(sheet);
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        Matcher cs = CHARSET.matcher(s);
        Matcher m = IMPORT.matcher(s);
        while (true) {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            cs.region(i, s.length());
            if (cs.lookingAt()) {
                i = cs.end();
                continue;
            }
            m.region(i, s.length());
            if (!m.lookingAt()) {
                break;
            }
            i = m.end();
            String target = null;
            for (int g = 1; g <= 5 && target == null; g++) {
                target = m.group(g);
            }
            String media = m.group(6).strip();
            if (depth >= MAX_IMPORT_DEPTH || !Media.matches(media, viewportPx)) {
                continue;
            }
            String text = sheet(linked.resolve(base, target), linked);
            if (text != null) {
                out.append(imported(text, linked.resolve(base, target), linked, viewportPx, depth + 1)).append('\n');
            }
        }
        return out.append(s, i, s.length()).toString();
    }

    private String sheet(String ref, Linked linked) {
        if (ref == null || !sheets.add(ref) || sheetChars >= MAX_SHEET_CHARS) {
            return null;
        }
        String text = linked.text(ref);
        if (text == null) {
            return null;
        }
        sheetChars += text.length();
        return sheetChars > MAX_SHEET_CHARS ? null : rebased(text, ref);
    }

    private static final Pattern URL = Pattern.compile("url\\(\\s*([\"']?)([^\"')]*)\\1\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:.*", Pattern.DOTALL);

    static String rebased(String sheet, String ref) {
        if (sheet.indexOf('(') < 0) {
            return sheet;
        }
        Matcher m = URL.matcher(sheet);
        StringBuilder out = new StringBuilder(sheet.length());
        while (m.find()) {
            String v = m.group(2).strip();
            String replacement = m.group();
            if (!v.isEmpty() && !v.startsWith("#") && !SCHEME.matcher(v).matches()) {
                String resolved = HtmlRefs.resolve(ref, v);
                if (resolved != null && resolved.indexOf('"') < 0 && resolved.indexOf('\\') < 0) {
                    replacement = "url(\"" + (SCHEME.matcher(resolved).matches() ? resolved : "/" + resolved) + "\")";
                }
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    // Filed under what the selector's last compound step needs: its id, else a class, else its tag
    private void index(String selector, Compiled c) {
        String last = lastStep(selector);
        Matcher m = ID.matcher(last);
        if (m.find()) {
            byId.computeIfAbsent(unescape(m.group(1)), k -> new ArrayList<>()).add(c);
            return;
        }
        m = CLASS.matcher(last);
        if (m.find()) {
            byClass.computeIfAbsent(unescape(m.group(1)), k -> new ArrayList<>()).add(c);
            return;
        }
        m = TAG.matcher(last);
        if (m.find()) {
            byTag.computeIfAbsent(m.group(1).toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(c);
            return;
        }
        anyElement.add(c);
    }

    // .md\:flex names the class md:flex
    private static String unescape(String name) {
        return name.replace("\\", "");
    }

    // The last compound selector, with the insides of brackets and :not() and the like taken out
    static String lastStep(String selector) {
        StringBuilder flat = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < selector.length(); i++) {
            char c = selector.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if ((c == ')' || c == ']') && depth > 0) {
                depth--;
            } else if (depth == 0) {
                flat.append(c);
            }
        }
        String s = flat.toString().strip();
        int cut = Math.max(Math.max(s.lastIndexOf(' '), s.lastIndexOf('>')), Math.max(s.lastIndexOf('+'), s.lastIndexOf('~')));
        return s.substring(cut + 1).strip();
    }

    private List<Hit> hits(Element e) {
        List<Hit> found = hits.get(e);
        if (found != null) {
            return found;
        }
        found = new ArrayList<>(4);
        if (work < MAX_MATCH_WORK) {
            Set<Compiled> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            test(e, byId.get(e.id()), found, seen);
            for (String c : e.classNames()) {
                test(e, byClass.get(c), found, seen);
            }
            test(e, byTag.get(e.normalName()), found, seen);
            test(e, anyElement, found, seen);
            found.sort(Comparator.comparingInt(Hit::specificity).thenComparingInt(Hit::order));
        }
        hits.put(e, found);
        return found;
    }

    private void test(Element e, List<Compiled> rules, List<Hit> found, Set<Compiled> seen) {
        if (rules == null) {
            return;
        }
        for (Compiled c : rules) {
            if (++work > MAX_MATCH_WORK) {
                return;
            }
            try {
                if (seen.add(c) && c.evaluator().matches(doc, e)) {
                    found.add(c.hit());
                }
            } catch (RuntimeException ignored) {
                // a selector jsoup cannot test on this element matches nothing
            }
        }
    }

    // Only structural pseudo-classes: text and regex ones (:matches, :contains, a deep :has) cost per element
    // and could be made to backtrack, and state ones (:hover) never match a printed page
    private static String usable(String selector) {
        // nothing on a printed page is hovered, focused or visited, so :not() of those always holds
        String s = wordAttributes(NOT_STATE.matcher(selector.replace(":link", "")).replaceAll(""));
        if (s == null || s.contains("::")) {
            return null;
        }
        // :has of one child compound costs a pass over the children, so layouts chosen by child count still match
        Matcher m = PSEUDO.matcher(CHILD_HAS.matcher(s.replaceAll(QUOTED, "")).replaceAll(" $1"));
        while (m.find()) {
            if (!STRUCTURAL.contains(m.group(1).toLowerCase(Locale.ROOT))) {
                return null;
            }
        }
        return s;
    }

    // a colon escaped in a class name (.md\\:flex) or quoted in an attribute value is not a pseudo-class
    private static final Pattern NOT_STATE = Pattern.compile(
            ":not[(][ ]*:(hover|focus|focus-visible|focus-within|active|visited|target|checked)[ ]*[)]");

    private static String wordAttributes(String selector) {
        Matcher m = WORD_ATTRIBUTE.matcher(selector);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = m.group(2).strip();
            if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                    && value.charAt(value.length() - 1) == value.charAt(0)) {
                value = value.substring(1, value.length() - 1);
            }
            if (!WORD.matcher(value).matches()) {
                return null;
            }
            m.appendReplacement(out, Matcher.quoteReplacement("[" + m.group(1).strip() + "~=(^|\\s)" + value + "(\\s|$)]"));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static final Pattern WORD_ATTRIBUTE = Pattern.compile("\\[([^\\[\\]~|^$*=]+)~=([^\\[\\]]*)\\]");
    private static final Pattern WORD = Pattern.compile("[-_A-Za-z0-9]+");

    private static final Pattern PSEUDO = Pattern.compile("(?<!\\\\):([a-zA-Z-]+)");
    private static final String QUOTED = "\"[^\"]*\"|'[^']*'";
    private static final Pattern CHILD_HAS = Pattern.compile(
            ":has[(][ ]*>[ ]*((?:[^\\s>+~,()]|[(][^()]{0,40}[)]){1,200})[ ]*[)]");

    private static final Set<String> STRUCTURAL = Set.of("root", "first-child", "last-child", "only-child",
            "first-of-type", "last-of-type", "only-of-type", "nth-child", "nth-last-child", "nth-of-type",
            "nth-last-of-type", "empty", "not", "is", "where");

    private static int specificity(String selector) {
        int ids = 0;
        int classes = 0;
        int tags = 0;
        boolean word = false;
        for (int i = 0; i < selector.length(); i++) {
            char c = selector.charAt(i);
            if (c == '#') {
                ids++;
                word = true;
            } else if (c == '.' || c == '[' || c == ':') {
                classes++;
                word = true;
            } else if (Character.isLetter(c) || c == '*') {
                if (!word && c != '*') {
                    tags++;
                }
                word = true;
            } else if (c == ' ' || c == '>' || c == '+' || c == '~') {
                word = false;
            }
        }
        return ids * 10_000 + classes * 100 + tags;
    }

    /** The cascaded declarations of {@code e}: rules by specificity then order, then the style attribute. */
    Map<String, String> declared(Element e) {
        Map<String, String> own = generated.get(e);
        if (own != null) {
            Map<String, String> out = new HashMap<>();
            put(out, own, false);
            put(out, own, true);
            return out;
        }
        Map<String, String> out = new HashMap<>();
        Presentation.apply(e, out);
        List<Hit> list = hits(e);
        Map<String, String> inline = e.hasAttr("style") ? Css.declarations(e.attr("style")) : Map.of();
        // Normal rules, then the style attribute, then !important rules, then an !important style attribute
        if (list != null) {
            for (Hit h : list) {
                put(out, h.declarations(), false);
            }
        }
        put(out, inline, false);
        if (list != null) {
            for (Hit h : list) {
                put(out, h.declarations(), true);
            }
        }
        put(out, inline, true);
        return out;
    }

    private static void put(Map<String, String> out, Map<String, String> declared, boolean important) {
        for (Map.Entry<String, String> d : declared.entrySet()) {
            boolean marked = d.getKey().startsWith(Css.IMPORTANT_MARK);
            if (marked == important) {
                out.put(marked ? d.getKey().substring(1) : d.getKey(), d.getValue());
            }
        }
    }

    Map<String, String> page() {
        return page;
    }

    List<WebFonts.Face> faces() {
        return faces;
    }

    List<Generated.Rule> pseudoRules() {
        return pseudoRules;
    }

    void match(Element e) {
        hits(e);
    }

    void generated(Element e, Map<String, String> declarations) {
        generated.put(e, declarations);
    }

    Map<String, Map<String, String>> margins() {
        return margins;
    }

    Map<String, Map<String, String>> margins(String... sides) {
        Map<String, Map<String, String>> out = new HashMap<>();
        margins.forEach((box, d) -> out.put(box, new HashMap<>(d)));
        for (String side : sides) {
            sidedMargins.getOrDefault(side, Map.of())
                    .forEach((box, d) -> out.computeIfAbsent(box, k -> new HashMap<>()).putAll(d));
        }
        return out;
    }

    boolean styledPages(String side) {
        return sidedMargins.containsKey(side);
    }

    /** Whether the page asks for its own paper (a size, or named pages), in which case it is printed as it asks. */
    boolean declaresPages() {
        return page.containsKey("size") || !named.isEmpty();
    }

    /** A named page's declarations over the default page's, or null when no @page has that name. */
    Map<String, String> page(String name) {
        Map<String, String> own = named.get(name);
        if (own == null) {
            return null;
        }
        Map<String, String> out = new HashMap<>(page);
        out.putAll(own);
        return out;
    }
}

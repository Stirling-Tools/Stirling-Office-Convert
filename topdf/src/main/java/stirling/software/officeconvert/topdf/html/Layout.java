package stirling.software.officeconvert.topdf.html;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import stirling.software.officeconvert.model.Paragraph;

/** Side-by-side layouts (flex rows and grids) as a page can hold them: short items as one line, the rest as
 * the columns of a borderless table. */
final class Layout {

    /** How a container's visible children sit side by side: on one line, or as {@code columns} with these widths.
     * Named grid areas give {@code areas}: per row, cells of {item or -1, column span, vertical merge}. */
    record Plan(List<Element> items, boolean inline, int columns, float[] widths, float gap, List<List<int[]>> areas) {
        Plan(List<Element> items, boolean inline, int columns, float[] widths, float gap) {
            this(items, inline, columns, widths, gap, null);
        }
    }

    static final int MAX_COLUMNS = 8;

    private static final int SHORT_ITEM = 48;

    private static final String BLOCKS = "p, h1, h2, h3, h4, h5, h6, ul, ol, dl, table, pre, blockquote";

    private Layout() {}

    static Plan of(Element e, Map<String, String> d, Look look, float room, HtmlContext ctx) {
        return of(e, d, look, room, ctx, false);
    }

    static Plan of(Element e, Map<String, String> d, Look look, float room, HtmlContext ctx, boolean inCell) {
        String display = d.getOrDefault("display", "").strip().toLowerCase(Locale.ROOT);
        boolean flex = display.equals("flex") || display.equals("inline-flex");
        boolean grid = display.equals("grid") || display.equals("inline-grid");
        if (!flex && !grid) {
            return null;
        }
        if (flex && d.getOrDefault("flex-direction", "row").strip().toLowerCase(Locale.ROOT).startsWith("column")) {
            return null;
        }
        List<Element> items = new ArrayList<>();
        if (!items(e, look, ctx, items, 0)) {
            return null;
        }
        if (items.size() < 2) {
            return null;
        }
        float gap = gap(d, look);
        if (flex && allShort(items) && (inCell || !framed(items, look, ctx) || !placed(items, look, ctx))) {
            return new Plan(items, true, items.size(), null, gap);
        }
        int columns = grid ? gridColumns(d.get("grid-template-columns"), look, room) : items.size();
        if (grid && columns < 2) {
            return areas(items, d.get("grid-template-areas"), look, room, ctx, gap);
        }
        if (columns < 2) {
            return null;
        }
        boolean wraps = d.getOrDefault("flex-wrap", "nowrap").strip().toLowerCase(Locale.ROOT).startsWith("wrap");
        columns = Math.min(Math.min(columns, items.size()), flex && !wraps ? MAX_COLUMNS * 2 : MAX_COLUMNS);
        if (flex) {
            // a row too wide for the page, like a carousel, wraps on paper instead of overflowing
            int fit = wrapColumns(items, look, room, ctx, columns, wraps ? 3 : columns);
            // a row that never wraps shrinks its items in a browser rather than stacking them
            columns = wraps ? fit : Math.max(Math.min(2, columns), fit);
        }
        // each item's words are measured once, so trying fewer columns costs only arithmetic
        List<Words> words = items.stream().map(Layout::words).toList();
        for (; columns >= 2; columns--) {
            if (room / columns < 36) {
                continue;
            }
            float[] widths = widths(items, columns, look, room, ctx, grid ? d : null);
            if (widths == null) {
                return null;
            }
            if (!cramped(words, widths, look)) {
                return new Plan(items, false, columns, widths, gap);
            }
            if (grid) {
                return null;
            }
        }
        return null;
    }

    // The visible children, with display: contents wrappers replaced by their own children as browsers do
    private static boolean items(Element e, Look look, HtmlContext ctx, List<Element> into, int depth) {
        for (Node n : e.childNodes()) {
            if (n instanceof TextNode t && !t.isBlank()) {
                return false;
            }
            if (!(n instanceof Element c) || !visible(c, look, ctx)) {
                continue;
            }
            String display = ctx.declared(c, look).getOrDefault("display", "").strip().toLowerCase(Locale.ROOT);
            if (display.equals("contents") && depth < 8) {
                if (!items(c, look, ctx, into, depth + 1)) {
                    return false;
                }
            } else {
                into.add(c);
            }
        }
        return true;
    }

    /** The longest word of an item's text, and how long that text is, both capped at 2,000 characters. */
    private record Words(int longest, int length) {}

    private static Words words(Element item) {
        String text = text(item, 2_000);
        int longest = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            run = Character.isWhitespace(text.charAt(i)) ? 0 : run + 1;
            longest = Math.max(longest, run);
        }
        return new Words(longest, text.length());
    }

    private static boolean cramped(List<Words> words, float[] widths, Look look) {
        for (int i = 0; i < words.size(); i++) {
            if (cramped(words.get(i), widths[i % widths.length], look)) {
                return true;
            }
        }
        return false;
    }

    // Too narrow for its words reads worse than stacked; prose needs room as cards pad text and enlarge headings
    private static boolean cramped(Words words, float w, Look look) {
        return w < Math.min(words.longest(), 14) * look.size * 0.5f || w < 90 && words.length() > 30;
    }

    // Rows of named areas: an item spans the columns and rows its name fills, as the page's grid places it
    private static Plan areas(List<Element> items, String template, Look look, float room, HtmlContext ctx, float gap) {
        List<String[]> rows = new ArrayList<>();
        if (template != null) {
            Matcher m = AREA_ROW.matcher(template);
            while (m.find() && rows.size() <= 24) {
                rows.add(m.group(1).strip().split("\\s+"));
            }
        }
        int n = rows.isEmpty() ? 0 : rows.get(0).length;
        if (n < 2 || n > MAX_COLUMNS || rows.size() > 24 || rows.stream().anyMatch(r -> r.length != n)) {
            return null;
        }
        Map<String, Integer> owner = owners(items, rows, look, ctx);
        if (owner.isEmpty()) {
            return null;
        }
        List<List<int[]>> out = new ArrayList<>();
        Set<String> started = new HashSet<>();
        for (int r = 0; r < rows.size(); r++) {
            String[] row = rows.get(r);
            List<int[]> cells = new ArrayList<>();
            for (int c = 0; c < n; ) {
                int span = 1;
                while (c + span < n && row[c + span].equals(row[c])) {
                    span++;
                }
                Integer item = owner.get(row[c]);
                if (item == null) {
                    cells.add(new int[] {-1, span, 0});
                } else if (!started.add(row[c])) {
                    cells.add(new int[] {-1, span, 2});
                } else {
                    if (cramped(words(items.get(item)), room * span / n, look)) {
                        return null;
                    }
                    boolean down = r + 1 < rows.size() && Arrays.asList(rows.get(r + 1)).contains(row[c]);
                    cells.add(new int[] {item, span, down ? 1 : 0});
                }
                c += span;
            }
            out.add(cells);
        }
        List<int[]> rest = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!owner.containsValue(i)) {
                rest.add(new int[] {i, 1, 0});
            }
            if (rest.size() == n || i == items.size() - 1 && !rest.isEmpty()) {
                while (rest.size() < n) {
                    rest.add(new int[] {-1, 1, 0});
                }
                out.add(rest);
                rest = new ArrayList<>();
            }
        }
        float[] widths = new float[n];
        Arrays.fill(widths, room / n);
        return new Plan(items, false, n, widths, gap, out);
    }

    // Each area's item: one naming it in grid-area, else the next unplaced item in the order the names appear
    private static Map<String, Integer> owners(List<Element> items, List<String[]> rows, Look look, HtmlContext ctx) {
        Set<String> names = new LinkedHashSet<>();
        for (String[] row : rows) {
            for (String t : row) {
                if (!t.matches("[.]+")) {
                    names.add(t);
                }
            }
        }
        Map<String, Integer> owner = new HashMap<>();
        boolean[] placed = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            String area = ctx.declared(items.get(i), look).getOrDefault("grid-area", "").split("/")[0].strip();
            if (names.contains(area) && !owner.containsKey(area)) {
                owner.put(area, i);
                placed[i] = true;
            }
        }
        int next = 0;
        for (String name : names) {
            while (next < items.size() && placed[next]) {
                next++;
            }
            if (!owner.containsKey(name) && next < items.size()) {
                owner.put(name, next);
                placed[next] = true;
            }
        }
        return owner;
    }

    // At most cap characters of an element's text: a deep page asks this of every container, so it stops early
    static String text(Element e, int cap) {
        StringBuilder sb = new StringBuilder();
        java.util.ArrayDeque<Node> stack = new java.util.ArrayDeque<>();
        stack.push(e);
        while (!stack.isEmpty() && sb.length() < cap) {
            Node n = stack.pop();
            if (n instanceof TextNode t) {
                sb.append(t.text());
            } else {
                for (int i = n.childNodeSize() - 1; i >= 0; i--) {
                    stack.push(n.childNode(i));
                }
            }
        }
        return sb.length() > cap ? sb.substring(0, cap) : sb.toString();
    }

    private static boolean visible(Element c, Look look, HtmlContext ctx) {
        if (c.hasAttr("hidden") || Flow.SKIPPED.contains(c.normalName())) {
            return false;
        }
        Map<String, String> cd = ctx.declared(c, look);
        if ("none".equalsIgnoreCase(cd.getOrDefault("display", "").strip())) {
            return false;
        }
        String position = cd.getOrDefault("position", "").strip().toLowerCase(Locale.ROOT);
        return !position.equals("absolute") && !position.equals("fixed") && !Hidden.visually(cd, look.size);
    }

    static boolean framed(List<Element> items, Look look, HtmlContext ctx) {
        for (Element c : items) {
            if (framed(ctx.declared(c, look))) {
                return true;
            }
        }
        return false;
    }

    static boolean framed(Map<String, String> cd) {
        int edges = 0;
        for (String side : new String[] {"top", "bottom", "left", "right"}) {
            edges += Flow.border(cd, side) > 0 ? 1 : 0;
        }
        Integer fill = Css.color(cd.get("background-color"));
        return edges >= 3 || fill != null && fill >= 0 && fill != 0xFFFFFF;
    }

    private static boolean placed(List<Element> items, Look look, HtmlContext ctx) {
        for (Element c : items) {
            Map<String, String> cd = ctx.declared(c, look);
            if (grows(cd) || size(cd.getOrDefault("flex-basis", cd.get("width")), look, 1000) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean allShort(List<Element> items) {
        for (Element c : items) {
            if (text(c, SHORT_ITEM + 1).length() > SHORT_ITEM || !c.select(BLOCKS).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static float gap(Map<String, String> d, Look look) {
        String g = d.getOrDefault("column-gap", d.get("gap"));
        if (g == null) {
            return 0;
        }
        List<String> parts = Css.tokens(g);
        if (parts.isEmpty()) {
            return 0;
        }
        Float pt = Css.length(parts.get(parts.size() - 1), look.size, 0);
        return pt == null ? 0 : Math.max(0, Math.min(72, pt));
    }

    // Wrapping rows hold as many items as fit at their basis or width
    private static int wrapColumns(List<Element> items, Look look, float room, HtmlContext ctx, int most,
            int unsized) {
        Map<String, String> first = ctx.declared(items.get(0), look);
        Float basis = size(first.getOrDefault("flex-basis", first.get("width")), look, room);
        if (basis == null || basis <= 0) {
            basis = size(first.get("min-width"), look, room);
        }
        if (basis == null || basis <= 0) {
            return Math.min(most, unsized);
        }
        return Math.max(1, Math.min(most, (int) Math.floor(room / basis)));
    }

    static int gridColumns(String template, Look look, float room) {
        if (template == null) {
            return 1;
        }
        String t = template.strip().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || t.equals("none") || t.startsWith("subgrid")) {
            return 1;
        }
        int count = 0;
        for (String token : tracks(t)) {
            if (token.startsWith("repeat(") && token.endsWith(")") && token.length() > 8) {
                String inner = token.substring(7, token.length() - 1);
                List<String> parts = Css.splitTopLevel(inner, ',');
                if (parts.size() < 2) {
                    continue;
                }
                String n = parts.get(0).strip();
                int tracksEach = Math.max(1, tracks(parts.get(1).strip()).size());
                if (n.matches("[0-9]{1,3}")) {
                    count += Integer.parseInt(n) * tracksEach;
                } else {
                    count += autoRepeat(parts.get(1).strip(), look, room);
                }
            } else if (!token.startsWith("[")) {
                count++;
            }
        }
        return Math.min(count, MAX_COLUMNS);
    }

    private static final Pattern AREA_ROW = Pattern.compile("\"([^\"]{0,500})\"");

    private static int autoRepeat(String track, Look look, float room) {
        String min = track;
        if (track.startsWith("minmax(") && track.endsWith(")") && track.length() > 8) {
            min = Css.splitTopLevel(track.substring(7, track.length() - 1), ',').get(0).strip();
        }
        Float pt = Css.length(min, look.size, room);
        return pt == null || pt <= 0 ? 1 : Math.max(1, (int) Math.floor(room / pt));
    }

    private static List<String> tracks(String t) {
        return Css.tokens(t);
    }

    private static float[] widths(List<Element> items, int columns, Look look, float room, HtmlContext ctx,
            Map<String, String> grid) {
        float[] w = new float[columns];
        float[] weight = new float[columns];
        if (grid != null && grid.get("grid-template-columns") != null) {
            List<String> list = tracks(grid.get("grid-template-columns").strip().toLowerCase(Locale.ROOT));
            if (list.size() == columns) {
                for (int c = 0; c < columns; c++) {
                    String tr = list.get(c);
                    if (tr.endsWith("fr")) {
                        weight[c] = number(tr.substring(0, tr.length() - 2));
                    } else {
                        Float pt = Css.length(tr, look.size, room);
                        w[c] = pt == null ? 0 : pt;
                    }
                }
            }
        } else {
            for (int c = 0; c < columns && c < items.size(); c++) {
                Map<String, String> cd = ctx.declared(items.get(c), look);
                Float pt = size(cd.getOrDefault("flex-basis", cd.get("width")), look, room);
                if (pt != null && pt >= room * 0.8f) {
                    // an item as wide as the row means a column layout in practice
                    return null;
                }
                if (pt != null && pt > 0) {
                    w[c] = pt;
                } else {
                    weight[c] = grows(cd) ? 3f : Math.max(0.5f, Math.min(3f, text(items.get(c), 200).length() / 60f));
                }
            }
        }
        float fixed = 0;
        float weights = 0;
        for (int c = 0; c < columns; c++) {
            fixed += w[c];
            weights += weight[c] > 0 || w[c] > 0 ? weight[c] : 1;
        }
        float rest = Math.max(0, room - fixed);
        for (int c = 0; c < columns; c++) {
            if (w[c] <= 0) {
                w[c] = rest * (weight[c] > 0 ? weight[c] : 1) / Math.max(0.01f, weights);
            }
        }
        float sum = 0;
        for (float v : w) {
            sum += v;
        }
        for (int c = 0; c < columns; c++) {
            w[c] = Math.max(24, w[c] * room / Math.max(1, sum));
        }
        return w;
    }

    static List<Paragraph.TabStop> stops(Plan plan, Map<String, String> d, Look look, float room, HtmlContext ctx) {
        List<Element> items = plan.items();
        int n = items.size();
        float[] width = new float[n];
        float[] grow = new float[n];
        boolean[] sized = new boolean[n];
        Paragraph.Align[] align = new Paragraph.Align[n];
        String justify = d.getOrDefault("justify-content", "normal").strip().toLowerCase(Locale.ROOT);
        boolean moved = !Set.of("normal", "flex-start", "start", "left", "stretch").contains(justify);
        float total = plan.gap() * (n - 1);
        for (int i = 0; i < n; i++) {
            Map<String, String> cd = ctx.declared(items.get(i), look);
            Look il = look.child(cd, room, false);
            String[] flex = flex(cd);
            grow[i] = number(flex[0]);
            Float basis = size(flex[1], look, room);
            if (basis == null) {
                basis = size(cd.get("width"), look, room);
            }
            float content = widestLine(items.get(i), il, ctx) * il.size * 0.5f
                    + side(cd, il, "left") + side(cd, il, "right");
            sized[i] = basis != null;
            width[i] = basis == null ? content : Math.max(basis, flex[1].equals("0") ? 0 : content * 0.5f);
            align[i] = il.align;
            moved |= grow[i] > 0 || basis != null;
            total += width[i];
        }
        float free = room - total;
        if (!moved || free < 0) {
            return null;
        }
        float grows = 0;
        for (float g : grow) {
            grows += g;
        }
        float lead = 0;
        float between = plan.gap();
        if (grows > 0) {
            for (int i = 0; i < n; i++) {
                width[i] += free * grow[i] / grows;
            }
        } else {
            switch (justify) {
                case "flex-end", "end", "right" -> lead = free;
                case "center" -> lead = free / 2;
                case "space-between" -> between += free / (n - 1);
                case "space-around" -> {
                    lead = free / n / 2;
                    between += free / n;
                }
                case "space-evenly" -> {
                    lead = free / (n + 1);
                    between += free / (n + 1);
                }
                default -> { }
            }
        }
        List<Paragraph.TabStop> stops = new ArrayList<>();
        float x = lead;
        for (int i = 0; i < n; i++) {
            float end = x + width[i];
            Paragraph.TabStop.Kind kind;
            if (sized[i] || grow[i] > 0) {
                kind = switch (align[i]) {
                    case RIGHT -> Paragraph.TabStop.Kind.RIGHT;
                    case CENTER -> Paragraph.TabStop.Kind.CENTER;
                    default -> Paragraph.TabStop.Kind.LEFT;
                };
            } else if (i == n - 1 && room - end < 1 || justify.equals("flex-end") || justify.equals("end")) {
                kind = Paragraph.TabStop.Kind.RIGHT;
            } else if (i > 0 || x >= 1) {
                kind = Paragraph.TabStop.Kind.CENTER;
            } else {
                kind = Paragraph.TabStop.Kind.LEFT;
            }
            float pos = switch (kind) {
                case RIGHT -> end;
                case CENTER -> x + width[i] / 2;
                default -> x;
            };
            stops.add(kind == Paragraph.TabStop.Kind.LEFT && pos < 1 ? null : new Paragraph.TabStop(pos, kind, (char) 0));
            x = end + between;
        }
        return stops;
    }

    private static String[] flex(Map<String, String> cd) {
        String grow = cd.getOrDefault("flex-grow", "0").strip();
        String basis = cd.getOrDefault("flex-basis", "auto").strip();
        String flex = cd.getOrDefault("flex", "").strip().toLowerCase(Locale.ROOT);
        if (flex.equals("auto")) {
            return new String[] {"1", "auto"};
        }
        if (flex.equals("none") || flex.equals("initial")) {
            return new String[] {"0", "auto"};
        }
        List<String> parts = Css.tokens(flex);
        if (!parts.isEmpty()) {
            boolean number = parts.get(0).matches("[0-9.]+");
            grow = number ? parts.get(0) : "1";
            basis = number ? "0" : parts.get(0);
            if (parts.size() > 1 && !parts.get(parts.size() - 1).matches("[0-9.]+")) {
                basis = parts.get(parts.size() - 1);
            }
        }
        return new String[] {grow, basis};
    }

    static boolean stacks(Element e, String tag, Map<String, String> d, Look parentLook, HtmlContext ctx) {
        Element parent = e.parent();
        if (parent != null) {
            Map<String, String> pd = ctx.declared(parent, parentLook);
            String shown = pd.getOrDefault("display", "").strip().toLowerCase(Locale.ROOT);
            if (shown.endsWith("flex") || shown.endsWith("grid")) {
                return shown.endsWith("flex")
                        && pd.getOrDefault("flex-direction", "row").strip().toLowerCase(Locale.ROOT).startsWith("column");
            }
        }
        String own = d.getOrDefault("display", Flow.BLOCKS.contains(tag) ? "block" : "inline").strip()
                .toLowerCase(Locale.ROOT);
        return !own.startsWith("inline") && !own.equals("contents") && !own.equals("none");
    }

    private static int widestLine(Element item, Look look, HtmlContext ctx) {
        int widest = 0;
        int line = 0;
        java.util.ArrayDeque<Node> stack = new java.util.ArrayDeque<>();
        for (int i = item.childNodeSize() - 1; i >= 0; i--) {
            stack.push(item.childNode(i));
        }
        int seen = 0;
        while (!stack.isEmpty() && seen++ < 2_000) {
            Node n = stack.pop();
            if (n instanceof TextNode t) {
                line += t.text().strip().length() + (t.text().isBlank() ? 0 : 1);
            } else if (n instanceof Element c) {
                String tag = c.normalName();
                if (tag.equals("br") || stacks(c, tag, ctx.declared(c, look), look, ctx)) {
                    widest = Math.max(widest, line);
                    line = 0;
                }
                for (int i = c.childNodeSize() - 1; i >= 0; i--) {
                    stack.push(c.childNode(i));
                }
            }
        }
        return Math.max(widest, line);
    }

    private static float side(Map<String, String> cd, Look look, String side) {
        return Math.max(0, Flow.length(cd.get("margin-" + side), look.size, 0))
                + Math.max(0, Flow.length(cd.get("padding-" + side), look.size, 0));
    }

    private static boolean grows(Map<String, String> cd) {
        String flex = cd.getOrDefault("flex", cd.getOrDefault("flex-grow", "")).strip();
        return !flex.isEmpty() && !flex.startsWith("0") && !flex.equals("none") && !flex.equals("initial");
    }

    private static float number(String s) {
        try {
            float v = Float.parseFloat(s.strip());
            return Float.isNaN(v) ? 1 : Math.max(0, Math.min(1e6f, v));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static Float size(String v, Look look, float room) {
        if (v == null || v.equalsIgnoreCase("auto") || v.equalsIgnoreCase("content")) {
            return null;
        }
        Float pt = Css.length(v, look.size, room);
        return pt == null ? null : Math.min(pt, room);
    }
}

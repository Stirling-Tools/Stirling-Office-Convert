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
        if (flex && allShort(items)) {
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

    private static boolean grows(Map<String, String> cd) {
        String flex = cd.getOrDefault("flex", cd.getOrDefault("flex-grow", "")).strip();
        return !flex.isEmpty() && !flex.startsWith("0") && !flex.equals("none") && !flex.equals("initial");
    }

    private static float number(String s) {
        try {
            return Math.max(0, Float.parseFloat(s.strip()));
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

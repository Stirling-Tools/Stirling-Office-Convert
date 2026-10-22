package stirling.software.officeconvert.topdf.html;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Table;

/** An HTML table as a grid: spans become merged cells, column widths come from the widths given or the text. */
final class HtmlTables {

    static final int MAX_COLUMNS = 63;

    static final int MAX_ROWS = 20_000;

    static final String ROWS = "> tr, > thead > tr, > tbody > tr, > tfoot > tr";

    private static final float MIN_COLUMN = 12f;

    private final HtmlContext ctx;

    HtmlTables(HtmlContext ctx) {
        this.ctx = ctx;
    }

    private record Slot(Element element, Table.Cell cell, Map<String, String> declared, Look look) {}

    Table table(Element table, Map<String, String> d, Look look, Flow.Frame frame, boolean inCentre, int depth)
            throws IOException {
        Elements rows = table.select(ROWS);
        if (rows.isEmpty()) {
            return null;
        }
        if (rows.size() > MAX_ROWS) {
            ctx.warn("Only the first " + MAX_ROWS + " rows of a table were converted");
        }
        int rowCount = Math.min(rows.size(), MAX_ROWS);
        float frameBorder = frameBorder(table);
        List<TreeMap<Integer, Slot>> grid = new ArrayList<>();
        List<Set<Integer>> taken = new ArrayList<>();
        for (int r = 0; r < rowCount; r++) {
            grid.add(new TreeMap<>());
            taken.add(new HashSet<>());
        }
        Table t = new Table();
        int columns = 0;
        for (int r = 0; r < rowCount; r++) {
            Element tr = rows.get(r);
            Map<String, String> rowDecl = ctx.declared(tr, look);
            if (hidden(tr, rowDecl, look)) {
                // a row the page hides keeps its place in the grid but shows nothing
                t.rows.add(new Table.Row());
                continue;
            }
            Look rowLook = look.child(rowDecl, frame.width(), false);
            Table.Row row = new Table.Row();
            // A browser breaks a tall row across pages rather than cutting it off
            row.splits = !Flow.avoidsBreaks(rowDecl);
            row.height = Math.max(0, Flow.length(rowDecl.get("height"), rowLook.size, 0));
            row.header = "thead".equals(tr.parent() == null ? "" : tr.parent().normalName());
            boolean allHeads = true;
            int col = 0;
            for (Element td : tr.select("> td, > th")) {
                if (hidden(td, ctx.declared(td, rowLook), rowLook)) {
                    continue;
                }
                while (taken.get(r).contains(col)) {
                    col++;
                }
                if (col >= MAX_COLUMNS) {
                    ctx.warn("Only the first " + MAX_COLUMNS + " columns of a table were converted");
                    break;
                }
                allHeads &= "th".equals(td.normalName());
                Map<String, String> cellDecl = ctx.declared(td, rowLook);
                if (!cellDecl.containsKey("background-color") && rowDecl.containsKey("background-color")) {
                    cellDecl.put("background-color", rowDecl.get("background-color"));
                }
                if (!cellDecl.containsKey("vertical-align") && rowDecl.containsKey("vertical-align")) {
                    cellDecl.put("vertical-align", rowDecl.get("vertical-align"));
                }
                Look cellLook = rowLook.child(cellDecl, frame.width(), false);
                int colspan = Math.min(span(td.attr("colspan"), 1), MAX_COLUMNS - col);
                int rowspan = span(td.attr("rowspan"), 1);
                rowspan = rowspan == 0 ? rowCount - r : Math.min(rowspan, rowCount - r);
                Table.Cell cell = cell(cellDecl, colspan, frameBorder);
                cell.vMerge = rowspan > 1 ? 1 : 0;
                grid.get(r).put(col, new Slot(td, cell, cellDecl, cellLook));
                for (int below = r + 1; below < r + rowspan; below++) {
                    for (int c = col; c < col + colspan; c++) {
                        taken.get(below).add(c);
                    }
                    Table.Cell cont = cell(cellDecl, colspan, frameBorder);
                    cont.vMerge = 2;
                    grid.get(below).put(col, new Slot(null, cont, cellDecl, cellLook));
                }
                col += colspan;
                columns = Math.max(columns, col);
            }
            row.header |= allHeads && !tr.select("> th").isEmpty();
            t.rows.add(row);
        }
        if (columns == 0) {
            return null;
        }
        boolean leading = true;
        for (Table.Row row : t.rows) {
            leading &= row.header;
            row.header = leading;
        }
        float width = widths(t, table, d, look, frame, grid, columns);
        Integer tableFill = Css.color(d.get("background-color"));
        for (int r = 0; r < rowCount; r++) {
            Table.Row row = t.rows.get(r);
            int col = 0;
            for (Map.Entry<Integer, Slot> e : grid.get(r).entrySet()) {
                while (col < e.getKey()) {
                    row.cells.add(empty(frameBorder));
                    col++;
                }
                Slot s = e.getValue();
                if (s.cell().shading < 0 && tableFill != null && tableFill >= 0) {
                    s.cell().shading = tableFill;
                }
                if (s.element() != null) {
                    float room = 0;
                    for (int c = col; c < col + s.cell().gridSpan; c++) {
                        room += t.columnWidths.get(c);
                    }
                    contents(s, room - t.cellMarginLeft - t.cellMarginRight, depth + 2);
                }
                row.cells.add(s.cell());
                col += s.cell().gridSpan;
            }
            while (col < columns) {
                row.cells.add(empty(frameBorder));
                col++;
            }
        }
        outerBorders(t, d);
        indent(t, table, d, look, frame, width, inCentre);
        return t;
    }

    private static boolean hidden(Element e, Map<String, String> d, Look look) {
        return e.hasAttr("hidden") || "none".equalsIgnoreCase(d.getOrDefault("display", "").strip())
                || "collapse".equalsIgnoreCase(d.getOrDefault("visibility", "").strip()) || Hidden.visually(d, look.size);
    }

    private void contents(Slot s, float room, int depth) throws IOException {
        Flow flow = new Flow(ctx, b -> s.cell().paragraphs.add((Paragraph) b), true);
        flow.children(s.element(), s.look(), new Flow.Frame(0, 0, Math.max(MIN_COLUMN, room), -1), depth);
    }

    private static int span(String v, int fallback) {
        try {
            return Math.max(0, Math.min(1_000, Integer.parseInt(v.strip())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static float frameBorder(Element table) {
        if (!table.hasAttr("border")) {
            return 0;
        }
        String b = table.attr("border").strip();
        if (b.isEmpty()) {
            return 0.75f;
        }
        try {
            return Float.parseFloat(b) > 0 ? 0.75f : 0;
        } catch (NumberFormatException e) {
            return 0.75f;
        }
    }

    private static Table.Cell empty(float frameBorder) {
        return cell(Map.of(), 1, frameBorder);
    }

    private static Table.Cell cell(Map<String, String> d, int colspan, float frameBorder) {
        Table.Cell c = new Table.Cell();
        c.gridSpan = Math.max(1, colspan);
        c.top = border(d, "top", frameBorder);
        c.bottom = border(d, "bottom", frameBorder);
        c.left = border(d, "left", frameBorder);
        c.right = border(d, "right", frameBorder);
        Integer fill = Css.color(d.get("background-color"));
        c.shading = fill == null ? -1 : fill;
        String v = d.getOrDefault("vertical-align", "").strip().toLowerCase(Locale.ROOT);
        c.vAlign = switch (v) {
            case "middle", "center" -> Table.VAlign.CENTER;
            case "bottom" -> Table.VAlign.BOTTOM;
            default -> Table.VAlign.TOP;
        };
        return c;
    }

    static Table.Border border(Map<String, String> d, String side, float fallback) {
        float w = Flow.border(d, side);
        if (w <= 0) {
            return fallback > 0 && !d.containsKey("border-" + side) ? new Table.Border(fallback, 0x808080) : Table.Border.NONE;
        }
        Integer c = null;
        String color = d.get("border-" + side + "-color");
        if (color != null) {
            c = Css.color(color);
        }
        String all = d.get("border-" + side);
        if (c == null && all != null) {
            for (String token : Css.tokens(all)) {
                Integer tc = Css.color(token);
                if (tc != null) {
                    c = tc;
                }
            }
        }
        return new Table.Border(w, c == null || c < 0 ? 0 : c);
    }

    private static void outerBorders(Table t, Map<String, String> d) {
        int last = t.rows.size() - 1;
        for (int r = 0; r <= last; r++) {
            List<Table.Cell> cells = t.rows.get(r).cells;
            for (int i = 0; i < cells.size(); i++) {
                Table.Cell c = cells.get(i);
                if (r == 0) {
                    c.top = stronger(c.top, border(d, "top", 0));
                }
                if (r == last) {
                    c.bottom = stronger(c.bottom, border(d, "bottom", 0));
                }
                if (i == 0) {
                    c.left = stronger(c.left, border(d, "left", 0));
                }
                if (i == cells.size() - 1) {
                    c.right = stronger(c.right, border(d, "right", 0));
                }
            }
        }
    }

    private static Table.Border stronger(Table.Border a, Table.Border b) {
        return b.width() > a.width() ? b : a;
    }

    private float widths(Table t, Element table, Map<String, String> d, Look look, Flow.Frame frame,
            List<TreeMap<Integer, Slot>> grid, int columns) {
        float room = frame.width();
        Float given = width(d.get("width"), look, room);
        float[] fixed = new float[columns];
        float[] natural = new float[columns];
        float[] least = new float[columns];
        float[] unwrapped = new float[columns];
        Elements cols = table.select("> col, > colgroup > col");
        for (int c = 0; c < Math.min(columns, cols.size()); c++) {
            Float w = width(ctx.declared(cols.get(c), look).get("width"), look, given == null ? room : given);
            fixed[c] = w == null ? 0 : w;
        }
        float padding = padding(table, d, look, grid);
        t.cellMarginLeft = padding;
        t.cellMarginRight = padding;
        for (TreeMap<Integer, Slot> row : grid) {
            for (Map.Entry<Integer, Slot> e : row.entrySet()) {
                Slot s = e.getValue();
                if (s.element() == null || s.cell().gridSpan != 1) {
                    continue;
                }
                int c = e.getKey();
                Float w = width(s.declared().get("width"), s.look(), given == null ? room : given);
                if (w != null && fixed[c] == 0) {
                    fixed[c] = w + 2 * padding;
                }
                String text = s.element().text();
                float em = s.look().size * 0.5f;
                natural[c] = Math.max(natural[c], Math.min(room, text.length() * em + 2 * padding + 2));
                int longest = 0;
                for (String word : text.split("\\s+")) {
                    longest = Math.max(longest, word.length());
                }
                least[c] = Math.max(least[c], Math.min(room / 2, longest * em + 2 * padding));
                boolean nowrap = s.look().whiteSpace.equals("nowrap") || s.look().whiteSpace.equals("pre");
                unwrapped[c] = Math.max(unwrapped[c], (nowrap ? text.length() : longest) * em + 2 * padding);
            }
        }
        spanned(grid, natural, padding, room);
        float need = 0;
        for (int c = 0; c < columns; c++) {
            need += Math.max(Math.max(fixed[c], unwrapped[c]), MIN_COLUMN);
        }
        need = Math.max(need, given == null ? 0 : given);
        if (need > room) {
            ctx.wants(frame.left() + need);
        }
        float fixedSum = 0;
        float naturalSum = 0;
        for (int c = 0; c < columns; c++) {
            natural[c] = Math.max(Math.max(natural[c], least[c]), MIN_COLUMN);
            if (fixed[c] > 0) {
                fixedSum += fixed[c];
            } else {
                naturalSum += natural[c];
            }
        }
        float total = given != null ? given : Math.min(room, fixedSum + naturalSum);
        float rest = Math.max(0, total - fixedSum);
        float sum = 0;
        for (int c = 0; c < columns; c++) {
            float w = MIN_COLUMN;
            if (fixed[c] > 0) {
                w = fixed[c];
            } else if (naturalSum > 0) {
                w = rest * natural[c] / naturalSum;
            }
            w = Math.max(MIN_COLUMN, w);
            t.columnWidths.add(w);
            sum += w;
        }
        if (sum > room) {
            for (int c = 0; c < columns; c++) {
                t.columnWidths.set(c, Math.max(MIN_COLUMN, t.columnWidths.get(c) * room / sum));
            }
            sum = room;
        }
        floorAtLongestWord(t, least);
        return sum;
    }

    // A cell across columns widens them evenly when its text needs more than they have
    private static void spanned(List<TreeMap<Integer, Slot>> grid, float[] natural, float padding, float room) {
        for (TreeMap<Integer, Slot> row : grid) {
            for (Map.Entry<Integer, Slot> e : row.entrySet()) {
                Slot s = e.getValue();
                int span = s.cell().gridSpan;
                if (s.element() == null || span < 2) {
                    continue;
                }
                int from = e.getKey();
                int to = Math.min(natural.length, from + span);
                float need = Math.min(room, s.element().text().length() * s.look().size * 0.5f + 2 * padding + 2);
                float have = 0;
                for (int c = from; c < to; c++) {
                    have += Math.max(natural[c], MIN_COLUMN);
                }
                if (need > have) {
                    float add = (need - have) / (to - from);
                    for (int c = from; c < to; c++) {
                        natural[c] = Math.max(natural[c], MIN_COLUMN) + add;
                    }
                }
            }
        }
    }

    // A column narrower than its longest word breaks words letter by letter; wider columns give up the room
    private static void floorAtLongestWord(Table t, float[] least) {
        float need = 0;
        float spare = 0;
        for (int c = 0; c < least.length; c++) {
            float w = t.columnWidths.get(c);
            need += Math.max(0, least[c] - w);
            spare += Math.max(0, w - least[c]);
        }
        if (need <= 0 || spare <= 0) {
            return;
        }
        float take = Math.min(1f, need / spare);
        for (int c = 0; c < least.length; c++) {
            float w = t.columnWidths.get(c);
            float floor = least[c] * Math.min(1f, spare / need);
            t.columnWidths.set(c, w < least[c] ? Math.max(w, floor) : w - (w - least[c]) * take);
        }
    }

    private float padding(Element table, Map<String, String> d, Look look, List<TreeMap<Integer, Slot>> grid) {
        String cellpadding = table.attr("cellpadding").strip();
        if (!cellpadding.isEmpty()) {
            Float pt = Css.length(cellpadding.matches("[0-9.]+") ? cellpadding + "px" : cellpadding, look.size, 0);
            if (pt != null) {
                return Math.max(0, Math.min(36, pt));
            }
        }
        for (TreeMap<Integer, Slot> row : grid) {
            for (Slot s : row.values()) {
                if (s.element() != null) {
                    String p = s.declared().get("padding-left");
                    Float pt = p == null ? null : Css.length(p, s.look().size, 0);
                    return pt == null ? 1.5f : Math.max(0, Math.min(36, pt));
                }
            }
        }
        return 1.5f;
    }

    private static Float width(String v, Look look, float room) {
        if (v == null || v.equalsIgnoreCase("auto")) {
            return null;
        }
        Float pt = Css.length(v, look.size, room);
        return pt == null || pt <= 0 ? null : Math.min(pt, 14_400f);
    }

    private static void indent(Table t, Element table, Map<String, String> d, Look look, Flow.Frame frame, float width,
            boolean inCentre) {
        boolean centred = "auto".equalsIgnoreCase(d.getOrDefault("margin-left", "").strip())
                && "auto".equalsIgnoreCase(d.getOrDefault("margin-right", "").strip());
        float left = Flow.length(d.get("margin-left"), look.size, frame.width());
        if (centred || inCentre || "center".equalsIgnoreCase(table.attr("align"))) {
            left = Math.max(0, (frame.width() - width) / 2);
        }
        t.indent = frame.left() + left;
    }

    /** True for a table that only stacks things: one cell a row and no borders drawn. */
    static boolean layoutOnly(Element table, Map<String, String> d, HtmlContext ctx, Look look) {
        if (frameBorder(table) > 0 || Flow.border(d, "top") > 0 || Flow.border(d, "bottom") > 0) {
            return false;
        }
        Elements rows = table.select(ROWS);
        if (rows.isEmpty() || rows.size() > MAX_ROWS) {
            return false;
        }
        for (Element tr : rows) {
            Elements cells = tr.select("> td, > th");
            if (cells.size() > 1) {
                return false;
            }
            for (Element td : cells) {
                Map<String, String> cd = ctx.declared(td, look);
                if (Flow.border(cd, "top") > 0 || Flow.border(cd, "bottom") > 0 || Flow.border(cd, "left") > 0
                        || td.normalName().equals("th")) {
                    return false;
                }
            }
        }
        return true;
    }
}

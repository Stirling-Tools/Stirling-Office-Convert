package stirling.software.officeconvert.topdf.html;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import stirling.software.officeconvert.model.Block;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
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

    private record Slot(Element element, Table.Cell cell, Map<String, String> declared, Look look, float[] pad) {

        float across() {
            return pad[1] + pad[3];
        }
    }

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
        Float cellpadding = cellpadding(table, look);
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
                int colspan = Math.min(Math.max(1, span(td.attr("colspan"), 1)), MAX_COLUMNS - col);
                for (int c = col + 1; c < col + colspan; c++) {
                    if (taken.get(r).contains(c)) {
                        colspan = c - col;
                    }
                }
                int rowspan = span(td.attr("rowspan"), 1);
                rowspan = rowspan == 0 ? rowCount - r : Math.min(rowspan, rowCount - r);
                Table.Cell cell = cell(cellDecl, colspan, frameBorder);
                cell.vMerge = rowspan > 1 ? 1 : 0;
                float[] pad = pads(cellDecl, cellLook, cellpadding);
                grid.get(r).put(col, new Slot(td, cell, cellDecl, cellLook, pad));
                for (int below = r + 1; below < r + rowspan; below++) {
                    for (int c = col; c < col + colspan; c++) {
                        taken.get(below).add(c);
                    }
                    Table.Cell cont = cell(cellDecl, colspan, frameBorder);
                    cont.vMerge = 2;
                    grid.get(below).put(col, new Slot(null, cont, cellDecl, cellLook, pad));
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
                    contents(s, t, room, depth + 2);
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

    private void contents(Slot s, Table t, float room, int depth) throws IOException {
        float extraLeft = Math.max(0, s.pad()[3] - t.cellMarginLeft);
        float extraRight = Math.max(0, s.pad()[1] - t.cellMarginRight);
        float inner = room - t.cellMarginLeft - t.cellMarginRight;
        if (inner - extraLeft - extraRight < MIN_COLUMN) {
            extraLeft = 0;
            extraRight = 0;
        }
        Flow flow = new Flow(ctx, s.cell()::add, true);
        flow.children(s.element(), s.look(), new Flow.Frame(0, 0, Math.max(MIN_COLUMN, inner - extraLeft - extraRight), -1),
                depth);
        if (s.cell().empty()) {
            return;
        }
        List<Block> blocks = s.cell().blocks();
        for (Block b : blocks) {
            switch (b) {
                case Paragraph p -> {
                    p.indentLeft += extraLeft;
                    p.indentRight += extraRight;
                }
                case Table nested -> nested.indent += nested.rightToLeft ? extraRight : extraLeft;
            }
        }
        if (blocks.getFirst() instanceof Paragraph first) {
            first.spaceBefore += s.pad()[0];
        }
        if (blocks.getLast() instanceof Paragraph last) {
            last.spaceAfter += s.pad()[2];
        }
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
        float[] margins = margins(grid);
        t.cellMarginLeft = margins[0];
        t.cellMarginRight = margins[1];
        for (TreeMap<Integer, Slot> row : grid) {
            for (Map.Entry<Integer, Slot> e : row.entrySet()) {
                Slot s = e.getValue();
                if (s.element() == null || s.cell().gridSpan != 1) {
                    continue;
                }
                int c = e.getKey();
                Float w = width(s.declared().get("width"), s.look(), given == null ? room : given);
                float across = s.across();
                if (w != null && fixed[c] == 0) {
                    fixed[c] = w + across;
                }
                String text = s.element().text();
                float size = s.look().size;
                natural[c] = Math.max(natural[c], Math.min(room, Glyphs.estimate(text, size, s.look().font, s.look().bold)
                        + across + 2));
                float longest = 0;
                for (String word : text.split("\\s+")) {
                    longest = Math.max(longest, Glyphs.word(word, size, s.look().bold, s.look().font));
                }
                least[c] = Math.max(least[c], Math.min(room / 2, longest + across));
                least[c] = Math.max(least[c], Math.min(room, leastInside(s.element(), size * 0.5f, 1) + across));
                boolean nowrap = s.look().whiteSpace.equals("nowrap") || s.look().whiteSpace.equals("pre");
                unwrapped[c] = Math.max(unwrapped[c], (nowrap ? Glyphs.estimate(text, size, s.look().font, s.look().bold) : longest) + across);
                float[] pictures = pictures(s, room);
                if (pictures[0] > 0) {
                    natural[c] = Math.max(natural[c], Math.min(room, pictures[1] + across));
                    least[c] = Math.max(least[c], Math.min(room, pictures[0] + across));
                    unwrapped[c] = Math.max(unwrapped[c], pictures[0] + across);
                }
            }
        }
        spanned(grid, natural, room);
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

    private static float leastInside(Element cell, float em, int level) {
        if (level >= Table.MAX_NESTING) {
            return 0;
        }
        float most = 0;
        ArrayDeque<Element> open = new ArrayDeque<>(cell.children());
        while (!open.isEmpty()) {
            Element e = open.pop();
            if (e.normalName().equals("table")) {
                most = Math.max(most, leastTable(e, em, level + 1));
            } else {
                e.children().reversed().forEach(open::push);
            }
        }
        return most;
    }

    static float least(Element table, Look look) {
        return leastTable(table, look.size * 0.5f, 1);
    }

    private static float leastTable(Element table, float em, int level) {
        float[] columns = new float[MAX_COLUMNS];
        int used = 0;
        Elements rows = table.select(ROWS);
        for (int r = 0; r < Math.min(rows.size(), MAX_ROWS); r++) {
            int col = 0;
            for (Element td : rows.get(r).select("> td, > th")) {
                int span = Math.max(1, span(td.attr("colspan"), 1));
                if (col + span > MAX_COLUMNS) {
                    break;
                }
                if (span == 1) {
                    int longest = 0;
                    for (String word : td.text().split("\\s+")) {
                        longest = Math.max(longest, word.length());
                    }
                    columns[col] = Math.max(columns[col], Math.max(longest * em, leastInside(td, em, level)));
                }
                col += span;
                used = Math.max(used, col);
            }
        }
        float pad = 2 * frameBorder(table) + 2 * cellPadding(table);
        float sum = 0;
        for (int c = 0; c < used; c++) {
            sum += Math.max(MIN_COLUMN, columns[c] + pad);
        }
        return sum;
    }

    private static float cellPadding(Element table) {
        String v = table.attr("cellpadding").strip();
        try {
            float px = v.isEmpty() ? -1 : Float.parseFloat(v);
            return px >= 0 ? Math.min(36, px * 0.75f) : 1.5f;
        } catch (NumberFormatException e) {
            return 1.5f;
        }
    }

    // A cell across columns widens them evenly when its text needs more than they have
    private static void spanned(List<TreeMap<Integer, Slot>> grid, float[] natural, float room) {
        for (TreeMap<Integer, Slot> row : grid) {
            for (Map.Entry<Integer, Slot> e : row.entrySet()) {
                Slot s = e.getValue();
                int span = s.cell().gridSpan;
                if (s.element() == null || span < 2) {
                    continue;
                }
                int from = e.getKey();
                int to = Math.min(natural.length, from + span);
                float need = Math.min(room, Glyphs.estimate(s.element().text(), s.look().size) + s.across() + 2);
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

    private static Float cellpadding(Element table, Look look) {
        String cellpadding = table.attr("cellpadding").strip();
        if (cellpadding.isEmpty()) {
            return null;
        }
        Float pt = Css.length(cellpadding.matches("[0-9.]+") ? cellpadding + "px" : cellpadding, look.size, 0);
        return pt == null ? null : Math.max(0, Math.min(36, pt));
    }

    private static float[] pads(Map<String, String> d, Look look, Float cellpadding) {
        String[] sides = {"top", "right", "bottom", "left"};
        float[] pad = new float[4];
        for (int i = 0; i < 4; i++) {
            String v = d.get("padding-" + sides[i]);
            Float pt = v == null || v.contains("%") ? null : Css.length(v, look.size, 0);
            if (pt == null) {
                pt = cellpadding != null ? cellpadding : i % 2 == 1 ? 1.5f : 0f;
            }
            pad[i] = Math.max(0, Math.min(36, pt));
        }
        return pad;
    }

    private static float[] margins(List<TreeMap<Integer, Slot>> grid) {
        float left = Float.MAX_VALUE;
        float right = Float.MAX_VALUE;
        for (TreeMap<Integer, Slot> row : grid) {
            for (Slot s : row.values()) {
                if (s.element() != null) {
                    left = Math.min(left, s.pad()[3]);
                    right = Math.min(right, s.pad()[1]);
                }
            }
        }
        return left == Float.MAX_VALUE ? new float[] {1.5f, 1.5f} : new float[] {left, right};
    }

    private float[] pictures(Slot s, float room) {
        float widest = 0;
        float all = 0;
        for (Element img : s.element().select("img, svg")) {
            Float w = pictureWidth(img, s.look(), room);
            if (w != null) {
                widest = Math.max(widest, w);
                all += w;
            }
        }
        return new float[] {Math.min(room, widest), Math.min(room, all)};
    }

    private Float pictureWidth(Element img, Look look, float room) {
        Map<String, String> d = ctx.declared(img, look);
        String v = d.get("width");
        if (v != null && !v.contains("%") && !v.equalsIgnoreCase("auto")) {
            Float pt = Css.length(v, look.size, room);
            return pt == null || pt <= 0 ? null : pt;
        }
        if (v != null || !img.normalName().equals("img")) {
            return null;
        }
        String src = img.attr("src").strip();
        if (!src.regionMatches(true, 0, "data:image/", 0, 11) || src.regionMatches(true, 0, "data:image/svg", 0, 14)
                || src.length() >= 1 << 20) {
            return null;
        }
        try {
            Picture pic = ctx.images().picture(img, d, look, room, ctx.pageRoom());
            return pic == null ? null : pic.width;
        } catch (IOException e) {
            return null;
        }
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
        if (look.rightToLeft) {
            t.rightToLeft = true;
            float right = centred || inCentre || "center".equalsIgnoreCase(table.attr("align")) ? left
                    : Flow.length(d.get("margin-right"), look.size, frame.width());
            t.indent = frame.right() + right;
        }
    }

    private static final int BUTTON_TEXT = 60;

    // A short cell with its own fill in a table of no set width is a button, which keeps its width
    private static boolean filled(Map<String, String> cd, Element td) {
        Integer fill = Css.color(cd.getOrDefault("background-color", td.attr("bgcolor")));
        return fill != null && fill >= 0 && fill != 0xFFFFFF;
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
        String given = table.hasAttr("width") ? table.attr("width") : d.getOrDefault("width", "auto");
        boolean shrinks = given.isBlank() || given.strip().equalsIgnoreCase("auto");
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
                if (shrinks && filled(cd, td) && Layout.text(td, BUTTON_TEXT + 1).length() <= BUTTON_TEXT) {
                    return false;
                }
            }
        }
        return true;
    }
}

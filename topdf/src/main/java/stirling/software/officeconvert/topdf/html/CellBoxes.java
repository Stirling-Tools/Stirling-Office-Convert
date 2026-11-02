package stirling.software.officeconvert.topdf.html;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jsoup.nodes.Element;

import stirling.software.officeconvert.model.Block;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.Table;

final class CellBoxes {

    private CellBoxes() {}

    static boolean gradient(HtmlContext ctx, Table.Cell cell, String css, float width, float height, float padLeft,
            float corner, Look look) throws IOException {
        Gradient g = Gradient.parse(css);
        Picture pic = g == null ? null : ctx.images().gradient(g, width, height, corner);
        if (pic == null) {
            return false;
        }
        pic.wrap = Picture.Wrap.NONE;
        pic.behind = true;
        pic.x = -padLeft;
        pic.y = 0;
        if (!(cell.blocks().getFirst() instanceof Paragraph)) {
            cell.tables.replaceAll(n -> new Table.Nested(n.before() + 1, n.table()));
            cell.paragraphs.addFirst(bar(1, look));
        }
        cell.paragraphs.getFirst().inlines.addFirst(new Inline.Image(pic));
        cell.shading = -1;
        return true;
    }

    static boolean decorate(HtmlContext ctx, Look blockLook, Table.Cell cell, Map<String, String> d, Look look, float x,
            float width, float height, int backdrop) throws IOException {
        float corner = BoxCss.corner(d, look, width, height);
        List<BoxPaint.Shadow> shadows = ctx.shadowsLeft() ? BoxPaint.shadows(d.get("box-shadow"), look.size, look.color)
                : List.of();
        Gradient gradient = Gradient.parse(d.get("background-gradient"));
        if (corner <= 0 && shadows.isEmpty() && gradient == null || cell.empty() || !(height >= 1)
                || height > ctx.pageRoom() * BoxPaint.MAX_PAGE_SHARE) {
            return false;
        }
        Table.Border edge = BoxPaint.even(cell);
        if (corner > 0 && edge == null && BoxPaint.bordered(cell)) {
            corner = 0;
        }
        Picture pic = gradient == null ? null : ctx.images().gradient(gradient, width, height, corner);
        if (pic == null && corner <= 0 && shadows.isEmpty()) {
            return false;
        }
        int fill = cell.shading;
        int ground = backdrop >= 0 ? backdrop : ctx.canvas();
        boolean stroked = pic == null && corner > 0 && edge != null;
        Integer own = Css.color(d.get("background-color"));
        int cover = fill >= 0 ? fill : own != null && own >= 0 ? own : shadows.isEmpty() ? -1
                : ground >= 0 ? ground : 0xFFFFFF;
        if (pic == null && cover < 0 && !stroked) {
            return false;
        }
        List<Inline> drawn = new ArrayList<>(BoxPaint.shadows(shadows, x, 0, width, height, corner, ground));
        ctx.drewShadows(drawn.size());
        if (pic != null) {
            pic.wrap = Picture.Wrap.NONE;
            pic.behind = true;
            pic.x = x;
            pic.y = 0;
            drawn.add(new Inline.Image(pic));
        } else {
            float inset = stroked ? edge.width() / 2 : 0;
            drawn.add(new Inline.Shape(x + inset, inset, width - 2 * inset, height - 2 * inset, cover,
                    stroked ? edge.rgb() : -1, stroked ? edge.width() : 0, corner > 0, false, true, 0,
                    Math.max(0, corner - inset)));
            if (stroked) {
                cell.top = Table.Border.NONE;
                cell.bottom = Table.Border.NONE;
                cell.left = Table.Border.NONE;
                cell.right = Table.Border.NONE;
            }
        }
        if (cell.blocks().getFirst() instanceof Paragraph first) {
            first.inlines.addAll(0, drawn);
        } else {
            cell.tables.replaceAll(n -> new Table.Nested(n.before() + 1, n.table()));
            cell.paragraphs.addFirst(Flow.anchorParagraph(drawn, 0, blockLook.run()));
        }
        cell.shading = -1;
        for (Paragraph p : cell.paragraphs) {
            if (p.shading == fill) {
                p.shading = -1;
            }
        }
        return true;
    }

    static boolean itemGradient(HtmlContext ctx, Table.Row row, Table.Cell cell, Element item, Look look, float width,
            float margin) throws IOException {
        Map<String, String> d = ctx.declared(item, look);
        Float tall = BoxCss.boxLength(d.get("height"), look, 0);
        if (tall == null || tall < 1 || cell.empty() || Gradient.parse(d.get("background-gradient")) == null) {
            return false;
        }
        boolean borderBox = "border-box".equalsIgnoreCase(d.getOrDefault("box-sizing", "").strip());
        float high = Math.min(ctx.pageRoom(), tall + (borderBox ? 0
                : Math.max(0, BoxCss.padding(d.get("padding-top"), look.size, width))
                + Math.max(0, BoxCss.padding(d.get("padding-bottom"), look.size, width))));
        int fill = cell.shading;
        if (gradient(ctx, cell, d.get("background-gradient"), width, high, margin, BoxCss.corner(d, look, width, high),
                look)) {
            for (Paragraph p : cell.paragraphs) {
                if (p.shading == fill) {
                    p.shading = -1;
                }
            }
            row.height = Math.max(row.height, high);
            return true;
        }
        return false;
    }

    static Paragraph bar(float height, Look look) {
        Paragraph p = new Paragraph();
        p.lineRule = Paragraph.LineRule.EXACT;
        p.lineHeight = Math.max(1, Math.min(height, 400));
        p.inlines.add(new Inline.Text("\u00a0", look.run().withSize(1), null, -1));
        return p;
    }

    static Map<String, String> framedChild(HtmlContext ctx, Element item, Map<String, String> d, Look look) {
        if (Layout.framed(d) || BoxCss.shadowed(d) || item.childrenSize() != 1 || !item.ownText().isBlank()) {
            return null;
        }
        Element only = item.child(0);
        Map<String, String> cd = ctx.declared(only, look);
        String display = cd.getOrDefault("display", Flow.BLOCKS.contains(only.normalName()) ? "block" : "inline")
                .strip().toLowerCase(Locale.ROOT);
        if (!display.equals("block") || !(Layout.framed(cd) || BoxCss.shadowed(cd) || BoxCss.hasEdges(cd))) {
            return null;
        }
        return cd;
    }

    static float gutter(HtmlContext ctx, List<Element> items, Look look, Flow.Frame frame) {
        float widest = 0;
        for (Element item : items) {
            Map<String, String> d = ctx.declared(item, look);
            if (framedChild(ctx, item, d, look) != null) {
                widest = Math.max(widest, Math.max(0, BoxCss.length(d.get("padding-left"), look.size, frame.width()))
                        + Math.max(0, BoxCss.length(d.get("padding-right"), look.size, frame.width())));
            }
        }
        return Math.min(72, widest);
    }

    static void decorate(HtmlContext ctx, Look blockLook, Table.Row row, Table t, Map<Table.Cell, Element> placed,
            Set<Table.Cell> painted, Look look, Flow.Frame frame) throws IOException {
        int columns = Math.min(row.cells.size(), t.columnWidths.size());
        List<Map<String, String>> styles = new ArrayList<>(Collections.nCopies(columns, null));
        boolean wanted = false;
        for (int c = 0; c < columns; c++) {
            Table.Cell cell = row.cells.get(c);
            Element item = placed.get(cell);
            if (item == null || painted.contains(cell)) {
                continue;
            }
            Map<String, String> d = ctx.declared(item, look);
            Map<String, String> box = framedChild(ctx, item, d, look);
            d = box != null ? box : d;
            styles.set(c, d);
            wanted |= d.containsKey("border-radius") || d.containsKey("box-shadow")
                    || d.containsKey("background-gradient");
        }
        if (!wanted) {
            return;
        }
        float tallest = row.height;
        for (int c = 0; c < columns; c++) {
            Table.Cell cell = row.cells.get(c);
            float w = t.columnWidths.get(c) - t.cellMarginLeft - t.cellMarginRight;
            tallest = Math.max(tallest, Measure.height(cell.blocks(), w) + spaceAfter(cell.blocks())
                    + cell.top.width() + cell.bottom.width());
        }
        boolean any = false;
        for (int c = 0; c < columns; c++) {
            Table.Cell cell = row.cells.get(c);
            Map<String, String> d = styles.get(c);
            if (d == null) {
                continue;
            }
            boolean shadowEdge = cell.top.equals(new Table.Border(0.5f, BoxCss.SHADOW_EDGE))
                    && cell.top.equals(cell.bottom) && cell.top.equals(cell.left) && cell.top.equals(cell.right);
            if (shadowEdge && !BoxPaint.shadows(d.get("box-shadow"), look.size, look.color).isEmpty()) {
                cell.top = Table.Border.NONE;
                cell.bottom = Table.Border.NONE;
                cell.left = Table.Border.NONE;
                cell.right = Table.Border.NONE;
            }
            float w = t.columnWidths.get(c) - t.cellMarginLeft - t.cellMarginRight;
            if (decorate(ctx, blockLook, cell, d, look, 0, w, tallest, frame.shading())) {
                any = true;
            } else if (shadowEdge) {
                Table.Border edge = new Table.Border(0.5f, BoxCss.SHADOW_EDGE);
                cell.top = edge;
                cell.bottom = edge;
                cell.left = edge;
                cell.right = edge;
            }
        }
        if (any) {
            row.splits = false;
            row.height = Math.max(row.height, tallest);
        }
    }

    private static float spaceAfter(List<Block> blocks) {
        float total = 0;
        for (Block b : blocks) {
            if (b instanceof Paragraph p) {
                total += Math.max(0, p.spaceAfter);
            }
        }
        return total;
    }

    static void gapColumns(Table t, float gap) {
        int n = t.columnWidths.size();
        if (n < 2) {
            return;
        }
        float give = gap * (n - 1) / n;
        for (int c = n - 1; c >= 0; c--) {
            t.columnWidths.set(c, Math.max(24, t.columnWidths.get(c) - give));
            if (c > 0) {
                t.columnWidths.add(c, gap);
                for (Table.Row row : t.rows) {
                    row.cells.add(c, new Table.Cell());
                }
            }
        }
        for (int r = t.rows.size() - 1; r > 0; r--) {
            Table.Row spacer = new Table.Row();
            spacer.height = gap;
            spacer.exactHeight = true;
            for (int c = 0; c < t.columnWidths.size(); c++) {
                spacer.cells.add(new Table.Cell());
            }
            t.rows.add(r, spacer);
        }
        t.cellMarginLeft = 0;
        t.cellMarginRight = 0;
    }

    // Items that drew nothing (a picture that could not be read, an empty wrapper) leave no column behind
    static void dropEmptyColumns(Table t) {
        float room = 0;
        for (float w : t.columnWidths) {
            room += w;
        }
        for (int c = t.columnWidths.size() - 1; c >= 0; c--) {
            boolean empty = true;
            for (Table.Row row : t.rows) {
                empty &= row.cells.get(c).empty();
            }
            if (empty) {
                t.columnWidths.remove(c);
                for (Table.Row row : t.rows) {
                    row.cells.remove(c);
                }
            }
        }
        float left = 0;
        for (float w : t.columnWidths) {
            left += w;
        }
        for (int c = 0; c < t.columnWidths.size() && left > 0; c++) {
            t.columnWidths.set(c, t.columnWidths.get(c) * room / left);
        }
    }
}

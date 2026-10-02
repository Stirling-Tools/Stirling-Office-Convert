package stirling.software.officeconvert.topdf.rtf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

final class TableBuilder {

    static final int MAX_GRID = 1024;

    private static final int DEFAULT_MARGIN = 108;

    private static final int DEFAULT_WIDTH = 1440;

    private static final int CELL_XML = 72;

    private static final int ROW_XML = 96;

    private record Row(RowProps props, List<String> cells, String trPr, String[] tcPr) {}

    private final ColorTable colors;

    private final Budget budget;

    private final List<Row> rows = new ArrayList<>();

    private List<String> cells = new ArrayList<>();

    private StringBuilder cell = new StringBuilder();

    TableBuilder(ColorTable colors, Budget budget) {
        this.colors = colors;
        this.budget = budget;
    }

    void block(String xml) {
        budget.charge(xml.length());
        cell.append(xml);
    }

    void nested(CharSequence chargedXml) {
        cell.append(chargedXml);
    }

    void endCell() {
        budget.charge(CELL_XML);
        cells.add(cell.toString());
        cell = new StringBuilder();
    }

    void endRow(RowProps props) {
        if (!cell.isEmpty()) {
            endCell();
        }
        int n = Math.max(1, Math.max(props.cells.size(), cells.size()));
        Row last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
        boolean same = last != null && last.tcPr().length == n && sameCells(last.props(), props);
        String trPr = same ? last.trPr() : trPr(props);
        String[] tcPr = same ? last.tcPr() : new String[n];
        long cost = ROW_XML + trPr.length() + (long) CELL_XML * (n - cells.size());
        for (int i = 0; i < n; i++) {
            if (!same) {
                tcPr[i] = tcPr(i < props.cells.size() ? props.cells.get(i) : new RowProps.CellDef(), props, colors);
            }
            cost += tcPr[i].length();
        }
        budget.charge(cost);
        rows.add(new Row(props, cells, trPr, tcPr));
        cells = new ArrayList<>();
    }

    private static boolean sameCells(RowProps a, RowProps b) {
        if (a.cells.size() != b.cells.size() || a.keep != b.keep || a.height != b.height || a.header != b.header
                || a.shadeColor != b.shadeColor) {
            return false;
        }
        for (int i = 0; i < a.cells.size(); i++) {
            if (a.cells.get(i) != b.cells.get(i)) {
                return false;
            }
        }
        return true;
    }

    boolean pending() {
        return !cell.isEmpty() || !cells.isEmpty();
    }

    void write(Appendable out, RowProps last) throws IOException {
        if (pending()) {
            endRow(last == null ? new RowProps() : last);
        }
        if (rows.isEmpty()) {
            return;
        }
        List<int[]> edges = new ArrayList<>();
        TreeSet<Integer> bounds = new TreeSet<>();
        for (Row r : rows) {
            int[] e = edges(r);
            edges.add(e);
            for (int v : e) {
                if (bounds.size() < MAX_GRID) {
                    bounds.add(v);
                }
            }
        }
        Integer[] grid = bounds.toArray(new Integer[0]);
        String head = head(rows.get(0).props(), grid);
        budget.charge(head.length() + "</w:tbl>".length());
        out.append(head);
        for (int i = 0; i < rows.size(); i++) {
            StringBuilder b = new StringBuilder(1024);
            row(b, rows.get(i), edges.get(i), grid);
            rows.set(i, null);
            out.append(b);
        }
        rows.clear();
        out.append("</w:tbl>");
    }

    private String head(RowProps first, Integer[] grid) {
        int marLeft = margin(first.padLeft, first.gap);
        int marRight = margin(first.padRight, first.gap);
        StringBuilder b = new StringBuilder(4096);
        b.append("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/>");
        if (first.align != null) {
            b.append("<w:jc w:val=\"").append(first.align).append("\"/>");
        }
        CellCheck firstCell = new CellCheck(first);
        int ind = grid[0] + (firstCell.padLeft != null ? firstCell.padLeft : marLeft);
        if (first.align == null) {
            b.append("<w:tblInd w:w=\"").append(ind).append("\" w:type=\"dxa\"/>");
        }
        String borders = borders(colors, first.top, first.leftBorder, first.bottom, first.rightBorder, first.insideH,
                first.insideV);
        if (!borders.isEmpty()) {
            b.append("<w:tblBorders>").append(borders).append("</w:tblBorders>");
        }
        if (first.floating != null && (first.floating.x != null || first.floating.y != null
                || first.floating.xSpec != null || first.floating.ySpec != null)) {
            b.append(first.floating.xml());
        }
        if (!first.autofit) {
            b.append("<w:tblLayout w:type=\"fixed\"/>");
        }
        b.append("<w:tblCellMar>");
        if (first.padTop != null) {
            b.append("<w:top w:w=\"").append(first.padTop).append("\" w:type=\"dxa\"/>");
        }
        b.append("<w:left w:w=\"").append(marLeft).append("\" w:type=\"dxa\"/>");
        if (first.padBottom != null) {
            b.append("<w:bottom w:w=\"").append(first.padBottom).append("\" w:type=\"dxa\"/>");
        }
        b.append("<w:right w:w=\"").append(marRight).append("\" w:type=\"dxa\"/></w:tblCellMar>");
        if (first.rtl) {
            b.append("<w:bidiVisual/>");
        }
        b.append("</w:tblPr><w:tblGrid>");
        for (int i = 1; i < grid.length; i++) {
            b.append("<w:gridCol w:w=\"").append(grid[i] - grid[i - 1]).append("\"/>");
        }
        if (grid.length == 1) {
            b.append("<w:gridCol w:w=\"").append(DEFAULT_WIDTH).append("\"/>");
        }
        return b.append("</w:tblGrid>").toString();
    }

    private static int margin(Integer pad, int gap) {
        if (pad != null) {
            return pad;
        }
        return gap >= 0 ? gap : DEFAULT_MARGIN;
    }

    private record CellCheck(Integer padLeft) {
        CellCheck(RowProps p) {
            this(p.cells.isEmpty() ? null : p.cells.get(0).padLeft);
        }
    }

    private static int[] edges(Row r) {
        RowProps p = r.props();
        int n = Math.max(p.cells.size(), r.cells().size());
        n = Math.max(1, n);
        int[] e = new int[n + 1];
        e[0] = p.left;
        for (int i = 0; i < n; i++) {
            RowProps.CellDef c = i < p.cells.size() ? p.cells.get(i) : null;
            int edge = c == null ? e[i] : c.edge;
            if (edge <= e[i]) {
                int w = c != null && c.widthType == 3 && c.width > 0 ? c.width
                        : invalid(p) ? p.widthType == 3 && p.width > 0 ? p.width / n : DEFAULT_WIDTH : 0;
                edge = e[i] + w;
            }
            e[i + 1] = edge;
        }
        return e;
    }

    private static boolean invalid(RowProps p) {
        for (RowProps.CellDef c : p.cells) {
            if (c.edge > p.left) {
                return false;
            }
        }
        return true;
    }

    private static int index(Integer[] grid, int v) {
        int lo = 0;
        int hi = grid.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (grid[mid] < v) {
                lo = mid + 1;
            } else if (grid[mid] > v) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return Math.max(0, Math.min(grid.length - 1, lo));
    }

    private static String trPr(RowProps p) {
        StringBuilder b = new StringBuilder();
        if (p.keep) {
            b.append("<w:cantSplit/>");
        }
        if (p.height != 0) {
            b.append("<w:trHeight w:val=\"").append(Math.abs(p.height)).append("\" w:hRule=\"")
                    .append(p.height < 0 ? "exact" : "atLeast").append("\"/>");
        }
        if (p.header) {
            b.append("<w:tblHeader/>");
        }
        return b.toString();
    }

    private static String tcPr(RowProps.CellDef d, RowProps p, ColorTable colors) {
        StringBuilder b = new StringBuilder();
        if (d.vmergeFirst) {
            b.append("<w:vMerge w:val=\"restart\"/>");
        } else if (d.vmerged) {
            b.append("<w:vMerge/>");
        }
        String borders = borders(colors, d.top, d.left, d.bottom, d.right, null, null);
        if (!borders.isEmpty()) {
            b.append("<w:tcBorders>").append(borders).append("</w:tcBorders>");
        }
        String shd = d.shade.xml(colors);
        if (shd.isEmpty() && p.shadeColor >= 0 && colors.explicit(p.shadeColor)) {
            shd = "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"" + colors.hex(p.shadeColor) + "\"/>";
        }
        b.append(shd);
        if (d.noWrap) {
            b.append("<w:noWrap/>");
        }
        if (d.padLeft != null || d.padRight != null || d.padTop != null || d.padBottom != null) {
            b.append("<w:tcMar>");
            pad(b, "top", d.padTop);
            pad(b, "left", d.padLeft);
            pad(b, "bottom", d.padBottom);
            pad(b, "right", d.padRight);
            b.append("</w:tcMar>");
        }
        if (d.direction != null) {
            b.append("<w:textDirection w:val=\"").append(d.direction).append("\"/>");
        }
        if (d.valign != null) {
            b.append("<w:vAlign w:val=\"").append(d.valign).append("\"/>");
        }
        return b.toString();
    }

    private static void row(StringBuilder b, Row r, int[] e, Integer[] grid) {
        RowProps p = r.props();
        b.append("<w:tr><w:trPr>");
        int before = index(grid, e[0]);
        if (before > 0) {
            b.append("<w:gridBefore w:val=\"").append(before).append("\"/><w:wBefore w:w=\"")
                    .append(e[0] - grid[0]).append("\" w:type=\"dxa\"/>");
        }
        int after = grid.length - 1 - index(grid, e[e.length - 1]);
        if (after > 0) {
            b.append("<w:gridAfter w:val=\"").append(after).append("\"/>");
        }
        b.append(r.trPr()).append("</w:trPr>");
        int n = e.length - 1;
        int i = 0;
        StringBuilder carry = new StringBuilder();
        int lastEnd = -1;
        while (i < n) {
            RowProps.CellDef d = i < p.cells.size() ? p.cells.get(i) : null;
            int j = i + 1;
            while (j < n && j < p.cells.size() && p.cells.get(j).merged && d != null && d.mergeFirst) {
                j++;
            }
            int lo = e[i];
            int hi = e[j];
            if (hi <= lo) {
                if (i < r.cells().size()) {
                    carry.append(r.cells().get(i));
                }
                i = j;
                continue;
            }
            int span = index(grid, hi) - index(grid, lo);
            b.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(hi - lo).append("\" w:type=\"dxa\"/>");
            if (span > 1) {
                b.append("<w:gridSpan w:val=\"").append(span).append("\"/>");
            }
            b.append(r.tcPr()[i]).append("</w:tcPr>");
            String content = carry + (i < r.cells().size() ? r.cells().get(i) : "");
            carry.setLength(0);
            b.append(content);
            if (content.isEmpty() || content.endsWith("</w:tbl>")) {
                b.append("<w:p/>");
            }
            lastEnd = b.length();
            b.append("</w:tc>");
            i = j;
        }
        if (!carry.isEmpty() && lastEnd >= 0) {
            if (carry.toString().endsWith("</w:tbl>")) {
                carry.append("<w:p/>");
            }
            b.insert(lastEnd, carry);
        }
        b.append("</w:tr>");
    }

    private static void pad(StringBuilder b, String side, Integer v) {
        if (v != null) {
            b.append("<w:").append(side).append(" w:w=\"").append(v).append("\" w:type=\"dxa\"/>");
        }
    }

    static String borders(ColorTable colors, Border top, Border left, Border bottom, Border right, Border insideH,
            Border insideV) {
        StringBuilder b = new StringBuilder();
        if (top != null) {
            b.append(top.xml("top", colors));
        }
        if (left != null) {
            b.append(left.xml("left", colors));
        }
        if (bottom != null) {
            b.append(bottom.xml("bottom", colors));
        }
        if (right != null) {
            b.append(right.xml("right", colors));
        }
        if (insideH != null) {
            b.append(insideH.xml("insideH", colors));
        }
        if (insideV != null) {
            b.append(insideV.xml("insideV", colors));
        }
        return b.toString();
    }
}

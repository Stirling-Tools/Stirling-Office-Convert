package stirling.software.officeconvert.topdf.odf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.w3c.dom.Element;

/** ODF text tables written as Word tables: column widths, row heights, spans (covered cells become merged cells),
 * borders, padding, shading and header rows. */
final class WordTables {

    static final int MAX_COLUMNS = 63;

    static final int MAX_ROWS = 50_000;

    private final OdtWriter w;

    WordTables(OdtWriter w) {
        this.w = w;
    }

    private record Row(Element row, boolean header) {}

    String table(Element t, TextBody body) throws IOException {
        Styles.Scope scope = body.scope;
        String name = Dom.attr(t, Ns.TABLE, "style-name");
        Props tp = w.styles.props("table", name, scope, "table-properties", false);
        List<Double> widths = new ArrayList<>();
        List<String> defaultCellStyles = new ArrayList<>();
        List<Double> relWidths = new ArrayList<>();
        columns(t, scope, widths, relWidths, defaultCellStyles, 0);
        List<Row> rows = new ArrayList<>();
        rows(t, rows, false, 0);
        int gridCount = Math.min(MAX_COLUMNS, Math.max(widths.size(), maxCells(rows)));
        if (gridCount == 0 || rows.isEmpty()) {
            return null;
        }
        while (widths.size() < gridCount) {
            widths.add(Double.NaN);
        }
        double tableWidth = tp.pt("style:width", Double.NaN);
        if (Double.isNaN(tableWidth) || "margins".equals(tp.get("table:align"))) {
            double avail = w.textWidth(body) - tp.pt("fo:margin-left", 0) - tp.pt("fo:margin-right", 0);
            tableWidth = avail * Length.percent(tp.get("style:rel-width"), 100) / 100;
        }
        relative(widths, relWidths, tableWidth);
        double known = 0;
        int unknown = 0;
        for (int i = 0; i < gridCount; i++) {
            if (Double.isNaN(widths.get(i))) {
                unknown++;
            } else {
                known += widths.get(i);
            }
        }
        if (unknown > 0) {
            double rest = Double.isNaN(tableWidth) ? 72 * unknown : Math.max(18 * unknown, tableWidth - known);
            for (int i = 0; i < gridCount; i++) {
                if (Double.isNaN(widths.get(i))) {
                    widths.set(i, rest / unknown);
                }
            }
        }
        double sum = 0;
        for (int i = 0; i < gridCount; i++) {
            sum += widths.get(i);
        }
        StringBuilder b = new StringBuilder("<w:tbl><w:tblPr>");
        b.append("<w:tblW w:w=\"").append(Length.twips(sum)).append("\" w:type=\"dxa\"/>");
        String align = tp.get("table:align", "left");
        switch (align) {
            case "center" -> b.append("<w:jc w:val=\"center\"/>");
            case "right" -> b.append("<w:jc w:val=\"right\"/>");
            default -> {
            }
        }
        if (!align.equals("center") && !align.equals("right")) {
            double ind = tp.pt("fo:margin-left", 0);
            if (w.compatibilityMode < 15) {
                ind += firstCellPadding(t, scope);
            }
            b.append("<w:tblInd w:w=\"").append(Length.twips(ind)).append("\" w:type=\"dxa\"/>");
        }
        String bg = Colors.fill(tp.get("fo:background-color"));
        if (bg != null) {
            b.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(bg).append("\"/>");
        }
        b.append("<w:tblLayout w:type=\"fixed\"/>");
        b.append("<w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"0\" w:type=\"dxa\"/>"
                + "<w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"0\" w:type=\"dxa\"/></w:tblCellMar>");
        b.append("</w:tblPr><w:tblGrid>");
        for (int i = 0; i < gridCount; i++) {
            b.append("<w:gridCol w:w=\"").append(Math.max(1, Length.twips(widths.get(i)))).append("\"/>");
        }
        b.append("</w:tblGrid>");
        int[] spanLeft = new int[gridCount];
        int[] spanWidth = new int[gridCount];
        String[] spanCellPr = new String[gridCount];
        int r = 0;
        for (Row row : rows) {
            if (r++ >= MAX_ROWS) {
                break;
            }
            b.append(row(row, body, widths, gridCount, defaultCellStyles, spanLeft, spanWidth, spanCellPr));
        }
        return b.append("</w:tbl>").toString();
    }

    private double firstCellPadding(Element t, Styles.Scope scope) {
        List<Row> rows = new ArrayList<>();
        rows(t, rows, false, 0);
        if (rows.isEmpty()) {
            return 0;
        }
        for (Element c : Dom.kids(rows.get(0).row())) {
            if (Dom.is(c, Ns.TABLE, "table-cell") || Dom.is(c, Ns.TABLE, "covered-table-cell")) {
                Props cp = w.styles.props("table-cell", Dom.attr(c, Ns.TABLE, "style-name"), scope,
                        "table-cell-properties", true);
                return cp.pt("fo:padding-left", 0);
            }
        }
        return 0;
    }

    private static void relative(List<Double> widths, List<Double> rel, double tableWidth) {
        boolean missing = false;
        double total = 0;
        for (int i = 0; i < widths.size(); i++) {
            missing |= Double.isNaN(widths.get(i));
            total += rel.get(i);
        }
        if (!missing || !(total > 0) || !(tableWidth > 0)) {
            return;
        }
        for (int i = 0; i < widths.size(); i++) {
            widths.set(i, tableWidth * rel.get(i) / total);
        }
    }

    private int columns(Element parent, Styles.Scope scope, List<Double> widths, List<Double> rel,
            List<String> cellStyles, int depth) {
        if (depth > 4) {
            return 0;
        }
        for (Element k : Dom.kids(parent)) {
            if (Dom.is(k, Ns.TABLE, "table-column")) {
                int repeat = Math.max(1, Math.min(MAX_COLUMNS, Dom.integer(k, Ns.TABLE, "number-columns-repeated", 1)));
                Props cp = w.styles.props("table-column", Dom.attr(k, Ns.TABLE, "style-name"), scope,
                        "table-column-properties", false);
                double width = cp.pt("style:column-width", Double.NaN);
                double relWidth = relWidth(cp.get("style:rel-column-width"));
                for (int i = 0; i < repeat && widths.size() < MAX_COLUMNS; i++) {
                    widths.add(width);
                    rel.add(relWidth);
                    cellStyles.add(Dom.attr(k, Ns.TABLE, "default-cell-style-name"));
                }
            } else if (Dom.is(k, Ns.TABLE, "table-columns") || Dom.is(k, Ns.TABLE, "table-header-columns")
                    || Dom.is(k, Ns.TABLE, "table-column-group")) {
                columns(k, scope, widths, rel, cellStyles, depth + 1);
            }
        }
        return widths.size();
    }

    private static double relWidth(String v) {
        if (v == null) {
            return 0;
        }
        try {
            return Math.max(0, Double.parseDouble(v.replace("*", "").trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void rows(Element parent, List<Row> rows, boolean header, int depth) {
        if (depth > 4) {
            return;
        }
        for (Element k : Dom.kids(parent)) {
            if (Dom.is(k, Ns.TABLE, "table-row")) {
                int repeat = Math.max(1, Math.min(1000, Dom.integer(k, Ns.TABLE, "number-rows-repeated", 1)));
                for (int i = 0; i < repeat && rows.size() < MAX_ROWS; i++) {
                    rows.add(new Row(k, header));
                }
            } else if (Dom.is(k, Ns.TABLE, "table-header-rows")) {
                rows(k, rows, true, depth + 1);
            } else if (Dom.is(k, Ns.TABLE, "table-rows") || Dom.is(k, Ns.TABLE, "table-row-group")) {
                rows(k, rows, header, depth + 1);
            } else if (Dom.is(k, Ns.TEXT, "soft-page-break")) {
                continue;
            }
        }
    }

    private static int maxCells(List<Row> rows) {
        int max = 0;
        for (Row r : rows) {
            int n = 0;
            for (Element c : Dom.kids(r.row())) {
                if (Dom.is(c, Ns.TABLE, "table-cell") || Dom.is(c, Ns.TABLE, "covered-table-cell")) {
                    n += Math.max(1, Math.min(MAX_COLUMNS, Dom.integer(c, Ns.TABLE, "number-columns-repeated", 1)));
                }
            }
            max = Math.max(max, n);
        }
        return max;
    }

    private String row(Row row, TextBody body, List<Double> widths, int grid, List<String> defaults, int[] spanLeft,
            int[] spanWidth, String[] spanCellPr) throws IOException {
        Styles.Scope scope = body.scope;
        Props rp = w.styles.props("table-row", Dom.attr(row.row(), Ns.TABLE, "style-name"), scope,
                "table-row-properties", false);
        StringBuilder b = new StringBuilder("<w:tr><w:trPr>");
        double exact = rp.pt("style:row-height", Double.NaN);
        double min = rp.pt("style:min-row-height", Double.NaN);
        if (!Double.isNaN(exact) && exact > 0) {
            b.append("<w:trHeight w:val=\"").append(Length.twips(exact)).append("\" w:hRule=\"exact\"/>");
        } else if (!Double.isNaN(min) && min > 0) {
            b.append("<w:trHeight w:val=\"").append(Length.twips(min)).append("\" w:hRule=\"atLeast\"/>");
        }
        if ("always".equals(rp.get("fo:keep-together"))) {
            b.append("<w:cantSplit/>");
        }
        if (row.header()) {
            b.append("<w:tblHeader/>");
        }
        b.append("</w:trPr>");
        List<Element> cells = new ArrayList<>();
        for (Element c : Dom.kids(row.row())) {
            if (Dom.is(c, Ns.TABLE, "table-cell") || Dom.is(c, Ns.TABLE, "covered-table-cell")) {
                int repeat = Math.max(1, Math.min(MAX_COLUMNS, Dom.integer(c, Ns.TABLE, "number-columns-repeated", 1)));
                for (int i = 0; i < repeat; i++) {
                    cells.add(c);
                }
            }
        }
        int col = 0;
        int ci = 0;
        while (col < grid) {
            if (spanLeft[col] > 0) {
                int span = spanWidth[col];
                spanLeft[col]--;
                b.append("<w:tc><w:tcPr>").append(spanCellPr[col]).append("<w:vMerge/></w:tcPr><w:p/></w:tc>");
                ci += span;
                col += span;
                continue;
            }
            Element c = ci < cells.size() ? cells.get(ci) : null;
            if (c == null) {
                break;
            }
            if (Dom.is(c, Ns.TABLE, "covered-table-cell")) {
                ci++;
                b.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(Length.twips(widths.get(col)))
                        .append("\" w:type=\"dxa\"/></w:tcPr><w:p/></w:tc>");
                col++;
                continue;
            }
            int span = Math.max(1, Math.min(grid - col, Dom.integer(c, Ns.TABLE, "number-columns-spanned", 1)));
            int down = Math.max(1, Math.min(MAX_ROWS, Dom.integer(c, Ns.TABLE, "number-rows-spanned", 1)));
            String style = Dom.attr(c, Ns.TABLE, "style-name");
            if (style == null && col < defaults.size()) {
                style = defaults.get(col);
            }
            double width = 0;
            for (int k = col; k < col + span; k++) {
                width += widths.get(k);
            }
            String tcPr = cellPr(style, scope, width, span);
            b.append("<w:tc><w:tcPr>").append(tcPr);
            if (down > 1) {
                b.append("<w:vMerge w:val=\"restart\"/>");
                spanLeft[col] = down - 1;
                spanWidth[col] = span;
                spanCellPr[col] = tcPr;
            }
            b.append("</w:tcPr>");
            TextBody cell = body.nested(body.part, scope);
            cell.blocks(c, null);
            b.append(cell.cellXml()).append("</w:tc>");
            ci += span;
            col += span;
        }
        return b.append("</w:tr>").toString();
    }

    private String cellPr(String style, Styles.Scope scope, double width, int span) {
        Props cp = w.styles.props("table-cell", style, scope, "table-cell-properties", true);
        StringBuilder b = new StringBuilder();
        b.append("<w:tcW w:w=\"").append(Length.twips(width)).append("\" w:type=\"dxa\"/>");
        if (span > 1) {
            b.append("<w:gridSpan w:val=\"").append(span).append("\"/>");
        }
        StringBuilder borders = new StringBuilder();
        for (String side : new String[] {"top", "left", "bottom", "right"}) {
            String spec = cp.get("fo:border-" + side);
            if (spec == null) {
                spec = cp.get("fo:border");
            }
            String widths = cp.get("style:border-line-width-" + side);
            if (widths == null) {
                widths = cp.get("style:border-line-width");
            }
            Border border = Border.parse(spec, widths);
            borders.append(border == null ? "<w:" + side + " w:val=\"nil\"/>" : border.word(side, 0));
        }
        b.append("<w:tcBorders>").append(borders).append("</w:tcBorders>");
        String bg = Colors.fill(cp.get("fo:background-color"));
        if (bg != null) {
            b.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"").append(bg).append("\"/>");
        }
        b.append("<w:tcMar>");
        for (String side : new String[] {"top", "left", "bottom", "right"}) {
            double pad = cp.pt("fo:padding-" + side, cp.pt("fo:padding", 0));
            b.append("<w:").append(side).append(" w:w=\"").append(Math.max(0, Length.twips(pad)))
                    .append("\" w:type=\"dxa\"/>");
        }
        b.append("</w:tcMar>");
        String valign = cp.get("style:vertical-align");
        if ("middle".equals(valign)) {
            b.append("<w:vAlign w:val=\"center\"/>");
        } else if ("bottom".equals(valign)) {
            b.append("<w:vAlign w:val=\"bottom\"/>");
        }
        return b.toString();
    }
}

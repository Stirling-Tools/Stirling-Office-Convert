package stirling.software.officeconvert.odt;

import java.io.IOException;
import java.util.List;

import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Table;
import stirling.software.officeconvert.sink.Borders;

final class OdtTables {

    private final OdtStyles styles;
    private final OdtBody body;
    private int count;

    OdtTables(OdtStyles styles, OdtBody body) {
        this.styles = styles;
        this.body = body;
    }

    String table(StringBuilder sb, Table t, boolean own) throws IOException {
        int columns = t.columnWidths.size();
        if (columns == 0 || t.rows.isEmpty()) {
            return null;
        }
        float total = 0;
        for (float w : t.columnWidths) {
            total += w;
        }
        String props = "<style:table-properties style:width=\"" + OdtXml.pt(total) + "\" table:align=\""
                + (t.rightToLeft ? "right\" fo:margin-right=\"" : "left\" fo:margin-left=\"")
                + OdtXml.pt(t.indent) + "\" fo:margin-top=\"0pt\" fo:margin-bottom=\"0pt\""
                + (t.rightToLeft ? " style:writing-mode=\"rl-tb\"" : "")
                + (t.pageBreakBefore ? " fo:break-before=\"page\"" : "") + " table:border-model=\"collapsing\"/>";
        String name = own ? styles.own("table", null, props) : styles.get("table", null, props);
        sb.append("<table:table table:name=\"Table").append(++count).append("\" table:style-name=\"").append(name).append("\">");
        for (float w : t.columnWidths) {
            sb.append("<table:table-column table:style-name=\"").append(styles.get("table-column", null,
                    "<style:table-column-properties style:column-width=\"" + OdtXml.pt(w) + "\"/>")).append("\"/>");
        }
        int headers = 0;
        while (headers < t.rows.size() && t.rows.get(headers).header) {
            headers++;
        }
        for (int r = 0; r < t.rows.size(); r++) {
            if (r == 0 && headers > 0) {
                sb.append("<table:table-header-rows>");
            }
            row(sb, t, r, columns);
            if (headers > 0 && r == headers - 1) {
                sb.append("</table:table-header-rows>");
            }
        }
        sb.append("</table:table>");
        return own ? name : null;
    }

    private void row(StringBuilder sb, Table t, int r, int columns) throws IOException {
        Table.Row row = t.rows.get(r);
        String height = OdtXml.pt(Math.max(0.5f, row.height));
        String rowStyle = styles.get("table-row", null, "<style:table-row-properties "
                + (row.exactHeight ? "style:row-height=\"" : "style:min-row-height=\"") + height + "\" fo:keep-together=\""
                + (row.splits ? "auto" : "always") + "\"/>");
        sb.append("<table:table-row table:style-name=\"").append(rowStyle).append("\">");
        int col = 0;
        for (Table.Cell cell : row.cells) {
            if (col >= columns) {
                break;
            }
            int span = Math.max(1, Math.min(cell.gridSpan, columns - col));
            if (cell.vMerge == 2) {
                for (int k = 0; k < span; k++) {
                    sb.append("<table:covered-table-cell/>");
                }
                col += span;
                continue;
            }
            sb.append("<table:table-cell table:style-name=\"").append(cellStyle(t, cell)).append('"');
            if (span > 1) {
                sb.append(" table:number-columns-spanned=\"").append(span).append('"');
            }
            int down = cell.vMerge == 1 ? rowsMerged(t, r, col) : 1;
            if (down > 1) {
                sb.append(" table:number-rows-spanned=\"").append(down).append('"');
            }
            sb.append(" office:value-type=\"string\">");
            List<Paragraph> paras = cell.paragraphs;
            if (paras.isEmpty()) {
                sb.append("<text:p text:style-name=\"").append(emptyStyle()).append("\"/>");
            }
            for (Paragraph p : paras) {
                body.paragraph(sb, p, OdtBody.Place.NESTED, false);
            }
            sb.append("</table:table-cell>");
            for (int k = 1; k < span; k++) {
                sb.append("<table:covered-table-cell/>");
            }
            col += span;
        }
        for (; col < columns; col++) {
            sb.append("<table:table-cell office:value-type=\"string\"><text:p text:style-name=\"").append(emptyStyle())
                    .append("\"/></table:table-cell>");
        }
        sb.append("</table:table-row>");
    }

    private static int rowsMerged(Table t, int r, int col) {
        int n = 1;
        for (int k = r + 1; k < t.rows.size(); k++) {
            Table.Cell below = cellAt(t.rows.get(k), col);
            if (below == null || below.vMerge != 2) {
                break;
            }
            n++;
        }
        return n;
    }

    private static Table.Cell cellAt(Table.Row row, int col) {
        int c = 0;
        for (Table.Cell cell : row.cells) {
            if (c == col) {
                return cell;
            }
            c += Math.max(1, cell.gridSpan);
            if (c > col) {
                return null;
            }
        }
        return null;
    }

    private String cellStyle(Table t, Table.Cell cell) {
        StringBuilder a = new StringBuilder("<style:table-cell-properties");
        a.append(" fo:padding-left=\"").append(OdtXml.pt(t.cellMarginLeft)).append("\" fo:padding-right=\"")
                .append(OdtXml.pt(t.cellMarginRight)).append("\" fo:padding-top=\"0pt\" fo:padding-bottom=\"0pt\"");
        border(a, "top", cell.top);
        border(a, "bottom", cell.bottom);
        border(a, "left", cell.left);
        border(a, "right", cell.right);
        if (cell.shading >= 0) {
            a.append(" fo:background-color=\"").append(OdtXml.colour(cell.shading)).append('"');
        }
        a.append(" style:vertical-align=\"").append(switch (cell.vAlign) {
            case CENTER -> "middle";
            case BOTTOM -> "bottom";
            default -> "top";
        }).append("\"/>");
        return styles.get("table-cell", null, a.toString());
    }

    private static void border(StringBuilder a, String side, Table.Border b) {
        a.append(" fo:border-").append(side).append("=\"");
        if (b.visible()) {
            a.append(OdtXml.pt(Borders.width(b.width()))).append(" solid ").append(OdtXml.colour(b.rgb()));
        } else {
            a.append("none");
        }
        a.append('"');
    }

    private String emptyStyle() {
        return styles.get("paragraph", "Standard", "<style:paragraph-properties fo:margin-top=\"0pt\" fo:margin-bottom=\"0pt\""
                + " fo:line-height=\"1pt\"/><style:text-properties fo:font-size=\"1pt\" style:font-size-asian=\"1pt\""
                + " style:font-size-complex=\"1pt\"/>");
    }
}

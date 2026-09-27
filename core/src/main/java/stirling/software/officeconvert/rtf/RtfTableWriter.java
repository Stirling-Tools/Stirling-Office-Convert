package stirling.software.officeconvert.rtf;

import java.io.IOException;
import java.util.List;

import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Table;

final class RtfTableWriter {

    private final RtfTables tables;
    private final RtfBody body;

    RtfTableWriter(RtfTables tables, RtfBody body) {
        this.tables = tables;
        this.body = body;
    }

    void table(StringBuilder sb, Table t) throws IOException {
        int columns = t.columnWidths.size();
        if (columns == 0) {
            return;
        }
        int[] edges = new int[columns + 1];
        int indent = t.floating() ? 0 : RtfText.twips(t.indent);
        edges[0] = indent;
        float total = 0;
        for (int c = 0; c < columns; c++) {
            total += t.columnWidths.get(c);
            edges[c + 1] = indent + RtfText.twips(total);
        }
        for (Table.Row row : t.rows) {
            rowDefinition(sb, t, row, edges, total);
            int col = 0;
            for (Table.Cell cell : row.cells) {
                if (col >= columns) {
                    break;
                }
                col += Math.max(1, Math.min(cell.gridSpan, columns - col));
                List<Paragraph> paras = cell.paragraphs;
                if (paras.isEmpty() || cell.vMerge == 2) {
                    sb.append("\\pard\\plain \\intbl\\ltrpar\\ql\\sl-20\\slmult0{\\fs2 }\\fs2\\cell\n");
                    continue;
                }
                for (int i = 0; i < paras.size(); i++) {
                    body.paragraph(sb, paras.get(i), i + 1 < paras.size() ? RtfBody.End.PAR : RtfBody.End.CELL, true, false);
                }
            }
            for (; col < columns; col++) {
                sb.append("\\pard\\plain \\intbl\\ltrpar\\ql\\sl-20\\slmult0{\\fs2 }\\fs2\\cell\n");
            }
            sb.append("\\pard\\plain \\intbl\\ltrpar{");
            rowDefinition(sb, t, row, edges, total);
            sb.append("\\row}\n");
        }
    }

    private void rowDefinition(StringBuilder sb, Table t, Table.Row row, int[] edges, float total) {
        int columns = edges.length - 1;
        int height = Math.max(1, RtfText.twips(Math.max(0.5f, row.height)));
        sb.append("\\trowd\\ltrrow\\trgaph0\\trleft").append(edges[0]).append("\\trrh").append(row.exactHeight ? -height : height)
                .append(row.splits ? "" : "\\trkeep");
        if (row.header) {
            sb.append("\\trhdr");
        }
        sb.append("\\trftsWidth3\\trwWidth").append(RtfText.twips(total)).append("\\trpaddl").append(RtfText.twips(t.cellMarginLeft))
                .append("\\trpaddr").append(RtfText.twips(t.cellMarginRight))
                .append("\\trpaddt0\\trpaddb0\\trpaddfl3\\trpaddfr3\\trpaddft3\\trpaddfb3\\tblind").append(edges[0])
                .append("\\tblindtype3\\trautofit0");
        if (t.floating()) {
            sb.append("\\tphpg\\tpvpg\\tposx").append(RtfText.twips(t.floatX)).append("\\tposy").append(RtfText.twips(t.floatY))
                    .append("\\tdfrmtxtLeft0\\tdfrmtxtRight").append(RtfText.twips(t.floatRoom))
                    .append("\\tdfrmtxtTop0\\tdfrmtxtBottom0");
        }
        int col = 0;
        for (Table.Cell cell : row.cells) {
            if (col >= columns) {
                break;
            }
            int span = Math.max(1, Math.min(cell.gridSpan, columns - col));
            if (cell.vMerge == 1) {
                sb.append("\\clvmgf");
            } else if (cell.vMerge == 2) {
                sb.append("\\clvmrg");
            }
            sb.append(switch (cell.vAlign) {
                case CENTER -> "\\clvertalc";
                case BOTTOM -> "\\clvertalb";
                default -> "\\clvertalt";
            });
            border(sb, "t", cell.top);
            border(sb, "l", cell.left);
            border(sb, "b", cell.bottom);
            border(sb, "r", cell.right);
            if (cell.shading >= 0) {
                sb.append("\\clcbpat").append(tables.colour(cell.shading)).append("\\clshdng0");
            }
            sb.append("\\clftsWidth3\\clwWidth").append(edges[col + span] - edges[col]).append("\\cellx").append(edges[col + span]);
            col += span;
        }
        for (; col < columns; col++) {
            sb.append("\\clvertalt\\clftsWidth3\\clwWidth").append(edges[col + 1] - edges[col]).append("\\cellx").append(edges[col + 1]);
        }
        sb.append('\n');
    }

    private void border(StringBuilder sb, String side, Table.Border b) {
        sb.append("\\clbrdr").append(side);
        if (b.visible()) {
            sb.append("\\brdrs\\brdrw").append(RtfBody.borderWidth(b.width())).append("\\brdrcf").append(tables.colour(b.rgb()));
        } else {
            sb.append("\\brdrnone");
        }
    }
}

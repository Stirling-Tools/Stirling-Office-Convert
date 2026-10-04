package stirling.software.officeconvert.pptx;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Table;
import stirling.software.officeconvert.slides.LineBoxes;
import stirling.software.officeconvert.slides.TableShape;

final class TableXml {

    private static final float WORD_BASELINE = 0.8f;

    private static final float EMPTY_LINE = 1.3f;

    private final TextXml text;

    TableXml(TextXml text) {
        this.text = text;
    }

    void table(StringBuilder sb, TableShape shape, int id) {
        Map<Table.Cell, Float> offsets = new IdentityHashMap<>();
        Table t = RowPieces.split(shape.table(), shape.y(), offsets);
        sb.append("<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"").append(id).append("\" name=\"Table ").append(id - 1)
                .append("\"/><p:cNvGraphicFramePr><a:graphicFrameLocks noGrp=\"1\"/></p:cNvGraphicFramePr><p:nvPr/>")
                .append("</p:nvGraphicFramePr><p:xfrm><a:off x=\"").append(Ooxml.offset(shape.x())).append("\" y=\"")
                .append(Ooxml.offset(shape.y())).append("\"/><a:ext cx=\"").append(Ooxml.emu(shape.width()))
                .append("\" cy=\"").append(Ooxml.emu(heightOf(t))).append("\"/></p:xfrm>")
                .append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\"><a:tbl>")
                .append(t.rightToLeft ? "<a:tblPr rtl=\"1\"/>" : "<a:tblPr/>").append("<a:tblGrid>");
        for (float w : t.columnWidths) {
            sb.append("<a:gridCol w=\"").append(Ooxml.emu(Math.max(1f, w))).append("\"/>");
        }
        sb.append("</a:tblGrid>");
        int cols = t.columnWidths.size();
        for (int r = 0; r < t.rows.size(); r++) {
            Table.Row row = t.rows.get(r);
            float h = rowHeight(t, r);
            sb.append("<a:tr h=\"").append(Ooxml.emu(h)).append("\">");
            int col = 0;
            for (Table.Cell cell : row.cells) {
                if (col >= cols) {
                    break;
                }
                int span = Math.max(1, Math.min(cell.gridSpan, cols - col));
                boolean continued = cell.vMerge == 2;
                int rowSpan = cell.vMerge == 1 ? rowSpan(t, r, col) : 1;
                cell(sb, t, cell, span, rowSpan, continued, h, spanned(t, r, rowSpan), offsets.get(cell));
                for (int k = 1; k < span; k++) {
                    sb.append("<a:tc hMerge=\"1\"").append(continued ? " vMerge=\"1\"" : "").append('>');
                    emptyCell(sb, h);
                    sb.append("</a:tc>");
                }
                col += span;
            }
            for (; col < cols; col++) {
                sb.append("<a:tc>");
                emptyCell(sb, h);
                sb.append("</a:tc>");
            }
            sb.append("</a:tr>");
        }
        sb.append("</a:tbl></a:graphicData></a:graphic></p:graphicFrame>");
    }

    private void cell(StringBuilder sb, Table t, Table.Cell cell, int span, int rowSpan, boolean continued, float rowHeight,
            float room, Float offset) {
        sb.append("<a:tc");
        if (span > 1) {
            sb.append(" gridSpan=\"").append(span).append('"');
        }
        if (rowSpan > 1) {
            sb.append(" rowSpan=\"").append(rowSpan).append('"');
        }
        if (continued) {
            sb.append(" vMerge=\"1\"");
        }
        sb.append("><a:txBody><a:bodyPr/><a:lstStyle/>");
        List<Paragraph> paras = continued ? List.of() : cell.paragraphs;
        if (paras.isEmpty()) {
            emptyParagraph(sb, room);
        }
        for (int i = 0; i < paras.size(); i++) {
            text.cellParagraph(sb, paras.get(i), i == 0);
        }
        float lead = offset != null ? offset : paras.isEmpty() ? 0 : cell.top.width() + paras.getFirst().spaceBefore;
        float top = paras.isEmpty() ? 0 : topInset(cell, rowSpan > 1 ? Float.MAX_VALUE : rowHeight, lead);
        float left = t.cellMarginLeft + (cell.left.visible() ? 0.5f : 0);
        float right = t.cellMarginRight + (cell.right.visible() ? 0.5f : 0);
        sb.append("</a:txBody><a:tcPr marL=\"").append(Ooxml.emu(left)).append("\" marR=\"").append(Ooxml.emu(right))
                .append("\" marT=\"").append(Ooxml.emu(top))
                .append("\" marB=\"0\" anchor=\"").append(switch (cell.vAlign) {
                    case CENTER -> "ctr";
                    case BOTTOM -> "b";
                    default -> "t";
                }).append("\">");
        border(sb, "a:lnL", cell.left);
        border(sb, "a:lnR", cell.right);
        border(sb, "a:lnT", cell.top);
        border(sb, "a:lnB", cell.bottom);
        if (cell.shading >= 0) {
            Ooxml.solidFill(sb, cell.shading);
        } else {
            sb.append("<a:noFill/>");
        }
        sb.append("</a:tcPr></a:tc>");
    }

    private static void emptyCell(StringBuilder sb, float room) {
        sb.append("<a:txBody><a:bodyPr/><a:lstStyle/>");
        emptyParagraph(sb, room);
        sb.append("</a:txBody><a:tcPr marT=\"0\" marB=\"0\"/>");
    }

    private static void emptyParagraph(StringBuilder sb, float room) {
        sb.append("<a:p><a:endParaRPr sz=\"").append(Ooxml.fontSize(Math.clamp(room / EMPTY_LINE, 1f, 18f)))
                .append("\" dirty=\"0\"/></a:p>");
    }

    private static float spanned(Table t, int row, int rows) {
        float h = 0;
        for (int r = row; r < Math.min(t.rows.size(), row + rows); r++) {
            h += rowHeight(t, r);
        }
        return h;
    }

    private static float topInset(Table.Cell cell, float rowHeight, float lead) {
        if (cell.vAlign != Table.VAlign.TOP) {
            return 0;
        }
        Paragraph first = cell.paragraphs.getFirst();
        float lineHeight = first.lineHeight > 0 ? first.lineHeight : 12f;
        float size = first.markStyle != null ? first.markStyle.size() : lineHeight / 1.2f;
        float inset = lead + WORD_BASELINE * lineHeight
                - LineBoxes.baseline(lineHeight, size, fontOf(first));
        float content = 0;
        for (int i = 0; i < cell.paragraphs.size(); i++) {
            Paragraph p = cell.paragraphs.get(i);
            content += (i == 0 ? 0 : p.spaceBefore) + Math.max(1, p.sourceLines) * Math.max(1f, p.lineHeight);
        }
        return Math.clamp(inset, 0, Math.max(0, rowHeight - content));
    }

    private static String fontOf(Paragraph p) {
        for (Inline in : p.inlines) {
            if (in instanceof Inline.Text t && t.style().font() != null) {
                return t.style().font();
            }
        }
        RunStyle mark = p.markStyle;
        return mark == null ? null : mark.font();
    }

    private static void border(StringBuilder sb, String tag, Table.Border b) {
        if (b == null || !b.visible()) {
            sb.append('<').append(tag).append(" w=\"0\"><a:noFill/></").append(tag).append('>');
            return;
        }
        sb.append('<').append(tag).append(" w=\"").append(Ooxml.emu(b.width())).append("\" cmpd=\"sng\">");
        Ooxml.solidFill(sb, Math.max(0, b.rgb()));
        sb.append("</").append(tag).append('>');
    }

    static int rowSpan(Table t, int row, int col) {
        int span = 1;
        for (int r = row + 1; r < t.rows.size(); r++) {
            Table.Cell below = cellAt(t.rows.get(r), col);
            if (below == null || below.vMerge != 2) {
                break;
            }
            span++;
        }
        return span;
    }

    static Table.Cell cellAt(Table.Row row, int col) {
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

    static float rowHeight(Table t, int r) {
        Table.Row row = t.rows.get(r);
        float top = 0;
        float bottom = 0;
        for (Table.Cell c : row.cells) {
            top = Math.max(top, c.top.width());
            bottom = Math.max(bottom, c.bottom.width());
        }
        return Math.max(1f, row.height + top + (r == t.rows.size() - 1 ? bottom : 0));
    }

    static float heightOf(Table t) {
        float h = 0;
        for (int r = 0; r < t.rows.size(); r++) {
            h += rowHeight(t, r);
        }
        return h;
    }
}

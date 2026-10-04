package stirling.software.officeconvert.pptx;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Table;

final class RowPieces {

    private static final float SLACK = 0.25f;

    private RowPieces() {}

    static Table split(Table t, float y, Map<Table.Cell, Float> offsets) {
        float[] tops = new float[t.rows.size() + 1];
        tops[0] = y;
        for (int r = 0; r < t.rows.size(); r++) {
            tops[r + 1] = tops[r] + TableXml.rowHeight(t, r);
        }
        Table out = null;
        for (int r = 0; r < t.rows.size(); r++) {
            int col = 0;
            for (Table.Cell cell : t.rows.get(r).cells) {
                int span = cell.vMerge == 1 ? TableXml.rowSpan(t, r, col) : 1;
                List<List<Paragraph>> parts = span > 1 ? parts(cell, tops, r, span) : null;
                if (parts != null) {
                    if (out == null) {
                        out = copy(t);
                    }
                    apply(out, r, col, cell, parts, tops, offsets);
                }
                col += Math.max(1, cell.gridSpan);
            }
        }
        return out == null ? t : out;
    }

    private static List<List<Paragraph>> parts(Table.Cell cell, float[] tops, int row, int span) {
        List<List<Paragraph>> parts = new ArrayList<>();
        for (int i = 0; i < span; i++) {
            parts.add(new ArrayList<>());
        }
        int at = 0;
        for (Paragraph p : cell.paragraphs) {
            if (Float.isNaN(p.sourceTop) || Float.isNaN(p.sourceBottom)) {
                return null;
            }
            int k = at;
            while (k + 1 < span && tops[row + k + 1] <= p.sourceTop) {
                k++;
            }
            float slack = SLACK * Math.max(1f, p.lineHeight);
            if (p.sourceTop < tops[row + k] - slack || p.sourceBottom > tops[row + k + 1] + slack) {
                return null;
            }
            parts.get(k).add(p);
            at = k;
        }
        return parts;
    }

    private static void apply(Table out, int row, int col, Table.Cell origin, List<List<Paragraph>> parts, float[] tops,
            Map<Table.Cell, Float> offsets) {
        for (int i = 0; i < parts.size(); i++) {
            Table.Cell piece = TableXml.cellAt(out.rows.get(row + i), col);
            if (piece == null) {
                continue;
            }
            piece.vMerge = 0;
            piece.gridSpan = origin.gridSpan;
            piece.left = origin.left;
            piece.right = origin.right;
            piece.top = i == 0 ? origin.top : Table.Border.NONE;
            piece.bottom = i == parts.size() - 1 ? origin.bottom : Table.Border.NONE;
            piece.shading = origin.shading;
            piece.vAlign = Table.VAlign.TOP;
            piece.paragraphs.clear();
            piece.paragraphs.addAll(parts.get(i));
            if (!piece.paragraphs.isEmpty()) {
                offsets.put(piece, Math.max(0, piece.paragraphs.getFirst().sourceTop - tops[row + i]));
            }
        }
    }

    private static Table copy(Table t) {
        Table out = new Table();
        out.columnWidths.addAll(t.columnWidths);
        out.indent = t.indent;
        out.cellMarginLeft = t.cellMarginLeft;
        out.cellMarginRight = t.cellMarginRight;
        out.pageBreakBefore = t.pageBreakBefore;
        out.floatX = t.floatX;
        out.floatY = t.floatY;
        out.floatRoom = t.floatRoom;
        out.rightToLeft = t.rightToLeft;
        out.indentEnd = t.indentEnd;
        for (Table.Row row : t.rows) {
            Table.Row r = new Table.Row();
            r.height = row.height;
            r.header = row.header;
            r.exactHeight = row.exactHeight;
            r.splits = row.splits;
            for (Table.Cell cell : row.cells) {
                Table.Cell c = new Table.Cell();
                c.paragraphs.addAll(cell.paragraphs);
                c.gridSpan = cell.gridSpan;
                c.vMerge = cell.vMerge;
                c.top = cell.top;
                c.bottom = cell.bottom;
                c.left = cell.left;
                c.right = cell.right;
                c.shading = cell.shading;
                c.vAlign = cell.vAlign;
                r.cells.add(c);
            }
            out.rows.add(r);
        }
        return out;
    }
}

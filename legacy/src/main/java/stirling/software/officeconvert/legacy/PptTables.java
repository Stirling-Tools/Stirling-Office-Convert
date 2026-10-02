package stirling.software.officeconvert.legacy;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFTable;
import org.apache.poi.hslf.usermodel.HSLFTableCell;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hslf.usermodel.HSLFTextRun;
import org.apache.poi.sl.usermodel.Insets2D;
import org.apache.poi.sl.usermodel.TableCell.BorderEdge;
import org.apache.poi.sl.usermodel.VerticalAlignment;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Table;
import stirling.software.officeconvert.slides.TableShape;

final class PptTables {

    private record Merged(Table.Cell cell, int row, int col, int rows, int cols) {}

    private PptTables() {}

    static void table(HSLFSlide s, TableShape shape, PptText text) {
        Table t = shape.table();
        if (t.rightToLeft) {
            t.mirror();
        }
        int rows = t.rows.size();
        int cols = t.columnWidths.size();
        if (rows == 0 || cols == 0) {
            return;
        }
        HSLFTable table = s.createTable(rows, cols);
        for (int c = 0; c < cols; c++) {
            table.setColumnWidth(c, Math.max(1f, t.columnWidths.get(c)));
        }
        for (int r = 0; r < rows; r++) {
            table.setRowHeight(r, Math.max(1f, t.rows.get(r).height));
        }
        table.moveTo(shape.x(), shape.y());
        float size = typicalSize(t);
        List<Merged> merged = new ArrayList<>();
        Table.Cell[] above = new Table.Cell[cols];
        for (int r = 0; r < rows; r++) {
            int col = 0;
            for (Table.Cell cell : t.rows.get(r).cells) {
                if (col >= cols) {
                    break;
                }
                int span = Math.max(1, Math.min(cell.gridSpan, cols - col));
                Table.Cell source = cell.vMerge == 2 && above[col] != null ? above[col] : cell;
                for (int k = 0; k < span; k++) {
                    HSLFTableCell slot = table.getCell(r, col + k);
                    if (slot == null) {
                        continue;
                    }
                    slot.setFillColor(source.shading >= 0 ? new Color(source.shading) : null);
                    border(slot, BorderEdge.top, cell.vMerge == 2 ? null : source.top);
                    border(slot, BorderEdge.bottom, source.bottom);
                    border(slot, BorderEdge.left, k == 0 ? source.left : null);
                    border(slot, BorderEdge.right, k == span - 1 ? source.right : null);
                    slot.setInsets(new Insets2D(0, t.cellMarginLeft, 0, t.cellMarginRight));
                    slot.setVerticalAlignment(valign(source.vAlign));
                    if (k == 0 && cell.vMerge == 0 && span == 1 && !cell.paragraphs.isEmpty()) {
                        text.cell(slot, cell.paragraphs);
                    } else {
                        quiet(slot, t.rows.get(r).height, size);
                    }
                }
                if ((span > 1 || cell.vMerge == 1) && !cell.paragraphs.isEmpty()) {
                    merged.add(new Merged(cell, r, col, cell.vMerge == 1 ? rowSpan(t, r, col) : 1, span));
                }
                if (cell.vMerge != 2) {
                    above[col] = cell;
                }
                col += span;
            }
        }
        for (Merged m : merged) {
            overlay(s, shape, m, text);
        }
    }

    private static void overlay(HSLFSlide s, TableShape shape, Merged m, PptText text) {
        Table t = shape.table();
        double x = shape.x();
        double y = shape.y();
        double w = 0;
        double h = 0;
        for (int c = 0; c < m.col() + m.cols(); c++) {
            if (c < m.col()) {
                x += t.columnWidths.get(c);
            } else {
                w += t.columnWidths.get(c);
            }
        }
        for (int r = 0; r < m.row() + m.rows(); r++) {
            if (r < m.row()) {
                y += t.rows.get(r).height;
            } else {
                h += t.rows.get(r).height;
            }
        }
        HSLFTextBox box = s.createTextBox();
        box.setAnchor(new Rectangle2D.Double(x, y, Math.max(1, w), Math.max(1, h)));
        box.setInsets(new Insets2D(0, t.cellMarginLeft, 0, t.cellMarginRight));
        box.setWordWrap(true);
        box.setFillColor(null);
        box.setLineColor(null);
        box.setVerticalAlignment(valign(m.cell().vAlign));
        text.cell(box, m.cell().paragraphs);
    }

    private static void quiet(HSLFTableCell slot, float rowHeight, float size) {
        HSLFTextRun run = slot.setText("");
        if (run != null) {
            run.setFontSize((double) Math.max(1f, Math.min(size, (float) Math.floor(rowHeight / 1.2f * 2) / 2f)));
        }
    }

    private static float typicalSize(Table t) {
        Map<Float, Integer> counts = new HashMap<>();
        for (Table.Row row : t.rows) {
            for (Table.Cell cell : row.cells) {
                for (Paragraph p : cell.paragraphs) {
                    for (Inline in : p.inlines) {
                        if (in instanceof Inline.Text text && text.style().size() > 0) {
                            counts.merge(text.style().size(), text.text().length(), Integer::sum);
                        }
                    }
                }
            }
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(10f);
    }

    private static int rowSpan(Table t, int row, int col) {
        int n = 1;
        while (row + n < t.rows.size()) {
            Table.Cell below = at(t.rows.get(row + n), col);
            if (below == null || below.vMerge != 2) {
                break;
            }
            n++;
        }
        return n;
    }

    private static Table.Cell at(Table.Row row, int col) {
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

    private static VerticalAlignment valign(Table.VAlign a) {
        return switch (a) {
            case CENTER -> VerticalAlignment.MIDDLE;
            case BOTTOM -> VerticalAlignment.BOTTOM;
            default -> VerticalAlignment.TOP;
        };
    }

    private static void border(HSLFTableCell cell, BorderEdge edge, Table.Border b) {
        if (b == null || !b.visible()) {
            return;
        }
        cell.setBorderWidth(edge, b.width());
        cell.setBorderColor(edge, new Color(Math.max(0, b.rgb())));
    }
}

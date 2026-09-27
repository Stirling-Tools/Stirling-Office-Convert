package stirling.software.officeconvert.build;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import stirling.software.officeconvert.extract.PageGraphics.Rule;
import stirling.software.officeconvert.layout.PageLayout;
import stirling.software.officeconvert.layout.ParaDraft;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph.Align;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Table;
import stirling.software.officeconvert.table.TableDetection;

final class TableBuilder {

    private static final float FIT_SPARE = 1f;

    private static final int MIN_BODY_ROWS = 3;

    private final ParagraphFactory paragraphs;

    TableBuilder(ParagraphFactory paragraphs) {
        this.paragraphs = paragraphs;
    }

    Table build(PageLayout.TableItem ti, PageLayout layout, float colLeft) {
        TableDetection.Found f = ti.table();
        Table t = new Table();
        for (int c = 0; c < f.cols(); c++) {
            t.columnWidths.add(Math.max(4f, f.colEdges()[c + 1] - f.colEdges()[c]));
        }
        t.indent = f.left() - colLeft;
        float pad = ti.pad();
        float sideBorder = f.ruled() ? 0.5f : 0f;
        t.cellMarginLeft = Math.max(0, pad - sideBorder);
        t.cellMarginRight = Math.max(0, pad - sideBorder);
        float[] rowEdges = ti.rowEdges();
        TableDetection.FoundCell[][] anchor = new TableDetection.FoundCell[f.rows()][f.cols()];
        int[][] index = new int[f.rows()][f.cols()];
        boolean[][] covered = new boolean[f.rows()][f.cols()];
        for (int i = 0; i < f.cells().size(); i++) {
            TableDetection.FoundCell c = f.cells().get(i);
            if (c.row() >= f.rows() || c.col() >= f.cols()) {
                continue;
            }
            anchor[c.row()][c.col()] = c;
            index[c.row()][c.col()] = i;
            for (int r = c.row(); r < Math.min(f.rows(), c.row() + c.rowSpan()); r++) {
                for (int k = c.col(); k < Math.min(f.cols(), c.col() + c.colSpan()); k++) {
                    covered[r][k] = true;
                }
            }
        }
        List<Rule> rules = layout.page().graphics().rules();
        for (int r = 0; r < f.rows(); r++) {
            Table.Row row = new Table.Row();
            row.height = Math.max(4f, rowEdges[r + 1] - rowEdges[r]);
            row.header = r < f.headerRows() && f.rows() - f.headerRows() >= MIN_BODY_ROWS;
            int k = 0;
            while (k < f.cols()) {
                TableDetection.FoundCell c = anchor[r][k];
                Table.Cell cell = new Table.Cell();
                if (c != null) {
                    cell.gridSpan = Math.max(1, Math.min(c.colSpan(), f.cols() - k));
                    cell.vMerge = c.rowSpan() > 1 ? 1 : 0;
                    borders(cell, c, rules, f.ruled());
                    cell.shading = c.fill() == null ? -1 : c.fill();
                    cell.vAlign = switch (c.vAlign()) {
                        case CENTER -> Table.VAlign.CENTER;
                        case BOTTOM -> Table.VAlign.BOTTOM;
                        default -> Table.VAlign.TOP;
                    };
                    float cellTop = rowEdges[r] + cell.top.width();
                    float prev = cellTop;
                    for (ParaDraft d : ti.cellParas().get(index[r][k])) {
                        float innerLeft = d.colLeft;
                        float innerRight = d.colRight;
                        float sb = cell.vAlign == Table.VAlign.TOP ? Math.max(0, paragraphs.wordTop(d) - prev) : 0;
                        if (prev != cellTop) {
                            sb = Math.max(0, paragraphs.wordTop(d) - prev);
                        }
                        Paragraph p = paragraphs.detached(d, innerLeft, innerRight, sb);
                        cell.paragraphs.add(p);
                        prev = paragraphs.wordBottom(d);
                    }
                    k += cell.gridSpan;
                } else {
                    TableDetection.FoundCell above = null;
                    for (int rr = r - 1; rr >= 0 && above == null; rr--) {
                        TableDetection.FoundCell a = anchor[rr][k];
                        if (a != null && a.row() + a.rowSpan() > r) {
                            above = a;
                        }
                    }
                    if (above != null) {
                        cell.vMerge = 2;
                        cell.gridSpan = Math.max(1, Math.min(above.colSpan(), f.cols() - k));
                        borders(cell, above, rules, f.ruled());
                        cell.shading = above.fill() == null ? -1 : above.fill();
                    }
                    k += cell.gridSpan;
                }
                row.cells.add(cell);
            }
            float topBorder = 0;
            float bottomBorder = 0;
            for (Table.Cell cell : row.cells) {
                topBorder = Math.max(topBorder, cell.top.width());
                bottomBorder = Math.max(bottomBorder, cell.bottom.width());
            }
            row.height = Math.max(1f, row.height - topBorder - (r == f.rows() - 1 ? bottomBorder : 0));
            t.rows.add(row);
        }
        widenForText(t, f, ti);
        fitCells(t);
        keepColumnHeaders(t);
        return t;
    }

    private static void keepColumnHeaders(Table t) {
        boolean header = true;
        for (Table.Row row : t.rows) {
            long filled = row.cells.stream().filter(c -> c.paragraphs.stream().anyMatch(p -> !p.inlines.isEmpty())).count();
            header &= row.header && row.cells.size() >= 2 && filled * 2 > row.cells.size();
            row.header = header;
        }
    }

    private static void fitCells(Table t) {
        for (int r = 0; r < t.rows.size(); r++) {
            Table.Row row = t.rows.get(r);
            for (Table.Cell cell : row.cells) {
                if (cell.vMerge != 0 || cell.paragraphs.isEmpty()) {
                    continue;
                }
                float avail = row.height;
                float content = 0;
                int lines = 0;
                for (Paragraph p : cell.paragraphs) {
                    int n = Math.max(1, lineCount(p));
                    content += p.spaceBefore + p.spaceAfter + n * wordLine(p);
                    lines += n;
                }
                float excess = content - avail;
                float spare = excess + FIT_SPARE;
                if (spare <= 0.05f || lines == 0) {
                    continue;
                }
                for (Paragraph p : cell.paragraphs) {
                    float cut = Math.min(p.spaceBefore, spare);
                    p.spaceBefore -= cut;
                    spare -= cut;
                    excess -= cut;
                }
                if (excess <= 0.05f) {
                    continue;
                }
                float perLine = excess / lines;
                for (Paragraph p : cell.paragraphs) {
                    float floor = p.markStyle != null ? p.markStyle.size() * 0.9f : p.lineHeight * 0.85f;
                    p.lineHeight = Math.max(floor, p.lineHeight - perLine);
                }
            }
        }
    }

    private static float wordLine(Paragraph p) {
        if (p.lineRule != Paragraph.LineRule.AT_LEAST) {
            return p.lineHeight;
        }
        float size = 0;
        for (Inline in : p.inlines) {
            if (in instanceof Inline.Text t) {
                size = Math.max(size, t.style().size());
            }
        }
        return Math.max(p.lineHeight, SINGLE * size);
    }

    private static final float SINGLE = 1.15f;

    private static int lineCount(Paragraph p) {
        return p.sourceLines;
    }

    private static void widenForText(Table t, TableDetection.Found f, PageLayout.TableItem ti) {
        int n = t.columnWidths.size();
        float[] need = new float[n];
        float[] freeLeft = new float[n];
        Arrays.fill(freeLeft, Float.MAX_VALUE);
        List<List<Paragraph>> byColumn = new ArrayList<>();
        for (int c = 0; c < n; c++) {
            byColumn.add(new ArrayList<>());
        }
        int cellIndex = 0;
        for (Table.Row row : t.rows) {
            int col = 0;
            for (Table.Cell cell : row.cells) {
                if (cell.gridSpan == 1 && col < n) {
                    for (Paragraph p : cell.paragraphs) {
                        byColumn.get(col).add(p);
                        freeLeft[col] = Math.min(freeLeft[col], p.align == Align.LEFT || p.align == Align.JUSTIFY
                                ? Math.max(0, p.indentLeft) : 0);
                        if (p.sourceLines == 1) {
                            float w = Math.max(0, p.indentLeft) + p.textWidth + t.cellMarginLeft + t.cellMarginRight + 2f;
                            need[col] = Math.max(need[col], w);
                        }
                    }
                }
                col += cell.gridSpan;
                cellIndex++;
            }
        }
        for (int c = 0; c < n; c++) {
            float deficit = need[c] - t.columnWidths.get(c);
            if (deficit <= 0) {
                continue;
            }
            if (c + 1 < n && freeLeft[c + 1] != Float.MAX_VALUE) {
                float take = Math.min(deficit, Math.min(freeLeft[c + 1], t.columnWidths.get(c + 1) - need[c + 1]));
                if (take > 0) {
                    t.columnWidths.set(c, t.columnWidths.get(c) + take);
                    t.columnWidths.set(c + 1, t.columnWidths.get(c + 1) - take);
                    freeLeft[c + 1] -= take;
                    for (Paragraph p : byColumn.get(c + 1)) {
                        p.indentLeft = Math.max(0, p.indentLeft - take);
                    }
                    deficit -= take;
                }
            }
            if (deficit > 0) {
                t.columnWidths.set(c, t.columnWidths.get(c) + deficit);
            }
        }
    }

    private static void borders(Table.Cell cell, TableDetection.FoundCell c, List<Rule> rules, boolean ruled) {
        cell.top = c.borderTop() ? border(rules, true, c.top(), c.left(), c.right()) : Table.Border.NONE;
        cell.bottom = c.borderBottom() ? border(rules, true, c.bottom(), c.left(), c.right()) : Table.Border.NONE;
        cell.left = c.borderLeft() ? border(rules, false, c.left(), c.top(), c.bottom()) : Table.Border.NONE;
        cell.right = c.borderRight() ? border(rules, false, c.right(), c.top(), c.bottom()) : Table.Border.NONE;
    }

    private static Table.Border border(List<Rule> rules, boolean horizontal, float pos, float from, float to) {
        Rule best = null;
        float bestD = Table.BORDER_REACH;
        float mid = (from + to) / 2f;
        for (Rule r : rules) {
            if (r.horizontal() != horizontal || r.start() > mid || r.end() < mid) {
                continue;
            }
            float d = Math.abs(r.pos() - pos);
            if (d < bestD) {
                bestD = d;
                best = r;
            }
        }
        if (best == null) {
            return new Table.Border(0.5f, 0);
        }
        return new Table.Border(Math.max(0.25f, Math.min(best.thickness(), 6f)), best.rgb());
    }
}

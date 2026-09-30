package stirling.software.officeconvert.topdf.pptx;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.List;

import org.apache.poi.sl.usermodel.PaintStyle;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTableCell;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTableCellProperties;

import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;

final class TablePainter {

    private static final int MAX_CELLS = 100_000;

    private static final float DEFAULT_LR = 7.2f;

    private static final float DEFAULT_TB = 3.6f;

    private final Deck deck;

    private final PdfCanvas canvas;

    private final ShapePainter shapes;

    TablePainter(Deck deck, PdfCanvas canvas, ShapePainter shapes) {
        this.deck = deck;
        this.canvas = canvas;
        this.shapes = shapes;
    }

    private record Cell(XSLFTableCell cell, CTTableCell ct, int row, int col, int rows, int cols, TextFrame text) {}

    void paint(XSLFTable table, Space space) throws IOException {
        Rectangle2D anchor = table.getAnchor();
        int rows = table.getNumberOfRows();
        int cols = table.getNumberOfColumns();
        if (anchor == null || rows == 0 || cols == 0) {
            return;
        }
        if ((long) rows * cols > MAX_CELLS) {
            deck.job().warn("A table of more than " + MAX_CELLS + " cells was left out");
            deck.job().losePart();
            return;
        }
        float[] widths = new float[cols];
        for (int c = 0; c < cols; c++) {
            widths[c] = (float) Math.max(0, table.getColumnWidth(c));
        }
        float[] heights = new float[rows];
        for (int r = 0; r < rows; r++) {
            heights[r] = (float) Math.max(0, table.getRowHeight(r));
        }
        TableStyles.CellStyle[][] styles = deck.tableStyles().resolve(deck.ppt(), table, rows, cols);
        Cell[][] grid = new Cell[rows][cols];
        List<XSLFTableRow> rowList = table.getRows();
        for (int r = 0; r < rows && r < rowList.size(); r++) {
            List<XSLFTableCell> cells = rowList.get(r).getCells();
            for (int c = 0; c < cols && c < cells.size(); c++) {
                deck.job().checkpoint();
                XSLFTableCell cell = cells.get(c);
                CTTableCell ct = (CTTableCell) cell.getXmlObject();
                if (ct.isSetHMerge() && ct.getHMerge() || ct.isSetVMerge() && ct.getVMerge()) {
                    continue;
                }
                int gs = Math.max(1, Math.min(cols - c, ct.isSetGridSpan() ? ct.getGridSpan() : 1));
                int rs = Math.max(1, Math.min(rows - r, ct.isSetRowSpan() ? ct.getRowSpan() : 1));
                TextStyles.Scope scope = new TextStyles.Scope(space.relsPart(), shapes.slideNumber(),
                        styles[r][c].defaults());
                TextFrame text = TextFrame.of(deck, cell, scope, insets(ct), null, true);
                grid[r][c] = new Cell(cell, ct, r, c, rs, gs, text);
            }
        }
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Cell cell = grid[r][c];
                if (cell == null || cell.rows() != 1 || cell.text() == null) {
                    continue;
                }
                heights[r] = Math.max(heights[r], cell.text().height(span(widths, c, cell.cols())));
            }
        }
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Cell cell = grid[r][c];
                if (cell == null || cell.rows() == 1 || cell.text() == null) {
                    continue;
                }
                float need = cell.text().height(span(widths, c, cell.cols()));
                float have = span(heights, r, cell.rows());
                if (need > have) {
                    heights[r + cell.rows() - 1] += need - have;
                }
            }
        }
        float totalW = span(widths, 0, cols);
        float totalH = span(heights, 0, rows);
        Frame f = space.place(anchor, table.getRotation(), false, false);
        double sx = anchor.getWidth() > 0 ? f.width() / anchor.getWidth() : 1;
        double sy = anchor.getHeight() > 0 ? f.height() / anchor.getHeight() : 1;
        float[] xs = positions((float) f.x(), widths, (float) sx);
        float[] ys = positions((float) f.y(), heights, (float) sy);
        canvas.save();
        try {
            canvas.transform(f.moved(f.x(), f.y(), totalW * sx, totalH * sy).shapeTransform());
            Rectangle2D whole = new Rectangle2D.Float(xs[0], ys[0], xs[cols] - xs[0], ys[rows] - ys[0]);
            Fill background = deck.tableStyles().background(deck.ppt(), table, whole);
            if (background != null) {
                canvas.rect(xs[0], ys[0], xs[cols] - xs[0], ys[rows] - ys[0], background, null);
            }
            for (Cell[] row : grid) {
                for (Cell cell : row) {
                    if (cell != null) {
                        fill(cell, styles[cell.row()][cell.col()], rect(cell, xs, ys));
                    }
                }
            }
            TableBorders borders = new TableBorders(rows, cols, table.getSheet());
            for (Cell[] row : grid) {
                for (Cell cell : row) {
                    if (cell != null) {
                        borders.far(cell.ct().getTcPr(), styles, cell.row(), cell.col(), cell.rows(), cell.cols());
                    }
                }
            }
            for (Cell[] row : grid) {
                for (Cell cell : row) {
                    if (cell != null) {
                        borders.near(cell.ct().getTcPr(), styles, cell.row(), cell.col(), cell.rows(), cell.cols());
                    }
                }
            }
            borders.draw(canvas, xs, ys);
            for (Cell[] row : grid) {
                for (Cell cell : row) {
                    if (cell != null && cell.text() != null) {
                        cell.text().draw(canvas, rect(cell, xs, ys));
                    }
                }
            }
        } finally {
            canvas.restore();
        }
    }

    private static Rectangle2D rect(Cell cell, float[] xs, float[] ys) {
        float x = xs[cell.col()];
        float y = ys[cell.row()];
        return new Rectangle2D.Float(x, y, xs[cell.col() + cell.cols()] - x, ys[cell.row() + cell.rows()] - y);
    }

    private static float[] positions(float start, float[] sizes, float scale) {
        float[] out = new float[sizes.length + 1];
        out[0] = start;
        for (int i = 0; i < sizes.length; i++) {
            out[i + 1] = out[i] + sizes[i] * scale;
        }
        return out;
    }

    private static float span(float[] sizes, int from, int count) {
        float s = 0;
        for (int i = from; i < from + count && i < sizes.length; i++) {
            s += sizes[i];
        }
        return s;
    }

    private static TextFrame.Insets insets(CTTableCell ct) {
        CTTableCellProperties p = ct.getTcPr();
        float l = DEFAULT_LR;
        float t = DEFAULT_TB;
        float r = DEFAULT_LR;
        float b = DEFAULT_TB;
        if (p != null) {
            l = p.isSetMarL() ? emu(p.getMarL()) : DEFAULT_LR;
            t = p.isSetMarT() ? emu(p.getMarT()) : DEFAULT_TB;
            r = p.isSetMarR() ? emu(p.getMarR()) : DEFAULT_LR;
            b = p.isSetMarB() ? emu(p.getMarB()) : DEFAULT_TB;
        }
        return new TextFrame.Insets(l, t, r, b);
    }

    private static float emu(Object v) {
        if (v instanceof Number n) {
            return n.longValue() / 12_700f;
        }
        try {
            return Long.parseLong(String.valueOf(v)) / 12_700f;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void fill(Cell cell, TableStyles.CellStyle style, Rectangle2D r) throws IOException {
        CTTableCellProperties p = cell.ct().getTcPr();
        Fill fill = null;
        if (p != null && (p.isSetSolidFill() || p.isSetGradFill() || p.isSetNoFill() || p.isSetBlipFill()
                || p.isSetPattFill() || p.isSetGrpFill())) {
            if (p.isSetNoFill()) {
                return;
            }
            PaintStyle ps;
            try {
                ps = cell.cell().getFillPaint();
            } catch (RuntimeException e) {
                ps = null;
            }
            fill = Paints.fill(ps, r, null);
        } else if (style.fill != null && style.fill.getAlpha() > 0) {
            fill = Fill.solid(style.fill);
        }
        if (fill != null) {
            canvas.rect((float) r.getX(), (float) r.getY(), (float) r.getWidth(), (float) r.getHeight(), fill, null);
        }
    }
}

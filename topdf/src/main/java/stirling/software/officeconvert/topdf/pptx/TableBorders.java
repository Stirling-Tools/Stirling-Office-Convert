package stirling.software.officeconvert.topdf.pptx;

import java.io.IOException;

import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.openxmlformats.schemas.drawingml.x2006.main.CTLineProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTableCellProperties;

import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.Stroke;

final class TableBorders {

    private final Stroke[][] horizontal;

    private final Stroke[][] vertical;

    private final XSLFSheet sheet;

    TableBorders(int rows, int cols, XSLFSheet sheet) {
        this.horizontal = new Stroke[rows + 1][cols];
        this.vertical = new Stroke[rows][cols + 1];
        this.sheet = sheet;
    }

    void far(CTTableCellProperties p, TableStyles.CellStyle[][] styles, int row, int col, int rows, int cols) {
        for (int k = 0; k < cols; k++) {
            set(horizontal, row + rows, col + k, resolve(p == null ? null : p.getLnB(), p != null && p.isSetLnB(),
                    styles[row + rows - 1][col + k], TableStyles.Edge.BOTTOM), false);
        }
        for (int k = 0; k < rows; k++) {
            set(vertical, row + k, col + cols, resolve(p == null ? null : p.getLnR(), p != null && p.isSetLnR(),
                    styles[row + k][col + cols - 1], TableStyles.Edge.RIGHT), false);
        }
    }

    void near(CTTableCellProperties p, TableStyles.CellStyle[][] styles, int row, int col, int rows, int cols) {
        for (int k = 0; k < cols; k++) {
            set(horizontal, row, col + k, resolve(p == null ? null : p.getLnT(), p != null && p.isSetLnT(),
                    styles[row][col + k], TableStyles.Edge.TOP), true);
        }
        for (int k = 0; k < rows; k++) {
            set(vertical, row + k, col, resolve(p == null ? null : p.getLnL(), p != null && p.isSetLnL(),
                    styles[row + k][col], TableStyles.Edge.LEFT), true);
        }
    }

    private static void set(Stroke[][] grid, int a, int b, Stroke s, boolean override) {
        if (s != null && (override || grid[a][b] == null)) {
            grid[a][b] = s;
        }
    }

    private Stroke resolve(CTLineProperties own, boolean ownSet, TableStyles.CellStyle style, TableStyles.Edge e) {
        Stroke s;
        Stroke base = style.borders[e.ordinal()];
        if (ownSet) {
            s = own == null || own.isSetNoFill() ? null : TableStyles.line(own, sheet);
            if (own != null && !own.isSetNoFill() && !own.isSetSolidFill() && base != null) {
                s = own.isSetW() ? base.width(Math.min(1000, own.getW() / 12_700f)) : base;
            }
        } else {
            s = base;
        }
        return s == null || !(s.width() > 0) || s.color().getAlpha() == 0 ? null : s;
    }

    void draw(PdfCanvas canvas, float[] xs, float[] ys) throws IOException {
        for (int r = 0; r < horizontal.length; r++) {
            Stroke[] line = horizontal[r];
            int c = 0;
            while (c < line.length) {
                Stroke s = line[c];
                int end = c + 1;
                while (end < line.length && s != null && s.equals(line[end])) {
                    end++;
                }
                if (s != null) {
                    canvas.line(xs[c], ys[r], xs[end], ys[r], s);
                }
                c = end;
            }
        }
        int cols = vertical.length == 0 ? 0 : vertical[0].length;
        for (int c = 0; c < cols; c++) {
            int r = 0;
            while (r < vertical.length) {
                Stroke s = vertical[r][c];
                int end = r + 1;
                while (end < vertical.length && s != null && s.equals(vertical[end][c])) {
                    end++;
                }
                if (s != null) {
                    canvas.line(xs[c], ys[r], xs[c], ys[end], s);
                }
                r = end;
            }
        }
    }
}

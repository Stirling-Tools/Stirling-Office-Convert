package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;
import java.io.IOException;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.util.CellRangeAddress;

import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.Stroke;

final class BorderPainter {

    private final Grid grid;

    private final PdfCanvas canvas;

    private final double device;

    BorderPainter(Grid grid, PdfCanvas canvas, double device) {
        this.grid = grid;
        this.canvas = canvas;
        this.device = device > 0 ? device : 1;
    }

    static double deviceWidth(double width, double device) {
        return Math.max(1, Math.round(width / PrintMetrics.PX * device)) * PrintMetrics.PX / device;
    }

    // GDI centres an even number of device pixels on the edge and puts the odd pixel of an odd width after it
    static double centre(double at, double w, double device) {
        double px = PrintMetrics.PX / (device > 0 ? device : 1);
        return Math.round(w / px) % 2 == 1 ? at + px / 2 : at;
    }

    // At full size Excel's PDF export also strokes a hairline along the first device pixel of a thin line
    static void solid(PdfCanvas canvas, boolean horizontal, double at, double from, double to, double w, Color color,
            boolean edge, double device) throws IOException {
        double c = centre(at, w, device);
        if (edge) {
            double h = PrintMetrics.PX / device / 2;
            Stroke s = Stroke.solid(Hairline.STROKE, color);
            double a = c - w / 2 + h;
            double b = from - w / 2 + h;
            double e = to + w / 2 - h;
            if (horizontal) {
                canvas.line((float) b, (float) a, (float) e, (float) a, s);
            } else {
                canvas.line((float) a, (float) b, (float) a, (float) e, s);
            }
        }
        if (horizontal) {
            canvas.rect((float) (from - w / 2), (float) (c - w / 2), (float) (to - from + w), (float) w,
                    Fill.solid(color), null);
        } else {
            canvas.rect((float) (c - w / 2), (float) (from - w / 2), (float) w, (float) (to - from + w),
                    Fill.solid(color), null);
        }
    }

    static boolean fullSize(double device) {
        return Math.abs(device - 1) < 1e-9;
    }

    void paint(Band rows, Band cols) throws IOException {
        int shown = rows.first - 1;
        for (int r = rows.first; r <= rows.last + 1; r++) {
            int above = shown;
            if (r <= rows.last && rows.size(r) > 0) {
                shown = r;
            }
            if (r > rows.first && r <= rows.last && rows.size(r) <= 0 && rows.size(r - 1) <= 0) {
                continue;
            }
            double y = rows.start(r);
            Run run = new Run(true, y);
            for (int c = cols.first; c <= cols.last; c++) {
                if (cols.size(c) <= 0) {
                    continue;
                }
                BorderLine line = horizontal(r, above, c, rows);
                run.add(cols.start(c), cols.end(c), line);
            }
            run.flush();
        }
        shown = cols.first - 1;
        for (int c = cols.first; c <= cols.last + 1; c++) {
            double x = cols.start(c);
            int left = shown;
            if (c <= cols.last && cols.size(c) > 0) {
                shown = c;
            }
            Run run = new Run(false, x);
            for (int r = rows.first; r <= rows.last; r++) {
                if (rows.size(r) <= 0) {
                    continue;
                }
                BorderLine line = vertical(r, c, left, cols);
                run.add(rows.start(r), rows.end(r), line);
            }
            run.flush();
        }
        diagonals(rows, cols);
    }

    // A page prints only its own cells' edges, not those of the cells beyond its sides
    private BorderLine horizontal(int r, int above, int c, Band rows) {
        CellRangeAddress m = grid.merges().isEmpty() ? null : grid.mergeCovering(r, c);
        if (m != null && r > m.getFirstRow() && r <= m.getLastRow()) {
            return BorderLine.NONE;
        }
        CellFormat a = above >= rows.first ? grid.formatAt(above, c) : null;
        CellFormat b = r <= rows.last ? grid.formatAt(r, c) : null;
        return BorderLine.stronger(a == null ? null : a.bottom(), b == null ? null : b.top());
    }

    private BorderLine vertical(int r, int c, int left, Band cols) {
        CellRangeAddress m = grid.merges().isEmpty() ? null : grid.mergeCovering(r, c);
        if (m != null && c > m.getFirstColumn() && c <= m.getLastColumn()) {
            return BorderLine.NONE;
        }
        CellFormat a = left >= cols.first ? grid.formatAt(r, left) : null;
        CellFormat b = c <= cols.last ? grid.formatAt(r, c) : null;
        return BorderLine.stronger(a == null ? null : a.right(), b == null ? null : b.left());
    }

    private void diagonals(Band rows, Band cols) throws IOException {
        for (Grid.RowInfo info : grid.rows(rows.first, rows.last).values()) {
            info.formats(cols.first, cols.last, (col, f) -> diagonal(info.index, col, f, rows, cols));
        }
    }

    private void diagonal(int row, int col, CellFormat f, Band rows, Band cols) throws IOException {
        if (!f.diagonal().visible() || !(f.diagonalUp() || f.diagonalDown())) {
            return;
        }
        double x0 = cols.start(col);
        double x1 = cols.end(col);
        double y0 = rows.start(row);
        double y1 = rows.end(row);
        CellRangeAddress m = grid.mergeAt(row, col);
        if (m != null) {
            x1 = cols.end(m.getLastColumn());
            y1 = rows.end(m.getLastRow());
        }
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        Stroke s = stroke(f.diagonal());
        if (f.diagonalDown()) {
            canvas.line((float) x0, (float) y0, (float) x1, (float) y1, s);
        }
        if (f.diagonalUp()) {
            canvas.line((float) x0, (float) y1, (float) x1, (float) y0, s);
        }
    }

    static Stroke stroke(BorderLine line) {
        Stroke s = Stroke.solid((float) line.width(), line.color());
        float[] dash = line.dash();
        return dash == null ? s : s.dash(0, dash);
    }

    private final class Run {

        private final boolean horizontal;

        private final double at;

        private BorderLine line;

        private double from;

        private double to;

        Run(boolean horizontal, double at) {
            this.horizontal = horizontal;
            this.at = at;
        }

        void add(double a, double b, BorderLine l) throws IOException {
            if (line != null && (!l.equals(line) || Math.abs(a - to) > 0.01)) {
                flush();
            }
            if (!l.visible()) {
                return;
            }
            if (line == null) {
                line = l;
                from = a;
            }
            to = b;
        }

        void flush() throws IOException {
            if (line == null) {
                return;
            }
            draw(horizontal, at, from, to, line);
            line = null;
        }
    }

    private void draw(boolean horizontal, double at, double from, double to, BorderLine line) throws IOException {
        if (line.style() == BorderStyle.DOUBLE) {
            double off = 0.96;
            Stroke s = Stroke.solid(0.72f, line.color());
            single(horizontal, at - off, from, to, s);
            single(horizontal, at + off, from, to, s);
            return;
        }
        if (line.dash() == null && line.style() != BorderStyle.HAIR) {
            solid(canvas, horizontal, at, from, to, deviceWidth(line.width(), device), line.color(),
                    line.style() == BorderStyle.THIN && fullSize(device), device);
            return;
        }
        if (line.style() == BorderStyle.HAIR) {
            Hairline.draw(canvas, horizontal, at, from, to, line.color(), device);
            return;
        }
        single(horizontal, at, from, to, stroke(line));
    }

    private void single(boolean horizontal, double at, double from, double to, Stroke s)
            throws IOException {
        if (horizontal) {
            canvas.line((float) from, (float) at, (float) to, (float) at, s);
        } else {
            canvas.line((float) at, (float) from, (float) at, (float) to, s);
        }
    }
}

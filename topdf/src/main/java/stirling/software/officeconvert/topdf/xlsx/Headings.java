package stirling.software.officeconvert.topdf.xlsx;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.apache.poi.ss.util.CellReference;

import stirling.software.officeconvert.topdf.pdf.PdfCanvas;

final class Headings {

    static final double THIN = 0.96;

    private final Grid grid;

    private final FontSpec font;

    private final int digits;

    Headings(Grid grid, int lastRow) {
        this.grid = grid;
        FontSpec def = grid.book().styles().defaultFont();
        String family = def.family().toLowerCase(Locale.ROOT).startsWith("aptos") ? "Segoe UI" : def.family();
        double size = def.family().toLowerCase(Locale.ROOT).startsWith("aptos") ? 10 : def.size();
        this.font = new FontSpec(family, size, false, false, null, false, java.awt.Color.BLACK, null);
        this.digits = Math.max(1, String.valueOf(lastRow + 1).length());
    }

    double width(double device) {
        PrintMetrics m = grid.book().metrics();
        long pad = Math.round(2 * CellLayout.PAD / PrintMetrics.PX * device);
        return ((digits + 1) * m.printerDigit(device) + pad) * PrintMetrics.PX / device;
    }

    double height(double device) {
        double h = grid.defaultHeight() > 0 ? grid.defaultHeight() : grid.book().metrics().printerRowPoints();
        return Paginator.quantize(h, device);
    }

    void paint(PdfCanvas canvas, List<Band> rows, List<Band> cols, double device) throws IOException {
        double width = width(device);
        double height = height(device);
        Typesetter t = grid.book().typesetter();
        double thin = BorderPainter.deviceWidth(THIN, device);
        boolean edge = BorderPainter.fullSize(device);
        double totalW = 0;
        for (Band b : cols) {
            totalW += b.length();
        }
        double totalH = 0;
        for (Band b : rows) {
            totalH += b.length();
        }
        BorderPainter.solid(canvas, true, height, 0, width + totalW, thin, BlockPainter.GRID, edge, device);
        BorderPainter.solid(canvas, false, width, 0, height + totalH, thin, BlockPainter.GRID, edge, device);
        double x = width;
        double baseline = height - grid.rowDescent(-1);
        for (Band b : cols) {
            for (int c = b.first; c <= b.last; c++) {
                double w = b.size(c);
                if (w <= 0) {
                    continue;
                }
                String label = CellReference.convertNumToColString(c);
                double tw = t.width(label, font, font.size());
                t.draw(canvas, label, font, font.size(), x + CellLayout.CENTRED_SHIFT + (w - tw) / 2, baseline);
                x += w;
                BorderPainter.solid(canvas, false, x, 0, height, thin, BlockPainter.GRID, edge, device);
            }
        }
        double y = height;
        for (Band b : rows) {
            for (int r = b.first; r <= b.last; r++) {
                double h = b.size(r);
                if (h <= 0) {
                    continue;
                }
                String label = String.valueOf(r + 1);
                double tw = t.width(label, font, font.size());
                t.draw(canvas, label, font, font.size(), CellLayout.CENTRED_SHIFT + (width - tw) / 2,
                        y + h - grid.rowDescent(r));
                y += h;
                BorderPainter.solid(canvas, true, y, 0, width, thin, BlockPainter.GRID, edge, device);
            }
        }
    }
}

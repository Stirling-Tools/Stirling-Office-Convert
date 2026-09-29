package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;
import java.io.IOException;

import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.Stroke;

final class Hairline {

    static final float STROKE = 0.14f;

    static final float FILL = 0.12f;

    private Hairline() {}

    // GDI draws a one pixel line in the device pixel after its edge
    static void draw(PdfCanvas canvas, boolean horizontal, double at, double from, double to, Color color,
            double device) throws IOException {
        double px = PrintMetrics.PX / (device > 0 ? device : 1);
        Stroke s = Stroke.solid((float) (px * STROKE / FILL), color);
        Fill f = Fill.solid(color);
        double mid = at + px / 2;
        if (horizontal) {
            canvas.line((float) from, (float) mid, (float) to, (float) mid, s);
            canvas.rect((float) from, (float) at, (float) (to - from), (float) px, f, null);
        } else {
            canvas.line((float) mid, (float) from, (float) mid, (float) to, s);
            canvas.rect((float) at, (float) from, (float) px, (float) (to - from), f, null);
        }
    }
}

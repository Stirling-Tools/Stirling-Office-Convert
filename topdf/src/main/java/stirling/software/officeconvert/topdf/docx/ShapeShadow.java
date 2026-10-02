package stirling.software.officeconvert.topdf.docx;

import java.awt.Color;
import java.util.List;

import stirling.software.officeconvert.topdf.pdf.Fill;

// Outer shadows: a blurred copy of a box, offset and drawn in layers that fade out across the blur radius
final class ShapeShadow {

    private static final int LAYERS = 4;

    private ShapeShadow() {}

    static void soft(float x, float y, float w, float h, Chart.Shadow s, List<Op> ops) {
        float sx = x + s.dx();
        float sy = y + s.dy();
        float alpha = s.color().getAlpha() / 255f;
        if (s.blur() < 0.5f) {
            ops.add(new Op.Rect(sx, sy, w, h, Fill.solid(s.color()), null));
            return;
        }
        float layer = (float) (1 - Math.pow(1 - alpha, 1.0 / LAYERS));
        Color c = new Color(s.color().getRed(), s.color().getGreen(), s.color().getBlue(),
                Math.max(1, Math.round(layer * 255)));
        for (int k = 0; k < LAYERS; k++) {
            float grow = s.blur() / 2 - (k + 0.5f) * s.blur() / LAYERS;
            if (w + 2 * grow <= 0 || h + 2 * grow <= 0) {
                continue;
            }
            ops.add(new Op.Rect(sx - grow, sy - grow, w + 2 * grow, h + 2 * grow, Fill.solid(c), null));
        }
    }
}

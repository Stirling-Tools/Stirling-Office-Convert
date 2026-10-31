package stirling.software.officeconvert.extract;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;

record GlyphPrint(float[] cells, float left, float bottom, float right, float top, float advance) {

    static final int GRID = 16;

    private static final int SUPER = 4;

    private static final double STRETCH = 3;

    static GlyphPrint of(Shape ink, float advance) {
        Rectangle2D b = ink.getBounds2D();
        if (b.isEmpty() || !Double.isFinite(b.getWidth()) || !Double.isFinite(b.getHeight())) {
            return null;
        }
        return new GlyphPrint(cells(ink, b), (float) b.getMinX(), (float) b.getMinY(), (float) b.getMaxX(),
                (float) b.getMaxY(), advance);
    }

    float width() {
        return right - left;
    }

    float height() {
        return top - bottom;
    }

    float aspect() {
        return (float) Math.log(Math.max(width(), 1e-6f) / Math.max(height(), 1e-6f));
    }

    float shape(GlyphPrint o) {
        return shape(o, Float.MAX_VALUE);
    }

    float shape(GlyphPrint o, float bound) {
        float limit = bound * cells.length;
        float sum = 0;
        for (int i = 0; i < cells.length; i++) {
            float d = cells[i] - o.cells[i];
            sum += d * d;
            if (sum > limit) {
                return Float.MAX_VALUE;
            }
        }
        return sum / cells.length;
    }

    private static float[] cells(Shape ink, Rectangle2D b) {
        int n = GRID * SUPER;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            double scale = n / Math.max(b.getWidth(), b.getHeight());
            double sx = Math.min(n / b.getWidth(), scale * STRETCH);
            double sy = Math.min(n / b.getHeight(), scale * STRETCH);
            AffineTransform t = AffineTransform.getTranslateInstance(n / 2.0, n / 2.0);
            t.scale(sx, -sy);
            t.translate(-b.getCenterX(), -b.getCenterY());
            g.fill(t.createTransformedShape(ink));
        } finally {
            g.dispose();
        }
        Raster r = img.getRaster();
        int[] px = r.getPixels(0, 0, n, n, (int[]) null);
        float[] cells = new float[GRID * GRID];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                cells[(y / SUPER) * GRID + x / SUPER] += px[y * n + x];
            }
        }
        float full = 255f * SUPER * SUPER;
        for (int i = 0; i < cells.length; i++) {
            cells[i] /= full;
        }
        return smoothed(cells);
    }

    private static float[] smoothed(float[] cells) {
        float[] out = new float[cells.length];
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                float sum = 0;
                float weight = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int yy = y + dy;
                        int xx = x + dx;
                        if (yy >= 0 && yy < GRID && xx >= 0 && xx < GRID) {
                            float w = (2 - Math.abs(dx)) * (2 - Math.abs(dy));
                            sum += w * cells[yy * GRID + xx];
                            weight += w;
                        }
                    }
                }
                out[y * GRID + x] = sum / weight;
            }
        }
        return out;
    }
}

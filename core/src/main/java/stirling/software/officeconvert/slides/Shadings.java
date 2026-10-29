package stirling.software.officeconvert.slides;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.pdmodel.common.function.PDFunctionType3;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType3;

final class Shadings {

    private static final int SAMPLES = 128;

    private static final int MAX_STOPS = 10;

    private static final double TOLERANCE = 2.5 / 255;

    private static final double CENTRED = 0.005;

    private Shadings() {}

    static Gradient of(PDShading shading, AffineTransform toDisplay, Rectangle2D box) {
        if (box.getWidth() < 0.5 || box.getHeight() < 0.5 || shading.getBBox() != null) {
            return null;
        }
        try {
            return switch (shading) {
                case PDShadingType3 radial -> radial(radial, toDisplay, box);
                case PDShadingType2 axial when axial.getShadingType() == PDShading.SHADING_TYPE2 -> axial(axial, toDisplay, box);
                default -> null;
            };
        } catch (IOException | RuntimeException | NoninvertibleTransformException e) {
            return null;
        }
    }

    private static Gradient axial(PDShadingType2 shading, AffineTransform toDisplay, Rectangle2D box)
            throws IOException, NoninvertibleTransformException {
        float[] c = floats(shading.getCoords(), 4);
        if (c == null) {
            return null;
        }
        double vx = c[2] - c[0];
        double vy = c[3] - c[1];
        double length = vx * vx + vy * vy;
        if (length < 1e-9) {
            return null;
        }
        AffineTransform inverse = toDisplay.createInverse();
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (Point2D corner : corners(box)) {
            Point2D p = inverse.transform(corner, null);
            double s = ((p.getX() - c[0]) * vx + (p.getY() - c[1]) * vy) / length;
            low = Math.min(low, s);
            high = Math.max(high, s);
        }
        if (high - low < 1e-6) {
            return null;
        }
        double gx = inverse.getScaleX() * vx + inverse.getShearY() * vy;
        double gy = inverse.getShearX() * vx + inverse.getScaleY() * vy;
        float angle = (float) Math.floorMod(Math.round(Math.toDegrees(Math.atan2(gy, gx)) * 1000), 360_000) / 1000f;
        return sample(shading, false, angle, low, high);
    }

    private static Gradient radial(PDShadingType3 shading, AffineTransform toDisplay, Rectangle2D box)
            throws IOException {
        float[] c = floats(shading.getCoords(), 6);
        if (c == null || c[2] < 0 || c[5] < 0) {
            return null;
        }
        double span = Math.max(Math.max(c[2], c[5]), 1e-6);
        if (Math.abs(c[3] - c[0]) > 1e-3 * span || Math.abs(c[4] - c[1]) > 1e-3 * span || !conformal(toDisplay)) {
            return null;
        }
        double scale = Math.sqrt(Math.abs(toDisplay.getDeterminant()));
        double inner = c[2] * scale;
        double outer = c[5] * scale;
        if (Math.abs(outer - inner) < 1e-6) {
            return null;
        }
        Point2D centre = toDisplay.transform(new Point2D.Double(c[0], c[1]), null);
        double tolerance = CENTRED * Math.max(box.getWidth(), box.getHeight()) + 0.5;
        if (Math.abs(centre.getX() - box.getCenterX()) > tolerance || Math.abs(centre.getY() - box.getCenterY()) > tolerance) {
            return null;
        }
        double reach = Math.hypot(box.getWidth(), box.getHeight()) / 2;
        return sample(shading, true, 0, -inner / (outer - inner), (reach - inner) / (outer - inner));
    }

    private static boolean conformal(AffineTransform t) {
        double a = t.getScaleX();
        double b = t.getShearY();
        double c = t.getShearX();
        double d = t.getScaleY();
        double eps = 1e-3 * Math.max(Math.max(Math.abs(a), Math.abs(b)), Math.max(Math.abs(c), Math.abs(d)));
        return Math.abs(a - d) <= eps && Math.abs(b + c) <= eps || Math.abs(a + d) <= eps && Math.abs(b - c) <= eps;
    }

    private static Gradient sample(PDShadingType2 shading, boolean radial, float angle, double from, double to)
            throws IOException {
        boolean[] extend = extend(shading.getExtend());
        float[] domain = floats(shading.getDomain(), 2);
        float t0 = domain == null ? 0 : domain[0];
        float t1 = domain == null ? 1 : domain[1];
        List<Double> at = new ArrayList<>();
        for (int i = 0; i <= SAMPLES; i++) {
            at.add((double) i / SAMPLES);
        }
        List<Double> edges = new ArrayList<>(List.of(0.0, 1.0));
        if (shading.getFunction() instanceof PDFunctionType3 stitched && stitched.getBounds() != null && t1 != t0) {
            for (float bound : stitched.getBounds().toFloatArray()) {
                edges.add((double) (bound - t0) / (t1 - t0));
            }
        }
        for (double edge : edges) {
            double u = (edge - from) / (to - from);
            if (u > 0 && u < 1) {
                at.add(u);
            }
        }
        at.sort(null);
        int n = at.size();
        double[] positions = new double[n];
        double[][] rgb = new double[n][];
        PDColorSpace space = shading.getColorSpace();
        for (int i = 0; i < n; i++) {
            double u = at.get(i);
            double s = from + u * (to - from);
            if (s < -1e-6 && !extend[0] || s > 1 + 1e-6 && !extend[1]) {
                return null;
            }
            s = Math.clamp(s, 0, 1);
            float[] rgbOf = space.toRGB(shading.evalFunction((float) (t0 + s * (t1 - t0))));
            positions[i] = u;
            rgb[i] = new double[] {rgbOf[0], rgbOf[1], rgbOf[2]};
        }
        List<Integer> kept = new ArrayList<>();
        kept.add(0);
        simplify(positions, rgb, 0, n - 1, kept);
        kept.add(n - 1);
        if (kept.size() > MAX_STOPS) {
            return null;
        }
        float[] stops = new float[kept.size()];
        int[] colours = new int[kept.size()];
        for (int i = 0; i < kept.size(); i++) {
            int k = kept.get(i);
            stops[i] = (float) positions[k];
            colours[i] = rgb(rgb[k]);
        }
        return new Gradient(radial, angle, stops, colours);
    }

    private static void simplify(double[] positions, double[][] rgb, int from, int to, List<Integer> kept) {
        double worst = TOLERANCE;
        int split = -1;
        double span = positions[to] - positions[from];
        for (int i = from + 1; i < to; i++) {
            double f = span <= 0 ? 0 : (positions[i] - positions[from]) / span;
            for (int ch = 0; ch < 3; ch++) {
                double d = Math.abs(rgb[i][ch] - (rgb[from][ch] + f * (rgb[to][ch] - rgb[from][ch])));
                if (d > worst) {
                    worst = d;
                    split = i;
                }
            }
        }
        if (split < 0) {
            return;
        }
        simplify(positions, rgb, from, split, kept);
        kept.add(split);
        simplify(positions, rgb, split, to, kept);
    }

    private static int rgb(double[] c) {
        int r = (int) Math.round(Math.clamp(c[0], 0, 1) * 255);
        int g = (int) Math.round(Math.clamp(c[1], 0, 1) * 255);
        int b = (int) Math.round(Math.clamp(c[2], 0, 1) * 255);
        return r << 16 | g << 8 | b;
    }

    private static Point2D[] corners(Rectangle2D b) {
        return new Point2D[] {new Point2D.Double(b.getMinX(), b.getMinY()), new Point2D.Double(b.getMaxX(), b.getMinY()),
                new Point2D.Double(b.getMinX(), b.getMaxY()), new Point2D.Double(b.getMaxX(), b.getMaxY())};
    }

    private static boolean[] extend(COSArray a) {
        boolean[] out = new boolean[2];
        for (int i = 0; a != null && i < 2 && i < a.size(); i++) {
            out[i] = a.getObject(i) instanceof COSBoolean b && b.getValue();
        }
        return out;
    }

    private static float[] floats(COSArray a, int n) {
        if (a == null || a.size() < n) {
            return null;
        }
        float[] f = a.toFloatArray();
        for (float v : f) {
            if (!Float.isFinite(v)) {
                return null;
            }
        }
        return f;
    }
}

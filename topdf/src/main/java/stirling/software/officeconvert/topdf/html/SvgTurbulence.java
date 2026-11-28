package stirling.software.officeconvert.topdf.html;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

import org.jsoup.nodes.Element;

final class SvgTurbulence {

    private static final int B = 0x100;
    private static final int BM = 0xff;
    private static final int N = 0x1000;
    private static final int RAND_M = 2147483647;
    private static final int RAND_A = 16807;
    private static final int RAND_Q = 127773;
    private static final int RAND_R = 2836;
    private static final int MAX_OCTAVES = 10;

    private final int[] lattice = new int[B + B + 2];
    private final double[][][] gradient = new double[4][B + B + 2][2];

    private SvgTurbulence(long seed) {
        seed = setup(seed);
        for (int k = 0; k < 4; k++) {
            for (int i = 0; i < B; i++) {
                lattice[i] = i;
                for (int j = 0; j < 2; j++) {
                    seed = random(seed);
                    gradient[k][i][j] = (double) ((seed % (B + B)) - B) / B;
                }
                double s = Math.sqrt(gradient[k][i][0] * gradient[k][i][0] + gradient[k][i][1] * gradient[k][i][1]);
                if (s > 0) {
                    gradient[k][i][0] /= s;
                    gradient[k][i][1] /= s;
                }
            }
        }
        for (int i = B - 1; i > 0; i--) {
            int k = lattice[i];
            seed = random(seed);
            int j = (int) (seed % B);
            lattice[i] = lattice[j];
            lattice[j] = k;
        }
        for (int i = 0; i < B + 2; i++) {
            lattice[B + i] = lattice[i];
            for (int k = 0; k < 4; k++) {
                gradient[k][B + i][0] = gradient[k][i][0];
                gradient[k][B + i][1] = gradient[k][i][1];
            }
        }
    }

    private static long setup(long seed) {
        if (seed <= 0) {
            seed = -(seed % (RAND_M - 1)) + 1;
        }
        if (seed > RAND_M - 1) {
            seed = RAND_M - 1;
        }
        return seed;
    }

    private static long random(long seed) {
        long s = RAND_A * (seed % RAND_Q) - RAND_R * (seed / RAND_Q);
        if (s <= 0) {
            s += RAND_M;
        }
        return s;
    }

    private static double curve(double t) {
        return t * t * (3 - 2 * t);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private double noise(int channel, double vx, double vy, int[] stitch) {
        double t = vx + N;
        int bx0 = (int) t;
        int bx1 = bx0 + 1;
        double rx0 = t - (int) t;
        double rx1 = rx0 - 1;
        t = vy + N;
        int by0 = (int) t;
        int by1 = by0 + 1;
        double ry0 = t - (int) t;
        double ry1 = ry0 - 1;
        if (stitch != null) {
            if (bx0 >= stitch[2]) {
                bx0 -= stitch[0];
            }
            if (bx1 >= stitch[2]) {
                bx1 -= stitch[0];
            }
            if (by0 >= stitch[3]) {
                by0 -= stitch[1];
            }
            if (by1 >= stitch[3]) {
                by1 -= stitch[1];
            }
        }
        bx0 &= BM;
        bx1 &= BM;
        by0 &= BM;
        by1 &= BM;
        int i = lattice[bx0];
        int j = lattice[bx1];
        int b00 = lattice[i + by0];
        int b10 = lattice[j + by0];
        int b01 = lattice[i + by1];
        int b11 = lattice[j + by1];
        double sx = curve(rx0);
        double sy = curve(ry0);
        double[] q = gradient[channel][b00];
        double u = rx0 * q[0] + ry0 * q[1];
        q = gradient[channel][b10];
        double v = rx1 * q[0] + ry0 * q[1];
        double a = lerp(sx, u, v);
        q = gradient[channel][b01];
        u = rx0 * q[0] + ry1 * q[1];
        q = gradient[channel][b11];
        v = rx1 * q[0] + ry1 * q[1];
        double b = lerp(sx, u, v);
        return lerp(sy, a, b);
    }

    private double turbulence(int channel, double x, double y, double fx, double fy, int octaves, boolean fractal,
            boolean stitchTiles, double tx, double ty, double tw, double th) {
        int[] stitch = null;
        if (stitchTiles) {
            if (fx != 0) {
                double lo = Math.floor(tw * fx) / tw;
                double hi = Math.ceil(tw * fx) / tw;
                fx = fx / lo < hi / fx ? lo : hi;
            }
            if (fy != 0) {
                double lo = Math.floor(th * fy) / th;
                double hi = Math.ceil(th * fy) / th;
                fy = fy / lo < hi / fy ? lo : hi;
            }
            int w = (int) (tw * fx + 0.5);
            int h = (int) (th * fy + 0.5);
            stitch = new int[] {w, h, (int) (tx * fx + N + w), (int) (ty * fy + N + h)};
        }
        double sum = 0;
        double vx = x * fx;
        double vy = y * fy;
        double ratio = 1;
        for (int o = 0; o < octaves; o++) {
            double n = noise(channel, vx, vy, stitch);
            sum += fractal ? n / ratio : Math.abs(n) / ratio;
            vx *= 2;
            vy *= 2;
            ratio *= 2;
            if (stitch != null) {
                stitch[0] *= 2;
                stitch[2] = 2 * stitch[2] - N;
                stitch[1] *= 2;
                stitch[3] = 2 * stitch[3] - N;
            }
        }
        return sum;
    }

    static SvgFilter.Img render(Element p, int w, int h, AffineTransform toPixel, boolean linear) {
        float[] bf = SvgDraw.numbers(SvgDoc.attr(p, "baseFrequency"));
        double fx = bf.length > 0 ? bf[0] : 0;
        double fy = bf.length > 1 ? bf[1] : fx;
        SvgFilter.Img out = new SvgFilter.Img(w, h);
        if (fx < 0 || fy < 0) {
            return out;
        }
        float requested = SvgDraw.number(SvgDoc.attr(p, "numOctaves"), 1);
        int octaves = (int) Math.min(MAX_OCTAVES, requested < 0 ? 1 : requested);
        long seed = (long) SvgDraw.number(SvgDoc.attr(p, "seed"), 0);
        boolean fractal = SvgDoc.lower(SvgDoc.attr(p, "type")).equals("fractalnoise");
        boolean stitch = SvgDoc.lower(SvgDoc.attr(p, "stitchTiles")).equals("stitch");
        SvgTurbulence t = new SvgTurbulence(seed);
        AffineTransform inv;
        try {
            inv = toPixel.createInverse();
        } catch (NoninvertibleTransformException e) {
            return out;
        }
        Rectangle2D tile = inv.createTransformedShape(new Rectangle2D.Double(0, 0, w, h)).getBounds2D();
        Point2D.Double pt = new Point2D.Double();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                pt.setLocation(x + 0.5, y + 0.5);
                Point2D u = inv.transform(pt, null);
                double[] c = new double[4];
                for (int k = 0; k < 4; k++) {
                    double v = t.turbulence(k, u.getX(), u.getY(), fx, fy, octaves, fractal, stitch, tile.getX(), tile.getY(),
                            tile.getWidth(), tile.getHeight());
                    v = fractal ? (v + 1) / 2 : v;
                    c[k] = Math.max(0, Math.min(1, v));
                }
                int i = (y * w + x) * 4;
                float a = (float) c[3];
                out.p[i] = (float) c[0] * a;
                out.p[i + 1] = (float) c[1] * a;
                out.p[i + 2] = (float) c[2] * a;
                out.p[i + 3] = a;
            }
        }
        return out;
    }
}

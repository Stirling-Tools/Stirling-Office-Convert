package stirling.software.officeconvert.photosplit;

import java.awt.geom.Point2D;
import java.util.Arrays;
import java.util.List;

record RotatedRect(double cx, double cy, double width, double height, double angle) {

    double area() {
        return width * height;
    }

    List<Point2D.Double> corners() {
        double c = Math.cos(Math.toRadians(angle));
        double s = Math.sin(Math.toRadians(angle));
        double hw = width / 2;
        double hh = height / 2;
        double[][] local = {{-hw, -hh}, {hw, -hh}, {hw, hh}, {-hw, hh}};
        Point2D.Double[] out = new Point2D.Double[4];
        for (int i = 0; i < 4; i++) {
            out[i] = new Point2D.Double(cx + local[i][0] * c - local[i][1] * s, cy + local[i][0] * s + local[i][1] * c);
        }
        return List.of(out);
    }

    boolean contains(double x, double y) {
        double a = Math.toRadians(angle);
        double dx = x - cx;
        double dy = y - cy;
        double u = dx * Math.cos(a) + dy * Math.sin(a);
        double v = -dx * Math.sin(a) + dy * Math.cos(a);
        return Math.abs(u) <= width / 2 && Math.abs(v) <= height / 2;
    }

    RotatedRect inset(double by) {
        return new RotatedRect(cx, cy, Math.max(1, width - 2 * by), Math.max(1, height - 2 * by), angle);
    }

    static RotatedRect enclosing(long[] points, int count) {
        long[] hull = hull(points, count);
        int n = hull.length;
        if (n == 0) {
            return new RotatedRect(0, 0, 0, 0, 0);
        }
        double bestArea = Double.MAX_VALUE;
        RotatedRect best = null;
        for (int i = 0; i < Math.max(1, n); i++) {
            long a = hull[i];
            long b = hull[(i + 1) % n];
            double ex = x(b) - x(a);
            double ey = y(b) - y(a);
            double len = Math.hypot(ex, ey);
            if (len == 0) {
                ex = 1;
                ey = 0;
                len = 1;
            }
            ex /= len;
            ey /= len;
            double minU = Double.MAX_VALUE;
            double maxU = -Double.MAX_VALUE;
            double minV = Double.MAX_VALUE;
            double maxV = -Double.MAX_VALUE;
            for (long p : hull) {
                double u = x(p) * ex + y(p) * ey;
                double v = -x(p) * ey + y(p) * ex;
                minU = Math.min(minU, u);
                maxU = Math.max(maxU, u);
                minV = Math.min(minV, v);
                maxV = Math.max(maxV, v);
            }
            double area = (maxU - minU) * (maxV - minV);
            if (area < bestArea - 1e-9) {
                bestArea = area;
                double mu = (minU + maxU) / 2;
                double mv = (minV + maxV) / 2;
                best = upright(mu * ex - mv * ey, mu * ey + mv * ex, maxU - minU, maxV - minV,
                        Math.toDegrees(Math.atan2(ey, ex)));
            }
        }
        return best;
    }

    private static RotatedRect upright(double cx, double cy, double w, double h, double degrees) {
        double a = degrees;
        while (a > 45) {
            a -= 90;
            double t = w;
            w = h;
            h = t;
        }
        while (a <= -45) {
            a += 90;
            double t = w;
            w = h;
            h = t;
        }
        return new RotatedRect(cx, cy, w, h, a);
    }

    static long point(int x, int y) {
        return (long) x << 32 | (y & 0xFFFFFFFFL);
    }

    private static int x(long p) {
        return (int) (p >> 32);
    }

    private static int y(long p) {
        return (int) p;
    }

    private static long[] hull(long[] points, int count) {
        long[] p = Arrays.copyOf(points, count);
        Arrays.sort(p);
        int n = 0;
        for (int i = 0; i < p.length; i++) {
            if (i == 0 || p[i] != p[i - 1]) {
                p[n++] = p[i];
            }
        }
        if (n < 3) {
            return Arrays.copyOf(p, n);
        }
        long[] h = new long[2 * n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(h[k - 2], h[k - 1], p[i]) <= 0) {
                k--;
            }
            h[k++] = p[i];
        }
        for (int i = n - 2, t = k + 1; i >= 0; i--) {
            while (k >= t && cross(h[k - 2], h[k - 1], p[i]) <= 0) {
                k--;
            }
            h[k++] = p[i];
        }
        return Arrays.copyOf(h, k - 1);
    }

    private static long cross(long o, long a, long b) {
        return (long) (x(a) - x(o)) * (y(b) - y(o)) - (long) (y(a) - y(o)) * (x(b) - x(o));
    }
}

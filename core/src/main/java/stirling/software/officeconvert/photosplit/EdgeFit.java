package stirling.software.officeconvert.photosplit;

import java.util.List;

final class EdgeFit {

    private static final double STEP = 0.5;
    private static final double INSIDE = 24;
    private static final double OUTSIDE = 4;
    private static final int SEGMENTS = 4;
    private static final double MIN_SIDE = 24;
    private static final double MAX_TURN = 1.5;
    private static final double PEAK_SHARE = 0.04;
    private static final double MIN_GRADIENT = 6;
    private static final int SHARP_REACH = 4;
    private static final double SHARPNESS = 1.6;

    record Fit(RotatedRect rect, boolean supported) {}

    private EdgeFit() {}

    static RotatedRect refine(Picture scan, RotatedRect rect) {
        return checked(scan, rect).rect();
    }

    static Fit checked(Picture scan, RotatedRect rect) {
        return fit(scan, rect, INSIDE, OUTSIDE, 2, SEGMENTS, List.of());
    }

    static RotatedRect refine(Picture scan, RotatedRect rect, double inside, double outside, int passes) {
        return refine(scan, rect, inside, outside, passes, SEGMENTS, List.of());
    }

    static RotatedRect conflicted(Picture scan, RotatedRect rect, double inside, List<RotatedRect> neighbors) {
        return refine(scan, rect, inside, 4, 2, 8, neighbors);
    }

    private static RotatedRect refine(Picture scan, RotatedRect rect, double inside, double outside, int passes,
            int segments, List<RotatedRect> neighbors) {
        return fit(scan, rect, inside, outside, passes, segments, neighbors).rect();
    }

    private static Fit fit(Picture scan, RotatedRect rect, double inside, double outside, int passes,
            int segments, List<RotatedRect> neighbors) {
        RotatedRect r = rect;
        boolean supported = false;
        for (int pass = 0; pass < passes; pass++) {
            RotatedRect next = once(scan, r, inside, outside, segments, neighbors);
            if (next == null) {
                break;
            }
            r = next;
            supported = true;
        }
        return new Fit(r, supported);
    }

    private static RotatedRect once(Picture scan, RotatedRect rect, double inside, double outside, int segments,
            List<RotatedRect> neighbors) {
        if (rect.width() < MIN_SIDE || rect.height() < MIN_SIDE) {
            return null;
        }
        double a = Math.toRadians(rect.angle());
        double c = Math.cos(a);
        double s = Math.sin(a);
        double[][] normals = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        double[] shift = new double[4];
        double turnSum = 0;
        double turnWeight = 0;
        for (int side = 0; side < 4; side++) {
            double nu = normals[side][0];
            double nv = normals[side][1];
            double half = nu != 0 ? rect.width() / 2 : rect.height() / 2;
            double length = nu != 0 ? rect.height() : rect.width();
            double[] t = new double[segments];
            double[] d = new double[segments];
            double[] wgt = new double[segments];
            int found = 0;
            for (int k = 0; k < segments; k++) {
                double from = -length * 0.4 + length * 0.8 * k / segments;
                double to = -length * 0.4 + length * 0.8 * (k + 1) / segments;
                double[] edge = edge(scan, rect, c, s, nu, nv, half, from, to, inside, outside, neighbors);
                if (edge != null) {
                    t[found] = (from + to) / 2;
                    d[found] = edge[0];
                    wgt[found] = edge[1];
                    found++;
                }
            }
            if (found < 2) {
                return null;
            }
            double sw = 0;
            double st = 0;
            double sd = 0;
            for (int k = 0; k < found; k++) {
                sw += wgt[k];
                st += wgt[k] * t[k];
                sd += wgt[k] * d[k];
            }
            double mt = st / sw;
            double md = sd / sw;
            double stt = 0;
            double std = 0;
            for (int k = 0; k < found; k++) {
                stt += wgt[k] * (t[k] - mt) * (t[k] - mt);
                std += wgt[k] * (t[k] - mt) * (d[k] - md);
            }
            double slope = stt > 0 ? std / stt : 0;
            shift[side] = md - slope * mt;
            turnSum -= Math.toDegrees(Math.atan(slope)) * sw;
            turnWeight += sw;
        }
        double turn = turnWeight > 0 ? turnSum / turnWeight : 0;
        if (Math.abs(turn) > MAX_TURN) {
            turn = 0;
        }
        double width = rect.width() + shift[1] + shift[3];
        double height = rect.height() + shift[0] + shift[2];
        if (width < MIN_SIDE / 2 || height < MIN_SIDE / 2) {
            return null;
        }
        double du = (shift[1] - shift[3]) / 2;
        double dv = (shift[2] - shift[0]) / 2;
        return new RotatedRect(rect.cx() + du * c - dv * s, rect.cy() + du * s + dv * c, width, height, rect.angle() + turn);
    }

    private static double[] edge(Picture scan, RotatedRect rect, double c, double s, double nu, double nv, double half,
            double from, double to, double inside, double outside, List<RotatedRect> neighbors) {
        int offsets = (int) Math.round((inside + outside) / STEP) + 1;
        double[][] profile = new double[offsets][3];
        int samples = Math.max(3, (int) ((to - from) / 1.5));
        double tu = -nv;
        double tv = nu;
        double[] rgb = new double[3];
        int used = 0;
        for (int i = 0; i < samples; i++) {
            if ((i & 31) == 0) {
                PhotoSplitter.checkpoint();
            }
            double along = from + (to - from) * (i + 0.5) / samples;
            double edgeU = nu * half + tu * along;
            double edgeV = nv * half + tv * along;
            double edgeX = rect.cx() + edgeU * c - edgeV * s;
            double edgeY = rect.cy() + edgeU * s + edgeV * c;
            boolean hidden = false;
            for (RotatedRect neighbor : neighbors) {
                if (neighbor.contains(edgeX, edgeY)) {
                    hidden = true;
                    break;
                }
            }
            if (hidden) {
                continue;
            }
            used++;
            for (int o = 0; o < offsets; o++) {
                double off = -inside + o * STEP;
                double u = nu * (half + off) + tu * along;
                double v = nv * (half + off) + tv * along;
                double x = rect.cx() + u * c - v * s;
                double y = rect.cy() + u * s + v * c;
                bilinear(scan, x - 0.5, y - 0.5, rgb);
                for (int k = 0; k < 3; k++) {
                    profile[o][k] += rgb[k];
                }
            }
        }
        if (used < 3) {
            return null;
        }
        double peak = 0;
        double[] grad = new double[offsets];
        for (int o = 1; o < offsets - 1; o++) {
            double g = 0;
            for (int k = 0; k < 3; k++) {
                g += Math.abs(profile[o + 1][k] - profile[o - 1][k]);
            }
            grad[o] = g / used;
            peak = Math.max(peak, grad[o]);
        }
        int at = -1;
        double floor = Math.max(MIN_GRADIENT, PEAK_SHARE * peak);
        for (int o = offsets - 3; o >= 2; o--) {
            if (grad[o] >= floor && grad[o] >= grad[o - 1] && grad[o] >= grad[o + 1] && sharp(grad, o)) {
                at = o;
                break;
            }
        }
        double best = at < 0 ? 0 : grad[at];
        if (at < 0) {
            return null;
        }
        double l = grad[at - 1];
        double r = grad[at + 1];
        double den = l - 2 * best + r;
        double sub = den < 0 ? 0.5 * (l - r) / den : 0;
        return new double[] {-inside + (at + sub) * STEP, best};
    }

    private static boolean sharp(double[] grad, int o) {
        double side = 0;
        if (o - SHARP_REACH >= 1) {
            side = Math.max(side, grad[o - SHARP_REACH]);
        }
        if (o + SHARP_REACH < grad.length - 1) {
            side = Math.max(side, grad[o + SHARP_REACH]);
        }
        return grad[o] >= SHARPNESS * side;
    }

    private static void bilinear(Picture scan, double x, double y, double[] out) {
        int w = scan.width();
        int h = scan.height();
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        double fx = x - x0;
        double fy = y - y0;
        int xa = Math.clamp(x0, 0, w - 1);
        int xb = Math.clamp(x0 + 1, 0, w - 1);
        int ya = Math.clamp(y0, 0, h - 1);
        int yb = Math.clamp(y0 + 1, 0, h - 1);
        int p00 = scan.rgb(xa, ya);
        int p01 = scan.rgb(xb, ya);
        int p10 = scan.rgb(xa, yb);
        int p11 = scan.rgb(xb, yb);
        for (int k = 0; k < 3; k++) {
            int sh = 16 - 8 * k;
            double top = (p00 >> sh & 0xFF) * (1 - fx) + (p01 >> sh & 0xFF) * fx;
            double bottom = (p10 >> sh & 0xFF) * (1 - fx) + (p11 >> sh & 0xFF) * fx;
            out[k] = top * (1 - fy) + bottom * fy;
        }
    }
}

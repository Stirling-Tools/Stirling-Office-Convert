package stirling.software.officeconvert.topdf.html;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.Locale;

import org.jsoup.nodes.Element;

final class SvgLighting {

    static final int MAX_ORDER = 32;

    private SvgLighting() {}

    static SvgFilter.Img convolve(Element p, SvgFilter.Img in, SvgDraw d) {
        SvgFilter.Img none = in;
        float[] order = SvgDraw.numbers(SvgDoc.attr(p, "order"));
        int ox = order.length > 0 ? (int) order[0] : 3;
        int oy = order.length > 1 ? (int) order[1] : ox;
        if (order.length > 2 || order.length > 0 && (order[0] != ox || order.length > 1 && order[1] != oy)
                || ox < 1 || oy < 1 || ox > MAX_ORDER || oy > MAX_ORDER) {
            return none;
        }
        float[] k = SvgDraw.numbers(SvgDoc.attr(p, "kernelMatrix"));
        if (k.length != ox * oy) {
            return none;
        }
        float sum = 0;
        for (float v : k) {
            sum += v;
        }
        boolean zero = Math.abs(sum) < 1e-6f;
        float divisor = SvgDraw.number(SvgDoc.attr(p, "divisor"), zero ? 1 : sum);
        if (divisor == 0) {
            divisor = zero ? 1 : sum;
        }
        float bias = SvgDraw.number(SvgDoc.attr(p, "bias"), 0);
        int tx = SvgDoc.has(p, "targetX") ? (int) SvgDraw.number(SvgDoc.attr(p, "targetX"), -1) : ox / 2;
        int ty = SvgDoc.has(p, "targetY") ? (int) SvgDraw.number(SvgDoc.attr(p, "targetY"), -1) : oy / 2;
        if (tx < 0 || tx >= ox || ty < 0 || ty >= oy) {
            return none;
        }
        String edge = SvgDoc.lower(SvgDoc.attr(p, "edgeMode"));
        boolean preserveAlpha = SvgDoc.lower(SvgDoc.attr(p, "preserveAlpha")).equals("true");
        d.charge((long) in.w * in.h * ox * oy);
        if (d.spent()) {
            return new SvgFilter.Img(in.w, in.h);
        }
        SvgFilter.Img out = new SvgFilter.Img(in.w, in.h);
        float[] px = new float[4];
        for (int y = 0; y < in.h; y++) {
            for (int x = 0; x < in.w; x++) {
                float r = 0;
                float g = 0;
                float b = 0;
                float a = 0;
                for (int j = 0; j < oy; j++) {
                    for (int i = 0; i < ox; i++) {
                        if (!sample(in, x - tx + i, y - ty + j, edge, px, preserveAlpha)) {
                            continue;
                        }
                        float w = k[(oy - 1 - j) * ox + (ox - 1 - i)];
                        r += px[0] * w;
                        g += px[1] * w;
                        b += px[2] * w;
                        a += px[3] * w;
                    }
                }
                int o = (y * in.w + x) * 4;
                if (preserveAlpha) {
                    float al = in.p[o + 3];
                    out.p[o] = clamp(r / divisor + bias) * al;
                    out.p[o + 1] = clamp(g / divisor + bias) * al;
                    out.p[o + 2] = clamp(b / divisor + bias) * al;
                    out.p[o + 3] = al;
                } else {
                    float al = clamp(a / divisor + bias);
                    out.p[o + 3] = al;
                    out.p[o] = Math.min(al, clamp(r / divisor + bias * al));
                    out.p[o + 1] = Math.min(al, clamp(g / divisor + bias * al));
                    out.p[o + 2] = Math.min(al, clamp(b / divisor + bias * al));
                }
            }
        }
        return out;
    }

    private static boolean sample(SvgFilter.Img in, int x, int y, String edge, float[] px, boolean unpremultiply) {
        if (x < 0 || y < 0 || x >= in.w || y >= in.h) {
            switch (edge) {
                case "none" -> {
                    return false;
                }
                case "wrap" -> {
                    x = Math.floorMod(x, in.w);
                    y = Math.floorMod(y, in.h);
                }
                default -> {
                    x = Math.max(0, Math.min(in.w - 1, x));
                    y = Math.max(0, Math.min(in.h - 1, y));
                }
            }
        }
        int o = (y * in.w + x) * 4;
        float a = in.p[o + 3];
        if (unpremultiply) {
            px[0] = a > 0 ? in.p[o] / a : 0;
            px[1] = a > 0 ? in.p[o + 1] / a : 0;
            px[2] = a > 0 ? in.p[o + 2] / a : 0;
        } else {
            px[0] = in.p[o];
            px[1] = in.p[o + 1];
            px[2] = in.p[o + 2];
        }
        px[3] = a;
        return true;
    }

    private static int channel(String v) {
        return switch (v.strip().toUpperCase(Locale.ROOT)) {
            case "R" -> 0;
            case "G" -> 1;
            case "B" -> 2;
            default -> 3;
        };
    }

    static SvgFilter.Img displace(SvgFilter.Img in, SvgFilter.Img map, double sx, double sy, String xs, String ys) {
        SvgFilter.Img out = new SvgFilter.Img(in.w, in.h);
        if (!Double.isFinite(sx) || !Double.isFinite(sy)) {
            return out;
        }
        int cx = channel(xs);
        int cy = channel(ys);
        for (int y = 0; y < in.h; y++) {
            for (int x = 0; x < in.w; x++) {
                int o = (y * in.w + x) * 4;
                float a = map.p[o + 3];
                float vx = cx == 3 ? a : a > 0 ? map.p[o + cx] / a : 0;
                float vy = cy == 3 ? a : a > 0 ? map.p[o + cy] / a : 0;
                double fx = x + sx * (vx - 0.5);
                double fy = y + sy * (vy - 0.5);
                if (!(fx >= 0 && fy >= 0 && fx < in.w && fy < in.h)) {
                    continue;
                }
                int s = ((int) fy * in.w + (int) fx) * 4;
                System.arraycopy(in.p, s, out.p, o, 4);
            }
        }
        return out;
    }

    static SvgFilter.Img light(Element p, SvgStyle ps, SvgFilter.Img in, boolean lin, boolean specular,
            SvgFilter.Ctx c) {
        SvgFilter.Img out = new SvgFilter.Img(in.w, in.h);
        Element source = null;
        for (Element e : p.children()) {
            String t = SvgDoc.local(e).toLowerCase(Locale.ROOT);
            if (t.equals("fedistantlight") || t.equals("fepointlight") || t.equals("fespotlight")) {
                source = e;
                break;
            }
        }
        float surface = SvgDraw.number(SvgDoc.attr(p, "surfaceScale"), 1);
        float constant = SvgDraw.number(SvgDoc.attr(p, specular ? "specularConstant" : "diffuseConstant"), 1);
        float exponent = SvgDraw.number(SvgDoc.attr(p, "specularExponent"), 1);
        if (specular && constant < 0 || source == null) {
            return out;
        }
        if (constant < 0) {
            for (int i = 0; i < out.p.length; i += 4) {
                out.p[i + 3] = 1;
            }
            return out;
        }
        exponent = Math.max(1, Math.min(128, exponent));
        String colour = ps.own("lighting-color", "white");
        int rgb;
        if (colour.equalsIgnoreCase("currentcolor")) {
            rgb = ps.color;
        } else {
            Integer parsed = Css.opaque(SvgDraw.percentAlpha(colour));
            rgb = parsed == null || parsed < 0 ? 0xFFFFFF : parsed;
        }
        float[] lc = {((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f};
        if (lin) {
            for (int i = 0; i < 3; i++) {
                lc[i] = SvgFilter.linear(lc[i]);
            }
        }
        String kind = SvgDoc.local(source).toLowerCase(Locale.ROOT);
        AffineTransform toPixel = c.toPixel();
        double zScale = Math.sqrt(Math.abs(toPixel.getDeterminant()));
        double[] light = new double[3];
        double[] spot = null;
        double spotExp = 1;
        double cone = -1;
        boolean distant = kind.equals("fedistantlight");
        if (distant) {
            double az = Math.toRadians(SvgDraw.number(SvgDoc.attr(source, "azimuth"), 0));
            double el = Math.toRadians(SvgDraw.number(SvgDoc.attr(source, "elevation"), 0));
            light = new double[] {Math.cos(az) * Math.cos(el), Math.sin(az) * Math.cos(el), Math.sin(el)};
        } else {
            light = position(source, "x", "y", "z", toPixel, zScale, c);
            if (kind.equals("fespotlight")) {
                double[] at = position(source, "pointsAtX", "pointsAtY", "pointsAtZ", toPixel, zScale, c);
                double[] sv = {at[0] - light[0], at[1] - light[1], at[2] - light[2]};
                double n = Math.sqrt(sv[0] * sv[0] + sv[1] * sv[1] + sv[2] * sv[2]);
                spot = n > 0 ? new double[] {sv[0] / n, sv[1] / n, sv[2] / n} : new double[] {0, 0, -1};
                spotExp = SvgDraw.number(SvgDoc.attr(source, "specularExponent"), 1);
                if (SvgDoc.has(source, "limitingConeAngle")) {
                    cone = Math.cos(Math.toRadians(Math.abs(SvgDraw.number(SvgDoc.attr(source, "limitingConeAngle"),
                            90))));
                }
            }
        }
        int w = in.w;
        int h = in.h;
        int pw = w + 2;
        float[] a = new float[pw * (h + 2)];
        for (int y = -1; y <= h; y++) {
            int cy = Math.max(0, Math.min(h - 1, y));
            for (int x = -1; x <= w; x++) {
                int cx = Math.max(0, Math.min(w - 1, x));
                a[(y + 1) * pw + x + 1] = in.p[(cy * w + cx) * 4 + 3];
            }
        }
        double s4 = -surface / 4.0;
        for (int y = 0; y < h; y++) {
            int up = y * pw + 1;
            int mid = up + pw;
            int down = mid + pw;
            for (int x = 0; x < w; x++) {
                double tl = a[up + x - 1];
                double tc = a[up + x];
                double tr = a[up + x + 1];
                double ml = a[mid + x - 1];
                double mc = a[mid + x];
                double mr = a[mid + x + 1];
                double bl = a[down + x - 1];
                double bc = a[down + x];
                double br = a[down + x + 1];
                double nx = s4 * ((tr + 2 * mr + br) - (tl + 2 * ml + bl));
                double ny = s4 * ((bl + 2 * bc + br) - (tl + 2 * tc + tr));
                double nn = Math.sqrt(nx * nx + ny * ny + 1);
                double n0 = nx / nn;
                double n1 = ny / nn;
                double n2 = 1 / nn;
                double l0;
                double l1;
                double l2;
                if (distant) {
                    l0 = light[0];
                    l1 = light[1];
                    l2 = light[2];
                } else {
                    double lx = light[0] - x;
                    double ly = light[1] - y;
                    double lz = light[2] - surface * mc;
                    double ln = Math.sqrt(lx * lx + ly * ly + lz * lz);
                    if (ln > 0) {
                        l0 = lx / ln;
                        l1 = ly / ln;
                        l2 = lz / ln;
                    } else {
                        l0 = 0;
                        l1 = 0;
                        l2 = 1;
                    }
                }
                double k = 1;
                if (spot != null) {
                    double minus = -(l0 * spot[0] + l1 * spot[1] + l2 * spot[2]);
                    if (minus <= 0 || cone >= 0 && minus < cone) {
                        k = 0;
                    } else {
                        k = Math.pow(minus, spotExp);
                    }
                }
                double f;
                if (specular) {
                    double hz = l2 + 1;
                    double hn = Math.sqrt(l0 * l0 + l1 * l1 + hz * hz);
                    double nh = hn > 0 ? (n0 * l0 + n1 * l1 + n2 * hz) / hn : 0;
                    f = constant * Math.pow(Math.max(0, nh), exponent);
                } else {
                    f = constant * Math.max(0, n0 * l0 + n1 * l1 + n2 * l2);
                }
                int o = (y * w + x) * 4;
                float r = clamp((float) (f * k * lc[0]));
                float g = clamp((float) (f * k * lc[1]));
                float b = clamp((float) (f * k * lc[2]));
                out.p[o] = r;
                out.p[o + 1] = g;
                out.p[o + 2] = b;
                out.p[o + 3] = specular ? Math.max(r, Math.max(g, b)) : 1;
            }
        }
        return out;
    }

    private static double[] position(Element e, String xa, String ya, String za, AffineTransform toPixel, double zScale,
            SvgFilter.Ctx c) {
        double x = SvgDraw.number(SvgDoc.attr(e, xa), 0);
        double y = SvgDraw.number(SvgDoc.attr(e, ya), 0);
        double z = SvgDraw.number(SvgDoc.attr(e, za), 0);
        if (c.boxUnits() && c.bbox() != null) {
            x = c.bbox().getX() + x * c.bbox().getWidth();
            y = c.bbox().getY() + y * c.bbox().getHeight();
            z = z * Math.sqrt((c.bbox().getWidth() * c.bbox().getWidth() + c.bbox().getHeight() * c.bbox().getHeight())
                    / 2);
        }
        Point2D pt = toPixel.transform(new Point2D.Double(x, y), null);
        return new double[] {pt.getX(), pt.getY(), z * zScale};
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v != v ? 0 : v;
    }
}

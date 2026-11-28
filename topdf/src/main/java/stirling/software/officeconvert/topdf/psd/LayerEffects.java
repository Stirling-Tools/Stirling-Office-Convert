package stirling.software.officeconvert.topdf.psd;

import java.util.Arrays;
import java.util.List;

final class LayerEffects {

    static final long MAX_PIXELS = 4L << 20;

    static final double INF = 1e20;

    record Stroke(float size, String position, float[] color, float opacity, int blend, Descriptor gradient) {}

    record Shadow(float[] color, float opacity, int blend, double angle, boolean global, float distance, float size,
            float spread, boolean conceal) {}

    record Bevel(String style, float size, float depth, boolean up, double angle, boolean global, float altitude,
            float[] light, float lightOpacity, int lightBlend, float[] dark, float darkOpacity, int darkBlend) {}

    final int left;

    final int top;

    final int width;

    final int height;

    final byte[] stroke;

    final byte[] shadow;

    final boolean strokeUnder;

    byte[] highlight;

    byte[] lowlight;

    byte[] glow;

    byte[] innerShadow;

    byte[] innerGlow;

    byte[] satin;

    byte[][] strokes = new byte[0][];

    private LayerEffects(int left, int top, int width, int height, byte[] stroke, byte[] shadow, boolean strokeUnder) {
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
        this.stroke = stroke;
        this.shadow = shadow;
        this.strokeUnder = strokeUnder;
    }

    static int extent(Stroke stroke, Shadow shadow, Bevel bevel, Shadow glow, Shadow innerShadow) {
        double e = 2;
        if (innerShadow != null) {
            e = Math.max(e, innerShadow.distance() + innerShadow.size() * 1.5 + 2);
        }
        if (glow != null) {
            e = Math.max(e, glow.size() * 1.5 + 2);
        }
        if (bevel != null) {
            e = Math.max(e, bevel.size() + 2);
        }
        if (stroke != null) {
            e = Math.max(e, stroke.size() + 2);
        }
        if (shadow != null) {
            e = Math.max(e, shadow.distance() + shadow.size() * 1.5 + 2);
        }
        return (int) Math.min(4096, Math.ceil(e));
    }

    static LayerEffects build(float[] alpha, int left, int top, int width, int height, Stroke stroke, Shadow shadow,
            Bevel bevel, Shadow glow, Shadow innerShadow, Shadow innerGlow, Shadow satin, List<Stroke> strokes,
            double globalAngle, boolean opaque) {
        byte[] s = stroke == null ? null : stroke(alpha, width, height, stroke, opaque);
        boolean under = s != null && stroke.position().equals("OutF");
        byte[] sh = null;
        if (shadow != null) {
            sh = bytes(soft(alpha, width, height, shadow, globalAngle));
        }
        LayerEffects fx = new LayerEffects(left, top, width, height, s, sh, under);
        if (bevel != null && bevel.size() > 0) {
            fx.bevel(alpha, bevel, globalAngle);
        }
        if (glow != null) {
            fx.glow = bytes(soft(alpha, width, height, glow, globalAngle));
        }
        float[] outside = null;
        if (innerShadow != null || innerGlow != null) {
            outside = new float[alpha.length];
            for (int i = 0; i < alpha.length; i++) {
                outside[i] = 1 - alpha[i];
            }
        }
        if (innerShadow != null) {
            float[] shifted = soft(outside, width, height, innerShadow, globalAngle);
            for (int i = 0; i < shifted.length; i++) {
                shifted[i] = alpha[i] * clamp(shifted[i]);
            }
            fx.innerShadow = bytes(shifted);
        }
        if (innerGlow != null) {
            float[] spread = soft(outside, width, height, innerGlow, globalAngle);
            for (int i = 0; i < spread.length; i++) {
                float b = clamp(spread[i]);
                spread[i] = alpha[i] * clamp(innerGlow.conceal() ? 2 * b : 1 - 2 * b);
            }
            fx.innerGlow = bytes(spread);
        }
        fx.strokes = new byte[strokes.size()][];
        for (int i = 0; i < strokes.size(); i++) {
            fx.strokes[i] = stroke(alpha, width, height, strokes.get(i), opaque);
        }
        if (satin != null) {
            float[] one = soft(alpha, width, height, satin, 0);
            Shadow back = new Shadow(satin.color(), satin.opacity(), satin.blend(), satin.angle() + 180, false,
                    satin.distance(), satin.size(), 0, satin.conceal());
            float[] two = soft(alpha, width, height, back, 0);
            for (int i = 0; i < one.length; i++) {
                float v = Math.abs(one[i] - two[i]);
                one[i] = alpha[i] * clamp(satin.conceal() ? 1 - v : v);
            }
            fx.satin = bytes(one);
        }
        return fx;
    }

    private static byte[] stroke(float[] alpha, int width, int height, Stroke stroke, boolean opaque) {
        byte[] s = null;
        if (stroke.size() > 0) {
            float peak = 0;
            long covered = 0;
            long partial = 0;
            float floor = 0.5f / 255;
            for (int i = 0; i < alpha.length; i++) {
                float a = alpha[i];
                peak = Math.max(peak, a);
                if (a > floor) {
                    covered++;
                    int x = i % width;
                    if (a < 0.98f && x > 0 && x < width - 1 && i >= width && i < alpha.length - width
                            && alpha[i - 1] > floor && alpha[i + 1] > floor && alpha[i - width] > floor
                            && alpha[i + width] > floor) {
                        partial++;
                    }
                }
            }
            boolean outside = stroke.position().equals("OutF");
            boolean soft = outside && peak >= 0.999f && partial * 4 > covered;
            float interior = peak >= 0.999f && opaque ? 1 : 0;
            float[] shape = alpha;
            if (peak < 0.999f || soft) {
                shape = new float[alpha.length];
                for (int i = 0; i < alpha.length; i++) {
                    shape[i] = alpha[i] > 0.5f / 255 ? 1 : 0;
                }
            }
            float[] dOut = distance(shape, width, height, true);
            float[] dIn = stroke.position().equals("OutF") ? null : distance(shape, width, height, false);
            s = new byte[width * height];
            float size = stroke.size();
            for (int i = 0; i < s.length; i++) {
                float v;
                switch (stroke.position()) {
                    case "InsF" -> v = clamp(size + 1 - dIn[i]) * shape[i];
                    case "CtrF" -> v = shape[i] >= 0.5f ? clamp(size / 2 + 1 - dIn[i])
                            : clamp(size / 2 + 1 - dOut[i]);
                    default -> v = soft ? clamp(size + 1 - dOut[i]) * (1 - alpha[i])
                            : shape[i] >= 0.5f ? interior : Math.max(shape[i], clamp(size + 1 - dOut[i]));
                }
                s[i] = (byte) Math.round(v * 255);
            }
        }
        return s;
    }

    private static byte[] bytes(float[] v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Math.round(clamp(v[i]) * 255);
        }
        return out;
    }

    private static float[] soft(float[] alpha, int width, int height, Shadow shadow, double globalAngle) {
        double angle = Math.toRadians(shadow.global() ? globalAngle : shadow.angle());
        int dx = (int) Math.floor(-Math.cos(angle) * shadow.distance() + 0.5 + 1e-9);
        int dy = (int) Math.floor(Math.sin(angle) * shadow.distance() + 0.5 + 1e-9);
        float spread = Math.max(0, Math.min(1, shadow.spread()));
        float grow = shadow.size() * spread;
        float[] grown = alpha;
        if (spread > 0) {
            float[] solid = new float[alpha.length];
            for (int i = 0; i < alpha.length; i++) {
                solid[i] = alpha[i] > 0.5f / 255 ? 1 : 0;
            }
            grown = solid;
            if (grow > 0) {
                float[] d = distance(solid, width, height, true);
                grown = new float[alpha.length];
                for (int i = 0; i < d.length; i++) {
                    grown[i] = solid[i] > 0 ? 1 : clamp(grow + 1 - d[i]);
                }
            }
        }
        float[] base = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                base[y * width + x] = sample(grown, width, height, x - dx, y - dy);
            }
        }
        gauss(base, width, height, shadow.size() * (1 - spread) * 0.4);
        return base;
    }

    private void bevel(float[] alpha, Bevel bevel, double globalAngle) {
        int n = width * height;
        float[] solid = new float[n];
        for (int i = 0; i < n; i++) {
            solid[i] = alpha[i] >= 0.5f ? 1 : 0;
        }
        float[] dIn = distance(solid, width, height, false);
        float[] dOut = distance(solid, width, height, true);
        float size = bevel.size();
        String style = bevel.style();
        float half = Math.max(0.5f, size / 2);
        float[] h = new float[n];
        for (int i = 0; i < n; i++) {
            boolean inside = solid[i] > 0;
            h[i] = switch (style) {
                case "OtrB" -> inside ? 1 : 1 - clamp(dOut[i] / size);
                case "Embs" -> inside ? 0.5f + 0.5f * clamp(dIn[i] / half) : 0.5f - 0.5f * clamp(dOut[i] / half);
                case "PlEb" -> inside ? clamp(dIn[i] / half) : clamp(dOut[i] / half);
                default -> inside ? clamp(dIn[i] / size) : 0;
            };
        }
        gauss(h, width, height, Math.max(0.5, size / 4));
        double angle = Math.toRadians(bevel.global() ? globalAngle : bevel.angle());
        double altitude = Math.toRadians(bevel.altitude());
        double lx = Math.cos(altitude) * Math.cos(angle);
        double ly = -Math.cos(altitude) * Math.sin(angle);
        double lz = Math.sin(altitude);
        double k = size * bevel.depth() * (bevel.up() ? 1 : -1);
        highlight = new byte[n];
        lowlight = new byte[n];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                float region = switch (style) {
                    case "OtrB" -> 1 - alpha[i];
                    case "InrB" -> alpha[i];
                    default -> 1;
                };
                if (region <= 0) {
                    continue;
                }
                double gx = (h[y * width + Math.min(width - 1, x + 1)] - h[y * width + Math.max(0, x - 1)]) / 2.0;
                double gy = (h[Math.min(height - 1, y + 1) * width + x] - h[Math.max(0, y - 1) * width + x]) / 2.0;
                double nx = -gx * k;
                double ny = -gy * k;
                double shade = (nx * lx + ny * ly + lz) / Math.sqrt(nx * nx + ny * ny + 1);
                float up = clamp((float) ((shade - lz) / Math.max(1e-6, 1 - lz)));
                float down = clamp((float) ((lz - shade) / Math.max(1e-6, lz)));
                highlight[i] = (byte) Math.round(up * region * 255);
                lowlight[i] = (byte) Math.round(down * region * 255);
            }
        }
    }

    float at(byte[] raster, int x, int y) {
        int lx = x - left;
        int ly = y - top;
        if (raster == null || lx < 0 || ly < 0 || lx >= width || ly >= height) {
            return 0;
        }
        return (raster[ly * width + lx] & 0xFF) / 255f;
    }

    private static float clamp(float v) {
        return v > 0 ? v < 1 ? v : 1 : 0;
    }

    private static float sample(float[] a, int w, int h, int x, int y) {
        return x < 0 || y < 0 || x >= w || y >= h ? 0 : a[y * w + x];
    }

    static void gauss(float[] a, int w, int h, double sigma) {
        if (!(sigma > 0.2)) {
            return;
        }
        double ideal = Math.sqrt(12 * sigma * sigma / 3 + 1);
        int lower = (int) Math.floor(ideal);
        if (lower % 2 == 0) {
            lower--;
        }
        int upper = lower + 2;
        double m = (12 * sigma * sigma - 3 * lower * lower - 4 * 3 * lower - 3 * 3) / (-4.0 * lower - 4);
        long small = Math.round(m);
        for (int pass = 0; pass < 3; pass++) {
            int r = ((pass < small ? lower : upper) - 1) / 2;
            if (r > 0) {
                blur(a, w, h, r);
            }
        }
    }

    static void blur(float[] a, int w, int h, int r) {
        float[] line = new float[Math.max(w, h)];
        for (int y = 0; y < h; y++) {
            box(a, y * w, 1, w, r, line);
        }
        for (int x = 0; x < w; x++) {
            box(a, x, w, h, r, line);
        }
    }

    private static void box(float[] a, int start, int step, int n, int r, float[] line) {
        for (int i = 0; i < n; i++) {
            line[i] = a[start + i * step];
        }
        float sum = 0;
        int window = 2 * r + 1;
        for (int i = -r; i <= r; i++) {
            sum += line[Math.max(0, Math.min(n - 1, i))];
        }
        for (int i = 0; i < n; i++) {
            a[start + i * step] = sum / window;
            sum -= line[Math.max(0, Math.min(n - 1, i - r))];
            sum += line[Math.max(0, Math.min(n - 1, i + r + 1))];
        }
    }

    static float[] distance(float[] alpha, int w, int h, boolean outside) {
        double[] f = new double[Math.max(w, h)];
        double[] d = new double[Math.max(w, h)];
        int[] v = new int[Math.max(w, h)];
        double[] z = new double[Math.max(w, h) + 1];
        double[] grid = new double[w * h];
        for (int i = 0; i < grid.length; i++) {
            boolean inside = alpha[i] >= 0.5f;
            grid[i] = inside == outside ? 0 : INF;
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                f[y] = grid[y * w + x];
            }
            pass(f, h, d, v, z);
            for (int y = 0; y < h; y++) {
                grid[y * w + x] = d[y];
            }
        }
        for (int y = 0; y < h; y++) {
            System.arraycopy(grid, y * w, f, 0, w);
            pass(f, w, d, v, z);
            System.arraycopy(d, 0, grid, y * w, w);
        }
        float[] out = new float[w * h];
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) Math.sqrt(grid[i]);
        }
        return out;
    }

    private static void pass(double[] f, int n, double[] d, int[] v, double[] z) {
        int k = 0;
        v[0] = 0;
        z[0] = -INF;
        z[1] = INF;
        for (int q = 1; q < n; q++) {
            double s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
            while (s <= z[k]) {
                k--;
                s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = INF;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) {
                k++;
            }
            d[q] = (double) (q - v[k]) * (q - v[k]) + f[v[k]];
        }
        Arrays.fill(z, 0, Math.min(z.length, n + 1), 0);
    }
}

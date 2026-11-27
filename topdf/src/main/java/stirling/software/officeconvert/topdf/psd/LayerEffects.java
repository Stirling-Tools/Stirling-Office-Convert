package stirling.software.officeconvert.topdf.psd;

import java.util.Arrays;

final class LayerEffects {

    static final long MAX_PIXELS = 4L << 20;

    static final double INF = 1e20;

    record Stroke(float size, String position, float[] color, float opacity, int blend) {}

    record Shadow(float[] color, float opacity, int blend, double angle, boolean global, float distance, float size,
            float spread) {}

    final int left;

    final int top;

    final int width;

    final int height;

    final byte[] stroke;

    final byte[] shadow;

    final boolean strokeUnder;

    private LayerEffects(int left, int top, int width, int height, byte[] stroke, byte[] shadow, boolean strokeUnder) {
        this.left = left;
        this.top = top;
        this.width = width;
        this.height = height;
        this.stroke = stroke;
        this.shadow = shadow;
        this.strokeUnder = strokeUnder;
    }

    static int extent(Stroke stroke, Shadow shadow) {
        double e = 2;
        if (stroke != null) {
            e = Math.max(e, stroke.size() + 2);
        }
        if (shadow != null) {
            e = Math.max(e, shadow.distance() + shadow.size() * 1.5 + 2);
        }
        return (int) Math.min(4096, Math.ceil(e));
    }

    static LayerEffects build(float[] alpha, int left, int top, int width, int height, Stroke stroke, Shadow shadow,
            double globalAngle) {
        byte[] s = null;
        boolean under = false;
        if (stroke != null && stroke.size() > 0) {
            float[] dOut = distance(alpha, width, height, true);
            float[] dIn = stroke.position().equals("OutF") ? null : distance(alpha, width, height, false);
            s = new byte[width * height];
            float size = stroke.size();
            under = stroke.position().equals("OutF");
            for (int i = 0; i < s.length; i++) {
                float v;
                switch (stroke.position()) {
                    case "InsF" -> v = alpha[i] >= 0.5f ? clamp(size + 1 - dIn[i]) * alpha[i] : 0;
                    case "CtrF" -> v = alpha[i] >= 0.5f ? clamp(size / 2 + 1 - dIn[i])
                            : clamp(size / 2 + 1 - dOut[i]);
                    default -> v = alpha[i] >= 0.5f ? 1 : Math.max(alpha[i], clamp(size + 1 - dOut[i]));
                }
                s[i] = (byte) Math.round(v * 255);
            }
        }
        byte[] sh = null;
        if (shadow != null) {
            double angle = Math.toRadians(shadow.global() ? globalAngle : shadow.angle());
            int dx = (int) Math.round(-Math.cos(angle) * shadow.distance());
            int dy = (int) Math.round(Math.sin(angle) * shadow.distance());
            float spread = Math.max(0, Math.min(1, shadow.spread()));
            float grow = shadow.size() * spread;
            float[] grown = alpha;
            if (grow > 0) {
                float[] d = distance(alpha, width, height, true);
                grown = new float[alpha.length];
                for (int i = 0; i < d.length; i++) {
                    grown[i] = alpha[i] >= 0.5f ? 1 : Math.max(alpha[i], clamp(grow + 1 - d[i]));
                }
            }
            float[] base = new float[width * height];
            for (int y = 0; y < height; y++) {
                int sy = y - dy;
                if (sy < 0 || sy >= height) {
                    continue;
                }
                for (int x = 0; x < width; x++) {
                    int sx = x - dx;
                    if (sx >= 0 && sx < width) {
                        base[y * width + x] = grown[sy * width + sx];
                    }
                }
            }
            int radius = Math.max(0, Math.round(shadow.size() * (1 - spread) / 3));
            if (radius > 0) {
                for (int pass = 0; pass < 3; pass++) {
                    blur(base, width, height, radius);
                }
            }
            sh = new byte[width * height];
            for (int i = 0; i < sh.length; i++) {
                sh[i] = (byte) Math.round(clamp(base[i]) * 255);
            }
        }
        return new LayerEffects(left, top, width, height, s, sh, under);
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

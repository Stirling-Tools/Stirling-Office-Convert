package stirling.software.officeconvert.jpx;

import java.util.Arrays;

final class Similarity {

    private static final int RADIUS = 5;

    private static final int TAPS = 2 * RADIUS + 1;

    private static final float[] KERNEL = kernel();

    private Similarity() {}

    private static float[] kernel() {
        float[] k = new float[TAPS];
        double sum = 0;
        for (int i = -RADIUS; i <= RADIUS; i++) {
            sum += Math.exp(-(i * i) / (2 * 1.5 * 1.5));
        }
        for (int i = -RADIUS; i <= RADIUS; i++) {
            k[i + RADIUS] = (float) (Math.exp(-(i * i) / (2 * 1.5 * 1.5)) / sum);
        }
        return k;
    }

    interface Rows {

        int count();

        void row(int c, int y, int[] out);
    }

    static Rows of(int[][] planes, int w) {
        return new Rows() {
            @Override
            public int count() {
                return planes.length;
            }

            @Override
            public void row(int c, int y, int[] out) {
                System.arraycopy(planes[c], y * w, out, 0, w);
            }
        };
    }

    static double ssim(int[][] a, int[][] b, int w, int h, int depth) {
        return ssim(of(a, w), of(b, w), w, h, depth);
    }

    static double ssim(Rows a, Rows b, int w, int h, int depth) {
        int[][] rows = new int[Math.max(a.count(), b.count())][w];
        float[][][] ring = new float[5][TAPS][w];
        int[] cached = new int[TAPS];
        Arrays.fill(cached, -1);
        float[] x = new float[w + 2 * RADIUS];
        float[] y = new float[w + 2 * RADIUS];
        double peak = (1 << depth) - 1;
        double c1 = 0.01 * peak * 0.01 * peak;
        double c2 = 0.03 * peak * 0.03 * peak;
        float[][] m = new float[5][w];
        int[] slots = new int[TAPS];
        double sum = 0;
        for (int row = 0; row < h; row++) {
            for (int k = -RADIUS; k <= RADIUS; k++) {
                int src = reflect(row + k, h);
                int slot = src % TAPS;
                if (cached[slot] != src) {
                    horizontal(a, b, rows, src, w, x, y, ring, slot);
                    cached[slot] = src;
                }
            }
            for (int k = -RADIUS; k <= RADIUS; k++) {
                slots[k + RADIUS] = reflect(row + k, h) % TAPS;
            }
            for (int map = 0; map < 5; map++) {
                float[] acc = m[map];
                Arrays.fill(acc, 0);
                for (int k = 0; k < TAPS; k++) {
                    float g = KERNEL[k];
                    float[] line = ring[map][slots[k]];
                    for (int col = 0; col < w; col++) {
                        acc[col] += g * line[col];
                    }
                }
            }
            for (int col = 0; col < w; col++) {
                double ux = m[0][col];
                double uy = m[1][col];
                double vx = m[2][col] - ux * ux;
                double vy = m[3][col] - uy * uy;
                double cv = m[4][col] - ux * uy;
                sum += (2 * ux * uy + c1) * (2 * cv + c2) / ((ux * ux + uy * uy + c1) * (vx + vy + c2));
            }
        }
        return sum / ((double) w * h);
    }

    private static void horizontal(Rows a, Rows b, int[][] rows, int row, int w, float[] x, float[] y,
            float[][][] ring, int slot) {
        luma(a, rows, row, w, x);
        luma(b, rows, row, w, y);
        for (int k = 1; k <= RADIUS; k++) {
            x[RADIUS - k] = x[RADIUS + reflect(-k, w)];
            y[RADIUS - k] = y[RADIUS + reflect(-k, w)];
            x[RADIUS + w - 1 + k] = x[RADIUS + reflect(w - 1 + k, w)];
            y[RADIUS + w - 1 + k] = y[RADIUS + reflect(w - 1 + k, w)];
        }
        for (int col = 0; col < w; col++) {
            float sx = 0;
            float sy = 0;
            float sxx = 0;
            float syy = 0;
            float sxy = 0;
            for (int k = -RADIUS; k <= RADIUS; k++) {
                int i = RADIUS + col + k;
                float g = KERNEL[k + RADIUS];
                float vx = x[i];
                float vy = y[i];
                sx += g * vx;
                sy += g * vy;
                sxx += g * vx * vx;
                syy += g * vy * vy;
                sxy += g * vx * vy;
            }
            ring[0][slot][col] = sx;
            ring[1][slot][col] = sy;
            ring[2][slot][col] = sxx;
            ring[3][slot][col] = syy;
            ring[4][slot][col] = sxy;
        }
    }

    private static void luma(Rows p, int[][] rows, int row, int w, float[] out) {
        if (p.count() >= 3) {
            p.row(0, row, rows[0]);
            p.row(1, row, rows[1]);
            p.row(2, row, rows[2]);
            int[] r = rows[0];
            int[] g = rows[1];
            int[] b = rows[2];
            for (int i = 0; i < w; i++) {
                out[RADIUS + i] = 0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i];
            }
        } else {
            p.row(0, row, rows[0]);
            int[] v = rows[0];
            for (int i = 0; i < w; i++) {
                out[RADIUS + i] = v[i];
            }
        }
    }

    private static int reflect(int i, int n) {
        if (n == 1) {
            return 0;
        }
        int period = 2 * (n - 1);
        int m = Math.floorMod(i, period);
        return m < n ? m : period - m;
    }
}

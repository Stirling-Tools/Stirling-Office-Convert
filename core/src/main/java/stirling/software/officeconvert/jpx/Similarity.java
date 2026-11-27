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

    static double ssim(int[][] a, int[][] b, int w, int h, int depth) {
        float[][][] ring = new float[5][TAPS][w];
        int[] cached = new int[TAPS];
        Arrays.fill(cached, -1);
        float[] x = new float[w];
        float[] y = new float[w];
        double peak = (1 << depth) - 1;
        double c1 = 0.01 * peak * 0.01 * peak;
        double c2 = 0.03 * peak * 0.03 * peak;
        float[] m = new float[5];
        double sum = 0;
        for (int row = 0; row < h; row++) {
            for (int k = -RADIUS; k <= RADIUS; k++) {
                int src = reflect(row + k, h);
                int slot = src % TAPS;
                if (cached[slot] != src) {
                    horizontal(a, b, src, w, x, y, ring, slot);
                    cached[slot] = src;
                }
            }
            for (int col = 0; col < w; col++) {
                Arrays.fill(m, 0);
                for (int k = -RADIUS; k <= RADIUS; k++) {
                    int slot = reflect(row + k, h) % TAPS;
                    float g = KERNEL[k + RADIUS];
                    for (int map = 0; map < 5; map++) {
                        m[map] += g * ring[map][slot][col];
                    }
                }
                double ux = m[0];
                double uy = m[1];
                double vx = m[2] - ux * ux;
                double vy = m[3] - uy * uy;
                double cv = m[4] - ux * uy;
                sum += (2 * ux * uy + c1) * (2 * cv + c2) / ((ux * ux + uy * uy + c1) * (vx + vy + c2));
            }
        }
        return sum / ((double) w * h);
    }

    private static void horizontal(int[][] a, int[][] b, int row, int w, float[] x, float[] y, float[][][] ring,
            int slot) {
        luma(a, row, w, x);
        luma(b, row, w, y);
        for (int col = 0; col < w; col++) {
            float sx = 0;
            float sy = 0;
            float sxx = 0;
            float syy = 0;
            float sxy = 0;
            for (int k = -RADIUS; k <= RADIUS; k++) {
                int i = reflect(col + k, w);
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

    private static void luma(int[][] p, int row, int w, float[] out) {
        int at = row * w;
        if (p.length >= 3) {
            for (int i = 0; i < w; i++) {
                out[i] = 0.299f * p[0][at + i] + 0.587f * p[1][at + i] + 0.114f * p[2][at + i];
            }
        } else {
            for (int i = 0; i < w; i++) {
                out[i] = p[0][at + i];
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

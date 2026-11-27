package stirling.software.officeconvert.jpx;

final class Fdwt {

    private static final float ALPHA = -1.586134342f;
    private static final float BETA = -0.052980118f;
    private static final float GAMMA = 0.882911075f;
    private static final float DELTA = 0.443506852f;
    private static final float INV_K = (float) (1 / 1.230174104914001);
    private static final float HALF_K = (float) (1.230174104914001 / 2);

    private static final int STRIP = 32;

    private Fdwt() {}

    static void reversible(int[] a, int stride, int[][] res) {
        int[] tmp = new int[scratch(res)];
        for (int r = res.length - 1; r >= 1; r--) {
            int w = res[r][2] - res[r][0];
            int h = res[r][3] - res[r][1];
            if (w == 0 || h == 0) {
                continue;
            }
            int px = res[r][0] & 1;
            int py = res[r][1] & 1;
            int lowW = res[r - 1][2] - res[r - 1][0];
            int lowH = res[r - 1][3] - res[r - 1][1];
            for (int x0 = 0; x0 < w; x0 += STRIP) {
                int sw = Math.min(STRIP, w - x0);
                for (int k = 0; k < h; k++) {
                    System.arraycopy(a, k * stride + x0, tmp, k * sw, sw);
                }
                lift53Rows(tmp, sw, h, py);
                for (int i = 0; i < h; i++) {
                    int k = i < lowH ? 2 * i + py : 2 * (i - lowH) + 1 - py;
                    System.arraycopy(tmp, k * sw, a, i * stride + x0, sw);
                }
            }
            for (int y = 0; y < h; y++) {
                int at = y * stride;
                System.arraycopy(a, at, tmp, 0, w);
                lift53(tmp, w, px);
                for (int i = 0; i < lowW; i++) {
                    a[at + i] = tmp[2 * i + px];
                }
                for (int i = 0; i < w - lowW; i++) {
                    a[at + lowW + i] = tmp[2 * i + 1 - px];
                }
            }
        }
    }

    static void irreversible(float[] a, int stride, int[][] res) {
        float[] tmp = new float[scratch(res)];
        for (int r = res.length - 1; r >= 1; r--) {
            int w = res[r][2] - res[r][0];
            int h = res[r][3] - res[r][1];
            if (w == 0 || h == 0) {
                continue;
            }
            int px = res[r][0] & 1;
            int py = res[r][1] & 1;
            int lowW = res[r - 1][2] - res[r - 1][0];
            int lowH = res[r - 1][3] - res[r - 1][1];
            for (int x0 = 0; x0 < w; x0 += STRIP) {
                int sw = Math.min(STRIP, w - x0);
                for (int k = 0; k < h; k++) {
                    System.arraycopy(a, k * stride + x0, tmp, k * sw, sw);
                }
                lift97Rows(tmp, sw, h, py);
                for (int i = 0; i < h; i++) {
                    int k = i < lowH ? 2 * i + py : 2 * (i - lowH) + 1 - py;
                    System.arraycopy(tmp, k * sw, a, i * stride + x0, sw);
                }
            }
            for (int y = 0; y < h; y++) {
                int at = y * stride;
                System.arraycopy(a, at, tmp, 0, w);
                lift97(tmp, w, px);
                for (int i = 0; i < lowW; i++) {
                    a[at + i] = tmp[2 * i + px];
                }
                for (int i = 0; i < w - lowW; i++) {
                    a[at + lowW + i] = tmp[2 * i + 1 - px];
                }
            }
        }
    }

    private static int scratch(int[][] res) {
        long m = 1;
        for (int r = 1; r < res.length; r++) {
            int w = res[r][2] - res[r][0];
            long columns = Math.multiplyExact((long) Math.min(STRIP, w), res[r][3] - res[r][1]);
            m = Math.max(m, Math.max(w, columns));
        }
        return Math.toIntExact(m);
    }

    static void lift53(int[] x, int n, int parity) {
        if (n == 1) {
            if (parity == 1) {
                x[0] *= 2;
            }
            return;
        }
        for (int k = 1 - parity; k < n; k += 2) {
            int l = k > 0 ? x[k - 1] : x[k + 1];
            int r = k + 1 < n ? x[k + 1] : x[k - 1];
            x[k] -= (l + r) >> 1;
        }
        for (int k = parity; k < n; k += 2) {
            int l = k > 0 ? x[k - 1] : x[k + 1];
            int r = k + 1 < n ? x[k + 1] : x[k - 1];
            x[k] += (l + r + 2) >> 2;
        }
    }

    private static void lift53Rows(int[] x, int sw, int n, int parity) {
        if (n == 1) {
            if (parity == 1) {
                for (int j = 0; j < sw; j++) {
                    x[j] *= 2;
                }
            }
            return;
        }
        for (int k = 1 - parity; k < n; k += 2) {
            int l = (k > 0 ? k - 1 : k + 1) * sw;
            int r = (k + 1 < n ? k + 1 : k - 1) * sw;
            int c = k * sw;
            for (int j = 0; j < sw; j++) {
                x[c + j] -= (x[l + j] + x[r + j]) >> 1;
            }
        }
        for (int k = parity; k < n; k += 2) {
            int l = (k > 0 ? k - 1 : k + 1) * sw;
            int r = (k + 1 < n ? k + 1 : k - 1) * sw;
            int c = k * sw;
            for (int j = 0; j < sw; j++) {
                x[c + j] += (x[l + j] + x[r + j] + 2) >> 2;
            }
        }
    }

    static void lift97(float[] x, int n, int parity) {
        if (n == 1) {
            return;
        }
        step97(x, n, 1 - parity, ALPHA);
        step97(x, n, parity, BETA);
        step97(x, n, 1 - parity, GAMMA);
        step97(x, n, parity, DELTA);
        for (int k = parity; k < n; k += 2) {
            x[k] *= INV_K;
        }
        for (int k = 1 - parity; k < n; k += 2) {
            x[k] *= HALF_K;
        }
    }

    private static void step97(float[] x, int n, int first, float c) {
        for (int k = first; k < n; k += 2) {
            float l = k > 0 ? x[k - 1] : x[k + 1];
            float r = k + 1 < n ? x[k + 1] : x[k - 1];
            x[k] += c * (l + r);
        }
    }

    private static void lift97Rows(float[] x, int sw, int n, int parity) {
        if (n == 1) {
            return;
        }
        step97Rows(x, sw, n, 1 - parity, ALPHA);
        step97Rows(x, sw, n, parity, BETA);
        step97Rows(x, sw, n, 1 - parity, GAMMA);
        step97Rows(x, sw, n, parity, DELTA);
        for (int k = 0; k < n; k++) {
            float f = (k & 1) == parity ? INV_K : HALF_K;
            int c = k * sw;
            for (int j = 0; j < sw; j++) {
                x[c + j] *= f;
            }
        }
    }

    private static void step97Rows(float[] x, int sw, int n, int first, float coef) {
        for (int k = first; k < n; k += 2) {
            int l = (k > 0 ? k - 1 : k + 1) * sw;
            int r = (k + 1 < n ? k + 1 : k - 1) * sw;
            int c = k * sw;
            for (int j = 0; j < sw; j++) {
                x[c + j] += coef * (x[l + j] + x[r + j]);
            }
        }
    }
}

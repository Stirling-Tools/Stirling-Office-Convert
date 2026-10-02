package stirling.software.officeconvert.jpx;

final class Idwt {

    private static final float ALPHA = -1.586134342f;
    private static final float BETA = -0.052980118f;
    private static final float GAMMA = 0.882911075f;
    private static final float DELTA = 0.443506852f;
    private static final float K = 1.230174105f;
    private static final float TWO_INV_K = 1.625732422f;

    private static final int STRIP = 32;

    private Idwt() {}

    static void reversible(int[] a, int stride, Resolution[] res, int top) {
        int[] tmp = new int[scratch(res, top)];
        for (int r = 1; r <= top; r++) {
            Resolution cur = res[r];
            Resolution low = res[r - 1];
            int w = cur.width();
            int h = cur.height();
            if (w == 0 || h == 0) {
                continue;
            }
            int px = cur.x0 & 1;
            int py = cur.y0 & 1;
            for (int y = 0; y < h; y++) {
                interleave(a, y * stride, 1, w, low.width(), px, tmp);
                lift53(tmp, w, px);
                System.arraycopy(tmp, 0, a, y * stride, w);
            }
            for (int x0 = 0; x0 < w; x0 += STRIP) {
                int sw = Math.min(STRIP, w - x0);
                interleaveRows(a, stride, x0, sw, h, low.height(), py, tmp);
                lift53Rows(tmp, sw, h, py);
                for (int k = 0; k < h; k++) {
                    System.arraycopy(tmp, k * sw, a, k * stride + x0, sw);
                }
            }
        }
    }

    static void irreversible(float[] a, int stride, Resolution[] res, int top) {
        float[] tmp = new float[scratch(res, top)];
        for (int r = 1; r <= top; r++) {
            Resolution cur = res[r];
            Resolution low = res[r - 1];
            int w = cur.width();
            int h = cur.height();
            if (w == 0 || h == 0) {
                continue;
            }
            int px = cur.x0 & 1;
            int py = cur.y0 & 1;
            for (int y = 0; y < h; y++) {
                interleave(a, y * stride, w, low.width(), px, tmp);
                lift97(tmp, w, px);
                System.arraycopy(tmp, 0, a, y * stride, w);
            }
            for (int x0 = 0; x0 < w; x0 += STRIP) {
                int sw = Math.min(STRIP, w - x0);
                interleaveRows(a, stride, x0, sw, h, low.height(), py, tmp);
                lift97Rows(tmp, sw, h, py);
                for (int k = 0; k < h; k++) {
                    System.arraycopy(tmp, k * sw, a, k * stride + x0, sw);
                }
            }
        }
    }

    private static int scratch(Resolution[] res, int top) {
        long m = 1;
        for (int r = 1; r <= top; r++) {
            int w = res[r].width();
            long columns = Math.multiplyExact((long) Math.min(STRIP, w), res[r].height());
            m = Math.max(m, Math.max(w, columns));
        }
        return Math.toIntExact(m);
    }

    private static void interleave(int[] a, int at, int step, int n, int lows, int parity, int[] out) {
        for (int i = 0; i < lows; i++) {
            out[2 * i + parity] = a[at + i * step];
        }
        int highs = n - lows;
        for (int i = 0; i < highs; i++) {
            out[2 * i + 1 - parity] = a[at + (lows + i) * step];
        }
    }

    private static void interleave(float[] a, int at, int n, int lows, int parity, float[] out) {
        for (int i = 0; i < lows; i++) {
            out[2 * i + parity] = a[at + i];
        }
        int highs = n - lows;
        for (int i = 0; i < highs; i++) {
            out[2 * i + 1 - parity] = a[at + lows + i];
        }
    }

    private static void interleaveRows(int[] a, int stride, int x0, int sw, int n, int lows, int parity, int[] out) {
        for (int i = 0; i < n; i++) {
            int k = i < lows ? 2 * i + parity : 2 * (i - lows) + 1 - parity;
            System.arraycopy(a, i * stride + x0, out, k * sw, sw);
        }
    }

    private static void interleaveRows(float[] a, int stride, int x0, int sw, int n, int lows, int parity,
            float[] out) {
        for (int i = 0; i < n; i++) {
            int k = i < lows ? 2 * i + parity : 2 * (i - lows) + 1 - parity;
            System.arraycopy(a, i * stride + x0, out, k * sw, sw);
        }
    }

    static void lift53(int[] x, int n, int parity) {
        if (n == 1) {
            if (parity == 1) {
                x[0] /= 2;
            }
            return;
        }
        for (int k = parity; k < n; k += 2) {
            int l = k > 0 ? x[k - 1] : x[k + 1];
            int r = k + 1 < n ? x[k + 1] : x[k - 1];
            x[k] -= (l + r + 2) >> 2;
        }
        for (int k = 1 - parity; k < n; k += 2) {
            int l = k > 0 ? x[k - 1] : x[k + 1];
            int r = k + 1 < n ? x[k + 1] : x[k - 1];
            x[k] += (l + r) >> 1;
        }
    }

    private static void lift53Rows(int[] x, int sw, int n, int parity) {
        if (n == 1) {
            if (parity == 1) {
                for (int j = 0; j < sw; j++) {
                    x[j] /= 2;
                }
            }
            return;
        }
        for (int k = parity; k < n; k += 2) {
            int l = (k > 0 ? k - 1 : k + 1) * sw;
            int r = (k + 1 < n ? k + 1 : k - 1) * sw;
            int c = k * sw;
            for (int j = 0; j < sw; j++) {
                x[c + j] -= (x[l + j] + x[r + j] + 2) >> 2;
            }
        }
        for (int k = 1 - parity; k < n; k += 2) {
            int l = (k > 0 ? k - 1 : k + 1) * sw;
            int r = (k + 1 < n ? k + 1 : k - 1) * sw;
            int c = k * sw;
            for (int j = 0; j < sw; j++) {
                x[c + j] += (x[l + j] + x[r + j]) >> 1;
            }
        }
    }

    static void lift97(float[] x, int n, int parity) {
        if (n == 1) {
            return;
        }
        for (int k = parity; k < n; k += 2) {
            x[k] *= K;
        }
        for (int k = 1 - parity; k < n; k += 2) {
            x[k] *= TWO_INV_K;
        }
        step97(x, n, parity, DELTA);
        step97(x, n, 1 - parity, GAMMA);
        step97(x, n, parity, BETA);
        step97(x, n, 1 - parity, ALPHA);
    }

    private static void step97(float[] x, int n, int first, float c) {
        for (int k = first; k < n; k += 2) {
            float l = k > 0 ? x[k - 1] : x[k + 1];
            float r = k + 1 < n ? x[k + 1] : x[k - 1];
            x[k] -= c * (l + r);
        }
    }

    private static void lift97Rows(float[] x, int sw, int n, int parity) {
        if (n == 1) {
            return;
        }
        for (int k = 0; k < n; k++) {
            float f = (k & 1) == parity ? K : TWO_INV_K;
            int c = k * sw;
            for (int j = 0; j < sw; j++) {
                x[c + j] *= f;
            }
        }
        step97Rows(x, sw, n, parity, DELTA);
        step97Rows(x, sw, n, 1 - parity, GAMMA);
        step97Rows(x, sw, n, parity, BETA);
        step97Rows(x, sw, n, 1 - parity, ALPHA);
    }

    private static void step97Rows(float[] x, int sw, int n, int first, float coef) {
        for (int k = first; k < n; k += 2) {
            int l = (k > 0 ? k - 1 : k + 1) * sw;
            int r = (k + 1 < n ? k + 1 : k - 1) * sw;
            int c = k * sw;
            for (int j = 0; j < sw; j++) {
                x[c + j] -= coef * (x[l + j] + x[r + j]);
            }
        }
    }
}

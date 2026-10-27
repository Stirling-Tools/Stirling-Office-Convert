package stirling.software.officeconvert.photosplit;

final class CubicWarp {

    private static final double A = -0.75;

    private CubicWarp() {}

    static ScanImage rotate(ScanImage src, double degrees) {
        int w = src.width;
        int h = src.height;
        double a = degrees * Math.PI / 180;
        double alpha = Math.cos(a);
        double beta = Math.sin(a);
        float cx = w / 2;
        float cy = h / 2;
        double[] m = {alpha, beta, (1 - alpha) * cx - beta * cy, -beta, alpha, beta * cx + (1 - alpha) * cy};
        invert(m);
        int[] out = new int[w * h];
        double[] wx = new double[4];
        double[] wy = new double[4];
        for (int y = 0; y < h; y++) {
            PhotoSplitter.checkpoint();
            for (int x = 0; x < w; x++) {
                double sx = m[0] * x + m[1] * y + m[2];
                double sy = m[3] * x + m[4] * y + m[5];
                out[y * w + x] = sample(src, sx, sy, wx, wy);
            }
        }
        return new ScanImage(w, h, out);
    }

    static int sample(ScanImage src, double sx, double sy, double[] wx, double[] wy) {
        int bx = (int) Math.floor(sx);
        int by = (int) Math.floor(sy);
        weights(sx - bx, wx);
        weights(sy - by, wy);
        int w = src.width;
        int h = src.height;
        int[] in = src.rgb;
        double r = 0;
        double g = 0;
        double b = 0;
        for (int i = 0; i < 4; i++) {
            int row = Math.clamp(by - 1 + i, 0, h - 1) * w;
            double rr = 0;
            double gg = 0;
            double bb = 0;
            for (int j = 0; j < 4; j++) {
                int p = in[row + Math.clamp(bx - 1 + j, 0, w - 1)];
                rr += (p >> 16 & 0xFF) * wx[j];
                gg += (p >> 8 & 0xFF) * wx[j];
                bb += (p & 0xFF) * wx[j];
            }
            r += rr * wy[i];
            g += gg * wy[i];
            b += bb * wy[i];
        }
        return channel(r) << 16 | channel(g) << 8 | channel(b);
    }

    private static int channel(double v) {
        return (int) Math.clamp(Math.rint(v), 0, 255);
    }

    private static void weights(double t, double[] c) {
        c[0] = ((A * (t + 1) - 5 * A) * (t + 1) + 8 * A) * (t + 1) - 4 * A;
        c[1] = ((A + 2) * t - (A + 3)) * t * t + 1;
        c[2] = ((A + 2) * (1 - t) - (A + 3)) * (1 - t) * (1 - t) + 1;
        c[3] = 1 - c[0] - c[1] - c[2];
    }

    private static void invert(double[] m) {
        double d = m[0] * m[4] - m[1] * m[3];
        d = d != 0 ? 1. / d : 0;
        double a11 = m[4] * d;
        double a22 = m[0] * d;
        m[0] = a11;
        m[1] *= -d;
        m[3] *= -d;
        m[4] = a22;
        double b1 = -m[0] * m[2] - m[1] * m[5];
        double b2 = -m[3] * m[2] - m[4] * m[5];
        m[2] = b1;
        m[5] = b2;
    }
}

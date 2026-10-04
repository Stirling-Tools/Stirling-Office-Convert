package stirling.software.officeconvert.jpx;

final class Conversions {

    private Conversions() {}

    static int[] sycc(int[][] rows, int[] depths, int width, boolean invert) {
        int[] y = rows[0];
        int[] cb = rows[1];
        int[] cr = rows[2];
        int max0 = (1 << depths[0]) - 1;
        int off1 = 1 << (depths[1] - 1);
        int off2 = 1 << (depths[2] - 1);
        int[] out = depths.clone();
        out[1] = depths[0];
        out[2] = depths[0];
        for (int x = 0; x < width; x++) {
            double l = y[x];
            double u = cb[x] - off1;
            double v = cr[x] - off2;
            int r = Math.clamp(Math.round(l + 1.402 * v), 0, max0);
            int g = Math.clamp(Math.round(l - 0.344136 * u - 0.714136 * v), 0, max0);
            int b = Math.clamp(Math.round(l + 1.772 * u), 0, max0);
            y[x] = invert ? max0 - r : r;
            cb[x] = invert ? max0 - g : g;
            cr[x] = invert ? max0 - b : b;
        }
        return out;
    }

    static int[] lab(int[][] rows, int[] depths, int width, long[] params) {
        boolean given = params != null && params[0] > 0 && params[2] > 0 && params[4] > 0;
        double sl = (given ? params[0] : 100.0) / ((1 << depths[0]) - 1);
        double sa = (given ? params[2] : 170.0) / ((1 << depths[1]) - 1);
        double sb = (given ? params[4] : 200.0) / ((1 << depths[2]) - 1);
        double ol = given ? params[1] : 0;
        double oa = given ? params[3] : 1 << (depths[1] - 1);
        double ob = given ? params[5]
                : depths[2] >= 3 ? (1 << (depths[2] - 2)) + (1 << (depths[2] - 3)) : 1 << (depths[2] - 1);
        int[] out = depths.clone();
        out[0] = 8;
        out[1] = 8;
        out[2] = 8;
        for (int x = 0; x < width; x++) {
            double l = (rows[0][x] - ol) * sl;
            double a = (rows[1][x] - oa) * sa;
            double b = (rows[2][x] - ob) * sb;
            double fy = (l + 16) / 116;
            double fx = fy + a / 500;
            double fz = fy - b / 200;
            double xx = 0.9642 * inverse(fx);
            double yy = inverse(fy);
            double zz = 0.8249 * inverse(fz);
            double rl = 3.1338561 * xx - 1.6168667 * yy - 0.4906146 * zz;
            double gl = -0.9787684 * xx + 1.9161415 * yy + 0.0334540 * zz;
            double bl = 0.0719453 * xx - 0.2289914 * yy + 1.4052427 * zz;
            rows[0][x] = gamma(rl);
            rows[1][x] = gamma(gl);
            rows[2][x] = gamma(bl);
        }
        return out;
    }

    private static double inverse(double f) {
        double c = f * f * f;
        return c > 216.0 / 24389 ? c : (116 * f - 16) * 27 / 24389;
    }

    private static int gamma(double v) {
        double c = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        return Math.clamp(Math.round(c * 255), 0, 255);
    }
}

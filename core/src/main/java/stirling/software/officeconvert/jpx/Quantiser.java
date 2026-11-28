package stirling.software.officeconvert.jpx;

import java.util.Arrays;

final class Quantiser {

    static final double[] RCT_WEIGHTS = {3, 0.6875, 0.6875};

    static final double[] ICT_WEIGHTS = {3, 0.344136 * 0.344136 + 1.772 * 1.772, 1.402 * 1.402 + 0.714136 * 0.714136};

    private final boolean reversible;

    private final int levels;

    private final int[] depths;

    private final double[] componentWeights;

    private final double[] lowNorms;

    private final double[] highNorms;

    private final int[][] exponents;

    private final int[][] mantissas;

    private final int[][] planes;

    private int guard = 1;

    Quantiser(boolean reversible, int levels, int[] depths, double[] componentWeights, double fineness) {
        this.reversible = reversible;
        this.levels = levels;
        this.depths = depths;
        this.componentWeights = componentWeights;
        lowNorms = new double[levels + 1];
        highNorms = new double[levels + 1];
        for (int d = 0; d <= levels; d++) {
            lowNorms[d] = norm(reversible, d, false);
            highNorms[d] = d == 0 ? 0 : norm(reversible, d, true);
        }
        int bands = 3 * levels + 1;
        exponents = new int[depths.length][bands];
        mantissas = new int[depths.length][bands];
        planes = new int[depths.length][bands];
        for (int c = 0; c < depths.length; c++) {
            for (int i = 0; i < bands; i++) {
                int r = i == 0 ? 0 : (i - 1) / 3 + 1;
                int orient = i == 0 ? Band.LL : (i - 1) % 3 + 1;
                if (reversible) {
                    exponents[c][i] = depths[c] + (orient == Band.LL ? 0 : orient == Band.HH ? 2 : 1);
                } else {
                    double target = fineness * Math.scalb(1.0, depths[c] - 8) / Math.sqrt(energy(r, orient));
                    int e = Math.getExponent(target);
                    int mu = (int) Math.round((target / Math.scalb(1.0, e) - 1) * 2048);
                    if (mu >= 2048) {
                        e++;
                        mu = 0;
                    }
                    int eps = Math.clamp(depths[c] - e, 0, 31);
                    exponents[c][i] = eps;
                    mantissas[c][i] = eps == depths[c] - e ? mu : 0;
                }
            }
        }
    }

    private static int index(int r, int orient) {
        return r == 0 ? 0 : 1 + 3 * (r - 1) + orient - 1;
    }

    double energy(int r, int orient) {
        int d = r == 0 ? levels : levels - r + 1;
        double h = (orient & 1) != 0 ? highNorms[d] : lowNorms[d];
        double v = (orient & 2) != 0 ? highNorms[d] : lowNorms[d];
        return h * h * v * v;
    }

    double step(int c, int r, int orient) {
        if (reversible) {
            return 1;
        }
        int i = index(r, orient);
        return Math.scalb(1.0, depths[c] - exponents[c][i]) * (1 + mantissas[c][i] / 2048.0);
    }

    double weight(int c, int r, int orient) {
        double s = step(c, r, orient);
        return s * s * energy(r, orient) * componentWeights[c];
    }

    synchronized void planes(int c, int r, int orient, int n) {
        int i = index(r, orient);
        planes[c][i] = Math.max(planes[c][i], n);
    }

    int settle() {
        int g = 1;
        for (int c = 0; c < planes.length; c++) {
            for (int i = 0; i < planes[c].length; i++) {
                g = Math.max(g, planes[c][i] - exponents[c][i] + 1);
            }
        }
        guard = Math.max(2, g);
        return guard;
    }

    int guard() {
        return guard;
    }

    int magnitudeBits(int c, int r, int orient) {
        return guard + exponents[c][index(r, orient)] - 1;
    }

    int[] exponents(int c) {
        return exponents[c];
    }

    int[] mantissas(int c) {
        return mantissas[c];
    }

    boolean sameAs(int c, int other) {
        return Arrays.equals(exponents[c], exponents[other])
                && Arrays.equals(mantissas[c], mantissas[other]);
    }

    private static double norm(boolean reversible, int level, boolean high) {
        int n = 16;
        double[] low = new double[n];
        double[] hi = new double[n];
        if (level == 0) {
            return 1;
        }
        if (high) {
            hi[n / 2] = 1;
        } else {
            low[n / 2] = 1;
        }
        double[] x = synthesis(reversible, low, hi);
        for (int d = 1; d < level; d++) {
            x = synthesis(reversible, x, new double[x.length]);
        }
        double e = 0;
        for (double v : x) {
            e += v * v;
        }
        return Math.sqrt(e);
    }

    private static double[] synthesis(boolean reversible, double[] low, double[] high) {
        int n = low.length * 2;
        double[] x = new double[n];
        for (int i = 0; i < low.length; i++) {
            x[2 * i] = low[i];
            x[2 * i + 1] = high[i];
        }
        if (reversible) {
            step(x, 0, -0.25);
            step(x, 1, 0.5);
        } else {
            for (int k = 0; k < n; k++) {
                x[k] *= (k & 1) == 0 ? 1.230174104914001 : 2 / 1.230174104914001;
            }
            step(x, 0, -0.443506852);
            step(x, 1, -0.882911075);
            step(x, 0, 0.052980118);
            step(x, 1, 1.586134342);
        }
        return x;
    }

    private static void step(double[] x, int first, double c) {
        int n = x.length;
        for (int k = first; k < n; k += 2) {
            double l = k > 0 ? x[k - 1] : x[k + 1];
            double r = k + 1 < n ? x[k + 1] : x[k - 1];
            x[k] += c * (l + r);
        }
    }
}

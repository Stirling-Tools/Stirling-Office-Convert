package stirling.software.officeconvert.topdf.docx;

import java.util.List;

final class Trends {

    private Trends() {}

    static double[] fit(String type, int order, List<double[]> points) {
        return switch (type) {
            case "linear" -> poly(points, 1, false, false);
            case "poly" -> poly(points, Math.max(2, Math.min(6, order)), false, false);
            case "log" -> positive(points, true, false) ? poly(points, 1, true, false) : null;
            case "exp" -> positive(points, false, true) ? exp(poly(points, 1, false, true)) : null;
            case "power" -> positive(points, true, true) ? exp(poly(points, 1, true, true)) : null;
            default -> null;
        };
    }

    static double at(String type, double[] fit, double x) {
        return switch (type) {
            case "log" -> x > 0 ? fit[0] + fit[1] * Math.log(x) : Double.NaN;
            case "exp" -> fit[0] * Math.exp(fit[1] * x);
            case "power" -> x > 0 ? fit[0] * Math.pow(x, fit[1]) : Double.NaN;
            default -> {
                double y = 0;
                for (int k = fit.length - 1; k >= 0; k--) {
                    y = y * x + fit[k];
                }
                yield y;
            }
        };
    }

    private static boolean positive(List<double[]> points, boolean x, boolean y) {
        for (double[] p : points) {
            if (x && p[0] <= 0 || y && p[1] <= 0) {
                return false;
            }
        }
        return true;
    }

    private static double[] exp(double[] fit) {
        return fit == null ? null : new double[] {Math.exp(fit[0]), fit[1]};
    }

    private static double[] poly(List<double[]> points, int degree, boolean logX, boolean logY) {
        int n = degree + 1;
        if (points.size() < n) {
            return null;
        }
        double[][] m = new double[n][n + 1];
        for (double[] p : points) {
            double x = logX ? Math.log(p[0]) : p[0];
            double y = logY ? Math.log(p[1]) : p[1];
            double[] powers = new double[2 * n];
            powers[0] = 1;
            for (int k = 1; k < powers.length; k++) {
                powers[k] = powers[k - 1] * x;
            }
            for (int r = 0; r < n; r++) {
                for (int c = 0; c < n; c++) {
                    m[r][c] += powers[r + c];
                }
                m[r][n] += powers[r] * y;
            }
        }
        for (int c = 0; c < n; c++) {
            int pivot = c;
            for (int r = c + 1; r < n; r++) {
                if (Math.abs(m[r][c]) > Math.abs(m[pivot][c])) {
                    pivot = r;
                }
            }
            if (Math.abs(m[pivot][c]) < 1e-300) {
                return null;
            }
            double[] t = m[c];
            m[c] = m[pivot];
            m[pivot] = t;
            for (int r = 0; r < n; r++) {
                if (r == c) {
                    continue;
                }
                double f = m[r][c] / m[c][c];
                for (int k = c; k <= n; k++) {
                    m[r][k] -= f * m[c][k];
                }
            }
        }
        double[] out = new double[n];
        for (int r = 0; r < n; r++) {
            out[r] = m[r][n] / m[r][r];
            if (!Double.isFinite(out[r])) {
                return null;
            }
        }
        return out;
    }
}

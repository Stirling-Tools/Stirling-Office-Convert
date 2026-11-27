package stirling.software.officeconvert.topdf.ofd;

import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;

final class OfdNumbers {

    static final int MAX_LIST = 1_000_000;

    private OfdNumbers() {}

    static double number(String s, double fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            double v = Double.parseDouble(s.trim());
            return Double.isFinite(v) ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static int integer(String s, int fallback) {
        double v = number(s, Double.NaN);
        return Double.isNaN(v) || Math.abs(v) > Integer.MAX_VALUE ? fallback : (int) v;
    }

    static double[] list(String s) {
        if (s == null || s.isBlank()) {
            return new double[0];
        }
        String[] parts = s.trim().split("[\\s,]+", MAX_LIST + 1);
        int n = Math.min(parts.length, MAX_LIST);
        double[] out = new double[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            double v = number(parts[i], Double.NaN);
            if (!Double.isNaN(v)) {
                out[k++] = v;
            }
        }
        return k == n ? out : Arrays.copyOf(out, k);
    }

    static double[] deltas(String s, int limit) {
        if (s == null || s.isBlank()) {
            return new double[0];
        }
        String[] parts = s.trim().split("\\s+", MAX_LIST + 1);
        double[] out = new double[Math.max(0, Math.min(limit, MAX_LIST))];
        int k = 0;
        for (int i = 0; i < parts.length && k < out.length; i++) {
            String p = parts[i];
            if (p.equals("g") && i + 2 < parts.length) {
                int times = integer(parts[i + 1], 0);
                double v = number(parts[i + 2], 0);
                for (int t = 0; t < times && k < out.length; t++) {
                    out[k++] = v;
                }
                i += 2;
            } else {
                double v = number(p, Double.NaN);
                if (!Double.isNaN(v)) {
                    out[k++] = v;
                }
            }
        }
        return Arrays.copyOf(out, k);
    }

    static Rectangle2D.Double box(String s) {
        double[] v = list(s);
        if (v.length < 4) {
            return null;
        }
        return new Rectangle2D.Double(v[0], v[1], v[2], v[3]);
    }

    static AffineTransform ctm(String s) {
        double[] v = list(s);
        if (v.length < 6) {
            return null;
        }
        AffineTransform t = new AffineTransform(v[0], v[1], v[2], v[3], v[4], v[5]);
        return t.getDeterminant() == 0 && v[0] == 0 && v[3] == 0 ? null : t;
    }
}

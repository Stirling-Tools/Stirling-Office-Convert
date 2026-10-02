package stirling.software.officeconvert.topdf.vsdx;

import java.util.Map;

final class Nurbs {

    private static final int SAMPLES = 64;

    private static final int MAX_DEGREE = 10;

    private Nurbs() {}

    static void add(Paths p, Map<String, String> c, double px, double py, double w, double h) {
        double[] e = Paths.numbers(c.get("E"), "NURBS(");
        if (e == null || e.length < 4 || (e.length - 4) % 4 != 0) {
            p.line(px, py);
            return;
        }
        double lastKnot = e[0];
        int degree = (int) e[1];
        boolean relX = e[2] == 0;
        boolean relY = e[3] == 0;
        int inner = (e.length - 4) / 4;
        int n = inner + 2;
        if (degree < 1 || degree > MAX_DEGREE || n <= degree || n > Paths.MAX_POINTS) {
            p.line(px, py);
            return;
        }
        double[] xs = new double[n];
        double[] ys = new double[n];
        double[] ws = new double[n];
        double[] own = new double[n];
        xs[0] = p.x();
        ys[0] = p.y();
        own[0] = Cells.parse(c.get("C"), 0);
        ws[0] = Cells.parse(c.get("D"), 1);
        for (int i = 0; i < inner; i++) {
            xs[i + 1] = relX ? e[4 + 4 * i] * w : e[4 + 4 * i];
            ys[i + 1] = relY ? e[5 + 4 * i] * h : e[5 + 4 * i];
            own[i + 1] = e[6 + 4 * i];
            ws[i + 1] = e[7 + 4 * i];
        }
        xs[n - 1] = px;
        ys[n - 1] = py;
        own[n - 1] = Cells.parse(c.get("A"), lastKnot);
        ws[n - 1] = Cells.parse(c.get("B"), 1);
        double[] knots = new double[n + degree + 1];
        for (int i = 0; i < degree; i++) {
            knots[i] = own[0];
        }
        System.arraycopy(own, 0, knots, degree, n);
        knots[n + degree] = lastKnot;
        for (int i = 1; i < knots.length; i++) {
            if (!(knots[i] >= knots[i - 1])) {
                p.line(px, py);
                return;
            }
        }
        double t0 = knots[degree];
        double t1 = knots[n];
        if (!(t1 > t0)) {
            p.line(px, py);
            return;
        }
        for (int s = 1; s <= SAMPLES; s++) {
            double t = s == SAMPLES ? t1 : t0 + (t1 - t0) * s / SAMPLES;
            double[] pt = point(t, degree, knots, xs, ys, ws);
            if (pt == null) {
                p.line(px, py);
                return;
            }
            p.line(pt[0], pt[1]);
        }
    }

    private static double[] point(double t, int degree, double[] knots, double[] xs, double[] ys, double[] ws) {
        int n = xs.length;
        int k = degree;
        while (k < n - 1 && t >= knots[k + 1]) {
            k++;
        }
        double[] dx = new double[degree + 1];
        double[] dy = new double[degree + 1];
        double[] dw = new double[degree + 1];
        for (int j = 0; j <= degree; j++) {
            int i = k - degree + j;
            if (i < 0 || i >= n) {
                return null;
            }
            dw[j] = ws[i];
            dx[j] = xs[i] * ws[i];
            dy[j] = ys[i] * ws[i];
        }
        for (int r = 1; r <= degree; r++) {
            for (int j = degree; j >= r; j--) {
                int i = k - degree + j;
                double den = knots[i + degree - r + 1] - knots[i];
                double a = den == 0 ? 0 : (t - knots[i]) / den;
                dx[j] = (1 - a) * dx[j - 1] + a * dx[j];
                dy[j] = (1 - a) * dy[j - 1] + a * dy[j];
                dw[j] = (1 - a) * dw[j - 1] + a * dw[j];
            }
        }
        if (dw[degree] == 0 || !Double.isFinite(dw[degree])) {
            return null;
        }
        return new double[] {dx[degree] / dw[degree], dy[degree] / dw[degree]};
    }
}

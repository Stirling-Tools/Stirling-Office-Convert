package stirling.software.officeconvert.topdf.vsdx;

import java.util.ArrayList;
import java.util.List;

final class Rounding {

    private static final double KAPPA = 0.5523;

    private Rounding() {}

    static void apply(Paths.Path path, double radius) {
        List<Paths.Segment> s = path.segments;
        if (!(radius > 0) || s.size() < 3 || s.get(0).op() != 'M') {
            return;
        }
        for (Paths.Segment seg : s) {
            if (seg.op() == 'C') {
                return;
            }
        }
        boolean closed = path.closed();
        int n = s.size();
        double[][] pts = new double[n][];
        for (int i = 0; i < n; i++) {
            pts[i] = s.get(i).pts();
        }
        int corners = closed ? n - 1 : n;
        List<Paths.Segment> out = new ArrayList<>();
        double[] start = null;
        for (int i = 0; i < corners; i++) {
            double[] corner = pts[i];
            boolean ends = !closed && (i == 0 || i == n - 1);
            double[] prev = ends ? null : pts[(i - 1 + (closed ? n - 1 : n)) % (closed ? n - 1 : n)];
            double[] next = ends ? null : pts[(i + 1) % (closed ? n - 1 : n)];
            if (ends || prev == null || next == null) {
                out.add(new Paths.Segment(out.isEmpty() ? 'M' : 'L', new double[] {corner[0], corner[1]}));
                continue;
            }
            double lin = Math.hypot(corner[0] - prev[0], corner[1] - prev[1]);
            double lout = Math.hypot(next[0] - corner[0], next[1] - corner[1]);
            double r = Math.min(radius, Math.min(lin, lout) / 2);
            if (!(r > 1e-9)) {
                out.add(new Paths.Segment(out.isEmpty() ? 'M' : 'L', new double[] {corner[0], corner[1]}));
                continue;
            }
            double[] a = toward(corner, prev, r / lin);
            double[] b = toward(corner, next, r / lout);
            out.add(new Paths.Segment(out.isEmpty() ? 'M' : 'L', a));
            if (start == null) {
                start = a;
            }
            out.add(new Paths.Segment('C', new double[] {a[0] + (corner[0] - a[0]) * KAPPA,
                a[1] + (corner[1] - a[1]) * KAPPA, b[0] + (corner[0] - b[0]) * KAPPA, b[1] + (corner[1] - b[1]) * KAPPA,
                b[0], b[1]}));
        }
        if (closed && !out.isEmpty()) {
            double[] first = out.get(0).pts();
            out.add(new Paths.Segment('L', new double[] {first[0], first[1]}));
        }
        s.clear();
        s.addAll(out);
    }

    private static double[] toward(double[] from, double[] to, double t) {
        return new double[] {from[0] + (to[0] - from[0]) * t, from[1] + (to[1] - from[1]) * t};
    }
}

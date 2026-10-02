package stirling.software.officeconvert.topdf.vsdx;

import java.util.ArrayList;
import java.util.List;

final class Arrows {

    private static final double[] SIZES = {0.5, 0.75, 1, 1.5, 2, 3, 4.5};

    private static final double LENGTH = 0.11 * PageWriter.EMU;

    private Arrows() {}

    static void draw(Slide slide, List<Paths.Path> paths, Affine m, Look.Stroke stroke) {
        if (stroke.paint() == null || stroke.begin() <= 0 && stroke.end() <= 0) {
            return;
        }
        Paths.Path open = null;
        for (Paths.Path p : paths) {
            if (!p.noLine && !p.closed() && p.segments.size() >= 2) {
                open = p;
                break;
            }
        }
        if (open == null) {
            return;
        }
        List<double[]> pts = new ArrayList<>();
        for (Paths.Segment s : open.segments) {
            double[] v = s.pts();
            for (int i = 0; i < v.length; i += 2) {
                pts.add(new double[] {m.x(v[i], v[i + 1]), m.y(v[i], v[i + 1])});
            }
        }
        if (stroke.begin() > 0) {
            head(slide, pts, false, stroke.begin(), stroke.beginSize(), stroke);
        }
        if (stroke.end() > 0) {
            head(slide, pts, true, stroke.end(), stroke.endSize(), stroke);
        }
    }

    private static void head(Slide slide, List<double[]> pts, boolean atEnd, int kind, double size, Look.Stroke s) {
        int n = pts.size();
        double[] tip = atEnd ? pts.get(n - 1) : pts.get(0);
        double[] from = null;
        for (int k = 1; k < n && from == null; k++) {
            double[] q = atEnd ? pts.get(n - 1 - k) : pts.get(k);
            if (Math.hypot(q[0] - tip[0], q[1] - tip[1]) > 1) {
                from = q;
            }
        }
        if (from == null) {
            return;
        }
        double dx = tip[0] - from[0];
        double dy = tip[1] - from[1];
        double len = Math.hypot(dx, dy);
        double ux = dx / len;
        double uy = dy / len;
        double scale = SIZES[(int) Math.max(0, Math.min(SIZES.length - 1, size))];
        double l = Math.max(LENGTH * scale, s.width() * 4 * scale);
        double w = l * 0.4;
        double bx = tip[0] - ux * l;
        double by = tip[1] - uy * l;
        double[] left = {bx - uy * w, by + ux * w};
        double[] right = {bx + uy * w, by - ux * w};
        switch (kind) {
            case 1, 6, 9, 12, 14, 19 -> slide.polygon(new double[][] {left, tip, right}, false, s.paint(), s.width());
            case 10, 11, 20, 21, 22 -> slide.circle(tip[0] - ux * l / 2, tip[1] - uy * l / 2, l / 2, s.paint());
            case 18, 23, 24 -> slide.polygon(new double[][] {tip, {tip[0] - ux * l / 2 - uy * w, tip[1] - uy * l / 2
                + ux * w}, {tip[0] - ux * l, tip[1] - uy * l}, {tip[0] - ux * l / 2 + uy * w, tip[1] - uy * l / 2
                    - ux * w}}, true, s.paint(), s.width());
            default -> slide.polygon(new double[][] {left, tip, right}, true, s.paint(), s.width());
        }
    }
}

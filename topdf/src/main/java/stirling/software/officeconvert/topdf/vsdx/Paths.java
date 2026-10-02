package stirling.software.officeconvert.topdf.vsdx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class Paths {

    static final int MAX_POINTS = 100_000;

    record Segment(char op, double[] pts) {}

    record Built(List<Path> paths, int points, boolean cut) {}

    static final class Path {
        final List<Segment> segments = new ArrayList<>();

        boolean noFill;

        boolean noLine;

        boolean closed() {
            if (segments.size() < 2 || segments.get(0).op() != 'M') {
                return false;
            }
            double[] first = segments.get(0).pts();
            double[] last = segments.get(segments.size() - 1).pts();
            double x = last[last.length - 2];
            double y = last[last.length - 1];
            double tol = 1e-6 * Math.max(1, Math.abs(first[0]) + Math.abs(first[1]));
            return Math.abs(x - first[0]) <= tol && Math.abs(y - first[1]) <= tol;
        }
    }

    private final List<Path> out = new ArrayList<>();

    private Path current;

    private double x;

    private double y;

    private int points;

    private boolean cut;

    private final int room;

    private Paths(int room) {
        this.room = room;
    }

    static Built build(Cells.Geometry g, double w, double h, int room) {
        Paths p = new Paths(room);
        boolean noFill = "1".equals(g.cells().get("NoFill"));
        boolean noLine = "1".equals(g.cells().get("NoLine"));
        if ("1".equals(g.cells().get("NoShow"))) {
            return new Built(List.of(), 0, false);
        }
        for (Sheet.Row r : g.rows()) {
            if (p.points >= room) {
                p.cut = true;
                break;
            }
            p.row(r, w, h);
        }
        for (Path path : p.out) {
            path.noFill = noFill || !path.closed();
            path.noLine = noLine;
        }
        p.out.removeIf(path -> path.segments.size() < 2);
        return new Built(p.out, p.points, p.cut);
    }

    private static double v(Map<String, String> c, String n) {
        return Cells.parse(c.get(n), 0);
    }

    private void row(Sheet.Row r, double w, double h) {
        Map<String, String> c = r.cells();
        double rx = v(c, "X");
        double ry = v(c, "Y");
        switch (r.type()) {
            case "MoveTo" -> move(rx, ry);
            case "RelMoveTo" -> move(rx * w, ry * h);
            case "LineTo" -> line(rx, ry);
            case "RelLineTo" -> line(rx * w, ry * h);
            case "ArcTo" -> arcTo(rx, ry, v(c, "A"));
            case "EllipticalArcTo" -> elliptical(rx, ry, v(c, "A"), v(c, "B"), v(c, "C"), Cells.parse(c.get("D"), 1));
            case "RelEllipticalArcTo" -> elliptical(rx * w, ry * h, v(c, "A") * w, v(c, "B") * h, v(c, "C"),
                    Cells.parse(c.get("D"), 1));
            case "RelCubBezTo" -> cubic(v(c, "A") * w, v(c, "B") * h, v(c, "C") * w, v(c, "D") * h, rx * w, ry * h);
            case "RelQuadBezTo" -> quad(v(c, "A") * w, v(c, "B") * h, rx * w, ry * h);
            case "PolylineTo" -> polyline(c.get("A"), rx, ry, w, h);
            case "NURBSTo" -> Nurbs.add(this, c, rx, ry, w, h);
            case "SplineStart", "SplineKnot" -> line(rx, ry);
            case "Ellipse" -> ellipse(rx, ry, v(c, "A"), v(c, "B"), v(c, "C"), v(c, "D"));
            default -> {
            }
        }
    }

    private void ensure() {
        if (current == null) {
            move(x, y);
        }
    }

    void move(double px, double py) {
        if (points >= room) {
            cut = true;
            return;
        }
        current = new Path();
        out.add(current);
        current.segments.add(new Segment('M', new double[] {px, py}));
        x = px;
        y = py;
        points++;
    }

    void line(double px, double py) {
        ensure();
        if (points >= room) {
            cut = true;
            return;
        }
        current.segments.add(new Segment('L', new double[] {px, py}));
        x = px;
        y = py;
        points++;
    }

    void cubic(double x1, double y1, double x2, double y2, double px, double py) {
        ensure();
        if (points + 3 > room) {
            cut = true;
            return;
        }
        current.segments.add(new Segment('C', new double[] {x1, y1, x2, y2, px, py}));
        x = px;
        y = py;
        points += 3;
    }

    double x() {
        return x;
    }

    double y() {
        return y;
    }

    private void quad(double cx, double cy, double px, double py) {
        cubic(x + 2.0 / 3 * (cx - x), y + 2.0 / 3 * (cy - y), px + 2.0 / 3 * (cx - px), py + 2.0 / 3 * (cy - py), px,
                py);
    }

    private void arcTo(double px, double py, double bow) {
        double dx = px - x;
        double dy = py - y;
        double chord = Math.hypot(dx, dy);
        if (Math.abs(bow) < 1e-9 || chord < 1e-12) {
            line(px, py);
            return;
        }
        double mx = (x + px) / 2 + bow * dy / chord;
        double my = (y + py) / 2 - bow * dx / chord;
        circleThrough(mx, my, px, py);
    }

    private void elliptical(double px, double py, double ax, double ay, double angle, double ratio) {
        if (!(Math.abs(ratio) > 1e-9)) {
            line(px, py);
            return;
        }
        double cos = Math.cos(-angle);
        double sin = Math.sin(-angle);
        double[] s = toCircle(x, y, cos, sin, ratio);
        double[] m = toCircle(ax, ay, cos, sin, ratio);
        double[] e = toCircle(px, py, cos, sin, ratio);
        List<double[]> beziers = circleArc(s[0], s[1], m[0], m[1], e[0], e[1]);
        if (beziers == null) {
            line(px, py);
            return;
        }
        double c2 = Math.cos(angle);
        double s2 = Math.sin(angle);
        for (double[] b : beziers) {
            double[] o = new double[6];
            for (int i = 0; i < 6; i += 2) {
                double ux = b[i] * ratio;
                double uy = b[i + 1];
                o[i] = ux * c2 - uy * s2;
                o[i + 1] = ux * s2 + uy * c2;
            }
            cubic(o[0], o[1], o[2], o[3], o[4], o[5]);
        }
        x = px;
        y = py;
    }

    private static double[] toCircle(double px, double py, double cos, double sin, double ratio) {
        double rx = px * cos - py * sin;
        double ry = px * sin + py * cos;
        return new double[] {rx / ratio, ry};
    }

    private void circleThrough(double mx, double my, double px, double py) {
        List<double[]> beziers = circleArc(x, y, mx, my, px, py);
        if (beziers == null) {
            line(px, py);
            return;
        }
        for (double[] b : beziers) {
            cubic(b[0], b[1], b[2], b[3], b[4], b[5]);
        }
        x = px;
        y = py;
    }

    static List<double[]> circleArc(double x1, double y1, double x2, double y2, double x3, double y3) {
        double d = 2 * (x1 * (y2 - y3) + x2 * (y3 - y1) + x3 * (y1 - y2));
        if (Math.abs(d) < 1e-12) {
            return null;
        }
        double s1 = x1 * x1 + y1 * y1;
        double s2 = x2 * x2 + y2 * y2;
        double s3 = x3 * x3 + y3 * y3;
        double cx = (s1 * (y2 - y3) + s2 * (y3 - y1) + s3 * (y1 - y2)) / d;
        double cy = (s1 * (x3 - x2) + s2 * (x1 - x3) + s3 * (x2 - x1)) / d;
        double r = Math.hypot(x1 - cx, y1 - cy);
        if (!(r > 0) || !Double.isFinite(r) || r > 1e7) {
            return null;
        }
        double a1 = Math.atan2(y1 - cy, x1 - cx);
        double a2 = Math.atan2(y2 - cy, x2 - cx);
        double a3 = Math.atan2(y3 - cy, x3 - cx);
        double ccw = norm(a3 - a1);
        boolean through = norm(a2 - a1) <= ccw;
        double sweep = through ? ccw : ccw - 2 * Math.PI;
        return arc(cx, cy, r, a1, sweep);
    }

    private static double norm(double a) {
        double t = a % (2 * Math.PI);
        return t < 0 ? t + 2 * Math.PI : t;
    }

    static List<double[]> arc(double cx, double cy, double r, double start, double sweep) {
        int n = Math.max(1, (int) Math.ceil(Math.abs(sweep) / (Math.PI / 2) - 1e-9));
        double step = sweep / n;
        double k = 4.0 / 3 * Math.tan(step / 4);
        List<double[]> out = new ArrayList<>(n);
        double a = start;
        for (int i = 0; i < n; i++) {
            double b = a + step;
            double ca = Math.cos(a);
            double sa = Math.sin(a);
            double cb = Math.cos(b);
            double sb = Math.sin(b);
            out.add(new double[] {cx + r * (ca - k * sa), cy + r * (sa + k * ca), cx + r * (cb + k * sb),
                cy + r * (sb - k * cb), cx + r * cb, cy + r * sb});
            a = b;
        }
        return out;
    }

    private void ellipse(double cx, double cy, double ax, double ay, double bx, double by) {
        double ux = ax - cx;
        double uy = ay - cy;
        double vx = bx - cx;
        double vy = by - cy;
        move(cx + ux, cy + uy);
        for (double[] b : arc(0, 0, 1, 0, 2 * Math.PI)) {
            double[] o = new double[6];
            for (int i = 0; i < 6; i += 2) {
                o[i] = cx + ux * b[i] + vx * b[i + 1];
                o[i + 1] = cy + uy * b[i] + vy * b[i + 1];
            }
            cubic(o[0], o[1], o[2], o[3], o[4], o[5]);
        }
        current = null;
    }

    private void polyline(String formula, double px, double py, double w, double h) {
        double[] nums = numbers(formula, "POLYLINE(");
        if (nums != null && nums.length >= 2) {
            boolean relX = nums[0] == 0;
            boolean relY = nums[1] == 0;
            for (int i = 2; i + 1 < nums.length && points < room; i += 2) {
                line(relX ? nums[i] * w : nums[i], relY ? nums[i + 1] * h : nums[i + 1]);
            }
        }
        line(px, py);
    }

    static int points(List<Path> paths) {
        int n = 0;
        for (Path p : paths) {
            for (Segment s : p.segments) {
                n += s.pts().length / 2;
            }
        }
        return n;
    }

    static double[] numbers(String formula, String head) {
        if (formula == null) {
            return null;
        }
        String f = formula.trim();
        if (!f.toUpperCase(Locale.ROOT).startsWith(head)) {
            return null;
        }
        int end = f.lastIndexOf(')');
        String body = f.substring(head.length(), end < 0 ? f.length() : end);
        int count = 1;
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) == ',' && ++count > 4 * MAX_POINTS) {
                return null;
            }
        }
        String[] parts = body.split(",");
        if (parts.length > 4 * MAX_POINTS) {
            return null;
        }
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Cells.parse(parts[i], Double.NaN);
            if (Double.isNaN(out[i])) {
                return null;
            }
        }
        return out;
    }
}

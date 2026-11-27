package stirling.software.officeconvert.topdf.ofd;

import java.awt.geom.Path2D;
import java.awt.geom.Point2D;

final class OfdPath {

    static final int MAX_COMMANDS = 200_000;

    private final String d;

    private int i;

    private OfdPath(String d) {
        this.d = d;
    }

    static Path2D.Double parse(String data) {
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        if (data == null) {
            return path;
        }
        new OfdPath(data).into(path);
        return path;
    }

    private void into(Path2D.Double path) {
        double x = 0;
        double y = 0;
        double sx = 0;
        double sy = 0;
        boolean open = false;
        char cmd = 0;
        int commands = 0;
        while (commands++ < MAX_COMMANDS) {
            skip();
            if (i >= d.length()) {
                return;
            }
            char c = d.charAt(i);
            if (Character.isLetter(c)) {
                cmd = c;
                i++;
            } else if (cmd == 0 || cmd == 'C') {
                i++;
                continue;
            }
            double[] p;
            switch (cmd) {
                case 'S', 'M' -> {
                    if ((p = numbers(2)) == null) {
                        return;
                    }
                    x = sx = p[0];
                    y = sy = p[1];
                    path.moveTo(x, y);
                    open = true;
                }
                case 'L' -> {
                    if ((p = numbers(2)) == null) {
                        return;
                    }
                    start(path, open, x, y);
                    open = true;
                    x = p[0];
                    y = p[1];
                    path.lineTo(x, y);
                }
                case 'Q' -> {
                    if ((p = numbers(4)) == null) {
                        return;
                    }
                    start(path, open, x, y);
                    open = true;
                    path.quadTo(p[0], p[1], p[2], p[3]);
                    x = p[2];
                    y = p[3];
                }
                case 'B' -> {
                    if ((p = numbers(6)) == null) {
                        return;
                    }
                    start(path, open, x, y);
                    open = true;
                    path.curveTo(p[0], p[1], p[2], p[3], p[4], p[5]);
                    x = p[4];
                    y = p[5];
                }
                case 'A' -> {
                    if ((p = numbers(7)) == null) {
                        return;
                    }
                    start(path, open, x, y);
                    open = true;
                    arc(path, x, y, p[0], p[1], p[2], p[3] != 0, p[4] != 0, p[5], p[6]);
                    x = p[5];
                    y = p[6];
                }
                case 'C' -> {
                    if (open) {
                        path.closePath();
                        Point2D at = path.getCurrentPoint();
                        x = at == null ? sx : at.getX();
                        y = at == null ? sy : at.getY();
                    }
                    open = false;
                }
                default -> {
                    return;
                }
            }
        }
    }

    private static void start(Path2D.Double path, boolean open, double x, double y) {
        if (!open || path.getCurrentPoint() == null) {
            path.moveTo(x, y);
        }
    }

    private double[] numbers(int n) {
        double[] out = new double[n];
        for (int k = 0; k < n; k++) {
            skip();
            int from = i;
            while (i < d.length()) {
                char c = d.charAt(i);
                if (c >= '0' && c <= '9' || c == '.' || c == '-' || c == '+'
                        || (c == 'e' || c == 'E') && i > from && i + 1 < d.length()
                        && (Character.isDigit(d.charAt(i + 1)) || d.charAt(i + 1) == '-' || d.charAt(i + 1) == '+')) {
                    i++;
                } else {
                    break;
                }
            }
            if (from == i) {
                return null;
            }
            try {
                double v = Double.parseDouble(d.substring(from, i));
                if (!Double.isFinite(v)) {
                    return null;
                }
                out[k] = v;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    private void skip() {
        while (i < d.length() && (Character.isWhitespace(d.charAt(i)) || d.charAt(i) == ',')) {
            i++;
        }
    }

    static void arc(Path2D.Double path, double x1, double y1, double rx, double ry, double angle, boolean large,
            boolean sweep, double x2, double y2) {
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        if (rx == 0 || ry == 0 || x1 == x2 && y1 == y2) {
            path.lineTo(x2, y2);
            return;
        }
        double phi = Math.toRadians(angle % 360);
        double cos = Math.cos(phi);
        double sin = Math.sin(phi);
        double dx = (x1 - x2) / 2;
        double dy = (y1 - y2) / 2;
        double x1p = cos * dx + sin * dy;
        double y1p = -sin * dx + cos * dy;
        double lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry);
        if (lambda > 1) {
            double s = Math.sqrt(lambda);
            rx *= s;
            ry *= s;
        }
        double rxs = rx * rx;
        double rys = ry * ry;
        double den = rxs * y1p * y1p + rys * x1p * x1p;
        double coef = den == 0 ? 0 : (large == sweep ? -1 : 1)
                * Math.sqrt(Math.max(0, (rxs * rys - den) / den));
        double cxp = coef * rx * y1p / ry;
        double cyp = -coef * ry * x1p / rx;
        double cx = cos * cxp - sin * cyp + (x1 + x2) / 2;
        double cy = sin * cxp + cos * cyp + (y1 + y2) / 2;
        double start = Math.atan2((y1p - cyp) / ry, (x1p - cxp) / rx);
        double end = Math.atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx);
        double delta = end - start;
        if (sweep && delta < 0) {
            delta += 2 * Math.PI;
        } else if (!sweep && delta > 0) {
            delta -= 2 * Math.PI;
        }
        int segments = Math.max(1, (int) Math.ceil(Math.abs(delta) / (Math.PI / 2) - 1e-9));
        double step = delta / segments;
        double k = 4.0 / 3 * Math.tan(step / 4);
        for (int s = 0; s < segments; s++) {
            double t1 = start + s * step;
            double t2 = t1 + step;
            double c1x = Math.cos(t1) - k * Math.sin(t1);
            double c1y = Math.sin(t1) + k * Math.cos(t1);
            double c2x = Math.cos(t2) + k * Math.sin(t2);
            double c2y = Math.sin(t2) - k * Math.cos(t2);
            path.curveTo(cx + rx * c1x * cos - ry * c1y * sin, cy + rx * c1x * sin + ry * c1y * cos,
                    cx + rx * c2x * cos - ry * c2y * sin, cy + rx * c2x * sin + ry * c2y * cos,
                    cx + rx * Math.cos(t2) * cos - ry * Math.sin(t2) * sin,
                    cy + rx * Math.cos(t2) * sin + ry * Math.sin(t2) * cos);
        }
    }
}

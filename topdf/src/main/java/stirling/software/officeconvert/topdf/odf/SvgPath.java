package stirling.software.officeconvert.topdf.odf;

import java.util.ArrayList;
import java.util.List;

final class SvgPath {

    private SvgPath() {}

    static void parse(String d, Shapes.Path p) {
        List<String> t = tokens(d);
        int i = 0;
        char cmd = 'M';
        double lastCx = 0;
        double lastCy = 0;
        char prev = 0;
        while (i < t.size() && !p.tooLong()) {
            String tok = t.get(i);
            if (Character.isLetter(tok.charAt(0))) {
                cmd = tok.charAt(0);
                i++;
                if (cmd == 'Z' || cmd == 'z') {
                    p.close();
                    prev = cmd;
                }
                continue;
            }
            boolean rel = Character.isLowerCase(cmd);
            double ox = rel ? p.cx : 0;
            double oy = rel ? p.cy : 0;
            int need = switch (Character.toUpperCase(cmd)) {
                case 'M', 'L', 'T' -> 2;
                case 'H', 'V' -> 1;
                case 'C' -> 6;
                case 'S', 'Q' -> 4;
                case 'A' -> 7;
                default -> 0;
            };
            if (need == 0 || i + need > t.size()) {
                return;
            }
            double[] a = new double[need];
            for (int j = 0; j < need; j++) {
                a[j] = num(t.get(i + j));
            }
            i += need;
            switch (Character.toUpperCase(cmd)) {
                case 'M' -> {
                    p.move(ox + a[0], oy + a[1]);
                    cmd = rel ? 'l' : 'L';
                }
                case 'L' -> p.line(ox + a[0], oy + a[1]);
                case 'H' -> p.line(ox + a[0], p.cy);
                case 'V' -> p.line(p.cx, oy + a[0]);
                case 'C' -> {
                    p.cubic(ox + a[0], oy + a[1], ox + a[2], oy + a[3], ox + a[4], oy + a[5]);
                    lastCx = ox + a[2];
                    lastCy = oy + a[3];
                }
                case 'S' -> {
                    boolean smooth = "CcSs".indexOf(prev) >= 0;
                    double c1x = smooth ? 2 * p.cx - lastCx : p.cx;
                    double c1y = smooth ? 2 * p.cy - lastCy : p.cy;
                    p.cubic(c1x, c1y, ox + a[0], oy + a[1], ox + a[2], oy + a[3]);
                    lastCx = ox + a[0];
                    lastCy = oy + a[1];
                }
                case 'Q' -> {
                    p.quad(ox + a[0], oy + a[1], ox + a[2], oy + a[3]);
                    lastCx = ox + a[0];
                    lastCy = oy + a[1];
                }
                case 'T' -> {
                    boolean smooth = "QqTt".indexOf(prev) >= 0;
                    double qx = smooth ? 2 * p.cx - lastCx : p.cx;
                    double qy = smooth ? 2 * p.cy - lastCy : p.cy;
                    p.quad(qx, qy, ox + a[0], oy + a[1]);
                    lastCx = qx;
                    lastCy = qy;
                }
                case 'A' -> arc(p, a[0], a[1], a[2], a[3] != 0, a[4] != 0, ox + a[5], oy + a[6]);
                default -> {
                }
            }
            prev = cmd;
        }
    }

    private static void arc(Shapes.Path p, double rx, double ry, double phiDeg, boolean large, boolean sweep, double x,
            double y) {
        double x1 = p.cx;
        double y1 = p.cy;
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        if (rx == 0 || ry == 0 || (x1 == x && y1 == y)) {
            p.line(x, y);
            return;
        }
        double phi = Math.toRadians(phiDeg);
        double cos = Math.cos(phi);
        double sin = Math.sin(phi);
        double dx = (x1 - x) / 2;
        double dy = (y1 - y) / 2;
        double x1p = cos * dx + sin * dy;
        double y1p = -sin * dx + cos * dy;
        double lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry);
        if (lambda > 1) {
            rx *= Math.sqrt(lambda);
            ry *= Math.sqrt(lambda);
        }
        double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double coef = (large == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
        double cxp = coef * rx * y1p / ry;
        double cyp = -coef * ry * x1p / rx;
        double cx = cos * cxp - sin * cyp + (x1 + x) / 2;
        double cy = sin * cxp + cos * cyp + (y1 + y) / 2;
        double t1 = Math.atan2((y1p - cyp) / ry, (x1p - cxp) / rx);
        double t2 = Math.atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx);
        double dt = t2 - t1;
        if (sweep && dt < 0) {
            dt += 2 * Math.PI;
        } else if (!sweep && dt > 0) {
            dt -= 2 * Math.PI;
        }
        if (Math.abs(phi) < 1e-9) {
            p.ellipse(cx, cy, rx, ry, t1, t1 + dt, false);
            return;
        }
        int n = Math.max(1, (int) Math.ceil(Math.abs(dt) / (Math.PI / 2)));
        double step = dt / n;
        double a = t1;
        for (int i = 0; i < n; i++) {
            double b = a + step;
            double alpha = 4.0 / 3 * Math.tan((b - a) / 4);
            double[] c1 = rot(cx, cy, rx * (Math.cos(a) - alpha * Math.sin(a)), ry * (Math.sin(a) + alpha * Math.cos(a)),
                    cos, sin);
            double[] c2 = rot(cx, cy, rx * (Math.cos(b) + alpha * Math.sin(b)), ry * (Math.sin(b) - alpha * Math.cos(b)),
                    cos, sin);
            double[] e = rot(cx, cy, rx * Math.cos(b), ry * Math.sin(b), cos, sin);
            p.cubic(c1[0], c1[1], c2[0], c2[1], e[0], e[1]);
            a = b;
        }
    }

    private static double[] rot(double cx, double cy, double x, double y, double cos, double sin) {
        return new double[] {cx + cos * x - sin * y, cy + sin * x + cos * y};
    }

    private static double num(String s) {
        try {
            double d = Double.parseDouble(s);
            return Double.isFinite(d) ? d : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> tokens(String d) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = d.length();
        while (i < n && out.size() < Shapes.MAX_TOKENS) {
            char c = d.charAt(i);
            if (Character.isWhitespace(c) || c == ',') {
                i++;
            } else if (Character.isLetter(c) && c != 'e' && c != 'E') {
                out.add(String.valueOf(c));
                i++;
            } else {
                int j = i;
                if (d.charAt(j) == '-' || d.charAt(j) == '+') {
                    j++;
                }
                boolean dot = false;
                while (j < n) {
                    char k = d.charAt(j);
                    if (Character.isDigit(k)) {
                        j++;
                    } else if (k == '.' && !dot) {
                        dot = true;
                        j++;
                    } else if ((k == 'e' || k == 'E') && j + 1 < n) {
                        j++;
                        if (d.charAt(j) == '-' || d.charAt(j) == '+') {
                            j++;
                        }
                    } else {
                        break;
                    }
                }
                if (j == i) {
                    i++;
                    continue;
                }
                out.add(d.substring(i, j));
                i = j;
            }
        }
        return out;
    }
}

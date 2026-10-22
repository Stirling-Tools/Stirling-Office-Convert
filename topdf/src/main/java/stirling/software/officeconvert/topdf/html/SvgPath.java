package stirling.software.officeconvert.topdf.html;

import java.awt.geom.Path2D;

/** SVG path data (M L H V C S Q T A Z, absolute and relative) as a Java 2D path. Bad data ends the path where it
 * goes wrong, as browsers do. */
final class SvgPath {

    private static final int MAX_COMMANDS = 100_000;

    private final String d;
    private int i;

    private SvgPath(String d) {
        this.d = d;
    }

    static Path2D.Float parse(String d) {
        SvgPath p = new SvgPath(d);
        Path2D.Float path = new Path2D.Float();
        try {
            p.read(path);
        } catch (RuntimeException e) {
            // keep what was read before the bad data
        }
        return path;
    }

    private void read(Path2D.Float path) {
        float x = 0;
        float y = 0;
        float startX = 0;
        float startY = 0;
        float ctrlX = 0;
        float ctrlY = 0;
        char last = ' ';
        boolean open = false;
        int commands = 0;
        while (commands++ < MAX_COMMANDS) {
            skip();
            if (i >= d.length()) {
                return;
            }
            char c = d.charAt(i);
            char cmd;
            if (Character.isLetter(c)) {
                cmd = c;
                i++;
            } else if (last != ' ' && last != 'Z' && last != 'z') {
                // numbers after a moveto are implicit linetos
                cmd = switch (last) {
                    case 'M' -> 'L';
                    case 'm' -> 'l';
                    default -> last;
                };
            } else {
                return;
            }
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? x : 0;
            float oy = rel ? y : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M' -> {
                    x = ox + num();
                    y = oy + num();
                    path.moveTo(x, y);
                    startX = x;
                    startY = y;
                    open = true;
                }
                case 'L' -> {
                    x = ox + num();
                    y = oy + num();
                    lineTo(path, x, y, open);
                    open = true;
                }
                case 'H' -> {
                    x = ox + num();
                    lineTo(path, x, y, open);
                    open = true;
                }
                case 'V' -> {
                    y = oy + num();
                    lineTo(path, x, y, open);
                    open = true;
                }
                case 'C' -> {
                    float x1 = ox + num();
                    float y1 = oy + num();
                    ctrlX = ox + num();
                    ctrlY = oy + num();
                    x = ox + num();
                    y = oy + num();
                    ensure(path, open, x1, y1);
                    path.curveTo(x1, y1, ctrlX, ctrlY, x, y);
                    open = true;
                }
                case 'S' -> {
                    boolean smooth = "CcSs".indexOf(last) >= 0;
                    float x1 = smooth ? 2 * x - ctrlX : x;
                    float y1 = smooth ? 2 * y - ctrlY : y;
                    ctrlX = ox + num();
                    ctrlY = oy + num();
                    x = ox + num();
                    y = oy + num();
                    ensure(path, open, x1, y1);
                    path.curveTo(x1, y1, ctrlX, ctrlY, x, y);
                    open = true;
                }
                case 'Q' -> {
                    ctrlX = ox + num();
                    ctrlY = oy + num();
                    x = ox + num();
                    y = oy + num();
                    ensure(path, open, ctrlX, ctrlY);
                    path.quadTo(ctrlX, ctrlY, x, y);
                    open = true;
                }
                case 'T' -> {
                    boolean smooth = "QqTt".indexOf(last) >= 0;
                    ctrlX = smooth ? 2 * x - ctrlX : x;
                    ctrlY = smooth ? 2 * y - ctrlY : y;
                    x = ox + num();
                    y = oy + num();
                    ensure(path, open, ctrlX, ctrlY);
                    path.quadTo(ctrlX, ctrlY, x, y);
                    open = true;
                }
                case 'A' -> {
                    float rx = Math.abs(num());
                    float ry = Math.abs(num());
                    float angle = num();
                    boolean large = flag();
                    boolean sweep = flag();
                    float nx = ox + num();
                    float ny = oy + num();
                    ensure(path, open, x, y);
                    arc(path, x, y, rx, ry, angle, large, sweep, nx, ny);
                    x = nx;
                    y = ny;
                    open = true;
                }
                case 'Z' -> {
                    if (open) {
                        path.closePath();
                    }
                    x = startX;
                    y = startY;
                    open = false;
                }
                default -> {
                    return;
                }
            }
            last = cmd;
        }
    }

    private static void lineTo(Path2D.Float path, float x, float y, boolean open) {
        if (open) {
            path.lineTo(x, y);
        } else {
            path.moveTo(x, y);
        }
    }

    private static void ensure(Path2D.Float path, boolean open, float x, float y) {
        if (!open && path.getCurrentPoint() == null) {
            path.moveTo(x, y);
        }
    }

    // The endpoint arc of SVG drawn as cubic curves through its centre form (SVG 1.1 appendix F.6)
    private static void arc(Path2D.Float path, float x1, float y1, float rx, float ry, float angle, boolean large,
            boolean sweep, float x2, float y2) {
        if (rx == 0 || ry == 0 || x1 == x2 && y1 == y2) {
            path.lineTo(x2, y2);
            return;
        }
        double phi = Math.toRadians(angle % 360);
        double cos = Math.cos(phi);
        double sin = Math.sin(phi);
        double dx = (x1 - x2) / 2.0;
        double dy = (y1 - y2) / 2.0;
        double x1p = cos * dx + sin * dy;
        double y1p = -sin * dx + cos * dy;
        double rxs = (double) rx * rx;
        double rys = (double) ry * ry;
        double lambda = x1p * x1p / rxs + y1p * y1p / rys;
        double a = rx;
        double b = ry;
        if (lambda > 1) {
            a *= Math.sqrt(lambda);
            b *= Math.sqrt(lambda);
            rxs = a * a;
            rys = b * b;
        }
        double num = rxs * rys - rxs * y1p * y1p - rys * x1p * x1p;
        double den = rxs * y1p * y1p + rys * x1p * x1p;
        double coef = (large == sweep ? -1 : 1) * Math.sqrt(Math.max(0, num / den));
        double cxp = coef * a * y1p / b;
        double cyp = coef * -b * x1p / a;
        double cx = cos * cxp - sin * cyp + (x1 + x2) / 2.0;
        double cy = sin * cxp + cos * cyp + (y1 + y2) / 2.0;
        double start = Math.atan2((y1p - cyp) / b, (x1p - cxp) / a);
        double end = Math.atan2((-y1p - cyp) / b, (-x1p - cxp) / a);
        double delta = end - start;
        if (sweep && delta < 0) {
            delta += 2 * Math.PI;
        } else if (!sweep && delta > 0) {
            delta -= 2 * Math.PI;
        }
        int segments = (int) Math.ceil(Math.abs(delta) / (Math.PI / 2));
        double step = delta / segments;
        double k = 4.0 / 3 * Math.tan(step / 4);
        for (int s = 0; s < segments; s++) {
            double t1 = start + s * step;
            double t2 = t1 + step;
            double c1x = Math.cos(t1) - k * Math.sin(t1);
            double c1y = Math.sin(t1) + k * Math.cos(t1);
            double c2x = Math.cos(t2) + k * Math.sin(t2);
            double c2y = Math.sin(t2) - k * Math.cos(t2);
            path.curveTo(px(cx, a, b, cos, sin, c1x, c1y), py(cy, a, b, cos, sin, c1x, c1y),
                    px(cx, a, b, cos, sin, c2x, c2y), py(cy, a, b, cos, sin, c2x, c2y),
                    px(cx, a, b, cos, sin, Math.cos(t2), Math.sin(t2)), py(cy, a, b, cos, sin, Math.cos(t2), Math.sin(t2)));
        }
    }

    private static float px(double cx, double a, double b, double cos, double sin, double ux, double uy) {
        return (float) (cx + a * ux * cos - b * uy * sin);
    }

    private static float py(double cy, double a, double b, double cos, double sin, double ux, double uy) {
        return (float) (cy + a * ux * sin + b * uy * cos);
    }

    private void skip() {
        while (i < d.length() && (Character.isWhitespace(d.charAt(i)) || d.charAt(i) == ',')) {
            i++;
        }
    }

    private boolean flag() {
        skip();
        char c = d.charAt(i++);
        if (c != '0' && c != '1') {
            throw new IllegalArgumentException("bad arc flag");
        }
        return c == '1';
    }

    private float num() {
        skip();
        int start = i;
        if (i < d.length() && (d.charAt(i) == '-' || d.charAt(i) == '+')) {
            i++;
        }
        boolean dot = false;
        boolean exp = false;
        while (i < d.length()) {
            char c = d.charAt(i);
            if (Character.isDigit(c)) {
                i++;
            } else if (c == '.' && !dot && !exp) {
                dot = true;
                i++;
            } else if ((c == 'e' || c == 'E') && !exp && i + 1 < d.length()) {
                exp = true;
                i++;
                if (d.charAt(i) == '-' || d.charAt(i) == '+') {
                    i++;
                }
            } else {
                break;
            }
        }
        float f = Float.parseFloat(d.substring(start, i));
        if (!Float.isFinite(f)) {
            throw new IllegalArgumentException("bad number");
        }
        return f;
    }
}

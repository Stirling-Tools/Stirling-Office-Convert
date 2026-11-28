package stirling.software.officeconvert.topdf.xps;

import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.List;

import org.w3c.dom.Element;

final class XpsGeometry {

    final Path2D.Double fill;

    final Path2D.Double stroke;

    private int segments;

    private final Budget budget;

    interface Budget {
        void spend(int segments) throws IOException;
    }

    static final int MAX_SEGMENTS = 1_000_000;

    static final class TooLong extends IOException {
        TooLong() {
            super("The path has more than " + MAX_SEGMENTS + " segments");
        }
    }

    private final boolean separate;

    private int total;

    private XpsGeometry(boolean evenOdd, Budget budget, boolean separate) {
        this(evenOdd, budget, separate, 20);
    }

    private XpsGeometry(boolean evenOdd, Budget budget, boolean separate, int capacity) {
        int rule = evenOdd ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO;
        this.fill = new Path2D.Double(rule, capacity);
        this.stroke = separate ? new Path2D.Double(rule, capacity) : fill;
        this.budget = budget;
        this.separate = separate;
    }

    Path2D.Double strokePath() {
        return separate ? stroke : fill;
    }

    boolean strokeDiffers() {
        return separate;
    }

    boolean isEmpty() {
        return fill.getCurrentPoint() == null && (!separate || stroke.getCurrentPoint() == null);
    }

    static XpsGeometry abbreviated(String data, Budget budget) throws IOException {
        Parser p = new Parser(data);
        boolean evenOdd = true;
        p.skipSpace();
        if (p.peek() == 'F') {
            p.pos++;
            double rule = p.number();
            evenOdd = rule == 0;
        }
        XpsGeometry g = new XpsGeometry(evenOdd, budget, false, Math.max(4, Math.min(1 << 16, data.length() / 12)));
        g.parse(p);
        g.flush();
        return g;
    }

    static XpsGeometry pathGeometry(Element geometry, String figures, String fillRule, AffineTransform transform,
            Budget budget) throws IOException {
        boolean evenOdd = !"NonZero".equals(fillRule);
        List<Element> figureElements = geometry == null ? List.of() : XpsXml.children(geometry, "PathFigure");
        XpsGeometry g = new XpsGeometry(evenOdd, budget, !figureElements.isEmpty());
        if (figures != null && !figures.isBlank()) {
            Parser p = new Parser(figures);
            p.skipSpace();
            if (!p.atEnd() && p.peek() == 'F') {
                p.pos++;
                p.number();
            }
            g.parse(p);
        }
        for (Element figure : figureElements) {
            g.figure(figure);
        }
        g.flush();
        if (transform != null && !transform.isIdentity()) {
            g.fill.transform(transform);
            if (g.separate) {
                g.stroke.transform(transform);
            }
        }
        return g;
    }

    private double cx;

    private double cy;

    private double sx;

    private double sy;

    private double lastCx;

    private double lastCy;

    private char lastCommand;

    private boolean open;

    private boolean broken;

    private boolean figureFilled = true;

    private void flush() throws IOException {
        budget.spend(segments);
        segments = 0;
    }

    private void count() throws IOException {
        if (++total > MAX_SEGMENTS) {
            throw new TooLong();
        }
        if (++segments >= 4096) {
            flush();
        }
    }

    private void moveTo(double x, double y) throws IOException {
        count();
        if (figureFilled) {
            fill.moveTo(x, y);
        }
        if (separate) {
            stroke.moveTo(x, y);
        }
        cx = sx = x;
        cy = sy = y;
        open = true;
        broken = false;
    }

    private void ensureOpen() throws IOException {
        if (!open) {
            moveTo(cx, cy);
        }
    }

    private void lineTo(double x, double y, boolean stroked) throws IOException {
        ensureOpen();
        count();
        if (figureFilled || !separate) {
            fill.lineTo(x, y);
        }
        if (!separate) {
            cx = x;
            cy = y;
            return;
        }
        if (stroked) {
            stroke.lineTo(x, y);
        } else {
            stroke.moveTo(x, y);
            broken = true;
        }
        cx = x;
        cy = y;
    }

    private void curveTo(double x1, double y1, double x2, double y2, double x, double y, boolean stroked)
            throws IOException {
        ensureOpen();
        count();
        if (figureFilled || !separate) {
            fill.curveTo(x1, y1, x2, y2, x, y);
        }
        if (!separate) {
            cx = x;
            cy = y;
            return;
        }
        if (stroked) {
            stroke.curveTo(x1, y1, x2, y2, x, y);
        } else {
            stroke.moveTo(x, y);
            broken = true;
        }
        cx = x;
        cy = y;
    }

    private void quadTo(double x1, double y1, double x, double y, boolean stroked) throws IOException {
        curveTo(cx + 2.0 / 3 * (x1 - cx), cy + 2.0 / 3 * (y1 - cy), x + 2.0 / 3 * (x1 - x), y + 2.0 / 3 * (y1 - y), x, y,
                stroked);
    }

    private void close(boolean stroked) throws IOException {
        if (!open) {
            return;
        }
        count();
        if (figureFilled || !separate) {
            fill.closePath();
        }
        if (separate) {
            if (stroked && broken) {
                stroke.lineTo(sx, sy);
            } else if (stroked) {
                stroke.closePath();
            } else {
                stroke.moveTo(sx, sy);
                }
        }
        cx = sx;
        cy = sy;
        open = false;
    }

    private void parse(Parser p) throws IOException {
        char command = 0;
        while (true) {
            p.skipSpace();
            if (p.atEnd()) {
                return;
            }
            char c = p.peek();
            if (Character.isLetter(c)) {
                command = c;
                p.pos++;
                if (c == 'Z' || c == 'z') {
                    close(true);
                    lastCommand = c;
                    continue;
                }
            } else if (command == 0 || command == 'Z' || command == 'z') {
                throw new IOException("The path data is not valid near \"" + p.near() + "\"");
            } else if (command == 'M') {
                command = 'L';
            } else if (command == 'm') {
                command = 'l';
            }
            segment(p, command);
            lastCommand = command;
        }
    }

    private void segment(Parser p, char command) throws IOException {
        boolean rel = Character.isLowerCase(command);
        double ox = rel ? cx : 0;
        double oy = rel ? cy : 0;
        switch (Character.toUpperCase(command)) {
            case 'M' -> moveTo(ox + p.number(), oy + p.number());
            case 'L' -> lineTo(ox + p.number(), oy + p.number(), true);
            case 'H' -> lineTo(ox + p.number(), cy, true);
            case 'V' -> lineTo(cx, oy + p.number(), true);
            case 'C' -> {
                double x1 = ox + p.number();
                double y1 = oy + p.number();
                double x2 = ox + p.number();
                double y2 = oy + p.number();
                double x = ox + p.number();
                double y = oy + p.number();
                curveTo(x1, y1, x2, y2, x, y, true);
                lastCx = x2;
                lastCy = y2;
                return;
            }
            case 'S' -> {
                char prev = Character.toUpperCase(lastCommand);
                double x1 = prev == 'C' || prev == 'S' ? 2 * cx - lastCx : cx;
                double y1 = prev == 'C' || prev == 'S' ? 2 * cy - lastCy : cy;
                double x2 = ox + p.number();
                double y2 = oy + p.number();
                double x = ox + p.number();
                double y = oy + p.number();
                curveTo(x1, y1, x2, y2, x, y, true);
                lastCx = x2;
                lastCy = y2;
                return;
            }
            case 'Q' -> {
                double x1 = ox + p.number();
                double y1 = oy + p.number();
                double x = ox + p.number();
                double y = oy + p.number();
                quadTo(x1, y1, x, y, true);
            }
            case 'A' -> {
                double rx = p.number();
                double ry = p.number();
                double angle = p.number();
                boolean large = p.flag();
                boolean sweep = p.flag();
                double x = ox + p.number();
                double y = oy + p.number();
                arc(rx, ry, angle, large, sweep, x, y, true);
            }
            default -> throw new IOException("The path data has an unknown command '" + command + "'");
        }
        lastCx = cx;
        lastCy = cy;
    }

    private void arc(double rx, double ry, double angle, boolean large, boolean sweep, double x, double y,
            boolean stroked) throws IOException {
        ensureOpen();
        double x0 = cx;
        double y0 = cy;
        if (x0 == x && y0 == y) {
            return;
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        if (rx == 0 || ry == 0 || !Double.isFinite(rx) || !Double.isFinite(ry)) {
            lineTo(x, y, stroked);
            return;
        }
        double phi = Math.toRadians(angle % 360);
        double cos = Math.cos(phi);
        double sin = Math.sin(phi);
        double dx = (x0 - x) / 2;
        double dy = (y0 - y) / 2;
        double x1p = cos * dx + sin * dy;
        double y1p = -sin * dx + cos * dy;
        double lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lambda > 1) {
            double s = Math.sqrt(lambda);
            rx *= s;
            ry *= s;
        }
        double num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p;
        double den = rx * rx * y1p * y1p + ry * ry * x1p * x1p;
        double coef = den == 0 ? 0 : Math.sqrt(Math.max(0, num / den));
        if (large == sweep) {
            coef = -coef;
        }
        double cxp = coef * rx * y1p / ry;
        double cyp = -coef * ry * x1p / rx;
        double ccx = cos * cxp - sin * cyp + (x0 + x) / 2;
        double ccy = sin * cxp + cos * cyp + (y0 + y) / 2;
        double theta1 = angle((x1p - cxp) / rx, (y1p - cyp) / ry);
        double delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry);
        if (!sweep && delta > 0) {
            delta -= 2 * Math.PI;
        } else if (sweep && delta < 0) {
            delta += 2 * Math.PI;
        }
        int pieces = (int) Math.ceil(Math.abs(delta) / (Math.PI / 2) - 1e-9);
        pieces = Math.max(1, Math.min(pieces, 4));
        double step = delta / pieces;
        double k = 4.0 / 3 * Math.tan(step / 4);
        double t = theta1;
        for (int i = 0; i < pieces; i++) {
            double c1 = Math.cos(t);
            double s1 = Math.sin(t);
            double c2 = Math.cos(t + step);
            double s2 = Math.sin(t + step);
            double ax = c1 - k * s1;
            double ay = s1 + k * c1;
            double bx = c2 + k * s2;
            double by = s2 - k * c2;
            Point2D p1 = map(ax, ay, rx, ry, cos, sin, ccx, ccy);
            Point2D p2 = map(bx, by, rx, ry, cos, sin, ccx, ccy);
            Point2D p3 = i == pieces - 1 ? new Point2D.Double(x, y) : map(c2, s2, rx, ry, cos, sin, ccx, ccy);
            curveTo(p1.getX(), p1.getY(), p2.getX(), p2.getY(), p3.getX(), p3.getY(), stroked);
            t += step;
        }
    }

    private static Point2D map(double ux, double uy, double rx, double ry, double cos, double sin, double cx,
            double cy) {
        double x = ux * rx;
        double y = uy * ry;
        return new Point2D.Double(cos * x - sin * y + cx, sin * x + cos * y + cy);
    }

    private static double angle(double ux, double uy) {
        return Math.atan2(uy, ux);
    }

    private static double angle(double ux, double uy, double vx, double vy) {
        return Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
    }

    private void figure(Element figure) throws IOException {
        double[] start = XpsXml.point(figure.getAttribute("StartPoint"));
        boolean closed = "true".equalsIgnoreCase(figure.getAttribute("IsClosed").strip());
        String filled = figure.getAttribute("IsFilled").strip();
        figureFilled = filled.isEmpty() || "true".equalsIgnoreCase(filled);
        open = false;
        moveTo(start == null ? 0 : start[0], start == null ? 0 : start[1]);
        boolean lastStroked = true;
        for (Element s : XpsXml.elements(figure)) {
            String stroked = s.getAttribute("IsStroked").strip();
            boolean on = stroked.isEmpty() || "true".equalsIgnoreCase(stroked);
            lastStroked = on;
            switch (s.getLocalName()) {
                case "PolyLineSegment" -> {
                    double[] pts = XpsXml.numbers(s.getAttribute("Points"));
                    for (int i = 0; i + 1 < pts.length; i += 2) {
                        lineTo(pts[i], pts[i + 1], on);
                    }
                }
                case "PolyBezierSegment" -> {
                    double[] pts = XpsXml.numbers(s.getAttribute("Points"));
                    for (int i = 0; i + 5 < pts.length; i += 6) {
                        curveTo(pts[i], pts[i + 1], pts[i + 2], pts[i + 3], pts[i + 4], pts[i + 5], on);
                    }
                }
                case "PolyQuadraticBezierSegment" -> {
                    double[] pts = XpsXml.numbers(s.getAttribute("Points"));
                    for (int i = 0; i + 3 < pts.length; i += 4) {
                        quadTo(pts[i], pts[i + 1], pts[i + 2], pts[i + 3], on);
                    }
                }
                case "ArcSegment" -> {
                    double[] pt = XpsXml.point(s.getAttribute("Point"));
                    double[] size = XpsXml.point(s.getAttribute("Size"));
                    if (pt == null || size == null) {
                        continue;
                    }
                    double rot = XpsXml.number(s.getAttribute("RotationAngle"), 0);
                    boolean large = "true".equalsIgnoreCase(s.getAttribute("IsLargeArc").strip());
                    boolean sweep = "Clockwise".equals(s.getAttribute("SweepDirection").strip());
                    arc(size[0], size[1], rot, large, sweep, pt[0], pt[1], on);
                }
                default -> {}
            }
        }
        if (closed) {
            close(lastStroked);
        }
        figureFilled = true;
    }

    static final class Parser {
        final String s;

        int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        char peek() {
            return s.charAt(pos);
        }

        void skipSpace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == ',' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        String near() {
            return s.substring(Math.max(0, pos - 10), Math.min(s.length(), pos + 10));
        }

        boolean flag() throws IOException {
            skipSpace();
            if (atEnd()) {
                throw new IOException("The path data ends too early");
            }
            char c = s.charAt(pos);
            if (c == '0' || c == '1') {
                int end = pos + 1;
                while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '.')) {
                    end++;
                }
                double v = Double.parseDouble(s.substring(pos, end));
                pos = end;
                return v != 0;
            }
            return number() != 0;
        }

        double number() throws IOException {
            skipSpace();
            int start = pos;
            int n = s.length();
            boolean negative = false;
            if (pos < n && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                negative = s.charAt(pos) == '-';
                pos++;
            }
            long mantissa = 0;
            int digits = 0;
            int scale = 0;
            boolean any = false;
            while (pos < n) {
                char c = s.charAt(pos);
                if (c < '0' || c > '9') {
                    break;
                }
                if (digits < 17) {
                    mantissa = mantissa * 10 + (c - '0');
                    if (mantissa != 0) {
                        digits++;
                    }
                } else {
                    scale--;
                }
                pos++;
                any = true;
            }
            if (pos < n && s.charAt(pos) == '.') {
                pos++;
                while (pos < n) {
                    char c = s.charAt(pos);
                    if (c < '0' || c > '9') {
                        break;
                    }
                    if (digits < 17) {
                        mantissa = mantissa * 10 + (c - '0');
                        if (mantissa != 0) {
                            digits++;
                        }
                        scale++;
                    }
                    pos++;
                    any = true;
                }
            }
            if (!any) {
                pos = start;
                throw new IOException("The path data is not valid near \"" + near() + "\"");
            }
            if (pos < n && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                int mark = pos;
                pos++;
                if (pos < n && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                boolean exp = false;
                while (pos < n && Character.isDigit(s.charAt(pos))) {
                    pos++;
                    exp = true;
                }
                if (!exp) {
                    pos = mark;
                } else {
                    double v = Double.parseDouble(s.substring(start, pos));
                    return Double.isFinite(v) ? v : 0;
                }
            }
            if (scale >= POW10.length || -scale >= POW10.length) {
                double v = Double.parseDouble(s.substring(start, pos));
                return Double.isFinite(v) ? v : 0;
            }
            double v = scale >= 0 ? mantissa / POW10[scale] : mantissa * POW10[-scale];
            return negative ? -v : v;
        }

        private static final double[] POW10 = {1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12,
            1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22};
    }
}

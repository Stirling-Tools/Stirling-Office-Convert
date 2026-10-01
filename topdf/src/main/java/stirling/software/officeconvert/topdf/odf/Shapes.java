package stirling.software.officeconvert.topdf.odf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.w3c.dom.Element;

/** ODF shape geometry as DrawingML: an enhanced geometry's path is evaluated (equations, modifiers, arcs) into a
 * custom geometry; simple shapes and shapes without a path use the matching preset. */
final class Shapes {

    record Geometry(String xml, boolean flipH, boolean flipV) {}

    static final int MAX_EQUATIONS = 4096;

    static final String DRAWOOO = "http://openoffice.org/2010/draw";

    static final int MAX_TOKENS = 200_000;

    private static final Map<String, String> PRESETS = Map.ofEntries(Map.entry("rectangle", "rect"),
            Map.entry("round-rectangle", "roundRect"), Map.entry("ellipse", "ellipse"), Map.entry("circle", "ellipse"),
            Map.entry("isosceles-triangle", "triangle"), Map.entry("right-triangle", "rtTriangle"),
            Map.entry("diamond", "diamond"), Map.entry("parallelogram", "parallelogram"),
            Map.entry("trapezoid", "trapezoid"), Map.entry("hexagon", "hexagon"), Map.entry("octagon", "octagon"),
            Map.entry("pentagon", "pentagon"), Map.entry("right-arrow", "rightArrow"),
            Map.entry("left-arrow", "leftArrow"), Map.entry("up-arrow", "upArrow"),
            Map.entry("down-arrow", "downArrow"), Map.entry("left-right-arrow", "leftRightArrow"),
            Map.entry("up-down-arrow", "upDownArrow"), Map.entry("star5", "star5"), Map.entry("star4", "star4"),
            Map.entry("star8", "star8"), Map.entry("cross", "plus"), Map.entry("can", "can"), Map.entry("cube", "cube"),
            Map.entry("heart", "heart"), Map.entry("smiley", "smileyFace"), Map.entry("sun", "sun"),
            Map.entry("moon", "moon"), Map.entry("chevron", "chevron"), Map.entry("pentagon-right", "homePlate"),
            Map.entry("ring", "donut"), Map.entry("frame", "frame"), Map.entry("mso-spt202", "rect"),
            Map.entry("mso-spt1", "rect"), Map.entry("mso-spt2", "roundRect"), Map.entry("mso-spt3", "ellipse"),
            Map.entry("flowchart-process", "flowChartProcess"), Map.entry("flowchart-decision", "flowChartDecision"),
            Map.entry("flowchart-terminator", "flowChartTerminator"));

    private Shapes() {}

    static Geometry geometry(Element shape, double widthPt, double heightPt) {
        String local = Dom.local(shape);
        if (local.equals("ellipse") || local.equals("circle")) {
            return preset("ellipse");
        }
        if (local.equals("rect")) {
            double r = Length.pt(Dom.attr(shape, Ns.DRAW, "corner-radius"), 0);
            if (r > 0 && Math.min(widthPt, heightPt) > 0) {
                long adj = Math.round(Math.min(50_000, r / Math.min(widthPt, heightPt) * 100_000));
                return new Geometry("<a:prstGeom prst=\"roundRect\"><a:avLst><a:gd name=\"adj\" fmla=\"val " + adj
                        + "\"/></a:avLst></a:prstGeom>", false, false);
            }
            return preset("rect");
        }
        if (local.equals("polygon") || local.equals("polyline")) {
            String xml = points(shape, local.equals("polygon"));
            return xml == null ? preset("rect") : new Geometry(xml, false, false);
        }
        if (local.equals("path")) {
            String xml = svgPath(shape);
            return xml == null ? preset("rect") : new Geometry(xml, false, false);
        }
        Element g = Dom.kid(shape, Ns.DRAW, "enhanced-geometry");
        if (g == null) {
            return preset("rect");
        }
        boolean flipH = "true".equals(Dom.attr(g, Ns.DRAW, "mirror-horizontal"));
        boolean flipV = "true".equals(Dom.attr(g, Ns.DRAW, "mirror-vertical"));
        String type = Dom.attr(g, Ns.DRAW, "type", "non-primitive");
        String path = Dom.attr(g, DRAWOOO, "enhanced-path");
        if (path == null || path.isBlank()) {
            path = Dom.attr(g, Ns.DRAW, "enhanced-path");
        }
        if (path != null && !path.isBlank()) {
            try {
                String xml = new Evaluator(g, widthPt, heightPt).custGeom(path);
                if (xml != null) {
                    return new Geometry(xml, flipH, flipV);
                }
            } catch (RuntimeException e) {
                // an unreadable path falls back to the preset of its type
            }
        }
        String prst = type.startsWith("ooxml-") ? type.substring(6) : PRESETS.getOrDefault(type, "rect");
        if (prst.equals("non-primitive")) {
            prst = "rect";
        }
        Geometry p = preset(prst);
        return new Geometry(p.xml(), flipH, flipV);
    }

    static Geometry preset(String prst) {
        return new Geometry("<a:prstGeom prst=\"" + prst + "\"><a:avLst/></a:prstGeom>", false, false);
    }

    private static String points(Element shape, boolean closed) {
        String pts = Dom.attr(shape, Ns.DRAW, "points");
        double[] vb = viewBox(Dom.attr(shape, Ns.SVG, "viewBox"));
        if (pts == null || vb == null) {
            return null;
        }
        String[] t = pts.trim().split("[\\s,]+");
        if (t.length < 4) {
            return null;
        }
        Path p = new Path(vb);
        for (int i = 0; i + 1 < t.length && i < MAX_TOKENS; i += 2) {
            double x = parse(t[i]);
            double y = parse(t[i + 1]);
            if (i == 0) {
                p.move(x, y);
            } else {
                p.line(x, y);
            }
        }
        if (closed) {
            p.close();
        }
        p.end(!closed, false);
        return p.xml(null);
    }

    private static String svgPath(Element shape) {
        String d = Dom.attr(shape, Ns.SVG, "d");
        double[] vb = viewBox(Dom.attr(shape, Ns.SVG, "viewBox"));
        if (d == null || vb == null) {
            return null;
        }
        Path p = new Path(vb);
        SvgPath.parse(d, p);
        p.end(false, false);
        return p.xml(null);
    }

    static double[] viewBox(String v) {
        if (v == null) {
            return null;
        }
        String[] t = v.trim().split("[\\s,]+");
        if (t.length != 4) {
            return null;
        }
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) {
            out[i] = parse(t[i]);
        }
        return out;
    }

    private static double parse(String s) {
        try {
            double d = Double.parseDouble(s);
            return Double.isFinite(d) ? d : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Path segments in viewBox units, written as one DrawingML path per ODF sub-path. */
    static final class Path {
        private final double ox;
        private final double oy;
        private final double w;
        private final double h;
        private final double k;
        private final StringBuilder paths = new StringBuilder();
        private StringBuilder current = new StringBuilder();
        private boolean noFill;
        private boolean noStroke;
        double cx;
        double cy;
        double sx;
        double sy;
        private int segments;

        Path(double[] viewBox) {
            this.ox = viewBox[0];
            this.oy = viewBox[1];
            this.w = Math.max(viewBox[2], 1e-6);
            this.h = Math.max(viewBox[3], 1e-6);
            this.k = 100_000 / Math.max(this.w, this.h);
        }

        private String pt(double x, double y) {
            return "<a:pt x=\"" + Math.round((x - ox) * k) + "\" y=\"" + Math.round((y - oy) * k) + "\"/>";
        }

        void move(double x, double y) {
            current.append("<a:moveTo>").append(pt(x, y)).append("</a:moveTo>");
            cx = sx = x;
            cy = sy = y;
            segments++;
        }

        void line(double x, double y) {
            if (current.isEmpty()) {
                move(cx, cy);
            }
            current.append("<a:lnTo>").append(pt(x, y)).append("</a:lnTo>");
            cx = x;
            cy = y;
            segments++;
        }

        void cubic(double x1, double y1, double x2, double y2, double x, double y) {
            if (current.isEmpty()) {
                move(cx, cy);
            }
            current.append("<a:cubicBezTo>").append(pt(x1, y1)).append(pt(x2, y2)).append(pt(x, y))
                    .append("</a:cubicBezTo>");
            cx = x;
            cy = y;
            segments++;
        }

        void quad(double x1, double y1, double x, double y) {
            cubic(cx + 2.0 / 3 * (x1 - cx), cy + 2.0 / 3 * (y1 - cy), x + 2.0 / 3 * (x1 - x), y + 2.0 / 3 * (y1 - y), x,
                    y);
        }

        void arcTo(double wr, double hr, double stDeg, double swDeg) {
            if (current.isEmpty()) {
                move(cx, cy);
            }
            current.append("<a:arcTo wR=\"").append(Math.round(wr * k)).append("\" hR=\"").append(Math.round(hr * k))
                    .append("\" stAng=\"").append(Math.round(stDeg * 60000)).append("\" swAng=\"")
                    .append(Math.round(swDeg * 60000)).append("\"/>");
            double st = Math.toRadians(stDeg);
            double en = Math.toRadians(stDeg + swDeg);
            double a0 = Math.atan2(wr * Math.sin(st), hr * Math.cos(st));
            double a1 = Math.atan2(wr * Math.sin(en), hr * Math.cos(en));
            double centerX = cx - wr * Math.cos(a0);
            double centerY = cy - hr * Math.sin(a0);
            cx = centerX + wr * Math.cos(a1);
            cy = centerY + hr * Math.sin(a1);
            segments++;
        }

        /** An elliptical arc on the ellipse centred at (ecx, ecy) with radii rx, ry, from parametric angle t0 to t1
         * (radians, y down), as cubic Beziers. */
        void ellipse(double ecx, double ecy, double rx, double ry, double t0, double t1, boolean moveFirst) {
            double x0 = ecx + rx * Math.cos(t0);
            double y0 = ecy + ry * Math.sin(t0);
            if (moveFirst || current.isEmpty()) {
                move(x0, y0);
            } else {
                line(x0, y0);
            }
            double sweep = t1 - t0;
            int n = (int) Math.ceil(Math.abs(sweep) / (Math.PI / 2) - 1e-9);
            n = Math.max(1, Math.min(16, n));
            double step = sweep / n;
            double a = t0;
            for (int i = 0; i < n; i++) {
                double b = a + step;
                double alpha = 4.0 / 3 * Math.tan((b - a) / 4);
                double c1x = ecx + rx * (Math.cos(a) - alpha * Math.sin(a));
                double c1y = ecy + ry * (Math.sin(a) + alpha * Math.cos(a));
                double c2x = ecx + rx * (Math.cos(b) + alpha * Math.sin(b));
                double c2y = ecy + ry * (Math.sin(b) - alpha * Math.cos(b));
                cubic(c1x, c1y, c2x, c2y, ecx + rx * Math.cos(b), ecy + ry * Math.sin(b));
                a = b;
            }
        }

        void close() {
            if (!current.isEmpty()) {
                current.append("<a:close/>");
                cx = sx;
                cy = sy;
            }
        }

        void flags(boolean fillOff, boolean strokeOff) {
            noFill |= fillOff;
            noStroke |= strokeOff;
        }

        void end(boolean fillOff, boolean strokeOff) {
            if (!current.isEmpty()) {
                paths.append("<a:path w=\"").append(Math.round(w * k)).append("\" h=\"").append(Math.round(h * k))
                        .append('"');
                if (noFill || fillOff) {
                    paths.append(" fill=\"none\"");
                }
                if (noStroke || strokeOff) {
                    paths.append(" stroke=\"0\"");
                }
                paths.append('>').append(current).append("</a:path>");
            }
            current = new StringBuilder();
            noFill = false;
            noStroke = false;
        }

        boolean tooLong() {
            return segments > MAX_TOKENS;
        }

        String xml(double[] textRect) {
            return xml(textRect, w * k, h * k);
        }

        String xml(double[] textRect, double shapeW, double shapeH) {
            if (paths.isEmpty()) {
                return null;
            }
            StringBuilder b = new StringBuilder("<a:custGeom><a:avLst/><a:gdLst/><a:ahLst/><a:cxnLst/>");
            if (textRect != null) {
                double sx = shapeW / w;
                double sy = shapeH / h;
                b.append("<a:rect l=\"").append(Math.round((textRect[0] - ox) * sx)).append("\" t=\"")
                        .append(Math.round((textRect[1] - oy) * sy)).append("\" r=\"")
                        .append(Math.round((textRect[2] - ox) * sx)).append("\" b=\"")
                        .append(Math.round((textRect[3] - oy) * sy)).append("\"/>");
            } else {
                b.append("<a:rect l=\"l\" t=\"t\" r=\"r\" b=\"b\"/>");
            }
            return b.append("<a:pathLst>").append(paths).append("</a:pathLst></a:custGeom>").toString();
        }
    }

    /** Evaluates an enhanced geometry: modifiers ($n), equations (?fn), the functions and identifiers of ODF 19.171. */
    static final class Evaluator {
        private final double[] modifiers;
        private final Map<String, String> equations = new HashMap<>();
        private final Map<String, Double> values = new HashMap<>();
        private final List<String> evaluating = new ArrayList<>();
        private final double vx;
        private final double vy;
        private final double vw;
        private final double vh;
        private final double logW;
        private final double logH;
        private final boolean logical;
        private final Element g;
        private final double widthPt;
        private final double heightPt;

        Evaluator(Element g, double widthPt, double heightPt) {
            this.g = g;
            this.widthPt = widthPt;
            this.heightPt = heightPt;
            String mods = Dom.attr(g, Ns.DRAW, "modifiers");
            List<Double> m = new ArrayList<>();
            if (mods != null && !mods.isBlank()) {
                for (String t : mods.trim().split("[\\s,]+")) {
                    m.add(parse(t));
                }
            }
            modifiers = new double[m.size()];
            for (int i = 0; i < modifiers.length; i++) {
                modifiers[i] = m.get(i);
            }
            int n = 0;
            for (Element e : Dom.kids(g, Ns.DRAW, "equation")) {
                if (n++ > MAX_EQUATIONS) {
                    break;
                }
                String name = Dom.attr(e, Ns.DRAW, "name");
                String formula = Dom.attr(e, Ns.DRAW, "formula");
                if (name != null && formula != null) {
                    equations.put(name, formula);
                }
            }
            logW = Math.max(1, widthPt / 72 * 2540);
            logH = Math.max(1, heightPt / 72 * 2540);
            double[] vb = viewBox(Dom.attr(g, Ns.SVG, "viewBox"));
            if (vb == null || vb[2] <= 0 || vb[3] <= 0) {
                logical = true;
                vx = 0;
                vy = 0;
                vw = logW;
                vh = logH;
            } else {
                logical = false;
                vx = vb[0];
                vy = vb[1];
                vw = vb[2];
                vh = vb[3];
            }
        }

        String custGeom(String path) {
            Path p = new Path(new double[] {vx, vy, vw, vh});
            List<String> tokens = tokenize(path);
            if (tokens.size() > MAX_TOKENS) {
                return null;
            }
            int i = 0;
            char cmd = 'M';
            boolean any = false;
            int subpath = 0;
            double[] sub = logical ? subViews() : null;
            while (i < tokens.size() && !p.tooLong()) {
                String t = tokens.get(i);
                if (t.length() == 1 && Character.isLetter(t.charAt(0))) {
                    cmd = t.charAt(0);
                    i++;
                    switch (cmd) {
                        case 'Z' -> p.close();
                        case 'N' -> {
                            p.end(false, false);
                            subpath++;
                        }
                        case 'F' -> p.flags(true, false);
                        case 'S' -> p.flags(false, true);
                        default -> {
                        }
                    }
                    continue;
                }
                int need = switch (cmd) {
                    case 'M', 'L' -> 2;
                    case 'C' -> 6;
                    case 'Q' -> 4;
                    case 'T', 'U' -> 6;
                    case 'A', 'B', 'W', 'V' -> 8;
                    case 'G' -> 4;
                    case 'X', 'Y' -> 2;
                    default -> 0;
                };
                if (need == 0 || i + need > tokens.size()) {
                    break;
                }
                double[] a = new double[need];
                for (int j = 0; j < need; j++) {
                    String tok = tokens.get(i + j);
                    a[j] = value(tok);
                    int axis = axis(cmd, j);
                    if (axis < 2 && sub != null && subpath < sub.length / 2 && isNumber(tok)) {
                        double scale = axis == 0 ? vw / Math.max(1e-9, sub[subpath * 2])
                                : vh / Math.max(1e-9, sub[subpath * 2 + 1]);
                        a[j] *= scale;
                    }
                }
                i += need;
                any = true;
                switch (cmd) {
                    case 'M' -> {
                        p.move(a[0], a[1]);
                        cmd = 'L';
                    }
                    case 'L' -> p.line(a[0], a[1]);
                    case 'C' -> p.cubic(a[0], a[1], a[2], a[3], a[4], a[5]);
                    case 'Q' -> p.quad(a[0], a[1], a[2], a[3]);
                    case 'T', 'U' -> {
                        double t0 = -Math.toRadians(a[4]);
                        double t1 = -Math.toRadians(a[5]);
                        if (t1 >= t0) {
                            t1 -= 2 * Math.PI;
                        }
                        if (Math.abs(a[5] - a[4]) >= 360 || a[4] == a[5]) {
                            t1 = t0 - 2 * Math.PI;
                        }
                        p.ellipse(a[0], a[1], a[2], a[3], t0, t1, cmd == 'U');
                        if (cmd == 'U') {
                            cmd = 'T';
                        }
                    }
                    case 'A', 'B', 'W', 'V' -> {
                        arc(p, a, cmd == 'W' || cmd == 'V', cmd == 'B' || cmd == 'V');
                    }
                    case 'G' -> p.arcTo(a[0], a[1], a[2], a[3]);
                    case 'X', 'Y' -> {
                        quadrant(p, a[0], a[1], cmd == 'X');
                        cmd = cmd == 'X' ? 'Y' : 'X';
                    }
                    default -> {
                    }
                }
            }
            p.end(false, false);
            if (!any) {
                return null;
            }
            double[] text = textArea();
            return p.xml(text, Length.emu(widthPt), Length.emu(heightPt));
        }

        private double[] subViews() {
            String v = Dom.attr(g, DRAWOOO, "sub-view-size");
            if (v == null || v.isBlank()) {
                return null;
            }
            String[] t = v.trim().split("[\\s,]+");
            double[] out = new double[t.length - t.length % 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = parse(t[i]);
            }
            return out.length == 0 ? null : out;
        }

        private static boolean isNumber(String t) {
            char c = t.charAt(0);
            return Character.isDigit(c) || c == '-' || c == '.' || c == '+';
        }

        private static int axis(char cmd, int j) {
            return switch (cmd) {
                case 'T', 'U' -> j < 4 ? j % 2 : 2;
                case 'G' -> j < 2 ? j : 2;
                default -> j % 2;
            };
        }

        private double[] textArea() {
            String areas = Dom.attr(g, Ns.DRAW, "text-areas");
            if (areas == null || areas.isBlank()) {
                return null;
            }
            List<String> t = tokenize(areas);
            if (t.size() < 4) {
                return null;
            }
            return new double[] {value(t.get(0)), value(t.get(1)), value(t.get(2)), value(t.get(3))};
        }

        private static void arc(Path p, double[] a, boolean clockwise, boolean move) {
            double x1 = Math.min(a[0], a[2]);
            double y1 = Math.min(a[1], a[3]);
            double x2 = Math.max(a[0], a[2]);
            double y2 = Math.max(a[1], a[3]);
            double rx = (x2 - x1) / 2;
            double ry = (y2 - y1) / 2;
            if (rx <= 0 || ry <= 0) {
                if (move) {
                    p.move(a[6], a[7]);
                } else {
                    p.line(a[6], a[7]);
                }
                return;
            }
            double ecx = x1 + rx;
            double ecy = y1 + ry;
            double t0 = Math.atan2((a[5] - ecy) / ry, (a[4] - ecx) / rx);
            double t1 = Math.atan2((a[7] - ecy) / ry, (a[6] - ecx) / rx);
            if (clockwise) {
                while (t1 <= t0) {
                    t1 += 2 * Math.PI;
                }
            } else {
                while (t1 >= t0) {
                    t1 -= 2 * Math.PI;
                }
            }
            p.ellipse(ecx, ecy, rx, ry, t0, t1, move);
        }

        private static void quadrant(Path p, double x, double y, boolean horizontalFirst) {
            double x0 = p.cx;
            double y0 = p.cy;
            double kappa = 0.5522847498;
            if (horizontalFirst) {
                p.cubic(x0 + (x - x0) * kappa, y0, x, y - (y - y0) * kappa, x, y);
            } else {
                p.cubic(x0, y0 + (y - y0) * kappa, x - (x - x0) * kappa, y, x, y);
            }
        }

        private static List<String> tokenize(String s) {
            List<String> out = new ArrayList<>();
            int i = 0;
            int n = s.length();
            while (i < n && out.size() <= MAX_TOKENS) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c) || c == ',') {
                    i++;
                    continue;
                }
                int j = i + 1;
                if (c == '?' || c == '$') {
                    while (j < n && Character.isLetterOrDigit(s.charAt(j))) {
                        j++;
                    }
                } else if (Character.isLetter(c)) {
                    while (j < n && Character.isLetter(s.charAt(j))) {
                        j++;
                    }
                    if (j - i > 1) {
                        out.add(s.substring(i, j));
                        i = j;
                        continue;
                    }
                } else {
                    while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.' || s.charAt(j) == 'e'
                            || s.charAt(j) == 'E' || (s.charAt(j) == '-' && (s.charAt(j - 1) == 'e'
                            || s.charAt(j - 1) == 'E')))) {
                        j++;
                    }
                }
                out.add(s.substring(i, j));
                i = j;
            }
            return out;
        }

        double value(String token) {
            if (token.startsWith("?")) {
                return equation(token.substring(1));
            }
            if (token.startsWith("$")) {
                int idx = (int) parse(token.substring(1));
                return idx >= 0 && idx < modifiers.length ? modifiers[idx] : 0;
            }
            Double id = identifier(token);
            if (id != null) {
                return id;
            }
            return parse(token);
        }

        private Double identifier(String name) {
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "pi" -> Math.PI;
                case "left" -> vx;
                case "top" -> vy;
                case "right" -> vx + vw;
                case "bottom" -> vy + vh;
                case "width" -> vw;
                case "height" -> vh;
                case "logwidth" -> logical ? vw : logW;
                case "logheight" -> logical ? vh : logH;
                case "xstretch", "ystretch" -> 0.0;
                case "hasstroke", "hasfill" -> 1.0;
                default -> null;
            };
        }

        private double equation(String name) {
            Double v = values.get(name);
            if (v != null) {
                return v;
            }
            String f = equations.get(name);
            if (f == null || evaluating.contains(name) || evaluating.size() > 256) {
                return 0;
            }
            evaluating.add(name);
            double r;
            try {
                r = new Formula(f, this).parse();
            } finally {
                evaluating.remove(evaluating.size() - 1);
            }
            if (!Double.isFinite(r)) {
                r = 0;
            }
            values.put(name, r);
            return r;
        }
    }

    private static final class Formula {
        private final String s;
        private final Evaluator ev;
        private int i;
        private int depth;

        Formula(String s, Evaluator ev) {
            this.s = s;
            this.ev = ev;
        }

        double parse() {
            double v = sum();
            return v;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private double sum() {
            double v = product();
            while (true) {
                ws();
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    char op = s.charAt(i++);
                    double r = product();
                    v = op == '+' ? v + r : v - r;
                } else {
                    return v;
                }
            }
        }

        private double product() {
            double v = unary();
            while (true) {
                ws();
                if (i < s.length() && (s.charAt(i) == '*' || s.charAt(i) == '/')) {
                    char op = s.charAt(i++);
                    double r = unary();
                    v = op == '*' ? v * r : r == 0 ? 0 : v / r;
                } else {
                    return v;
                }
            }
        }

        private double unary() {
            ws();
            if (i < s.length() && s.charAt(i) == '-') {
                i++;
                return -unary();
            }
            if (i < s.length() && s.charAt(i) == '+') {
                i++;
                return unary();
            }
            return primary();
        }

        private double primary() {
            ws();
            if (i >= s.length() || ++depth > 200) {
                return 0;
            }
            char c = s.charAt(i);
            if (c == '(') {
                i++;
                double v = sum();
                ws();
                if (i < s.length() && s.charAt(i) == ')') {
                    i++;
                }
                depth--;
                return v;
            }
            if (c == '?' || c == '$') {
                int j = i + 1;
                while (j < s.length() && Character.isLetterOrDigit(s.charAt(j))) {
                    j++;
                }
                double v = ev.value(s.substring(i, j));
                i = j;
                depth--;
                return v;
            }
            if (Character.isDigit(c) || c == '.') {
                int j = i;
                while (j < s.length() && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) {
                    j++;
                }
                if (j < s.length() && (s.charAt(j) == 'e' || s.charAt(j) == 'E') && j + 1 < s.length()
                        && (Character.isDigit(s.charAt(j + 1)) || s.charAt(j + 1) == '-' || s.charAt(j + 1) == '+')) {
                    j += 2;
                    while (j < s.length() && Character.isDigit(s.charAt(j))) {
                        j++;
                    }
                }
                double v = Shapes.parse(s.substring(i, j));
                i = j;
                depth--;
                return v;
            }
            if (Character.isLetter(c)) {
                int j = i;
                while (j < s.length() && Character.isLetterOrDigit(s.charAt(j))) {
                    j++;
                }
                String name = s.substring(i, j);
                i = j;
                ws();
                double v;
                if (i < s.length() && s.charAt(i) == '(') {
                    i++;
                    List<Double> args = new ArrayList<>();
                    while (true) {
                        args.add(sum());
                        ws();
                        if (i < s.length() && s.charAt(i) == ',') {
                            i++;
                            continue;
                        }
                        if (i < s.length() && s.charAt(i) == ')') {
                            i++;
                        }
                        break;
                    }
                    v = function(name, args);
                } else {
                    v = ev.value(name);
                }
                depth--;
                return v;
            }
            i++;
            depth--;
            return 0;
        }

        private static double function(String name, List<Double> a) {
            double x = a.isEmpty() ? 0 : a.get(0);
            double y = a.size() > 1 ? a.get(1) : 0;
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "abs" -> Math.abs(x);
                case "sqrt" -> Math.sqrt(Math.max(0, x));
                case "sin" -> Math.sin(x);
                case "cos" -> Math.cos(x);
                case "tan" -> Math.tan(x);
                case "atan" -> Math.atan(x);
                case "atan2" -> Math.atan2(x, y);
                case "min" -> Math.min(x, y);
                case "max" -> Math.max(x, y);
                case "if" -> x > 0 ? y : a.size() > 2 ? a.get(2) : 0;
                default -> 0;
            };
        }
    }
}

package stirling.software.officeconvert.topdf.odf;

import java.util.Locale;

import org.w3c.dom.Element;

/** A drawing object's box in points: position, size and clockwise rotation in degrees, read from svg:x/y/width/height
 * or from draw:transform. */
record Box(double x, double y, double w, double h, double rot) {

    static Box of(Element e) {
        double w = Length.pt(Dom.attr(e, Ns.SVG, "width"), Double.NaN);
        double h = Length.pt(Dom.attr(e, Ns.SVG, "height"), Double.NaN);
        if (Double.isNaN(w)) {
            w = Length.pt(Dom.attr(e, Ns.FO, "min-width"), 0);
        }
        if (Double.isNaN(h)) {
            h = Length.pt(Dom.attr(e, Ns.FO, "min-height"), 0);
        }
        double x = Length.pt(Dom.attr(e, Ns.SVG, "x"), 0);
        double y = Length.pt(Dom.attr(e, Ns.SVG, "y"), 0);
        String t = Dom.attr(e, Ns.DRAW, "transform");
        double rot = 0;
        if (t != null) {
            double angle = 0;
            double tx = Double.NaN;
            double ty = Double.NaN;
            String s = t.toLowerCase(Locale.ROOT);
            int i = 0;
            while (i < s.length()) {
                int open = s.indexOf('(', i);
                int close = open < 0 ? -1 : s.indexOf(')', open);
                if (open < 0 || close < 0) {
                    break;
                }
                String name = s.substring(i, open).trim();
                String[] args = s.substring(open + 1, close).trim().split("[\\s,]+");
                switch (name) {
                    case "rotate" -> angle += number(args, 0);
                    case "translate" -> {
                        tx = Length.pt(args[0], 0);
                        ty = args.length > 1 ? Length.pt(args[1], 0) : 0;
                    }
                    default -> {
                    }
                }
                i = close + 1;
            }
            if (!Double.isNaN(tx)) {
                double cos = Math.cos(-angle);
                double sin = Math.sin(-angle);
                double cx = tx + (w / 2) * cos - (h / 2) * sin;
                double cy = ty + (w / 2) * sin + (h / 2) * cos;
                x = cx - w / 2;
                y = cy - h / 2;
            }
            rot = Math.toDegrees(-angle);
            rot = ((rot % 360) + 360) % 360;
        }
        return new Box(x, y, Double.isFinite(w) ? w : 0, Double.isFinite(h) ? h : 0, rot);
    }

    private static double number(String[] args, int i) {
        if (i >= args.length) {
            return 0;
        }
        try {
            return Double.parseDouble(args[i]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

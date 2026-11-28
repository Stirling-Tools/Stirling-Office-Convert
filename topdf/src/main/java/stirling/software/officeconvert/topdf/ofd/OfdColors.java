package stirling.software.officeconvert.topdf.ofd;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.io.Dom;
import stirling.software.officeconvert.topdf.pdf.Gradient;

final class OfdColors {

    private OfdColors() {}

    static int[] ints(String s) {
        if (s == null || s.isBlank()) {
            return new int[0];
        }
        String[] parts = s.trim().split("[\\s,]+");
        int[] out = new int[Math.min(parts.length, 16)];
        for (int i = 0; i < out.length; i++) {
            String p = parts[i];
            try {
                out[i] = p.startsWith("#") ? Integer.parseInt(p.substring(1), 16)
                        : (int) Math.round(Double.parseDouble(p));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    static Color color(Element c, OfdResources res, OfdResources.ColorSpace fallback, Color absent) {
        if (c == null) {
            return absent;
        }
        OfdResources.ColorSpace cs = c.hasAttribute("ColorSpace") ? res.colorSpace(c.getAttribute("ColorSpace")) : null;
        if (cs == null) {
            cs = fallback;
        }
        int[] v;
        if (c.hasAttribute("Value")) {
            v = ints(c.getAttribute("Value"));
        } else if (c.hasAttribute("Index") && cs != null && !cs.palette().isEmpty()) {
            int index = OfdNumbers.integer(c.getAttribute("Index"), 0);
            v = index >= 0 && index < cs.palette().size() ? cs.palette().get(index) : new int[0];
        } else {
            v = new int[] {0, 0, 0};
        }
        int alpha = Math.clamp(OfdNumbers.integer(c.getAttribute("Alpha"), 255), 0, 255);
        return of(v, cs, alpha);
    }

    static Color of(int[] v, OfdResources.ColorSpace cs, int alpha) {
        String type = cs == null ? null : cs.type().toUpperCase(Locale.ROOT);
        int bits = cs == null ? 8 : Math.clamp(cs.bits(), 1, 16);
        double max = (1 << bits) - 1;
        if (type == null) {
            type = v.length == 1 ? "GRAY" : v.length >= 4 ? "CMYK" : "RGB";
        }
        if (type.equals("GRAY") || v.length < 3 && !type.equals("CMYK")) {
            int g = v.length > 0 ? scale(v[0], max) : 0;
            return new Color(g, g, g, alpha);
        }
        if (type.equals("CMYK")) {
            double c = v.length > 0 ? unit(v[0], max) : 0;
            double m = v.length > 1 ? unit(v[1], max) : 0;
            double y = v.length > 2 ? unit(v[2], max) : 0;
            double k = v.length > 3 ? unit(v[3], max) : 0;
            return new Color((int) Math.round(255 * (1 - c) * (1 - k)), (int) Math.round(255 * (1 - m) * (1 - k)),
                    (int) Math.round(255 * (1 - y) * (1 - k)), alpha);
        }
        return new Color(scale(v[0], max), scale(v[1], max), scale(v[2], max), alpha);
    }

    private static double unit(int v, double max) {
        return Math.clamp(v / max, 0, 1);
    }

    private static int scale(int v, double max) {
        return (int) Math.round(unit(v, max) * 255);
    }

    static Gradient shading(Element c, OfdResources res, OfdResources.ColorSpace fallback, int alpha) {
        if (c == null) {
            return null;
        }
        Element axial = Dom.kid(c, "AxialShd");
        Element radial = Dom.kid(c, "RadialShd");
        Element shd = axial != null ? axial : radial;
        if (shd == null) {
            return null;
        }
        List<Gradient.Stop> stops = new ArrayList<>();
        List<Element> segments = Dom.kids(shd, "Segment");
        double[] positions = positions(segments);
        for (int i = 0; i < segments.size(); i++) {
            Element s = segments.get(i);
            double pos = positions[i];
            Color col = color(Dom.kid(s, "Color"), res, fallback, Color.BLACK);
            if (alpha < 255) {
                col = new Color(col.getRed(), col.getGreen(), col.getBlue(), col.getAlpha() * alpha / 255);
            }
            stops.add(new Gradient.Stop((float) Math.clamp(pos, 0, 1), col));
        }
        if (stops.isEmpty()) {
            return null;
        }
        double[] a = OfdNumbers.list(shd.getAttribute("StartPoint"));
        double[] b = OfdNumbers.list(shd.getAttribute("EndPoint"));
        if (a.length < 2 || b.length < 2) {
            return null;
        }
        float[] p = {(float) a[0], (float) a[1], (float) b[0], (float) b[1]};
        for (float f : p) {
            if (!Float.isFinite(f)) {
                return null;
            }
        }
        if (axial != null) {
            return Gradient.linear(p[0], p[1], p[2], p[3], stops);
        }
        float r = (float) OfdNumbers.number(shd.getAttribute("EndRadius"), 0);
        if (!(r > 0) || !Float.isFinite(r)) {
            return null;
        }
        return Gradient.radial(p[0], p[1], p[2], p[3], r, stops);
    }

    private static double[] positions(List<Element> segments) {
        int n = segments.size();
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double v = OfdNumbers.number(segments.get(i).getAttribute("Position"), Double.NaN);
            out[i] = Double.isNaN(v) ? Double.NaN : Math.clamp(v, 0, 1);
        }
        if (n == 0) {
            return out;
        }
        if (Double.isNaN(out[0])) {
            out[0] = 0;
        }
        if (n > 1 && Double.isNaN(out[n - 1])) {
            out[n - 1] = 1;
        }
        int last = 0;
        for (int i = 1; i < n; i++) {
            if (Double.isNaN(out[i])) {
                continue;
            }
            for (int k = last + 1; k < i; k++) {
                out[k] = out[last] + (out[i] - out[last]) * (k - last) / (i - last);
            }
            last = i;
        }
        return out;
    }
}

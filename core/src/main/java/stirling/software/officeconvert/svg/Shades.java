package stirling.software.officeconvert.svg;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.graphics.color.PDColorSpace;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType2;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShadingType3;

final class Shades {

    private static final int SAMPLES = 96;

    private static final int MAX_STOPS = 96;

    private Shades() {}

    record Stop(double offset, int rgb, double opacity) {}

    static String gradient(PDShading shading, AffineTransform toSvg, Rectangle2D area, String id) {
        try {
            if (shading instanceof PDShadingType3 radial) {
                return radial(radial, toSvg, area, id);
            }
            if (shading instanceof PDShadingType2 axial && shading.getShadingType() == PDShading.SHADING_TYPE2) {
                return axial(axial, toSvg, area, id);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    private static String axial(PDShadingType2 s, AffineTransform toSvg, Rectangle2D area, String id)
            throws IOException {
        float[] c = floats(s.getCoords(), 4);
        if (c == null) {
            return null;
        }
        double dx = c[2] - c[0];
        double dy = c[3] - c[1];
        double len2 = dx * dx + dy * dy;
        if (!(len2 > 1e-12)) {
            return null;
        }
        boolean[] extend = extend(s.getExtend());
        double lo = 0;
        double hi = 1;
        Point2D[] corners = corners(area, toSvg);
        if (corners == null) {
            return null;
        }
        for (Point2D p : corners) {
            double u = ((p.getX() - c[0]) * dx + (p.getY() - c[1]) * dy) / len2;
            lo = Math.min(lo, u);
            hi = Math.max(hi, u);
        }
        lo = Math.max(lo, -1000);
        hi = Math.min(hi, 1000);
        List<Stop> stops = sample(s, s.getColorSpace());
        if (stops == null) {
            return null;
        }
        List<Stop> mapped = remap(stops, lo, hi, extend);
        StringBuilder sb = new StringBuilder(256);
        sb.append("<linearGradient id=\"").append(id).append("\" gradientUnits=\"userSpaceOnUse\" x1=\"");
        Num.append(sb, c[0] + lo * dx, 4);
        sb.append("\" y1=\"");
        Num.append(sb, c[1] + lo * dy, 4);
        sb.append("\" x2=\"");
        Num.append(sb, c[0] + hi * dx, 4);
        sb.append("\" y2=\"");
        Num.append(sb, c[1] + hi * dy, 4);
        sb.append("\" gradientTransform=\"");
        Num.matrix(sb, toSvg);
        sb.append("\">");
        stops(sb, mapped);
        sb.append("</linearGradient>");
        return sb.toString();
    }

    private static String radial(PDShadingType3 s, AffineTransform toSvg, Rectangle2D area, String id)
            throws IOException {
        float[] c = floats(s.getCoords(), 6);
        if (c == null || c[2] < 0 || c[5] < 0) {
            return null;
        }
        boolean[] extend = extend(s.getExtend());
        List<Stop> stops = sample(s, s.getColorSpace());
        if (stops == null) {
            return null;
        }
        double x0 = c[0];
        double y0 = c[1];
        double r0 = c[2];
        double x1 = c[3];
        double y1 = c[4];
        double r1 = c[5];
        if (r1 < r0) {
            double t;
            t = x0; x0 = x1; x1 = t;
            t = y0; y0 = y1; y1 = t;
            t = r0; r0 = r1; r1 = t;
            List<Stop> reversed = new ArrayList<>();
            for (int i = stops.size() - 1; i >= 0; i--) {
                Stop st = stops.get(i);
                reversed.add(new Stop(1 - st.offset(), st.rgb(), st.opacity()));
            }
            stops = reversed;
            extend = new boolean[] {extend[1], extend[0]};
        }
        double d = Math.hypot(x1 - x0, y1 - y0);
        if (!(r1 > 0) || d + r0 > r1 + 1e-6 * Math.max(1, r1)) {
            return null;
        }
        double hi = 1;
        if (!extend[1]) {
            Point2D[] corners = corners(area, toSvg);
            if (corners == null) {
                return null;
            }
            double far = 0;
            for (Point2D p : corners) {
                far = Math.max(far, Math.hypot(p.getX() - x1, p.getY() - y1));
            }
            if (far > r1 && d < 1e-9) {
                hi = Math.min(1000, (far - r0) / (r1 - r0) * 1.01);
            }
        }
        List<Stop> mapped = new ArrayList<>();
        if (!extend[0] && r0 > 0) {
            mapped.add(new Stop(0, stops.get(0).rgb(), 0));
            mapped.add(new Stop(1e-4, stops.get(0).rgb(), stops.get(0).opacity()));
        }
        for (Stop st : stops) {
            mapped.add(new Stop(st.offset() / hi, st.rgb(), st.opacity()));
        }
        if (hi > 1 || !extend[1]) {
            Stop last = stops.get(stops.size() - 1);
            double end = 1 / hi;
            if (hi > 1) {
                mapped.add(new Stop(Math.min(1, end + 1e-4), last.rgb(), 0));
                mapped.add(new Stop(1, last.rgb(), 0));
            }
        }
        double cx = x0 + (x1 - x0) * hi;
        double cy = y0 + (y1 - y0) * hi;
        double r = r0 + (r1 - r0) * hi;
        StringBuilder sb = new StringBuilder(256);
        sb.append("<radialGradient id=\"").append(id).append("\" gradientUnits=\"userSpaceOnUse\" cx=\"");
        Num.append(sb, cx, 4);
        sb.append("\" cy=\"");
        Num.append(sb, cy, 4);
        sb.append("\" r=\"");
        Num.append(sb, r, 4);
        sb.append("\" fx=\"");
        Num.append(sb, x0, 4);
        sb.append("\" fy=\"");
        Num.append(sb, y0, 4);
        if (r0 > 0) {
            sb.append("\" fr=\"");
            Num.append(sb, r0, 4);
        }
        sb.append("\" gradientTransform=\"");
        Num.matrix(sb, toSvg);
        sb.append("\">");
        stops(sb, mapped);
        sb.append("</radialGradient>");
        return sb.toString();
    }

    private static List<Stop> remap(List<Stop> stops, double lo, double hi, boolean[] extend) {
        double span = hi - lo;
        List<Stop> out = new ArrayList<>();
        Stop first = stops.get(0);
        Stop last = stops.get(stops.size() - 1);
        double start = -lo / span;
        double end = (1 - lo) / span;
        if (lo < 0 && !extend[0]) {
            out.add(new Stop(0, first.rgb(), 0));
            out.add(new Stop(Math.max(0, start - 1e-4), first.rgb(), 0));
        }
        for (Stop s : stops) {
            out.add(new Stop((s.offset() - lo) / span, s.rgb(), s.opacity()));
        }
        if (hi > 1 && !extend[1]) {
            out.add(new Stop(Math.min(1, end + 1e-4), last.rgb(), 0));
            out.add(new Stop(1, last.rgb(), 0));
        }
        return out;
    }

    private static void stops(StringBuilder sb, List<Stop> stops) {
        double previous = 0;
        for (Stop s : stops) {
            double o = Math.max(previous, Math.min(1, s.offset()));
            previous = o;
            sb.append("<stop offset=\"");
            Num.append(sb, o, 5);
            sb.append("\" stop-color=\"");
            Num.hex(sb, s.rgb());
            sb.append('"');
            if (s.opacity() < 1) {
                sb.append(" stop-opacity=\"");
                Num.append(sb, s.opacity(), 3);
                sb.append('"');
            }
            sb.append("/>");
        }
    }

    static List<Stop> sample(PDShadingType2 s, PDColorSpace cs) throws IOException {
        float t0 = 0;
        float t1 = 1;
        COSArray domain = s.getDomain();
        if (domain != null && domain.size() >= 2 && domain.get(0) instanceof COSNumber a
                && domain.get(1) instanceof COSNumber b) {
            t0 = a.floatValue();
            t1 = b.floatValue();
        }
        if (cs == null) {
            return null;
        }
        int[] rgb = new int[SAMPLES + 1];
        for (int i = 0; i <= SAMPLES; i++) {
            float t = t0 + (t1 - t0) * i / SAMPLES;
            float[] v = s.evalFunction(t);
            float[] c = cs.toRGB(v);
            rgb[i] = channel(c[0]) << 16 | channel(c[1]) << 8 | channel(c[2]);
        }
        List<Integer> keep = new ArrayList<>();
        keep.add(0);
        int anchor = 0;
        for (int i = 2; i <= SAMPLES; i++) {
            boolean fits = true;
            for (int k = anchor + 1; k < i && fits; k++) {
                fits = close(rgb[anchor], rgb[i], (double) (k - anchor) / (i - anchor), rgb[k]);
            }
            if (!fits) {
                anchor = i - 1;
                keep.add(anchor);
            }
        }
        keep.add(SAMPLES);
        while (keep.size() > MAX_STOPS) {
            List<Integer> thin = new ArrayList<>();
            for (int i = 0; i < keep.size(); i += 2) {
                thin.add(keep.get(i));
            }
            if (thin.get(thin.size() - 1) != SAMPLES) {
                thin.add(SAMPLES);
            }
            keep = thin;
        }
        List<Stop> stops = new ArrayList<>();
        for (int i : keep) {
            stops.add(new Stop((double) i / SAMPLES, rgb[i], 1));
        }
        return stops;
    }

    private static boolean close(int a, int b, double f, int actual) {
        for (int shift = 0; shift <= 16; shift += 8) {
            double ca = (a >> shift) & 0xFF;
            double cb = (b >> shift) & 0xFF;
            double expect = ca + (cb - ca) * f;
            if (Math.abs(expect - ((actual >> shift) & 0xFF)) > 2.5) {
                return false;
            }
        }
        return true;
    }

    private static int channel(float v) {
        return Float.isNaN(v) ? 0 : Math.round(Math.max(0, Math.min(1, v)) * 255);
    }

    private static boolean[] extend(COSArray a) {
        boolean[] e = {false, false};
        if (a != null && a.size() >= 2) {
            e[0] = a.get(0) instanceof COSBoolean b && b.getValue();
            e[1] = a.get(1) instanceof COSBoolean b && b.getValue();
        }
        return e;
    }

    private static float[] floats(COSArray a, int n) {
        if (a == null || a.size() < n) {
            return null;
        }
        float[] f = new float[n];
        for (int i = 0; i < n; i++) {
            if (!(a.get(i) instanceof COSNumber num) || !Float.isFinite(num.floatValue())) {
                return null;
            }
            f[i] = num.floatValue();
        }
        return f;
    }

    private static Point2D[] corners(Rectangle2D area, AffineTransform toSvg) {
        try {
            AffineTransform inv = toSvg.createInverse();
            return new Point2D[] {
                inv.transform(new Point2D.Double(area.getMinX(), area.getMinY()), null),
                inv.transform(new Point2D.Double(area.getMaxX(), area.getMinY()), null),
                inv.transform(new Point2D.Double(area.getMinX(), area.getMaxY()), null),
                inv.transform(new Point2D.Double(area.getMaxX(), area.getMaxY()), null)};
        } catch (NoninvertibleTransformException e) {
            return null;
        }
    }
}

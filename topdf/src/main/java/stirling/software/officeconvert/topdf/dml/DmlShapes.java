package stirling.software.officeconvert.topdf.dml;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.poi.sl.draw.geom.Context;
import org.apache.poi.sl.draw.geom.CustomGeometry;
import org.apache.poi.sl.draw.geom.Guide;
import org.apache.poi.sl.draw.geom.PathIf;
import org.apache.poi.sl.draw.geom.PresetGeometries;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** DrawingML preset and custom shape outlines, shared by the renderers that draw shapes themselves. */
public final class DmlShapes {

    /** One path of a shape: its outline in page coordinates and whether it is filled and stroked. */
    public record Outline(Shape shape, boolean filled, boolean stroked) {}

    private static final double EMU = 12_700;

    private DmlShapes() {}

    /** The outlines of a preset shape in the box, with {@code a:avLst} values by guide name; null if unknown. */
    public static List<Outline> preset(String name, Map<String, Long> adjust, Rectangle2D box) {
        if (name == null) {
            return null;
        }
        CustomGeometry geom;
        try {
            geom = PresetGeometries.getInstance().get(name);
        } catch (RuntimeException e) {
            return null;
        }
        if (geom == null) {
            return null;
        }
        List<Outline> out = new ArrayList<>();
        try {
            for (PathIf p : geom) {
                Shape s = path(geom, p, adjust, box);
                if (s != null) {
                    out.add(new Outline(s, p.isFilled(), p.isStroked()));
                }
            }
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
        return out.isEmpty() ? null : out;
    }

    private static Shape path(CustomGeometry geom, PathIf p, Map<String, Long> adjust, Rectangle2D box) {
        double w = p.getW();
        double h = p.getH();
        double sx;
        double sy;
        if (w == -1) {
            w = box.getWidth() * EMU;
            sx = 1 / EMU;
        } else {
            sx = box.getWidth() == 0 || w == 0 ? 1 : box.getWidth() / w;
        }
        if (h == -1) {
            h = box.getHeight() * EMU;
            sy = 1 / EMU;
        } else {
            sy = box.getHeight() == 0 || h == 0 ? 1 : box.getHeight() / h;
        }
        Context ctx = new Context(geom, new Rectangle2D.Double(0, 0, w, h), guide -> {
            Long v = adjust == null ? null : adjust.get(guide);
            if (v == null) {
                return null;
            }
            Guide g = new Guide();
            g.setName(guide);
            g.setFmla("val " + v);
            return g;
        });
        Path2D gp = p.getPath(ctx);
        if (gp == null) {
            return null;
        }
        AffineTransform at = AffineTransform.getTranslateInstance(box.getX(), box.getY());
        at.scale(sx, sy);
        Shape s = at.createTransformedShape(gp);
        return finite(s) ? s : null;
    }

    /** The outlines of an {@code a:custGeom} element in the box, from its literal path points; null if empty. */
    public static List<Outline> custom(Element custGeom, Rectangle2D box) {
        Element pathList = child(custGeom, "pathLst");
        if (pathList == null) {
            return null;
        }
        List<Outline> out = new ArrayList<>();
        for (Element path : children(pathList, "path")) {
            double pw = number(path, "w", 0);
            double ph = number(path, "h", 0);
            double sx = pw > 0 ? box.getWidth() / pw : 1 / EMU;
            double sy = ph > 0 ? box.getHeight() / ph : 1 / EMU;
            Path2D.Double p = new Path2D.Double();
            Point2D.Double at = new Point2D.Double(0, 0);
            boolean started = false;
            for (Element cmd : children(path, null)) {
                List<Element> pts = children(cmd, "pt");
                switch (cmd.getLocalName()) {
                    case "moveTo" -> {
                        if (!pts.isEmpty()) {
                            at = point(pts.get(0), box, sx, sy);
                            p.moveTo(at.x, at.y);
                            started = true;
                        }
                    }
                    case "lnTo" -> {
                        if (!pts.isEmpty() && started) {
                            at = point(pts.get(0), box, sx, sy);
                            p.lineTo(at.x, at.y);
                        }
                    }
                    case "cubicBezTo" -> {
                        if (pts.size() >= 3 && started) {
                            Point2D.Double a = point(pts.get(0), box, sx, sy);
                            Point2D.Double b = point(pts.get(1), box, sx, sy);
                            at = point(pts.get(2), box, sx, sy);
                            p.curveTo(a.x, a.y, b.x, b.y, at.x, at.y);
                        }
                    }
                    case "quadBezTo" -> {
                        if (pts.size() >= 2 && started) {
                            Point2D.Double a = point(pts.get(0), box, sx, sy);
                            at = point(pts.get(1), box, sx, sy);
                            p.quadTo(a.x, a.y, at.x, at.y);
                        }
                    }
                    case "arcTo" -> {
                        if (started) {
                            at = arc(p, at, cmd, sx, sy);
                        }
                    }
                    case "close" -> {
                        if (started) {
                            p.closePath();
                        }
                    }
                    default -> {
                    }
                }
            }
            if (started && finite(p)) {
                String stroke = path.getAttribute("stroke");
                boolean stroked = !"0".equals(stroke) && !"false".equals(stroke);
                out.add(new Outline(p, !"none".equals(path.getAttribute("fill")), stroked));
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static Point2D.Double arc(Path2D.Double p, Point2D.Double from, Element cmd, double sx, double sy) {
        double wr = number(cmd, "wR", 0) * sx;
        double hr = number(cmd, "hR", 0) * sy;
        double start = number(cmd, "stAng", 0) / 60000.0;
        double swing = number(cmd, "swAng", 0) / 60000.0;
        double rad = Math.toRadians(start);
        double cx = from.x - wr * Math.cos(rad);
        double cy = from.y - hr * Math.sin(rad);
        Arc2D.Double a = new Arc2D.Double(cx - wr, cy - hr, 2 * wr, 2 * hr, -start, -swing, Arc2D.OPEN);
        p.append(a, true);
        double end = Math.toRadians(start + swing);
        return new Point2D.Double(cx + wr * Math.cos(end), cy + hr * Math.sin(end));
    }

    private static Point2D.Double point(Element pt, Rectangle2D box, double sx, double sy) {
        return new Point2D.Double(box.getX() + number(pt, "x", 0) * sx, box.getY() + number(pt, "y", 0) * sy);
    }

    private static double number(Element e, String attr, double fallback) {
        String v = e.getAttribute(attr);
        if (v == null || v.isEmpty()) {
            return fallback;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static Element child(Element e, String local) {
        List<Element> k = children(e, local);
        return k.isEmpty() ? null : k.get(0);
    }

    private static List<Element> children(Element e, String local) {
        List<Element> out = new ArrayList<>();
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && (local == null || local.equals(c.getLocalName()))) {
                out.add(c);
            }
        }
        return out;
    }

    private static boolean finite(Shape s) {
        PathIterator it = s.getPathIterator(null);
        double[] c = new double[6];
        while (!it.isDone()) {
            int n = switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO, PathIterator.SEG_LINETO -> 2;
                case PathIterator.SEG_QUADTO -> 4;
                case PathIterator.SEG_CUBICTO -> 6;
                default -> 0;
            };
            for (int i = 0; i < n; i++) {
                if (!Double.isFinite(c[i]) || Math.abs(c[i]) > 1e7) {
                    return false;
                }
            }
            it.next();
        }
        return true;
    }
}

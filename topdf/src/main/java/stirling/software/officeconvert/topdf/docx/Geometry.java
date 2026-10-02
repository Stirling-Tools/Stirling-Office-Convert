package stirling.software.officeconvert.topdf.docx;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.topdf.dml.DmlShapes;

final class Geometry {

    private Geometry() {}

    static boolean isLine(String prst) {
        return prst != null && (prst.equals("line") || prst.startsWith("straightConnector")
                || prst.startsWith("bentConnector") || prst.startsWith("curvedConnector") || prst.equals("lineInv"));
    }

    static Shape shape(String prst, XEl geom, float x, float y, float w, float h, boolean flipH, boolean flipV) {
        if (prst == null) {
            return new Rectangle2D.Float(x, y, w, h);
        }
        if (isLine(prst)) {
            float x1 = flipH ? x + w : x;
            float x2 = flipH ? x : x + w;
            float y1 = flipV ? y + h : y;
            float y2 = flipV ? y : y + h;
            if (prst.equals("lineInv")) {
                return new Line2D.Float(x1, y2, x2, y1);
            }
            return new Line2D.Float(x1, y1, x2, y2);
        }
        float m = Math.min(w, h);
        return switch (prst) {
            case "rect", "flowChartProcess", "frame" -> new Rectangle2D.Float(x, y, w, h);
            case "roundRect", "flowChartAlternateProcess" -> {
                float a = adj(geom, "adj", 16667) * m;
                yield new RoundRectangle2D.Float(x, y, w, h, 2 * a, 2 * a);
            }
            case "ellipse", "flowChartConnector" -> new Ellipse2D.Float(x, y, w, h);
            case "triangle", "flowChartExtract" -> {
                float a = adj(geom, "adj", 50000);
                yield poly(x, y, w, h, flipH, flipV, a, 0, 1, 1, 0, 1);
            }
            case "rtTriangle" -> poly(x, y, w, h, flipH, flipV, 0, 0, 1, 1, 0, 1);
            case "diamond", "flowChartDecision" -> poly(x, y, w, h, flipH, flipV, 0.5f, 0, 1, 0.5f, 0.5f, 1, 0, 0.5f);
            case "parallelogram", "flowChartInputOutput" -> {
                float a = adj(geom, "adj", 25000) * m / Math.max(1, w);
                yield poly(x, y, w, h, flipH, flipV, a, 0, 1, 0, 1 - a, 1, 0, 1);
            }
            case "trapezoid", "flowChartManualOperation" -> {
                float a = adj(geom, "adj", 25000) * m / Math.max(1, w);
                yield poly(x, y, w, h, flipH, flipV, 0, 1, a, 0, 1 - a, 0, 1, 1);
            }
            case "pentagon", "homePlate" -> {
                float a = adj(geom, "adj", 50000) * m / Math.max(1, w);
                yield poly(x, y, w, h, flipH, flipV, 0, 0, 1 - a, 0, 1, 0.5f, 1 - a, 1, 0, 1);
            }
            case "chevron" -> {
                float a = adj(geom, "adj", 50000) * m / Math.max(1, w);
                yield poly(x, y, w, h, flipH, flipV, 0, 0, 1 - a, 0, 1, 0.5f, 1 - a, 1, 0, 1, a, 0.5f);
            }
            case "hexagon" -> {
                float a = adj(geom, "adj", 25000) * m / Math.max(1, w);
                yield poly(x, y, w, h, flipH, flipV, a, 0, 1 - a, 0, 1, 0.5f, 1 - a, 1, a, 1, 0, 0.5f);
            }
            case "octagon" -> {
                float a = adj(geom, "adj", 29289) * m;
                float ax = a / Math.max(1, w);
                float ay = a / Math.max(1, h);
                yield poly(x, y, w, h, flipH, flipV, ax, 0, 1 - ax, 0, 1, ay, 1, 1 - ay, 1 - ax, 1, ax, 1, 0, 1 - ay, 0,
                        ay);
            }
            case "rightArrow", "leftArrow" -> {
                float a1 = adj(geom, "adj1", 50000);
                float a2 = adj(geom, "adj2", 50000) * m / Math.max(1, w);
                float t = (1 - a1) / 2;
                boolean left = prst.equals("leftArrow");
                yield poly(x, y, w, h, flipH ^ left, flipV, 0, t, 1 - a2, t, 1 - a2, 0, 1, 0.5f, 1 - a2, 1, 1 - a2, 1 - t,
                        0, 1 - t);
            }
            case "upArrow", "downArrow" -> {
                float a1 = adj(geom, "adj1", 50000);
                float a2 = adj(geom, "adj2", 50000) * m / Math.max(1, h);
                float t = (1 - a1) / 2;
                boolean up = prst.equals("upArrow");
                yield poly(x, y, w, h, flipH, flipV ^ up, t, 0, 1 - t, 0, 1 - t, 1 - a2, 1, 1 - a2, 0.5f, 1, 0, 1 - a2,
                        t, 1 - a2);
            }
            case "plus", "flowChartPredefinedProcess" -> {
                if (prst.equals("plus")) {
                    float a = adj(geom, "adj", 25000) * m;
                    float ax = a / Math.max(1, w);
                    float ay = a / Math.max(1, h);
                    yield poly(x, y, w, h, false, false, ax, 0, 1 - ax, 0, 1 - ax, ay, 1, ay, 1, 1 - ay, 1 - ax, 1 - ay,
                            1 - ax, 1, ax, 1, ax, 1 - ay, 0, 1 - ay, 0, ay, ax, ay);
                }
                yield new Rectangle2D.Float(x, y, w, h);
            }
            case "custom" -> custom(geom, x, y, w, h, flipH, flipV);
            case "vmlpath" -> vmlPath(geom, x, y, w, h, flipH, flipV);
            default -> preset(prst, geom, x, y, w, h, flipH, flipV);
        };
    }

    private static Shape vmlPath(XEl shape, float x, float y, float w, float h, boolean flipH, boolean flipV) {
        Path2D.Float p = shape == null ? null : ShapePath.vml(shape.attr("path"),
                ShapePath.pair(shape.attr("coordsize"), 1000, 1000), ShapePath.pair(shape.attr("coordorigin"), 0, 0),
                x, y, w, h);
        if (p == null) {
            return new Rectangle2D.Float(x, y, w, h);
        }
        if (flipH || flipV) {
            AffineTransform t = AffineTransform.getTranslateInstance(x + w / 2, y + h / 2);
            t.scale(flipH ? -1 : 1, flipV ? -1 : 1);
            t.translate(-(x + w / 2), -(y + h / 2));
            return t.createTransformedShape(p);
        }
        return p;
    }

    // Every other preset comes from the shared DrawingML definitions instead of becoming a rectangle
    private static Shape preset(String prst, XEl geom, float x, float y, float w, float h, boolean flipH,
            boolean flipV) {
        Map<String, Long> adjust = new HashMap<>();
        XEl av = geom == null ? null : geom.child("a:avLst");
        if (av != null) {
            for (XEl g : av.children("a:gd")) {
                String f = g.attr("fmla", "");
                Integer v = f.startsWith("val ") ? Ooxml.integer(f.substring(4)) : null;
                if (v != null && g.attr("name") != null) {
                    adjust.put(g.attr("name"), v.longValue());
                }
            }
        }
        List<DmlShapes.Outline> outlines = DmlShapes.preset(prst, adjust, new Rectangle2D.Float(x, y, w, h));
        if (outlines == null) {
            return new Rectangle2D.Float(x, y, w, h);
        }
        Path2D.Float out = new Path2D.Float();
        boolean anyFilled = outlines.stream().anyMatch(DmlShapes.Outline::filled);
        for (DmlShapes.Outline o : outlines) {
            if (o.filled() || !anyFilled) {
                out.append(o.shape(), false);
            }
        }
        if (flipH || flipV) {
            AffineTransform t = AffineTransform.getTranslateInstance(x + w / 2, y + h / 2);
            t.scale(flipH ? -1 : 1, flipV ? -1 : 1);
            t.translate(-(x + w / 2), -(y + h / 2));
            return t.createTransformedShape(out);
        }
        return out;
    }

    private static float adj(XEl geom, String name, int fallback) {
        if (geom != null) {
            XEl av = geom.child("a:avLst");
            if (av != null) {
                for (XEl g : av.children("a:gd")) {
                    if (name.equals(g.attr("name"))) {
                        String f = g.attr("fmla", "");
                        if (f.startsWith("val ")) {
                            Integer v = Ooxml.integer(f.substring(4));
                            if (v != null) {
                                return Math.max(0, Math.min(100000, v)) / 100000f;
                            }
                        }
                    }
                }
            }
        }
        return fallback / 100000f;
    }

    private static Shape poly(float x, float y, float w, float h, boolean flipH, boolean flipV, float... pts) {
        Path2D.Float p = new Path2D.Float();
        for (int i = 0; i + 1 < pts.length; i += 2) {
            float px = flipH ? 1 - pts[i] : pts[i];
            float py = flipV ? 1 - pts[i + 1] : pts[i + 1];
            if (i == 0) {
                p.moveTo(x + px * w, y + py * h);
            } else {
                p.lineTo(x + px * w, y + py * h);
            }
        }
        p.closePath();
        return p;
    }

    private static Shape custom(XEl geom, float x, float y, float w, float h, boolean flipH, boolean flipV) {
        if (geom == null) {
            return new Rectangle2D.Float(x, y, w, h);
        }
        XEl list = geom.child("a:pathLst");
        if (list == null) {
            return new Rectangle2D.Float(x, y, w, h);
        }
        Path2D.Float out = new Path2D.Float();
        int commands = 0;
        for (XEl path : list.children("a:path")) {
            float pw = Ooxml.longValue(path.attr("w"), 0);
            float ph = Ooxml.longValue(path.attr("h"), 0);
            float sx = pw > 0 ? w / pw : 1f / 12700f;
            float sy = ph > 0 ? h / ph : 1f / 12700f;
            Point2D.Float cur = new Point2D.Float(0, 0);
            for (XEl c : path.kids) {
                if (++commands > 20_000) {
                    return out;
                }
                switch (c.name) {
                    case "a:moveTo" -> {
                        Point2D.Float p = point(c.child("a:pt"), sx, sy);
                        out.moveTo(map(p.x, x, w, flipH), map(p.y, y, h, flipV));
                        cur = p;
                    }
                    case "a:lnTo" -> {
                        Point2D.Float p = point(c.child("a:pt"), sx, sy);
                        lineOrMove(out, map(p.x, x, w, flipH), map(p.y, y, h, flipV));
                        cur = p;
                    }
                    case "a:cubicBezTo" -> {
                        var pts = c.children("a:pt");
                        if (pts.size() == 3) {
                            Point2D.Float a = point(pts.get(0), sx, sy);
                            Point2D.Float b = point(pts.get(1), sx, sy);
                            Point2D.Float e = point(pts.get(2), sx, sy);
                            ensureStarted(out, map(cur.x, x, w, flipH), map(cur.y, y, h, flipV));
                            out.curveTo(map(a.x, x, w, flipH), map(a.y, y, h, flipV), map(b.x, x, w, flipH),
                                    map(b.y, y, h, flipV), map(e.x, x, w, flipH), map(e.y, y, h, flipV));
                            cur = e;
                        }
                    }
                    case "a:quadBezTo" -> {
                        var pts = c.children("a:pt");
                        if (pts.size() == 2) {
                            Point2D.Float a = point(pts.get(0), sx, sy);
                            Point2D.Float e = point(pts.get(1), sx, sy);
                            ensureStarted(out, map(cur.x, x, w, flipH), map(cur.y, y, h, flipV));
                            out.quadTo(map(a.x, x, w, flipH), map(a.y, y, h, flipV), map(e.x, x, w, flipH),
                                    map(e.y, y, h, flipV));
                            cur = e;
                        }
                    }
                    case "a:arcTo" -> {
                        float wr = Ooxml.longValue(c.attr("wR"), 0) * sx;
                        float hr = Ooxml.longValue(c.attr("hR"), 0) * sy;
                        float st = Ooxml.longValue(c.attr("stAng"), 0) / 60000f;
                        float sw = Ooxml.longValue(c.attr("swAng"), 0) / 60000f;
                        double sr = Math.toRadians(st);
                        float cx = (float) (cur.x - wr * Math.cos(sr));
                        float cy = (float) (cur.y - hr * Math.sin(sr));
                        if (wr > 0 && hr > 0 && !flipH && !flipV) {
                            Arc2D.Float arc = new Arc2D.Float(x + cx - wr, y + cy - hr, 2 * wr, 2 * hr, -st, -sw,
                                    Arc2D.OPEN);
                            out.append(arc, true);
                        }
                        double er = Math.toRadians(st + sw);
                        cur = new Point2D.Float((float) (cx + wr * Math.cos(er)), (float) (cy + hr * Math.sin(er)));
                        if (flipH || flipV) {
                            lineOrMove(out, map(cur.x, x, w, flipH), map(cur.y, y, h, flipV));
                        }
                    }
                    case "a:close" -> {
                        if (out.getCurrentPoint() != null) {
                            out.closePath();
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        return out.getCurrentPoint() == null ? new Rectangle2D.Float(x, y, w, h) : out;
    }

    private static void ensureStarted(Path2D.Float p, float x, float y) {
        if (p.getCurrentPoint() == null) {
            p.moveTo(x, y);
        }
    }

    private static void lineOrMove(Path2D.Float p, float x, float y) {
        if (p.getCurrentPoint() == null) {
            p.moveTo(x, y);
        } else {
            p.lineTo(x, y);
        }
    }

    private static float map(float v, float origin, float size, boolean flip) {
        return flip ? origin + size - v : origin + v;
    }

    private static Point2D.Float point(XEl pt, float sx, float sy) {
        if (pt == null) {
            return new Point2D.Float(0, 0);
        }
        return new Point2D.Float(Ooxml.longValue(pt.attr("x"), 0) * sx, Ooxml.longValue(pt.attr("y"), 0) * sy);
    }
}

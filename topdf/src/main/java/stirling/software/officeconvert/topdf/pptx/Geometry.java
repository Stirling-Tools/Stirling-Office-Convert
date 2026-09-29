package stirling.software.officeconvert.topdf.pptx;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.sl.draw.geom.Context;
import org.apache.poi.sl.draw.geom.CustomGeometry;
import org.apache.poi.sl.draw.geom.Path;
import org.apache.poi.sl.draw.geom.PathIf;
import org.apache.poi.sl.usermodel.PaintStyle.PaintModifier;
import org.apache.poi.xslf.usermodel.XSLFSimpleShape;

final class Geometry {

    record Outline(Shape shape, PaintModifier fill, boolean stroked) {

        boolean filled() {
            return fill != null && fill != PaintModifier.NONE;
        }
    }

    private static final double EMU_PER_POINT = 12_700;

    private Geometry() {}

    static CustomGeometry of(XSLFSimpleShape shape) {
        try {
            return shape.getGeometry();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static List<Outline> outlines(XSLFSimpleShape shape, Rectangle2D box) {
        CustomGeometry geom = of(shape);
        List<Outline> out = new ArrayList<>();
        if (geom != null) {
            try {
                for (PathIf p : geom) {
                    Shape s = path(geom, p, shape, box);
                    if (s != null) {
                        out.add(new Outline(s, p.isFilled() ? p.getFill() : null, p.isStroked()));
                    }
                }
            } catch (RuntimeException e) {
                out.clear();
            }
        }
        if (out.isEmpty()) {
            out.add(new Outline(new Rectangle2D.Double(box.getX(), box.getY(), box.getWidth(), box.getHeight()),
                    PaintModifier.NORM, true));
        }
        return out;
    }

    static Rectangle2D textBox(XSLFSimpleShape shape, Rectangle2D box) {
        CustomGeometry geom = of(shape);
        if (geom == null) {
            return box;
        }
        try {
            Path bounds = geom.getTextBounds();
            if (bounds == null) {
                return box;
            }
            Shape s = path(geom, bounds, shape, box);
            if (s == null) {
                return box;
            }
            Rectangle2D r = s.getBounds2D();
            return r.getWidth() >= 0 && r.getHeight() >= 0 ? r : box;
        } catch (RuntimeException e) {
            return box;
        }
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

    private static Shape path(CustomGeometry geom, PathIf p, XSLFSimpleShape shape, Rectangle2D box) {
        double w = p.getW();
        double h = p.getH();
        double sx;
        double sy;
        if (w == -1) {
            w = box.getWidth() * EMU_PER_POINT;
            sx = 1 / EMU_PER_POINT;
        } else {
            sx = box.getWidth() == 0 || w == 0 ? 1 : box.getWidth() / w;
        }
        if (h == -1) {
            h = box.getHeight() * EMU_PER_POINT;
            sy = 1 / EMU_PER_POINT;
        } else {
            sy = box.getHeight() == 0 || h == 0 ? 1 : box.getHeight() / h;
        }
        Context ctx = new Context(geom, new Rectangle2D.Double(0, 0, w, h), shape);
        Path2D gp = p.getPath(ctx);
        if (gp == null) {
            return null;
        }
        AffineTransform at = new AffineTransform();
        at.translate(box.getX(), box.getY());
        at.scale(sx, sy);
        Shape out = at.createTransformedShape(gp);
        return finite(out) ? out : null;
    }
}

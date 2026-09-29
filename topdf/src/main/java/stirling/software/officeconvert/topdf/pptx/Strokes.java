package stirling.software.officeconvert.topdf.pptx;

import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.io.IOException;

import org.apache.poi.sl.usermodel.LineDecoration;
import org.apache.poi.sl.usermodel.LineDecoration.DecorationShape;
import org.apache.poi.sl.usermodel.LineDecoration.DecorationSize;
import org.apache.poi.sl.usermodel.StrokeStyle;
import org.apache.poi.sl.usermodel.StrokeStyle.LineCap;
import org.apache.poi.sl.usermodel.StrokeStyle.LineDash;

import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.Stroke;

final class Strokes {

    static final float DEFAULT_WIDTH = 0.75f;

    private Strokes() {}

    static Stroke of(StrokeStyle style) {
        if (style == null) {
            return null;
        }
        Color c;
        try {
            c = Paints.solid(style.getPaint());
        } catch (RuntimeException e) {
            c = null;
        }
        if (c == null || c.getAlpha() == 0) {
            return null;
        }
        double w = style.getLineWidth();
        float width = w > 0 && w < 1000 ? (float) w : DEFAULT_WIDTH;
        LineCap cap = style.getLineCap();
        Stroke.Cap sc = cap == LineCap.ROUND ? Stroke.Cap.ROUND : cap == LineCap.SQUARE ? Stroke.Cap.SQUARE
                : Stroke.Cap.BUTT;
        Stroke s = new Stroke(width, c, null, 0, sc, Stroke.Join.ROUND, 10);
        LineDash dash = style.getLineDash();
        if (dash != null && dash.pattern != null) {
            float[] p = new float[dash.pattern.length];
            float unit = Math.max(width, 0.25f);
            for (int i = 0; i < p.length; i++) {
                p[i] = dash.pattern[i] * unit;
            }
            if (sc != Stroke.Cap.BUTT) {
                for (int i = 1; i < p.length; i += 2) {
                    p[i] += unit;
                }
                for (int i = 0; i < p.length; i += 2) {
                    p[i] = Math.max(0.001f, p[i] - unit);
                }
            }
            s = s.dash(0, p);
        }
        return s;
    }

    static void decorations(PdfCanvas canvas, Shape path, LineDecoration deco, Stroke stroke) throws IOException {
        if (deco == null || stroke == null) {
            return;
        }
        double[][] ends = ends(path);
        if (ends == null) {
            return;
        }
        end(canvas, ends[0], deco.getHeadShape(), deco.getHeadWidth(), deco.getHeadLength(), stroke);
        end(canvas, ends[1], deco.getTailShape(), deco.getTailWidth(), deco.getTailLength(), stroke);
    }

    private static void end(PdfCanvas canvas, double[] at, DecorationShape type, DecorationSize width,
            DecorationSize length, Stroke stroke) throws IOException {
        if (type == null || type == DecorationShape.NONE || at == null) {
            return;
        }
        double lw = Math.max(stroke.width(), 1);
        double wid = lw * factor(width);
        double len = lw * factor(length);
        double angle = Math.atan2(at[3], at[2]);
        AffineTransform t = AffineTransform.getTranslateInstance(at[0], at[1]);
        t.rotate(angle);
        Path2D.Double p = new Path2D.Double();
        boolean fill = true;
        switch (type) {
            case TRIANGLE -> {
                p.moveTo(0, 0);
                p.lineTo(-len, -wid / 2);
                p.lineTo(-len, wid / 2);
                p.closePath();
            }
            case STEALTH -> {
                p.moveTo(0, 0);
                p.lineTo(-len, -wid / 2);
                p.lineTo(-len * 0.6, 0);
                p.lineTo(-len, wid / 2);
                p.closePath();
            }
            case DIAMOND -> {
                p.moveTo(len / 2, 0);
                p.lineTo(0, -wid / 2);
                p.lineTo(-len / 2, 0);
                p.lineTo(0, wid / 2);
                p.closePath();
            }
            case OVAL -> p.append(new Ellipse2D.Double(-len / 2, -wid / 2, len, wid), false);
            case ARROW -> {
                p.moveTo(-len, -wid / 2);
                p.lineTo(0, 0);
                p.lineTo(-len, wid / 2);
                fill = false;
            }
            default -> {
                return;
            }
        }
        Shape s = t.createTransformedShape(p);
        if (fill) {
            canvas.draw(s, Fill.solid(stroke.color()), null);
        } else {
            canvas.draw(s, null, stroke.dash(0).join(Stroke.Join.MITER));
        }
    }

    private static double factor(DecorationSize size) {
        if (size == null) {
            return 3;
        }
        return switch (size) {
            case SMALL -> 2;
            case LARGE -> 5;
            default -> 3;
        };
    }

    private static double[][] ends(Shape path) {
        PathIterator it = path.getPathIterator(null);
        double[] c = new double[6];
        double[] first = null;
        double[] firstDir = null;
        double lastX = 0;
        double lastY = 0;
        double[] tail = null;
        while (!it.isDone()) {
            int seg = it.currentSegment(c);
            double ex;
            double ey;
            double px;
            double py;
            switch (seg) {
                case PathIterator.SEG_MOVETO -> {
                    lastX = c[0];
                    lastY = c[1];
                    if (first == null) {
                        first = new double[] {c[0], c[1]};
                    }
                    it.next();
                    continue;
                }
                case PathIterator.SEG_LINETO -> {
                    ex = c[0];
                    ey = c[1];
                    px = c[0];
                    py = c[1];
                }
                case PathIterator.SEG_QUADTO -> {
                    ex = c[2];
                    ey = c[3];
                    px = c[0];
                    py = c[1];
                }
                case PathIterator.SEG_CUBICTO -> {
                    ex = c[4];
                    ey = c[5];
                    px = c[0];
                    py = c[1];
                }
                default -> {
                    return null;
                }
            }
            if (firstDir == null && first != null && (px != first[0] || py != first[1])) {
                firstDir = new double[] {first[0], first[1], first[0] - px, first[1] - py};
            } else if (firstDir == null && first != null && (ex != first[0] || ey != first[1])) {
                firstDir = new double[] {first[0], first[1], first[0] - ex, first[1] - ey};
            }
            double tx = seg == PathIterator.SEG_CUBICTO ? c[2] : seg == PathIterator.SEG_QUADTO ? c[0] : lastX;
            double ty = seg == PathIterator.SEG_CUBICTO ? c[3] : seg == PathIterator.SEG_QUADTO ? c[1] : lastY;
            if (tx == ex && ty == ey) {
                tx = lastX;
                ty = lastY;
            }
            if (tx != ex || ty != ey) {
                tail = new double[] {ex, ey, ex - tx, ey - ty};
            }
            lastX = ex;
            lastY = ey;
            it.next();
        }
        if (firstDir == null || tail == null) {
            return null;
        }
        return new double[][] {firstDir, tail};
    }
}

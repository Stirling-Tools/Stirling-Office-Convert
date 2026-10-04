package stirling.software.officeconvert.topdf.pptx;

import java.awt.Shape;
import java.awt.geom.FlatteningPathIterator;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

final class WarpPath {

    private static final int STEPS = 256;

    private final double cx;

    private final double cy;

    private final double rx;

    private final double ry;

    private final double x0;

    private final double width;

    private final double y0;

    private final double scale;

    private final boolean inward;

    private final double[] angle = new double[STEPS + 1];

    private final double[] length = new double[STEPS + 1];

    private WarpPath(Rectangle2D area, double start, double sweep, boolean inward, double x0, double x1, double y0,
            double y1) {
        this.cx = area.getCenterX();
        this.cy = area.getCenterY();
        this.rx = area.getWidth() / 2;
        this.ry = area.getHeight() / 2;
        this.x0 = x0;
        this.width = x1 - x0;
        this.y0 = y0;
        this.inward = inward;
        double thickness = Math.min(rx, ry) / 3;
        double midX = rx - thickness / 2;
        double midY = ry - thickness / 2;
        double total = 0;
        for (int i = 0; i <= STEPS; i++) {
            angle[i] = Math.toRadians(start + sweep * i / STEPS);
            if (i > 0) {
                total += Math.hypot(midX * (Math.cos(angle[i]) - Math.cos(angle[i - 1])),
                        midY * (Math.sin(angle[i]) - Math.sin(angle[i - 1])));
            }
            length[i] = total;
        }
        double s = total / width;
        this.scale = Math.min(s, thickness / Math.max(1e-6, y1 - y0));
    }

    static WarpPath of(TextFrame.Warp w, Rectangle2D area, double x0, double x1, double y0, double y1) {
        if (!(x1 - x0 > 0.01) || !(y1 - y0 > 0.01) || !(area.getWidth() > 1) || !(area.getHeight() > 1)) {
            return null;
        }
        double a = w.adj();
        return switch (w.preset()) {
            case "textArchUp" -> new WarpPath(area, a, Math.max(1, Math.min(360, 540 - 2 * a)), false, x0, x1, y0,
                    y1);
            case "textArchDown" -> new WarpPath(area, 180 + a, -Math.max(1, Math.min(360, 180 + 2 * a)), true, x0,
                    x1, y0, y1);
            case "textCircle" -> new WarpPath(area, a - 90, 360, false, x0, x1, y0, y1);
            default -> null;
        };
    }

    Shape follow(Shape s) {
        Path2D.Double out = new Path2D.Double();
        double[] c = new double[6];
        double lx = 0;
        double ly = 0;
        for (PathIterator it = new FlatteningPathIterator(s.getPathIterator(null), 0.02, 12); !it.isDone();
                it.next()) {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_CLOSE) {
                out.closePath();
                continue;
            }
            if (type == PathIterator.SEG_LINETO) {
                int steps = (int) Math.min(64, Math.ceil(Math.hypot(c[0] - lx, c[1] - ly) * scale / 2));
                for (int k = 1; k < steps; k++) {
                    double f = (double) k / steps;
                    Point2D p = map(lx + (c[0] - lx) * f, ly + (c[1] - ly) * f);
                    out.lineTo(p.getX(), p.getY());
                }
                Point2D p = map(c[0], c[1]);
                out.lineTo(p.getX(), p.getY());
            } else {
                Point2D p = map(c[0], c[1]);
                out.moveTo(p.getX(), p.getY());
            }
            lx = c[0];
            ly = c[1];
        }
        return out;
    }

    private Point2D map(double x, double y) {
        double along = Math.max(0, Math.min(length[STEPS], (length[STEPS] - width * scale) / 2 + (x - x0) * scale));
        double theta = angleAt(along);
        double depth = (y - y0) * scale;
        double thickness = Math.min(rx, ry) / 3;
        double in = inward ? thickness - depth : depth;
        double r1 = Math.max(0, rx - in);
        double r2 = Math.max(0, ry - in);
        return new Point2D.Double(cx + r1 * Math.cos(theta), cy + r2 * Math.sin(theta));
    }

    private double angleAt(double along) {
        int lo = 0;
        int hi = STEPS;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (length[mid] <= along) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double span = length[hi] - length[lo];
        double f = span > 0 ? (along - length[lo]) / span : 0;
        return angle[lo] + (angle[hi] - angle[lo]) * f;
    }
}

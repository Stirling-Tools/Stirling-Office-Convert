package stirling.software.officeconvert.extract;

import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

final class ClipArea {

    private static final int MAX_SHAPE_SEGMENTS = 5_000;

    private List<Path2D> source;
    private int size;
    private int shapeSegments;
    private Rectangle2D box;
    private final List<Path2D> shapes = new ArrayList<>();

    ClipArea of(List<Path2D> clips) {
        if (clips != source || clips.size() < size) {
            source = clips;
            size = 0;
            shapeSegments = 0;
            box = null;
            shapes.clear();
        }
        for (; size < clips.size(); size++) {
            Path2D clip = clips.get(size);
            Rectangle2D r = rectangle(clip);
            int segments = r == null ? segments(clip) : 0;
            if (r == null && shapeSegments + segments <= MAX_SHAPE_SEGMENTS) {
                shapes.add(clip);
                shapeSegments += segments;
            } else {
                Rectangle2D bounds = r == null ? clip.getBounds2D() : r;
                box = box == null ? bounds : box.createIntersection(bounds);
            }
        }
        return this;
    }

    boolean contains(double x, double y) {
        if (box != null && !box.contains(x, y)) {
            return false;
        }
        for (Path2D shape : shapes) {
            if (!shape.contains(x, y)) {
                return false;
            }
        }
        return true;
    }

    private static Rectangle2D rectangle(Path2D path) {
        Rectangle2D bounds = path.getBounds2D();
        if (!(bounds.getWidth() > 0) || !(bounds.getHeight() > 0)) {
            return null;
        }
        double[] c = new double[6];
        List<double[]> corners = new ArrayList<>(5);
        for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_CLOSE) {
                continue;
            }
            boolean start = type == PathIterator.SEG_MOVETO;
            if (start != corners.isEmpty() || !start && type != PathIterator.SEG_LINETO || corners.size() == 5
                    || !corner(bounds, c)) {
                return null;
            }
            corners.add(new double[] {c[0], c[1]});
        }
        if (corners.size() == 5 && same(corners.get(4), corners.get(0))) {
            corners.remove(4);
        }
        if (corners.size() != 4) {
            return null;
        }
        for (int i = 0; i < 4; i++) {
            double[] a = corners.get(i);
            double[] b = corners.get((i + 1) % 4);
            if ((a[0] == b[0]) == (a[1] == b[1])) {
                return null;
            }
        }
        return same(corners.get(0), corners.get(2)) || same(corners.get(1), corners.get(3)) ? null : bounds;
    }

    private static int segments(Path2D path) {
        int n = 0;
        double[] c = new double[6];
        for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
            it.currentSegment(c);
            n++;
        }
        return n;
    }

    private static boolean corner(Rectangle2D bounds, double[] c) {
        return (c[0] == bounds.getMinX() || c[0] == bounds.getMaxX())
                && (c[1] == bounds.getMinY() || c[1] == bounds.getMaxY());
    }

    private static boolean same(double[] a, double[] b) {
        return a[0] == b[0] && a[1] == b[1];
    }
}

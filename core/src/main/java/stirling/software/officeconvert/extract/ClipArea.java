package stirling.software.officeconvert.extract;

import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

final class ClipArea {

    private List<Path2D> source;
    private int size = -1;
    private Rectangle2D box;
    private final List<Path2D> shapes = new ArrayList<>();

    ClipArea of(List<Path2D> clips) {
        if (clips != source || clips.size() != size) {
            source = clips;
            size = clips.size();
            box = null;
            shapes.clear();
            for (Path2D clip : clips) {
                Rectangle2D r = rectangle(clip);
                if (r == null) {
                    if (!shapes.contains(clip)) {
                        shapes.add(clip);
                    }
                } else if (box == null) {
                    box = r;
                } else {
                    box = box.createIntersection(r);
                }
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
        double[] c = new double[6];
        double[] first = null;
        double[] last = null;
        int points = 0;
        for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_CLOSE) {
                continue;
            }
            boolean corner = (c[0] == bounds.getMinX() || c[0] == bounds.getMaxX())
                    && (c[1] == bounds.getMinY() || c[1] == bounds.getMaxY());
            boolean start = type == PathIterator.SEG_MOVETO;
            if (!corner || start != (points == 0) || type != PathIterator.SEG_LINETO && !start || ++points > 5
                    || last != null && !axisStep(last, c)) {
                return null;
            }
            last = new double[] {c[0], c[1]};
            first = first == null ? last : first;
        }
        return points >= 4 && axisStep(last, first) ? bounds : null;
    }

    private static boolean axisStep(double[] a, double[] b) {
        return a[0] == b[0] || a[1] == b[1];
    }
}

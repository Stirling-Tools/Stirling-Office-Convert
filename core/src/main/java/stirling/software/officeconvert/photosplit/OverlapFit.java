package stirling.software.officeconvert.photosplit;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OverlapFit {

    private static final double CONFLICT = 0.05;
    private static final double MIN_RETAINED_AREA = 0.6;
    private static final double MAX_INSIDE = 128;

    private record Shape(RotatedRect rect, Area polygon, double left, double right, double top, double bottom) {

        static Shape of(RotatedRect rect) {
            Path2D.Double path = new Path2D.Double();
            var corners = rect.corners();
            path.moveTo(corners.getFirst().x, corners.getFirst().y);
            double left = Double.POSITIVE_INFINITY, top = left;
            double right = Double.NEGATIVE_INFINITY, bottom = right;
            for (var point : corners) {
                path.lineTo(point.x, point.y);
                left = Math.min(left, point.x);
                right = Math.max(right, point.x);
                top = Math.min(top, point.y);
                bottom = Math.max(bottom, point.y);
            }
            path.closePath();
            return new Shape(rect, new Area(path), left, right, top, bottom);
        }
    }

    private OverlapFit() {}

    static List<RotatedRect> refine(Picture scan, List<RotatedRect> rectangles, PhotoSplitter.Options options) {
        if (rectangles.size() < 2) {
            return rectangles;
        }
        List<Shape> shapes = new ArrayList<>(rectangles.size());
        List<Integer> order = new ArrayList<>(rectangles.size());
        int[] parent = new int[rectangles.size()];
        for (int i = 0; i < rectangles.size(); i++) {
            PhotoSplitter.checkpoint();
            shapes.add(Shape.of(rectangles.get(i)));
            order.add(i);
            parent[i] = i;
        }
        List<Shape> initial = shapes;
        order.sort(Comparator.comparingDouble(i -> initial.get(i).left()));
        for (int i = 0; i < order.size(); i++) {
            Shape first = shapes.get(order.get(i));
            for (int j = i + 1; j < order.size(); j++) {
                PhotoSplitter.checkpoint();
                Shape second = shapes.get(order.get(j));
                if (second.left() >= first.right()) {
                    break;
                }
                if (overlap(first, second) > CONFLICT) {
                    parent[root(parent, order.get(j))] = root(parent, order.get(i));
                }
            }
        }
        Map<Integer, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < parent.length; i++) {
            groups.computeIfAbsent(root(parent, i), ignored -> new ArrayList<>()).add(i);
        }
        List<RotatedRect> result = new ArrayList<>(rectangles);
        for (List<Integer> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            List<Shape> proposed = new ArrayList<>(shapes);
            boolean valid = true;
            for (int index : group) {
                PhotoSplitter.checkpoint();
                RotatedRect original = result.get(index);
                double inside = Math.floor(Math.min(MAX_INSIDE, 0.25 * Math.min(original.width(), original.height())));
                List<RotatedRect> neighbors = new ArrayList<>();
                for (int other : group) {
                    if (other != index) {
                        neighbors.add(result.get(other));
                    }
                }
                RotatedRect fitted = EdgeFit.conflicted(scan, original, inside, neighbors);
                if (fitted.area() < Math.max(options.minArea(), MIN_RETAINED_AREA * original.area())
                        || fitted.area() > original.area() * 1.02) {
                    valid = false;
                    break;
                }
                proposed.set(index, Shape.of(fitted));
            }
            for (int index : group) {
                if (!valid) {
                    break;
                }
                for (int other = 0; other < proposed.size(); other++) {
                    PhotoSplitter.checkpoint();
                    if (index != other && overlap(proposed.get(index), proposed.get(other)) > CONFLICT) {
                        valid = false;
                        break;
                    }
                }
            }
            if (valid) {
                shapes = proposed;
                for (int index : group) {
                    result.set(index, proposed.get(index).rect());
                }
            }
        }
        return result;
    }

    private static int root(int[] parent, int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    static double overlap(RotatedRect first, RotatedRect second) {
        return overlap(Shape.of(first), Shape.of(second));
    }

    private static double overlap(Shape first, Shape second) {
        if (first.left() >= second.right() || second.left() >= first.right() || first.top() >= second.bottom()
                || second.top() >= first.bottom()) {
            return 0;
        }
        Area intersection = new Area(first.polygon());
        intersection.intersect(second.polygon());
        double[] point = new double[6];
        double firstX = 0, firstY = 0, previousX = 0, previousY = 0, sum = 0;
        for (PathIterator iterator = intersection.getPathIterator(null); !iterator.isDone(); iterator.next()) {
            int segment = iterator.currentSegment(point);
            if (segment == PathIterator.SEG_MOVETO) {
                firstX = previousX = point[0];
                firstY = previousY = point[1];
            } else if (segment == PathIterator.SEG_LINETO) {
                sum += previousX * point[1] - previousY * point[0];
                previousX = point[0];
                previousY = point[1];
            } else if (segment == PathIterator.SEG_CLOSE) {
                sum += previousX * firstY - previousY * firstX;
            }
        }
        return Math.abs(sum) / (2 * Math.min(first.rect().area(), second.rect().area()));
    }
}

package stirling.software.officeconvert.pdfa;

import java.awt.geom.GeneralPath;
import java.awt.geom.PathIterator;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

final class Outline {

    private static final double TOLERANCE = 0.35;

    private static final int MAX_POINTS = 30_000;

    private record Point(int x, int y, boolean on) {}

    private Outline() {}

    static byte[] encode(GeneralPath path) {
        List<List<Point>> contours = contours(path);
        int total = 0;
        for (List<Point> c : contours) {
            total += c.size();
        }
        if (contours.isEmpty() || total == 0 || total > MAX_POINTS) {
            return new byte[0];
        }
        int xMin = Integer.MAX_VALUE;
        int yMin = Integer.MAX_VALUE;
        int xMax = Integer.MIN_VALUE;
        int yMax = Integer.MIN_VALUE;
        for (List<Point> c : contours) {
            for (Point p : c) {
                xMin = Math.min(xMin, p.x());
                yMin = Math.min(yMin, p.y());
                xMax = Math.max(xMax, p.x());
                yMax = Math.max(yMax, p.y());
            }
        }
        ByteBuffer b = ByteBuffer.allocate(10 + 2 * contours.size() + 2 + total * 5 + 4);
        b.putShort((short) contours.size());
        b.putShort((short) xMin);
        b.putShort((short) yMin);
        b.putShort((short) xMax);
        b.putShort((short) yMax);
        int end = -1;
        for (List<Point> c : contours) {
            end += c.size();
            b.putShort((short) end);
        }
        b.putShort((short) 0);
        for (List<Point> c : contours) {
            for (Point p : c) {
                b.put((byte) (p.on() ? 1 : 0));
            }
        }
        int last = 0;
        for (List<Point> c : contours) {
            for (Point p : c) {
                b.putShort((short) (p.x() - last));
                last = p.x();
            }
        }
        last = 0;
        for (List<Point> c : contours) {
            for (Point p : c) {
                b.putShort((short) (p.y() - last));
                last = p.y();
            }
        }
        byte[] out = new byte[b.position()];
        b.flip();
        b.get(out);
        return out;
    }

    static int points(byte[] glyph) {
        ByteBuffer b = ByteBuffer.wrap(glyph);
        int n = b.getShort(0);
        return n <= 0 ? 0 : (b.getShort(10 + 2 * (n - 1)) & 0xFFFF) + 1;
    }

    private static List<List<Point>> contours(GeneralPath path) {
        List<List<Point>> out = new ArrayList<>();
        List<Point> cur = null;
        double[] c = new double[6];
        double x = 0;
        double y = 0;
        double sx = 0;
        double sy = 0;
        for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            switch (type) {
                case PathIterator.SEG_MOVETO -> {
                    close(out, cur);
                    cur = new ArrayList<>();
                    x = sx = c[0];
                    y = sy = c[1];
                    cur.add(pt(x, y, true));
                }
                case PathIterator.SEG_LINETO -> {
                    cur = started(cur, x, y);
                    x = c[0];
                    y = c[1];
                    cur.add(pt(x, y, true));
                }
                case PathIterator.SEG_QUADTO -> {
                    cur = started(cur, x, y);
                    cur.add(pt(c[0], c[1], false));
                    x = c[2];
                    y = c[3];
                    cur.add(pt(x, y, true));
                }
                case PathIterator.SEG_CUBICTO -> {
                    cur = started(cur, x, y);
                    cubic(cur, x, y, c[0], c[1], c[2], c[3], c[4], c[5]);
                    x = c[4];
                    y = c[5];
                }
                case PathIterator.SEG_CLOSE -> {
                    close(out, cur);
                    cur = null;
                    x = sx;
                    y = sy;
                }
                default -> {
                }
            }
        }
        close(out, cur);
        return out;
    }

    private static List<Point> started(List<Point> cur, double x, double y) {
        if (cur != null) {
            return cur;
        }
        List<Point> c = new ArrayList<>();
        c.add(pt(x, y, true));
        return c;
    }

    private static void close(List<List<Point>> out, List<Point> cur) {
        if (cur == null || cur.size() < 2) {
            return;
        }
        Point first = cur.get(0);
        Point last = cur.get(cur.size() - 1);
        if (cur.size() > 1 && last.on() && last.x() == first.x() && last.y() == first.y()) {
            cur.remove(cur.size() - 1);
        }
        if (cur.size() >= 2) {
            out.add(cur);
        }
    }

    private static void cubic(List<Point> cur, double x0, double y0, double x1, double y1, double x2, double y2,
            double x3, double y3) {
        double dx = x3 - 3 * x2 + 3 * x1 - x0;
        double dy = y3 - 3 * y2 + 3 * y1 - y0;
        double d = Math.hypot(dx, dy);
        int n = (int) Math.ceil(Math.cbrt(Math.sqrt(3) / 36 * d / TOLERANCE));
        n = Math.max(1, Math.min(16, n));
        for (int i = 0; i < n; i++) {
            double t0 = (double) i / n;
            double t1 = (double) (i + 1) / n;
            double[] a = at(x0, y0, x1, y1, x2, y2, x3, y3, t0);
            double[] b = at(x0, y0, x1, y1, x2, y2, x3, y3, t1);
            double[] da = tangent(x0, y0, x1, y1, x2, y2, x3, y3, t0);
            double[] db = tangent(x0, y0, x1, y1, x2, y2, x3, y3, t1);
            double h = (t1 - t0) / 3;
            double c1x = a[0] + da[0] * h;
            double c1y = a[1] + da[1] * h;
            double c2x = b[0] - db[0] * h;
            double c2y = b[1] - db[1] * h;
            double qx = (3 * (c1x + c2x) - (a[0] + b[0])) / 4;
            double qy = (3 * (c1y + c2y) - (a[1] + b[1])) / 4;
            cur.add(pt(qx, qy, false));
            cur.add(pt(b[0], b[1], true));
        }
    }

    private static double[] at(double x0, double y0, double x1, double y1, double x2, double y2, double x3, double y3,
            double t) {
        double u = 1 - t;
        double a = u * u * u;
        double b = 3 * u * u * t;
        double c = 3 * u * t * t;
        double d = t * t * t;
        return new double[] {a * x0 + b * x1 + c * x2 + d * x3, a * y0 + b * y1 + c * y2 + d * y3};
    }

    private static double[] tangent(double x0, double y0, double x1, double y1, double x2, double y2, double x3,
            double y3, double t) {
        double u = 1 - t;
        double a = 3 * u * u;
        double b = 6 * u * t;
        double c = 3 * t * t;
        return new double[] {a * (x1 - x0) + b * (x2 - x1) + c * (x3 - x2), a * (y1 - y0) + b * (y2 - y1) + c * (y3 - y2)};
    }

    private static Point pt(double x, double y, boolean on) {
        return new Point((int) Math.round(Math.max(-32_768, Math.min(32_767, x))),
                (int) Math.round(Math.max(-32_768, Math.min(32_767, y))), on);
    }
}

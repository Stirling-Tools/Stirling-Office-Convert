package stirling.software.officeconvert.topdf.psd;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.Arrays;

final class VectorMask {

    static final int MAX_BYTES = 8 << 20;

    static final int STRIP = 64;

    private final Area area;

    private final boolean invert;

    private final Rectangle bounds;

    private final int width;

    private final int height;

    private BufferedImage strip;

    private int stripTop = Integer.MIN_VALUE;

    private VectorMask(Area area, boolean invert, int width, int height) {
        this.area = area;
        this.invert = invert;
        this.width = width;
        this.height = height;
        this.bounds = area.getBounds().intersection(new Rectangle(0, 0, width, height));
    }

    static VectorMask of(byte[] data, int width, int height) {
        if (data == null || data.length < 8) {
            return null;
        }
        int flags = i32(data, 4);
        if ((flags & 4) != 0) {
            return null;
        }
        Area total = null;
        Path2D.Double path = null;
        int op = 1;
        boolean closed = false;
        double[] first = null;
        double[] last = null;
        int knots = 0;
        boolean startFull = false;
        for (int at = 8; at + 26 <= data.length; at += 26) {
            int selector = (data[at] & 0xFF) << 8 | data[at + 1] & 0xFF;
            if (selector == 8) {
                if (((data[at + 2] & 0xFF) << 8 | data[at + 3] & 0xFF) == 1 && path == null && total == null) {
                    startFull = true;
                }
            } else if (selector == 0 || selector == 3) {
                if (path != null) {
                    total = combine(total, finish(path, closed, first, last), op);
                }
                path = new Path2D.Double(Path2D.WIND_NON_ZERO);
                closed = selector == 0;
                first = null;
                last = null;
                op = (short) ((data[at + 4] & 0xFF) << 8 | data[at + 5] & 0xFF);
            } else if (selector >= 1 && selector <= 5 && selector != 3 && path != null) {
                if (++knots > 20_000) {
                    return null;
                }
                double[] k = new double[6];
                for (int i = 0; i < 6; i++) {
                    double v = i32(data, at + 2 + 4 * i) / (double) (1 << 24);
                    k[i] = i % 2 == 0 ? v * height : v * width;
                }
                if (first == null) {
                    path.moveTo(k[3], k[2]);
                    first = k;
                } else {
                    path.curveTo(last[5], last[4], k[1], k[0], k[3], k[2]);
                }
                last = k;
            }
        }
        if (path != null) {
            total = combine(total, finish(path, closed, first, last), op);
        }
        if (total == null) {
            total = startFull ? full() : new Area();
        }
        return new VectorMask(total, (flags & 1) != 0, width, height);
    }

    private static Area full() {
        return new Area(new Rectangle(-1 << 20, -1 << 20, 1 << 21, 1 << 21));
    }

    private static Area finish(Path2D.Double path, boolean closed, double[] first, double[] last) {
        if (first == null) {
            return new Area();
        }
        if (closed) {
            path.curveTo(last[5], last[4], first[1], first[0], first[3], first[2]);
            path.closePath();
        }
        return new Area(path);
    }

    private static Area combine(Area total, Area next, int op) {
        if (total == null) {
            if (op == 2) {
                Area all = full();
                all.subtract(next);
                return all;
            }
            return next;
        }
        switch (op) {
            case 2 -> total.subtract(next);
            case 3 -> total.intersect(next);
            case 0 -> total.exclusiveOr(next);
            default -> total.add(next);
        }
        return total;
    }

    VectorMask stroke(double lineWidth, String align) {
        boolean center = align == null || align.endsWith("Center");
        java.awt.BasicStroke pen = new java.awt.BasicStroke((float) (center ? lineWidth : 2 * lineWidth),
                java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER, 10);
        Area line = new Area(pen.createStrokedShape(area));
        if (!center && align.endsWith("Inside")) {
            line.intersect(area);
        } else if (!center) {
            line.subtract(area);
        }
        return new VectorMask(line, false, width, height);
    }

    java.awt.geom.Rectangle2D shape() {
        return area.getBounds2D().createIntersection(new Rectangle(0, 0, width, height));
    }

    void row(int y, float[] into) {
        float outside = invert ? 1 : 0;
        if (y < bounds.y || y >= bounds.y + bounds.height || bounds.width <= 0) {
            Arrays.fill(into, 0, width, outside);
            return;
        }
        int top = y - Math.floorMod(y - bounds.y, STRIP);
        if (top != stripTop) {
            if (strip == null) {
                strip = new BufferedImage(bounds.width, STRIP, BufferedImage.TYPE_BYTE_GRAY);
            }
            byte[] px = ((DataBufferByte) strip.getRaster().getDataBuffer()).getData();
            Arrays.fill(px, (byte) 0);
            Graphics2D g = strip.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g.setColor(java.awt.Color.WHITE);
                g.translate(-bounds.x, -top);
                g.fill(area);
            } finally {
                g.dispose();
            }
            stripTop = top;
        }
        byte[] px = ((DataBufferByte) strip.getRaster().getDataBuffer()).getData();
        int r = (y - top) * bounds.width;
        Arrays.fill(into, 0, width, outside);
        for (int x = 0; x < bounds.width; x++) {
            float v = (px[r + x] & 0xFF) / 255f;
            into[bounds.x + x] = invert ? 1 - v : v;
        }
    }

    private static int i32(byte[] b, int at) {
        return (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | b[at + 3] & 0xFF;
    }
}

package stirling.software.officeconvert.topdf.io;

import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.util.Comparator;
import java.util.stream.Stream;

import org.apache.poi.hemf.draw.HemfGraphics;
import org.apache.poi.hemf.record.emf.HemfRecord;
import org.apache.poi.hemf.usermodel.HemfPicture;
import org.apache.poi.sl.draw.Drawable;

final class EmfDrawing {

    private EmfDrawing() {}

    static void draw(HemfPicture picture, Graphics2D g, Rectangle2D target) {
        Shape clip = g.getClip();
        AffineTransform transform = g.getTransform();
        try {
            Rectangle2D bounds = bounds(picture, g);
            g.translate(target.getCenterX(), target.getCenterY());
            g.scale(target.getWidth() / bounds.getWidth(), target.getHeight() / bounds.getHeight());
            g.translate(-bounds.getCenterX(), -bounds.getCenterY());
            HemfGraphics context = new HemfGraphics(g, bounds);
            for (HemfRecord record : picture.getRecords()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("Conversion interrupted");
                }
                context.draw(record);
            }
        } finally {
            g.setTransform(transform);
            g.setClip(clip);
        }
    }

    private static Rectangle2D bounds(HemfPicture picture, Graphics2D g) {
        Rectangle2D header = picture.getHeader().getBoundsRectangle();
        if (Boolean.TRUE.equals(g.getRenderingHint(Drawable.EMF_FORCE_HEADER_BOUNDS))) {
            return header;
        }
        Rectangle2D window = new FirstBounds();
        Rectangle2D viewport = new FirstBounds();
        Rectangle2D drawing = new Rectangle2D.Double();
        picture.getInnerBounds(window, viewport, drawing);
        if (drawing.isEmpty()) {
            return !viewport.isEmpty() ? viewport : !window.isEmpty() ? window : header;
        }
        return Stream.of(header, window, viewport).min(Comparator.comparingDouble(b -> difference(b, drawing)))
                .orElse(header);
    }

    private static double difference(Rectangle2D a, Rectangle2D b) {
        return java.awt.geom.Point2D.distanceSq(a.getMinX(), a.getMinY(), b.getMinX(), b.getMinY())
                + java.awt.geom.Point2D.distanceSq(a.getMinX(), a.getMaxY(), b.getMinX(), b.getMaxY())
                + java.awt.geom.Point2D.distanceSq(a.getMaxX(), a.getMinY(), b.getMaxX(), b.getMinY())
                + java.awt.geom.Point2D.distanceSq(a.getMaxX(), a.getMaxY(), b.getMaxX(), b.getMaxY());
    }

    private static final class FirstBounds extends Rectangle2D.Double {
        private boolean offset;
        private boolean range;

        FirstBounds() {
            super(-1, -1, 0, 0);
        }

        @Override
        public void setRect(double x, double y, double w, double h) {
            if (offset && range) {
                return;
            }
            super.setRect(offset ? this.x : x, offset ? this.y : y,
                    range ? width : w, range ? height : h);
            offset |= x != -1 || y != -1;
            range |= w != 0 || h != 0;
        }

        @Override
        public boolean isEmpty() {
            double w = Math.rint(width);
            double h = Math.rint(height);
            return w <= 0 || h <= 0 || x == -1 && y == -1 || w == 1 && h == 1;
        }
    }
}

package stirling.software.officeconvert.topdf.io;

import java.awt.geom.Dimension2D;
import java.awt.geom.Rectangle2D;

import org.apache.poi.hemf.record.emf.HemfHeader;

final class EmfFrames {

    private static final double MAX_SIZE_DRIFT = 0.1;

    record Placement(Rectangle2D bounds, Rectangle2D frame, double widthPoints, double heightPoints) {

        Rectangle2D target(Rectangle2D box) {
            double sx = box.getWidth() / frame.getWidth();
            double sy = box.getHeight() / frame.getHeight();
            return new Rectangle2D.Double(box.getX() + (bounds.getX() - frame.getX()) * sx,
                    box.getY() + (bounds.getY() - frame.getY()) * sy, bounds.getWidth() * sx,
                    bounds.getHeight() * sy);
        }
    }

    private EmfFrames() {}

    static Placement of(HemfHeader h) {
        try {
            Rectangle2D bounds = h.getBoundsRectangle();
            Rectangle2D frame = h.getFrameRectangle();
            Dimension2D dev = h.getDeviceDimension();
            Dimension2D mm = h.getMilliDimension();
            if (bounds == null || frame == null || dev == null || mm == null || !(dev.getWidth() > 0)
                    || !(dev.getHeight() > 0) || !(mm.getWidth() > 0) || !(mm.getHeight() > 0)
                    || !(bounds.getWidth() > 0) || !(bounds.getHeight() > 0) || !(frame.getWidth() > 0)
                    || !(frame.getHeight() > 0)) {
                return null;
            }
            double px = dev.getWidth() / mm.getWidth() / 100;
            double py = dev.getHeight() / mm.getHeight() / 100;
            Rectangle2D f = new Rectangle2D.Double(frame.getX() * px, frame.getY() * py, frame.getWidth() * px,
                    frame.getHeight() * py);
            if (!f.contains(bounds.getCenterX(), bounds.getCenterY())
                    || Math.abs(bounds.getWidth() / f.getWidth() - 1) > MAX_SIZE_DRIFT
                    || Math.abs(bounds.getHeight() / f.getHeight() - 1) > MAX_SIZE_DRIFT) {
                return null;
            }
            return new Placement(bounds, f, frame.getWidth() / 100 * 72 / 25.4, frame.getHeight() / 100 * 72 / 25.4);
        } catch (RuntimeException e) {
            return null;
        }
    }
}

package stirling.software.officeconvert.topdf.pptx;

import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;

record Frame(double x, double y, double width, double height, double rotation, boolean flipH, boolean flipV,
        AffineTransform view) {

    Frame {
        if (flipH && flipV) {
            rotation += 180;
            flipH = false;
            flipV = false;
        }
        rotation = ((rotation % 360) + 360) % 360;
    }

    Frame(double x, double y, double width, double height, double rotation, boolean flipH, boolean flipV) {
        this(x, y, width, height, rotation, flipH, flipV, null);
    }

    double centerX() {
        return x + width / 2;
    }

    double centerY() {
        return y + height / 2;
    }

    Rectangle2D bounds() {
        return new Rectangle2D.Double(x, y, width, height);
    }

    AffineTransform shapeTransform() {
        AffineTransform t = new AffineTransform();
        if (rotation == 0 && !flipH && !flipV && view == null) {
            return t;
        }
        t.translate(centerX(), centerY());
        if (view != null) {
            t.concatenate(view);
        }
        t.rotate(Math.toRadians(rotation));
        t.scale(flipH ? -1 : 1, flipV ? -1 : 1);
        t.translate(-centerX(), -centerY());
        return t;
    }

    AffineTransform textTransform() {
        double r = rotation + (flipV ? 180 : 0);
        AffineTransform t = new AffineTransform();
        if (r % 360 == 0 && view == null) {
            return t;
        }
        t.translate(centerX(), centerY());
        if (view != null) {
            t.concatenate(view);
        }
        t.rotate(Math.toRadians(r));
        t.translate(-centerX(), -centerY());
        return t;
    }

    Frame moved(double nx, double ny, double w, double h) {
        return new Frame(nx, ny, w, h, rotation, flipH, flipV, view);
    }

    Frame viewed(AffineTransform v) {
        return v == null ? this : new Frame(x, y, width, height, rotation, flipH, flipV, v);
    }
}

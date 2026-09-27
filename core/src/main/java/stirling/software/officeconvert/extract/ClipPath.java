package stirling.software.officeconvert.extract;

import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;

import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;

final class ClipPath {

    private static final int MAX_SEGMENTS = 5_000;

    private GeneralPath path = new GeneralPath();
    private int segments;
    private int pendingRule = -1;

    void moveTo(float x, float y) {
        path.moveTo(x, y);
        segments++;
    }

    void lineTo(float x, float y) {
        if (path.getCurrentPoint() == null) {
            path.moveTo(x, y);
        } else {
            path.lineTo(x, y);
        }
        segments++;
    }

    void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        if (path.getCurrentPoint() == null) {
            path.moveTo(x3, y3);
        } else {
            path.curveTo(x1, y1, x2, y2, x3, y3);
        }
        segments++;
    }

    void rectangle(float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3) {
        path.moveTo(x0, y0);
        path.lineTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.closePath();
        segments += 4;
    }

    void close() {
        if (path.getCurrentPoint() != null) {
            path.closePath();
        }
    }

    void clipOnEnd(int windingRule) {
        pendingRule = windingRule;
    }

    void end(PDGraphicsState state) {
        if (pendingRule >= 0) {
            GeneralPath clip = segments > MAX_SEGMENTS ? new GeneralPath(path.getBounds2D()) : path;
            clip.setWindingRule(pendingRule == Path2D.WIND_EVEN_ODD ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
            state.intersectClippingPath(clip);
            pendingRule = -1;
        }
        path = new GeneralPath();
        segments = 0;
    }
}

package stirling.software.officeconvert.topdf.pptx;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

final class Space {

    static final Space SLIDE = new Space(new AffineTransform(), 1, 1, 0, false, false, null, 0, null);

    static final int MAX_DEPTH = 64;

    private final AffineTransform toSlide;

    private final double scaleX;

    private final double scaleY;

    private final double rotation;

    private final boolean flipH;

    private final boolean flipV;

    private final String relsPart;

    private final int depth;

    private final Color groupFill;

    private Space(AffineTransform toSlide, double scaleX, double scaleY, double rotation, boolean flipH, boolean flipV,
            String relsPart, int depth, Color groupFill) {
        this.toSlide = toSlide;
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.rotation = rotation;
        this.flipH = flipH;
        this.flipV = flipV;
        this.relsPart = relsPart;
        this.depth = depth;
        this.groupFill = groupFill;
    }

    Color groupFill() {
        return groupFill;
    }

    String relsPart() {
        return relsPart;
    }

    int depth() {
        return depth;
    }

    Space withRelsPart(String part) {
        return new Space(toSlide, scaleX, scaleY, rotation, flipH, flipV, part, depth, groupFill);
    }

    Frame place(Rectangle2D anchor, double rot, boolean fh, boolean fv) {
        double w = anchor.getWidth();
        double h = anchor.getHeight();
        Point2D c = toSlide.transform(new Point2D.Double(anchor.getCenterX(), anchor.getCenterY()), null);
        double r = ((rot % 360) + 360) % 360;
        int quadrant = ((int) Math.floor((r + 45) / 90)) % 4;
        double sw = quadrant % 2 == 1 ? scaleY : scaleX;
        double sh = quadrant % 2 == 1 ? scaleX : scaleY;
        double nw = w * sw;
        double nh = h * sh;
        boolean odd = flipH ^ flipV;
        double nr = rotation + (odd ? -rot : rot);
        return new Frame(c.getX() - nw / 2, c.getY() - nh / 2, nw, nh, nr, fh ^ flipH, fv ^ flipV);
    }

    Space group(Rectangle2D exterior, Rectangle2D interior, double rot, boolean fh, boolean fv, Color fill) {
        AffineTransform t = new AffineTransform(toSlide);
        double cx = exterior.getCenterX();
        double cy = exterior.getCenterY();
        t.translate(cx, cy);
        t.rotate(Math.toRadians(rot));
        t.scale(fh ? -1 : 1, fv ? -1 : 1);
        t.translate(-cx, -cy);
        double sx = interior.getWidth() == 0 ? 1 : exterior.getWidth() / interior.getWidth();
        double sy = interior.getHeight() == 0 ? 1 : exterior.getHeight() / interior.getHeight();
        t.translate(exterior.getX(), exterior.getY());
        t.scale(sx, sy);
        t.translate(-interior.getX(), -interior.getY());
        double r = ((rot % 360) + 360) % 360;
        int quadrant = ((int) Math.floor((r + 45) / 90)) % 4;
        double gsx = quadrant % 2 == 1 ? scaleY : scaleX;
        double gsy = quadrant % 2 == 1 ? scaleX : scaleY;
        boolean odd = flipH ^ flipV;
        return new Space(t, gsx * Math.abs(sx), gsy * Math.abs(sy), rotation + (odd ? -rot : rot), fh ^ flipH,
                fv ^ flipV, relsPart, depth + 1, fill != null ? fill : groupFill);
    }
}

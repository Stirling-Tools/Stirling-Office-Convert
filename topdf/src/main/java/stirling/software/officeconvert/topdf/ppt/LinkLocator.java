package stirling.software.officeconvert.topdf.ppt;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

final class LinkLocator extends PDFGraphicsStreamEngine {

    record Pending(Rectangle2D box, SlideLinks.Target target) {}

    private final Map<COSStream, List<Pending>> pending;

    private final float height;

    private final List<SlideLinks.Area> found = new ArrayList<>();

    private LinkLocator(Map<COSStream, List<Pending>> pending, float width, float height) {
        super(new PDPage(new PDRectangle(width, height)));
        this.pending = pending;
        this.height = height;
    }

    static List<SlideLinks.Area> locate(List<PDFormXObject> slide, Map<COSStream, List<Pending>> pending, float width,
            float height) throws IOException {
        if (pending.isEmpty()) {
            return List.of();
        }
        LinkLocator locator = new LinkLocator(new IdentityHashMap<>(pending), width, height);
        for (PDFormXObject form : slide) {
            locator.processChildStream(form, locator.getPage());
        }
        return locator.found;
    }

    @Override
    public void showForm(PDFormXObject form) throws IOException {
        List<Pending> mine = pending.remove(form.getCOSObject());
        if (mine == null) {
            super.showForm(form);
            return;
        }
        AffineTransform toPage = getGraphicsState().getCurrentTransformationMatrix().createAffineTransform();
        toPage.concatenate(form.getMatrix().createAffineTransform());
        for (Pending p : mine) {
            Rectangle2D r = toPage.createTransformedShape(p.box()).getBounds2D();
            if (r.getWidth() > 0 && r.getHeight() > 0) {
                found.add(new SlideLinks.Area(new Rectangle2D.Double(r.getX(), height - r.getMaxY(), r.getWidth(),
                        r.getHeight()), p.target()));
            }
        }
    }

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {}

    @Override
    public void drawImage(PDImage pdImage) {}

    @Override
    public void clip(int windingRule) {}

    @Override
    public void moveTo(float x, float y) {}

    @Override
    public void lineTo(float x, float y) {}

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {}

    @Override
    public Point2D getCurrentPoint() {
        return new Point2D.Float();
    }

    @Override
    public void closePath() {}

    @Override
    public void endPath() {}

    @Override
    public void strokePath() {}

    @Override
    public void fillPath(int windingRule) {}

    @Override
    public void fillAndStrokePath(int windingRule) {}

    @Override
    public void shadingFill(COSName shadingName) {}
}

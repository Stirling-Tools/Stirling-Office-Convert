package stirling.software.officeconvert.slides;

import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3CharProc;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import stirling.software.officeconvert.extract.OperatorBudget;
import stirling.software.officeconvert.layout.Box;

final class PaintOrder extends PDFGraphicsStreamEngine {

    enum Kind {
        FILL,
        STROKE,
        IMAGE,
        SHADING,
        TEXT
    }

    enum Outline {
        RECT,
        ROUNDED,
        OTHER
    }

    record Mark(Kind kind, Box box, int order, float alpha, boolean opaque, Outline outline, float radius) {}

    record Slant(float x, float y, float angle, float advance, int order) {}

    private static final int MAX_MARKS = 30_000;

    private static final double MIN_SLANT = 1.0;

    private final AffineTransform toDisplay;
    private final float width;
    private final float height;
    private final List<Mark> marks = new ArrayList<>();
    private final List<Slant> slants = new ArrayList<>();
    private final GeneralPath path = new GeneralPath();
    private Point2D current = new Point2D.Float();
    private int order;
    private final OperatorBudget budget = new OperatorBudget("ordering paint");
    private float radius;

    private PaintOrder(PDPage page, AffineTransform toDisplay, float width, float height) {
        super(page);
        this.toDisplay = toDisplay;
        this.width = width;
        this.height = height;
    }

    static PaintOrder read(PDPage page, AffineTransform toDisplay, float width, float height) {
        PaintOrder p = new PaintOrder(page, toDisplay, width, height);
        try {
            p.processPage(page);
        } catch (IOException | RuntimeException | StackOverflowError e) {
        }
        return p;
    }

    List<Mark> marks() {
        return marks;
    }

    List<Slant> slants() {
        return slants;
    }

    int drew(Box box, float tolerance, Kind... kinds) {
        int best = -1;
        for (Mark m : marks) {
            if (m.order() > best && is(m.kind(), kinds) && near(m.box(), box, tolerance)) {
                best = m.order();
            }
        }
        return best;
    }

    int outlineWith(Box rule, float tolerance) {
        boolean horizontal = rule.width() >= rule.height();
        int best = -1;
        for (Mark m : marks) {
            if (m.kind() != Kind.STROKE || m.order() <= best) {
                continue;
            }
            Box b = m.box();
            boolean along = horizontal
                    ? (Math.abs(rule.centreY() - b.top()) <= tolerance || Math.abs(rule.centreY() - b.bottom()) <= tolerance)
                            && rule.x() >= b.x() - tolerance && rule.right() <= b.right() + tolerance
                    : (Math.abs(rule.centreX() - b.x()) <= tolerance || Math.abs(rule.centreX() - b.right()) <= tolerance)
                            && rule.top() >= b.top() - tolerance && rule.bottom() <= b.bottom() + tolerance;
            if (along) {
                best = m.order();
            }
        }
        return best;
    }

    Mark drewMark(Box box, float tolerance, Kind... kinds) {
        Mark best = null;
        for (Mark m : marks) {
            if ((best == null || m.order() > best.order()) && is(m.kind(), kinds) && near(m.box(), box, tolerance)) {
                best = m;
            }
        }
        return best;
    }

    Mark drewAround(Box box, float tolerance, Kind... kinds) {
        Mark best = null;
        for (Mark m : marks) {
            Box b = m.box();
            if (!is(m.kind(), kinds) || b.x() > box.x() + tolerance || b.top() > box.top() + tolerance
                    || b.right() < box.right() - tolerance || b.bottom() < box.bottom() - tolerance) {
                continue;
            }
            if (best == null || b.area() < best.box().area() - 1 || b.area() <= best.box().area() + 1 && m.order() > best.order()) {
                best = m;
            }
        }
        return best;
    }

    int textOver(Box box) {
        int best = -1;
        for (Mark m : marks) {
            if (m.kind() == Kind.TEXT && m.order() > best
                    && m.box().overlapArea(box) >= 0.5f * Math.min(m.box().area(), box.area())) {
                best = m.order();
            }
        }
        return best;
    }

    void drewAll(Box box, float tolerance, Collection<Integer> out) {
        for (Mark m : marks) {
            if ((m.kind() == Kind.FILL || m.kind() == Kind.STROKE) && near(m.box(), box, tolerance)) {
                out.add(m.order());
            }
        }
    }

    boolean covered(Box box, int order) {
        for (Mark m : marks) {
            Box b = m.box();
            if (m.order() > order && m.opaque() && (m.kind() == Kind.FILL || m.kind() == Kind.IMAGE || m.kind() == Kind.SHADING)
                    && b.x() <= box.x() + 0.5f && b.top() <= box.top() + 0.5f && b.right() >= box.right() - 0.5f
                    && b.bottom() >= box.bottom() - 0.5f) {
                return true;
            }
        }
        return false;
    }

    void inside(Box box, Collection<Integer> out) {
        Box grown = box.grow(1f);
        for (Mark m : marks) {
            Box b = m.box();
            if (grown.contains(b.centreX(), b.centreY()) && b.width() <= grown.width() + 1
                    && b.height() <= grown.height() + 1) {
                out.add(m.order());
            }
        }
    }

    int firstInside(Box box, Kind... kinds) {
        Box grown = box.grow(1f);
        int best = -1;
        for (Mark m : marks) {
            Box b = m.box();
            if ((best < 0 || m.order() < best) && is(m.kind(), kinds) && grown.contains(b.centreX(), b.centreY())
                    && b.width() <= grown.width() + 1 && b.height() <= grown.height() + 1) {
                best = m.order();
            }
        }
        return best;
    }

    int lastInside(Box box, Kind... kinds) {
        Box grown = box.grow(1f);
        int best = -1;
        for (Mark m : marks) {
            Box b = m.box();
            if (m.order() > best && is(m.kind(), kinds) && grown.contains(b.centreX(), b.centreY())
                    && b.width() <= grown.width() + 1 && b.height() <= grown.height() + 1) {
                best = m.order();
            }
        }
        return best;
    }

    private static boolean is(Kind k, Kind[] kinds) {
        for (Kind want : kinds) {
            if (k == want) {
                return true;
            }
        }
        return false;
    }

    private static boolean near(Box a, Box b, float t) {
        return Math.abs(a.x() - b.x()) <= t && Math.abs(a.top() - b.top()) <= t && Math.abs(a.right() - b.right()) <= t
                && Math.abs(a.bottom() - b.bottom()) <= t;
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        if (!budget.run(operator)) {
            return;
        }
        try {
            super.processOperator(operator, operands);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
        }
    }

    @Override
    public void showForm(PDFormXObject form) throws IOException {
        if (budget.form()) {
            super.showForm(form);
        }
    }

    @Override
    public void showTransparencyGroup(PDTransparencyGroup form) throws IOException {
        if (budget.form()) {
            super.showTransparencyGroup(form);
        }
    }

    private void mark(Kind kind, Rectangle2D device) {
        float alpha = kind == Kind.STROKE ? (float) getGraphicsState().getAlphaConstant()
                : (float) getGraphicsState().getNonStrokeAlphaConstant();
        boolean path = kind == Kind.FILL || kind == Kind.STROKE;
        mark(kind, device, alpha, alpha >= 0.99f && getGraphicsState().getSoftMask() == null,
                path ? outline(this.path) : Outline.OTHER, path ? radius : 0);
    }

    private Outline outline(GeneralPath p) {
        int lines = 0;
        int curves = 0;
        int subpaths = 0;
        double curveSpan = 0;
        double[] c = new double[6];
        double lx = 0;
        double ly = 0;
        double sx = 0;
        double sy = 0;
        for (var it = p.getPathIterator(null); !it.isDone(); it.next()) {
            int seg = it.currentSegment(c);
            double tx = lx;
            double ty = ly;
            switch (seg) {
                case PathIterator.SEG_MOVETO -> {
                    subpaths++;
                    lx = sx = c[0];
                    ly = sy = c[1];
                    continue;
                }
                case PathIterator.SEG_CUBICTO -> {
                    curves++;
                    curveSpan += Math.max(Math.abs(c[4] - lx), Math.abs(c[5] - ly));
                    lx = c[4];
                    ly = c[5];
                    continue;
                }
                case PathIterator.SEG_QUADTO -> {
                    curves++;
                    curveSpan += Math.max(Math.abs(c[2] - lx), Math.abs(c[3] - ly));
                    lx = c[2];
                    ly = c[3];
                    continue;
                }
                case PathIterator.SEG_LINETO -> {
                    tx = c[0];
                    ty = c[1];
                }
                default -> {
                    tx = sx;
                    ty = sy;
                }
            }
            double dx = Math.abs(tx - lx);
            double dy = Math.abs(ty - ly);
            if (dx > 0.5 && dy > 0.5) {
                double corner = polylineCorner(p);
                radius = (float) (Math.max(0, corner) * Math.sqrt(Math.abs(toDisplay.getDeterminant())));
                return corner > 0 ? Outline.ROUNDED : Outline.OTHER;
            }
            if (dx > 0.1 || dy > 0.1) {
                lines++;
            }
            lx = tx;
            ly = ty;
        }
        radius = 0;
        if (subpaths != 1 || lines > 4) {
            return Outline.OTHER;
        }
        if (curves == 0) {
            return lines >= 3 ? Outline.RECT : Outline.OTHER;
        }
        if (curves == 4) {
            radius = (float) (curveSpan / 4 * Math.sqrt(Math.abs(toDisplay.getDeterminant())));
            return Outline.ROUNDED;
        }
        return Outline.OTHER;
    }

    private static double polylineCorner(GeneralPath p) {
        Rectangle2D b = p.getBounds2D();
        double zone = Math.min(b.getWidth(), b.getHeight()) / 2;
        double[] c = new double[6];
        int points = 0;
        int drawn = 0;
        boolean moved = false;
        double radius = 0;
        for (var it = p.getPathIterator(null); !it.isDone(); it.next()) {
            int seg = it.currentSegment(c);
            if (seg == PathIterator.SEG_CUBICTO || seg == PathIterator.SEG_QUADTO) {
                return -1;
            }
            if (seg == PathIterator.SEG_CLOSE) {
                continue;
            }
            if (seg == PathIterator.SEG_LINETO && moved) {
                drawn++;
            }
            moved = seg == PathIterator.SEG_MOVETO;
            double dx = Math.min(c[0] - b.getMinX(), b.getMaxX() - c[0]);
            double dy = Math.min(c[1] - b.getMinY(), b.getMaxY() - c[1]);
            if (dx < 0.75 && dy < 0.75) {
                return -1;
            }
            if (dx >= 0.75 && dy >= 0.75) {
                if (dx > zone || dy > zone) {
                    return -1;
                }
                radius = Math.max(radius, dx + dy + Math.sqrt(2 * dx * dy));
            }
            points++;
        }
        return drawn == 1 && points >= 12 && zone >= 1 ? Math.min(radius, zone) : -1;
    }

    private void mark(Kind kind, Rectangle2D device, float alpha, boolean opaque) {
        mark(kind, device, alpha, opaque, Outline.OTHER, 0);
    }

    private void mark(Kind kind, Rectangle2D device, float alpha, boolean opaque, Outline outline, float radius) {
        if (marks.size() >= MAX_MARKS || device == null || device.isEmpty() && kind != Kind.STROKE) {
            order++;
            return;
        }
        Rectangle2D clip = clipBounds();
        Rectangle2D painted = clip == null ? device : device.createIntersection(clip);
        if (painted.getWidth() < 0 || painted.getHeight() < 0) {
            order++;
            return;
        }
        Box b = display(painted);
        if (b.right() < 0 || b.bottom() < 0 || b.x() > width || b.top() > height) {
            order++;
            return;
        }
        if (kind == Kind.TEXT && !marks.isEmpty()) {
            Mark last = marks.getLast();
            if (last.kind() == Kind.TEXT && last.order() == order - 1 && sameLine(last.box(), b)) {
                marks.set(marks.size() - 1, new Mark(Kind.TEXT, last.box().union(b), order++, alpha, opaque, outline, radius));
                return;
            }
        }
        marks.add(new Mark(kind, b, order++, alpha, opaque, outline, radius));
    }

    private static boolean sameLine(Box a, Box b) {
        float h = Math.max(a.height(), b.height());
        return Math.abs(a.centreY() - b.centreY()) < 0.5f * h && b.x() - a.right() < 2 * h;
    }

    private Box display(Rectangle2D r) {
        Point2D a = toDisplay.transform(new Point2D.Double(r.getMinX(), r.getMinY()), null);
        Point2D c = toDisplay.transform(new Point2D.Double(r.getMaxX(), r.getMaxY()), null);
        Point2D b = toDisplay.transform(new Point2D.Double(r.getMinX(), r.getMaxY()), null);
        Point2D d = toDisplay.transform(new Point2D.Double(r.getMaxX(), r.getMinY()), null);
        float x0 = (float) Math.min(Math.min(a.getX(), b.getX()), Math.min(c.getX(), d.getX()));
        float x1 = (float) Math.max(Math.max(a.getX(), b.getX()), Math.max(c.getX(), d.getX()));
        float y0 = (float) Math.min(Math.min(a.getY(), b.getY()), Math.min(c.getY(), d.getY()));
        float y1 = (float) Math.max(Math.max(a.getY(), b.getY()), Math.max(c.getY(), d.getY()));
        return new Box(x0, y0, x1, y1);
    }

    private Rectangle2D clipBounds() {
        Rectangle2D b = null;
        for (Path2D p : getGraphicsState().getCurrentClippingPaths()) {
            Rectangle2D pb = p.getBounds2D();
            b = b == null ? pb : b.createIntersection(pb);
        }
        return b;
    }

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        path.moveTo(p0.getX(), p0.getY());
        path.lineTo(p1.getX(), p1.getY());
        path.lineTo(p2.getX(), p2.getY());
        path.lineTo(p3.getX(), p3.getY());
        path.closePath();
        current = p0;
    }

    @Override
    public void moveTo(float x, float y) {
        path.moveTo(x, y);
        current = new Point2D.Float(x, y);
    }

    @Override
    public void lineTo(float x, float y) {
        if (path.getCurrentPoint() == null) {
            path.moveTo(x, y);
        } else {
            path.lineTo(x, y);
        }
        current = new Point2D.Float(x, y);
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        if (path.getCurrentPoint() == null) {
            path.moveTo(x3, y3);
        } else {
            path.curveTo(x1, y1, x2, y2, x3, y3);
        }
        current = new Point2D.Float(x3, y3);
    }

    @Override
    public Point2D getCurrentPoint() {
        return current;
    }

    @Override
    public void closePath() {
        if (path.getCurrentPoint() != null) {
            path.closePath();
        }
    }

    @Override
    public void endPath() {
        path.reset();
    }

    @Override
    public void strokePath() {
        Rectangle2D b = path.getBounds2D();
        double w = getGraphicsState().getLineWidth() / 2.0;
        mark(Kind.STROKE, new Rectangle2D.Double(b.getX() - w, b.getY() - w, b.getWidth() + 2 * w, b.getHeight() + 2 * w));
        path.reset();
    }

    @Override
    public void fillPath(int windingRule) {
        mark(Kind.FILL, path.getBounds2D());
        path.reset();
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
        mark(Kind.FILL, path.getBounds2D());
        path.reset();
    }

    @Override
    public void clip(int windingRule) {
    }

    @Override
    public void shadingFill(COSName shadingName) {
        Rectangle2D clip = clipBounds();
        mark(Kind.SHADING, clip != null ? clip : new Rectangle2D.Double(-1e6, -1e6, 2e6, 2e6));
    }

    @Override
    public void drawImage(PDImage pdImage) {
        AffineTransform ctm = getGraphicsState().getCurrentTransformationMatrix().createAffineTransform();
        float alpha = (float) getGraphicsState().getNonStrokeAlphaConstant();
        boolean opaque = alpha >= 0.99f && getGraphicsState().getSoftMask() == null && !pdImage.isStencil()
                && !(pdImage instanceof PDImageXObject x && (x.getCOSObject().containsKey(COSName.SMASK)
                        || x.getCOSObject().containsKey(COSName.MASK)));
        mark(Kind.IMAGE, ctm.createTransformedShape(new Rectangle2D.Double(0, 0, 1, 1)).getBounds2D(), alpha, opaque);
    }

    @Override
    protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement) throws IOException {
        RenderingMode mode = getGraphicsState().getTextState().getRenderingMode();
        if (mode == RenderingMode.NEITHER || mode == RenderingMode.NEITHER_CLIP) {
            return;
        }
        AffineTransform trm = textRenderingMatrix.createAffineTransform();
        double w = Math.max(0.1, displacement.getX());
        slant(trm, w);
        mark(Kind.TEXT, trm.createTransformedShape(new Rectangle2D.Double(0, -0.2, w, 1.0)).getBounds2D());
    }

    private void slant(AffineTransform trm, double advance) {
        AffineTransform t = new AffineTransform(toDisplay);
        t.concatenate(trm);
        double angle = Math.toDegrees(Math.atan2(t.getShearY(), t.getScaleX()));
        if (Math.abs(angle - 90 * Math.round(angle / 90)) < MIN_SLANT || slants.size() >= MAX_MARKS) {
            return;
        }
        Point2D origin = t.transform(new Point2D.Double(), null);
        slants.add(new Slant((float) origin.getX(), (float) origin.getY(), (float) angle,
                (float) (advance * Math.hypot(t.getScaleX(), t.getShearY())), order));
    }

    @Override
    protected void showType3Glyph(Matrix textRenderingMatrix, PDType3Font font, int code, Vector displacement) {
    }

    @Override
    protected void processType3Stream(PDType3CharProc charProc, Matrix textRenderingMatrix) {}
}

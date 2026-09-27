package stirling.software.officeconvert.extract;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDType3CharProc;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics.ImageDraw;
import stirling.software.officeconvert.extract.PageGraphics.Rule;
import stirling.software.officeconvert.extract.PageGraphics.VectorMark;

final class GraphicsCollector extends PDFGraphicsStreamEngine {

    private static final int MAX_PATH_POINTS = 200_000;

    private static final int MAX_IMAGES = 10_000;

    private static final Point2D.Float CURVE = new Point2D.Float(Float.NaN, Float.NaN);

    private final AffineTransform toDisplay;
    private final float displayScale;
    private final float pageWidth;
    private final float pageHeight;
    private final List<List<Point2D.Float>> subpaths = new ArrayList<>();
    private List<Point2D.Float> current;
    private Point2D.Float lastDevicePoint;
    private int pointBudget = MAX_PATH_POINTS;
    private final OperatorBudget budget = new OperatorBudget("reading graphics");
    private final PastBudget pastBudget;
    private final float[] unkept = {Float.NaN, 0, 0, 0};
    private int unkeptPoints;
    private boolean onlyRectangles = true;
    private boolean pathCurved;
    private final Map<Object, Integer> paintOrder = new IdentityHashMap<>();
    private int painted;
    private final Set<Object> seeThrough = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Object, PageGraphics.Outline> outlines = new IdentityHashMap<>();
    private boolean evenOdd;
    private final Map<PDResources, Map<COSName, Integer>> tones = new IdentityHashMap<>();

    private int maskedGroups;

    private List<Path2D> lastClip;
    private int lastClipCount;
    private float[] lastClipBox;
    private final ClipPath clipPath = new ClipPath();

    private static final float PANEL_AREA = 200f;
    private static final int PANEL_SEGMENTS = 400;
    private static final int MAX_PANELS = 300;
    private int panels;

    private final List<Rule> rules = new ArrayList<>();
    private final List<Fill> fills = new ArrayList<>();
    private final List<ImageDraw> images = new ArrayList<>();
    private final List<VectorMark> marks = new ArrayList<>();

    private GraphicsCollector(PDPage page, AffineTransform toDisplay, float w, float h) {
        super(page);
        this.toDisplay = toDisplay;
        this.displayScale = (float) Math.sqrt(Math.abs(toDisplay.getDeterminant()));
        this.pageWidth = w;
        this.pageHeight = h;
        this.pastBudget = new PastBudget(w, h);
    }

    static PageGraphics read(PDPage page, AffineTransform toDisplay, float width, float height)
            throws IOException {
        GraphicsCollector c = new GraphicsCollector(page, toDisplay, width, height);
        try {
            c.processPage(page);
            Annotations.show(c, page);
        } catch (IOException e) {
            BrokenOperators.brokenStream(e);
        }
        HiddenFills.removeRules(c.rules, c.fills, c.paintOrder, c.seeThrough);
        HiddenFills.remove(c.fills, c.paintOrder, c.seeThrough);
        BlankPaint.remove(c.fills, c.marks, c.images, c.rules, c.paintOrder, width * height);
        return new PageGraphics(c.rules, c.fills, c.images, c.marks, c.pastBudget.areas(), c.paintOrder, c.seeThrough,
                c.outlines);
    }

    private Point2D.Float display(double x, double y) {
        Point2D.Float p = new Point2D.Float();
        toDisplay.transform(new Point2D.Double(x, y), p);
        return p;
    }

    private boolean spend(int points) {
        pointBudget -= points;
        return pointBudget > 0;
    }

    private void unkept(double x, double y) {
        float fx = (float) x;
        float fy = (float) y;
        unkeptPoints++;
        if (Float.isNaN(unkept[0])) {
            unkept[0] = unkept[2] = fx;
            unkept[1] = unkept[3] = fy;
        } else {
            unkept[0] = Math.min(unkept[0], fx);
            unkept[1] = Math.min(unkept[1], fy);
            unkept[2] = Math.max(unkept[2], fx);
            unkept[3] = Math.max(unkept[3], fy);
        }
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        if (!budget.run(operator)) {
            return;
        }
        try {
            super.processOperator(operator, operands);
        } catch (IOException | RuntimeException e) {
            BrokenOperators.skip(operator, e);
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
        if (!budget.form()) {
            return;
        }
        boolean masked = getGraphicsState().getSoftMask() != null;
        maskedGroups += masked ? 1 : 0;
        try {
            super.showTransparencyGroup(form);
        } finally {
            maskedGroups -= masked ? 1 : 0;
        }
    }

    @Override
    protected void showType3Glyph(
            Matrix textRenderingMatrix, PDType3Font font, int code, Vector displacement) {
    }

    @Override
    protected void processType3Stream(PDType3CharProc charProc, Matrix textRenderingMatrix) {}

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        clipPath.rectangle((float) p0.getX(), (float) p0.getY(), (float) p1.getX(), (float) p1.getY(),
                (float) p2.getX(), (float) p2.getY(), (float) p3.getX(), (float) p3.getY());
        onlyRectangles &= axisAligned(p0, p1, p2, p3);
        if (!spend(5)) {
            unkept(p0.getX(), p0.getY());
            unkept(p2.getX(), p2.getY());
            unkept(p1.getX(), p1.getY());
            unkept(p3.getX(), p3.getY());
            return;
        }
        subpaths.add(
                new ArrayList<>(
                        List.of(
                                display(p0.getX(), p0.getY()),
                                display(p1.getX(), p1.getY()),
                                display(p2.getX(), p2.getY()),
                                display(p3.getX(), p3.getY()),
                                display(p0.getX(), p0.getY()))));
        current = null;
        lastDevicePoint = new Point2D.Float((float) p0.getX(), (float) p0.getY());
    }

    @Override
    public void moveTo(float x, float y) {
        clipPath.moveTo(x, y);
        onlyRectangles = false;
        lastDevicePoint = new Point2D.Float(x, y);
        if (!spend(1)) {
            unkept(x, y);
            current = null;
            return;
        }
        current = new ArrayList<>();
        current.add(display(x, y));
        subpaths.add(current);
    }

    @Override
    public void lineTo(float x, float y) {
        clipPath.lineTo(x, y);
        if (current == null) {
            moveTo(x, y);
            return;
        }
        if (!spend(1)) {
            unkept(x, y);
            return;
        }
        current.add(display(x, y));
        lastDevicePoint = new Point2D.Float(x, y);
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        clipPath.curveTo(x1, y1, x2, y2, x3, y3);
        pathCurved = true;
        if (current == null) {
            moveTo(x3, y3);
            return;
        }
        if (!spend(3)) {
            unkept(x1, y1);
            unkept(x2, y2);
            unkept(x3, y3);
            return;
        }
        current.add(CURVE);
        current.add(display(x1, y1));
        current.add(display(x2, y2));
        current.add(display(x3, y3));
        lastDevicePoint = new Point2D.Float(x3, y3);
    }

    @Override
    public Point2D getCurrentPoint() {
        return lastDevicePoint == null ? new Point2D.Float() : lastDevicePoint;
    }

    @Override
    public void closePath() {
        clipPath.close();
        if (current != null && !current.isEmpty()) {
            current.add(current.getFirst());
        }
    }

    @Override
    public void endPath() {
        clipPath.end(getGraphicsState());
        subpaths.clear();
        current = null;
        pathCurved = false;
        unkept[0] = Float.NaN;
        unkeptPoints = 0;
        onlyRectangles = true;
    }

    @Override
    public void strokePath() {
        emit(false, true);
        endPath();
    }

    @Override
    public void fillPath(int windingRule) {
        evenOdd = windingRule == Path2D.WIND_EVEN_ODD;
        emit(true, false);
        endPath();
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
        evenOdd = windingRule == Path2D.WIND_EVEN_ODD;
        emit(true, true);
        endPath();
    }

    @Override
    public void clip(int windingRule) {
        clipPath.clipOnEnd(windingRule);
    }

    @Override
    public void shadingFill(COSName shadingName) {
        float[] clip = clipBox();
        VectorMark shading = new VectorMark(clip[0], clip[1], clip[2], clip[3], 1, true, false, true, true, 0, false, 0, 0, false);
        marks.add(shading);
        paintOrder.put(shading, painted++);
    }

    @Override
    public void drawImage(PDImage pdImage) throws IOException {
        if (images.size() >= MAX_IMAGES) {
            return;
        }
        PDGraphicsState gs = getGraphicsState();
        Matrix ctm = gs.getCurrentTransformationMatrix();
        AffineTransform at = ctm.createAffineTransform();
        Point2D.Float o = unit(at, 0, 0);
        Point2D.Float r = unit(at, 1, 0);
        Point2D.Float u = unit(at, 0, 1);
        Point2D.Float ur = unit(at, 1, 1);
        float minX = Math.min(Math.min(o.x, r.x), Math.min(u.x, ur.x));
        float maxX = Math.max(Math.max(o.x, r.x), Math.max(u.x, ur.x));
        float minY = Math.min(Math.min(o.y, r.y), Math.min(u.y, ur.y));
        float maxY = Math.max(Math.max(o.y, r.y), Math.max(u.y, ur.y));
        if (maxX - minX < 0.5f || maxY - minY < 0.5f) {
            return;
        }
        float[] clip = clipBox();
        float cx = Math.max(minX, clip[0]);
        float cy = Math.max(minY, clip[1]);
        float cr = Math.min(maxX, clip[2]);
        float cb = Math.min(maxY, clip[3]);
        if (cr - cx < 0.5f || cb - cy < 0.5f) {
            return;
        }

        float upX = u.x - o.x;
        float upY = u.y - o.y;
        float rightX = r.x - o.x;
        float rightY = r.y - o.y;
        int turns;
        if (Math.abs(upY) >= Math.abs(upX)) {
            turns = upY < 0 ? 0 : 2;
        } else {
            turns = upX > 0 ? 1 : 3;
        }
        float expectX = switch (turns) {
            case 0 -> 1;
            case 2 -> -1;
            default -> 0;
        };
        float expectY = switch (turns) {
            case 1 -> 1;
            case 3 -> -1;
            default -> 0;
        };
        boolean flipH = rightX * expectX + rightY * expectY < 0;
        double upLen = Math.hypot(upX, upY);
        double rightLen = Math.hypot(rightX, rightY);
        boolean skewed =
                Math.min(Math.abs(upX), Math.abs(upY)) > upLen * 0.02
                        || Math.min(Math.abs(rightX), Math.abs(rightY)) > rightLen * 0.02
                        || gs.getSoftMask() == null && maskedGroups == 0 && shapedClip(at);

        int stencil = -1;
        if (pdImage.isStencil()) {
            stencil = toRgb(gs.getNonStrokingColor(), 0);
        }
        float alpha = (float) gs.getNonStrokeAlphaConstant();
        if (alpha <= 0.01f) {
            return;
        }
        COSBase key = pdImage instanceof PDImageXObject x ? x.getCOSObject() : null;
        ImageDraw draw = new ImageDraw(minX, minY, maxX, maxY, cx, cy, cr, cb, pdImage, key, turns, flipH, false, skewed, stencil,
                Math.min(1f, alpha));
        images.add(draw);
        paintOrder.put(draw, painted++);
    }

    private Point2D.Float unit(AffineTransform ctm, double x, double y) {
        Point2D.Double user = new Point2D.Double();
        ctm.transform(new Point2D.Double(x, y), user);
        return display(user.x, user.y);
    }

    private boolean shapedClip(AffineTransform at) {
        Shape image = at.createTransformedShape(new Rectangle2D.Double(0, 0, 1, 1));
        for (Path2D clip : getGraphicsState().getCurrentClippingPaths()) {
            if (!rectangle(clip) && !clip.contains(image.getBounds2D())) {
                return true;
            }
        }
        return false;
    }

    private static boolean rectangle(Path2D path) {
        double[] c = new double[6];
        double lastX = Double.NaN;
        double lastY = Double.NaN;
        for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_CUBICTO || type == PathIterator.SEG_QUADTO) {
                return false;
            }
            if (type == PathIterator.SEG_LINETO && Math.abs(c[0] - lastX) > 0.01 && Math.abs(c[1] - lastY) > 0.01) {
                return false;
            }
            if (type != PathIterator.SEG_CLOSE) {
                lastX = c[0];
                lastY = c[1];
            }
        }
        return true;
    }

    private float[] clipBox() {
        List<Path2D> clips = getGraphicsState().getCurrentClippingPaths();
        if (clips != lastClip || clips.size() != lastClipCount || lastClipBox == null) {
            lastClip = clips;
            lastClipCount = clips.size();
            float[] box = {0, 0, pageWidth, pageHeight};
            Rectangle2D b = null;
            for (Path2D path : clips) {
                Rectangle2D pb = path.getBounds2D();
                b = b == null ? pb : b.createIntersection(pb);
            }
            if (b != null) {
                if (b.isEmpty()) {
                    b = new Rectangle2D.Double(b.getX(), b.getY(), 0, 0);
                }
                Point2D.Float a = display(b.getMinX(), b.getMinY());
                Point2D.Float c = display(b.getMaxX(), b.getMaxY());
                box[0] = Math.max(0, Math.min(a.x, c.x));
                box[1] = Math.max(0, Math.min(a.y, c.y));
                box[2] = Math.min(pageWidth, Math.max(a.x, c.x));
                box[3] = Math.min(pageHeight, Math.max(a.y, c.y));
            }
            lastClipBox = box;
        }
        return lastClipBox;
    }

    private float lineWidth() {
        PDGraphicsState gs = getGraphicsState();
        Matrix ctm = gs.getCurrentTransformationMatrix();
        double det =
                Math.abs(ctm.getScaleX() * ctm.getScaleY() - ctm.getShearX() * ctm.getShearY());
        float w = (float) (gs.getLineWidth() * Math.sqrt(det)) * displayScale;
        return w <= 0 ? 0.5f : w;
    }

    private void emit(boolean fill, boolean stroke) {
        PDGraphicsState gs = getGraphicsState();
        fill &= gs.getNonStrokeAlphaConstant() > 0.01;
        stroke &= gs.getAlphaConstant() > 0.01;
        if (!fill && !stroke) {
            return;
        }
        if (pointBudget <= 0) {
            pastBudget(fill, stroke);
            return;
        }
        if (subpaths.isEmpty()) {
            return;
        }
        boolean pattern = fill && gs.getNonStrokingColor().getColorSpace() instanceof PDPattern;
        int fillRgb = onPaper(toRgb(gs.getNonStrokingColor(), 0xFFFFFF), gs.getNonStrokeAlphaConstant());
        if (pattern) {
            int tone = tones.computeIfAbsent(getResources(), k -> new HashMap<>())
                    .computeIfAbsent(gs.getNonStrokingColor().getPatternName(), k -> PatternTone.of(gs.getNonStrokingColor(), getResources()));
            if (tone >= 0) {
                pattern = false;
                fillRgb = onPaper(tone, gs.getNonStrokeAlphaConstant());
            }
        }
        int strokeRgb = onPaper(toRgb(gs.getStrokingColor(), 0), gs.getAlphaConstant());
        PathShapes.Paint paint = new PathShapes.Paint(fill, stroke, pattern, fillRgb, strokeRgb, lineWidth(), evenOdd);
        PathShapes.Shapes shapes = PathShapes.classify(subpaths, pathCurved, paint, clipBox());
        rules.addAll(shapes.rules());
        fills.addAll(shapes.fills());
        if (shapes.mark() != null) {
            marks.add(shapes.mark());
            paintOrder.put(shapes.mark(), painted);
        }
        shapes.rules().forEach(r -> paintOrder.put(r, painted));
        shapes.fills().forEach(f -> paintOrder.put(f, painted));
        BlendMode blend = gs.getBlendMode();
        boolean blended = gs.getSoftMask() != null
                || blend != null && blend != BlendMode.NORMAL && blend != BlendMode.COMPATIBLE;
        if (pattern || gs.getNonStrokeAlphaConstant() < 0.99 || blended) {
            seeThrough.addAll(shapes.fills());
        }
        boolean faint = fill ? gs.getNonStrokeAlphaConstant() < 0.99 : gs.getAlphaConstant() < 0.99;
        if (shapes.mark() != null && faint) {
            seeThrough.add(shapes.mark());
            if (fill) {
                outlines.put(shapes.mark(), new PageGraphics.Outline(outline(),
                        toRgb(gs.getNonStrokingColor(), 0xFFFFFF), (float) gs.getNonStrokeAlphaConstant()));
            }
        } else if (shapes.mark() != null && fill && panel(shapes.mark()) && panels++ < MAX_PANELS) {
            outlines.put(shapes.mark(), new PageGraphics.Outline(outline(), shapes.mark().rgb(), 1f));
        }
        painted++;
    }

    private void pastBudget(boolean fill, boolean stroke) {
        if (onlyRectangles || !stroke && toRgb(getGraphicsState().getNonStrokingColor(), 0xFFFFFF) == 0xFFFFFF) {
            return;
        }
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        int points = unkeptPoints;
        for (List<Point2D.Float> sp : subpaths) {
            points += sp.size();
            for (Point2D.Float p : sp) {
                if (p != CURVE) {
                    widen(b, p);
                }
            }
        }
        if (!Float.isNaN(unkept[0])) {
            widen(b, display(unkept[0], unkept[1]));
            widen(b, display(unkept[2], unkept[3]));
            widen(b, display(unkept[0], unkept[3]));
            widen(b, display(unkept[2], unkept[1]));
        }
        float pad = stroke ? lineWidth() / 2f : 0;
        float[] clip = clipBox();
        float x = Math.max(b[0] - pad, clip[0]);
        float top = Math.max(b[1] - pad, clip[1]);
        float right = Math.min(b[2] + pad, clip[2]);
        float bottom = Math.min(b[3] + pad, clip[3]);
        float w = right - x;
        float h = bottom - top;
        if (w < 0 || h < 0 || Math.min(w, h) < 1.5f && Math.max(w, h) > 24f || w > pageWidth * 0.9f && h > pageHeight * 0.9f) {
            return;
        }
        pastBudget.add(x, top, right, bottom, points);
    }

    private static boolean axisAligned(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        return (Math.abs(p0.getX() - p1.getX()) < 0.01 || Math.abs(p0.getY() - p1.getY()) < 0.01)
                && (Math.abs(p1.getX() - p2.getX()) < 0.01 || Math.abs(p1.getY() - p2.getY()) < 0.01);
    }

    private static void widen(float[] b, Point2D.Float p) {
        b[0] = Math.min(b[0], p.x);
        b[1] = Math.min(b[1], p.y);
        b[2] = Math.max(b[2], p.x);
        b[3] = Math.max(b[3], p.y);
    }

    private static boolean panel(VectorMark m) {
        return m.filled() && !m.shading() && m.segments() <= PANEL_SEGMENTS
                && (m.right() - m.x()) * (m.bottom() - m.top()) >= PANEL_AREA;
    }

    private Path2D outline() {
        Path2D.Float p = new Path2D.Float(evenOdd ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
        for (List<Point2D.Float> sp : subpaths) {
            if (sp.isEmpty()) {
                continue;
            }
            p.moveTo(sp.getFirst().x, sp.getFirst().y);
            for (int i = 1; i < sp.size(); i++) {
                Point2D.Float q = sp.get(i);
                if (q != CURVE) {
                    p.lineTo(q.x, q.y);
                } else if (i + 3 < sp.size()) {
                    Point2D.Float c1 = sp.get(i + 1);
                    Point2D.Float c2 = sp.get(i + 2);
                    Point2D.Float to = sp.get(i + 3);
                    p.curveTo(c1.x, c1.y, c2.x, c2.y, to.x, to.y);
                    i += 3;
                }
            }
        }
        return p;
    }

    static int onPaper(int rgb, double alpha) {
        if (alpha >= 0.99) {
            return rgb;
        }
        int r = (int) Math.round(255 - alpha * (255 - ((rgb >> 16) & 0xFF)));
        int g = (int) Math.round(255 - alpha * (255 - ((rgb >> 8) & 0xFF)));
        int b = (int) Math.round(255 - alpha * (255 - (rgb & 0xFF)));
        return r << 16 | g << 8 | b;
    }

    static int toRgb(PDColor colour, int fallback) {
        if (colour == null) {
            return fallback;
        }
        try {
            return colour.toRGB() & 0xFFFFFF;
        } catch (IOException | RuntimeException e) {
            return fallback;
        }
    }
}

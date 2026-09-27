package stirling.software.officeconvert.table;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;
import org.apache.pdfbox.util.Matrix;

import stirling.software.officeconvert.table.PageContent.FillBox;
import stirling.software.officeconvert.table.PageContent.Ruling;

final class PageShapes extends PDFGraphicsStreamEngine {

    private static final int MAX_PATH_POINTS = 150_000;

    private static final float RULE_MAX_THICKNESS = 2.5f;

    private static final float RULE_MIN_LENGTH = 2f;

    private static final float AXIS_TOLERANCE = 1f;

    private static final Point2D.Float CURVE = new Point2D.Float(Float.NaN, Float.NaN);

    private final AffineTransform toDisplay;
    private final List<List<Point2D.Float>> subpaths = new ArrayList<>();
    private List<Point2D.Float> current;
    private Point2D.Float lastDevicePoint;
    private int pointBudget = MAX_PATH_POINTS;

    private final List<Ruling> horizontals = new ArrayList<>();
    private final List<Ruling> verticals = new ArrayList<>();
    private final List<FillBox> fills = new ArrayList<>();

    private PageShapes(PDPage page, AffineTransform toDisplay) {
        super(page);
        this.toDisplay = toDisplay;
    }

    record Result(List<Ruling> horizontals, List<Ruling> verticals, List<FillBox> fills) {}

    static Result read(PDPage page, AffineTransform toDisplay) throws IOException {
        PageShapes shapes = new PageShapes(page, toDisplay);
        shapes.processPage(page);
        return new Result(shapes.horizontals, shapes.verticals, shapes.fills);
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

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        if (!spend(5)) {
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
        lastDevicePoint = new Point2D.Float(x, y);
        if (!spend(1)) {
            current = null;
            return;
        }
        current = new ArrayList<>();
        current.add(display(x, y));
        subpaths.add(current);
    }

    @Override
    public void lineTo(float x, float y) {
        if (current == null) {
            moveTo(x, y);
            return;
        }
        if (!spend(1)) {
            return;
        }
        current.add(display(x, y));
        lastDevicePoint = new Point2D.Float(x, y);
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        if (current == null) {
            moveTo(x3, y3);
            return;
        }
        if (!spend(2)) {
            return;
        }
        current.add(CURVE);
        current.add(display(x3, y3));
        lastDevicePoint = new Point2D.Float(x3, y3);
    }

    @Override
    public Point2D getCurrentPoint() {
        return lastDevicePoint == null ? new Point2D.Float() : lastDevicePoint;
    }

    @Override
    public void closePath() {
        if (current != null && !current.isEmpty()) {
            current.add(current.getFirst());
        }
    }

    @Override
    public void endPath() {
        subpaths.clear();
        current = null;
    }

    @Override
    public void strokePath() {
        emitStroke();
        endPath();
    }

    @Override
    public void fillPath(int windingRule) {
        emitFill();
        endPath();
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
        emitFill();
        emitStroke();
        endPath();
    }

    @Override
    public void clip(int windingRule) {
    }

    @Override
    public void drawImage(PDImage pdImage) {}

    @Override
    public void shadingFill(COSName shadingName) {}

    private float lineWidth() {
        PDGraphicsState gs = getGraphicsState();
        Matrix ctm = gs.getCurrentTransformationMatrix();
        double det =
                Math.abs(ctm.getScaleX() * ctm.getScaleY() - ctm.getShearX() * ctm.getShearY());
        float w = (float) (gs.getLineWidth() * Math.sqrt(det));
        return w <= 0 ? 0.5f : w;
    }

    private void emitStroke() {
        if (pointBudget <= 0) {
            return;
        }
        float thickness = lineWidth();
        int rgb = Colours.toRgb(getGraphicsState().getStrokingColor(), 0);
        for (List<Point2D.Float> path : subpaths) {
            for (int i = 1; i < path.size(); i++) {
                Point2D.Float a = path.get(i - 1);
                Point2D.Float b = path.get(i);
                if (!isCurve(a) && !isCurve(b)) {
                    addSegment(a, b, thickness, rgb);
                }
            }
        }
    }

    private void addSegment(Point2D.Float a, Point2D.Float b, float thickness, int rgb) {
        float dx = Math.abs(a.x - b.x);
        float dy = Math.abs(a.y - b.y);
        if (dy <= AXIS_TOLERANCE && dx >= RULE_MIN_LENGTH) {
            horizontals.add(
                    new Ruling(
                            (a.y + b.y) / 2f,
                            Math.min(a.x, b.x),
                            Math.max(a.x, b.x),
                            thickness,
                            rgb));
        } else if (dx <= AXIS_TOLERANCE && dy >= RULE_MIN_LENGTH) {
            verticals.add(
                    new Ruling(
                            (a.x + b.x) / 2f,
                            Math.min(a.y, b.y),
                            Math.max(a.y, b.y),
                            thickness,
                            rgb));
        }
    }

    private void emitFill() {
        if (pointBudget <= 0) {
            return;
        }
        int rgb = Colours.toRgb(getGraphicsState().getNonStrokingColor(), 0xFFFFFF);
        for (List<Point2D.Float> path : subpaths) {
            if (path.size() < 3 || path.stream().anyMatch(PageShapes::isCurve)) {
                continue;
            }
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            for (Point2D.Float p : path) {
                minX = Math.min(minX, p.x);
                minY = Math.min(minY, p.y);
                maxX = Math.max(maxX, p.x);
                maxY = Math.max(maxY, p.y);
            }
            float w = maxX - minX;
            float h = maxY - minY;
            if (h <= RULE_MAX_THICKNESS && w >= RULE_MIN_LENGTH && w > h) {
                horizontals.add(new Ruling((minY + maxY) / 2f, minX, maxX, h, rgb));
            } else if (w <= RULE_MAX_THICKNESS && h >= RULE_MIN_LENGTH && h > w) {
                verticals.add(new Ruling((minX + maxX) / 2f, minY, maxY, w, rgb));
            } else if (w > RULE_MAX_THICKNESS && h > RULE_MAX_THICKNESS && isBox(path)) {
                fills.add(new FillBox(minX, minY, maxX, maxY, rgb));
            }
        }
    }

    private static boolean isCurve(Point2D.Float p) {
        return Float.isNaN(p.x);
    }

    private static boolean isBox(List<Point2D.Float> path) {
        for (int i = 1; i < path.size(); i++) {
            Point2D.Float a = path.get(i - 1);
            Point2D.Float b = path.get(i);
            if (Math.abs(a.x - b.x) > AXIS_TOLERANCE && Math.abs(a.y - b.y) > AXIS_TOLERANCE) {
                return false;
            }
        }
        return true;
    }
}

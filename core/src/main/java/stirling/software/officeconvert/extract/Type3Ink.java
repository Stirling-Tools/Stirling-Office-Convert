package stirling.software.officeconvert.extract;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType3CharProc;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

final class Type3Ink extends PDFGraphicsStreamEngine {

    private static final long MAX_OPERATORS = 20_000;

    private static final long MAX_MASK_PIXELS = 1L << 20;

    private static final int MAX_FORMS = 16;

    private static final long MAX_ELEMENTS = 20_000;

    private static final int MAX_AREA_ELEMENTS = 256;

    private final UnmappedGlyphs owner;
    private final GeneralPath ink = new GeneralPath();
    private final List<Path2D> evenOdd = new ArrayList<>();
    private final GeneralPath path = new GeneralPath();
    private long operators;
    private long elements;
    private long maskPixels;
    private int forms;
    private boolean failed;

    private Type3Ink(UnmappedGlyphs owner) {
        super(new PDPage(new PDRectangle(1, 1)));
        this.owner = owner;
    }

    static GeneralPath of(PDType3CharProc proc, UnmappedGlyphs owner) {
        Type3Ink engine = new Type3Ink(owner);
        try {
            engine.processChildStream(proc, engine.getPage());
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return engine.failed ? null : engine.painted();
    }

    private GeneralPath painted() {
        if (evenOdd.isEmpty()) {
            return ink;
        }
        if (evenOdd.size() == 1 && ink.getCurrentPoint() == null) {
            return new GeneralPath(evenOdd.getFirst());
        }
        int count = elements(ink);
        for (Path2D piece : evenOdd) {
            count += elements(piece);
        }
        if (count > MAX_AREA_ELEMENTS || !owner.spendArea(count)) {
            return null;
        }
        Area area = new Area(ink);
        for (Path2D piece : evenOdd) {
            area.add(new Area(piece));
        }
        return new GeneralPath(area);
    }

    private static int elements(Shape shape) {
        int count = 0;
        for (var it = shape.getPathIterator(null); !it.isDone(); it.next()) {
            count++;
        }
        return count;
    }

    private boolean grow(long more) {
        elements += more;
        if (elements > MAX_ELEMENTS) {
            failed = true;
        }
        return !failed;
    }

    @Override
    protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
        if (failed || ++operators > MAX_OPERATORS || !owner.operators().run(operator)) {
            failed = true;
            return;
        }
        super.processOperator(operator, operands);
    }

    @Override
    public void showForm(PDFormXObject form) throws IOException {
        if (++forms > MAX_FORMS) {
            failed = true;
            return;
        }
        super.showForm(form);
    }

    @Override
    public void showTransparencyGroup(PDTransparencyGroup form) {
        failed = true;
    }

    @Override
    protected void showGlyph(Matrix textRenderingMatrix, org.apache.pdfbox.pdmodel.font.PDFont font, int code,
            Vector displacement) {
        failed = true;
    }

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        if (!grow(5)) {
            return;
        }
        path.moveTo(p0.getX(), p0.getY());
        path.lineTo(p1.getX(), p1.getY());
        path.lineTo(p2.getX(), p2.getY());
        path.lineTo(p3.getX(), p3.getY());
        path.closePath();
    }

    @Override
    public void drawImage(PDImage image) throws IOException {
        if (!image.isStencil()) {
            failed = true;
            return;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        maskPixels += (long) w * h;
        if (w <= 0 || h <= 0 || maskPixels > MAX_MASK_PIXELS) {
            failed = true;
            return;
        }
        BufferedImage mask = image.getStencilImage(Color.BLACK);
        if (mask == null || mask.getWidth() != w || mask.getHeight() != h) {
            failed = true;
            return;
        }
        AffineTransform at = getGraphicsState().getCurrentTransformationMatrix().createAffineTransform();
        at.scale(1.0 / w, -1.0 / h);
        at.translate(0, -h);
        Path2D.Double runs = new Path2D.Double();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            mask.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; ) {
                if ((row[x] >>> 24) < 128) {
                    x++;
                    continue;
                }
                int from = x;
                while (x < w && (row[x] >>> 24) >= 128) {
                    x++;
                }
                if (!grow(5)) {
                    return;
                }
                runs.append(new Rectangle2D.Double(from, y, x - from, 1), false);
            }
        }
        ink.append(at.createTransformedShape(runs), false);
    }

    @Override
    public void clip(int windingRule) {
    }

    @Override
    public void moveTo(float x, float y) {
        if (!grow(1)) {
            return;
        }
        path.moveTo(x, y);
    }

    @Override
    public void lineTo(float x, float y) {
        if (!grow(1)) {
            return;
        }
        if (path.getCurrentPoint() == null) {
            path.moveTo(x, y);
        } else {
            path.lineTo(x, y);
        }
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        if (!grow(1)) {
            return;
        }
        if (path.getCurrentPoint() == null) {
            path.moveTo(x3, y3);
        } else {
            path.curveTo(x1, y1, x2, y2, x3, y3);
        }
    }

    @Override
    public Point2D getCurrentPoint() {
        return path.getCurrentPoint();
    }

    @Override
    public void closePath() {
        path.closePath();
    }

    @Override
    public void endPath() {
        path.reset();
    }

    @Override
    public void strokePath() {
        Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
        float width = getGraphicsState().getLineWidth() * (float) Math.sqrt(Math.abs(
                ctm.getScaleX() * ctm.getScaleY() - ctm.getShearX() * ctm.getShearY()));
        Shape stroked = new BasicStroke(Math.max(width, 1e-6f)).createStrokedShape(path);
        ink.append(stroked, false);
        path.reset();
    }

    @Override
    public void fillPath(int windingRule) {
        if (windingRule == Path2D.WIND_EVEN_ODD) {
            Path2D.Double filled = new Path2D.Double(path);
            filled.setWindingRule(Path2D.WIND_EVEN_ODD);
            evenOdd.add(filled);
        } else {
            ink.append(path, false);
        }
        path.reset();
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
        GeneralPath copy = new GeneralPath(path);
        fillPath(windingRule);
        path.append(copy, false);
        strokePath();
    }

    @Override
    public void shadingFill(COSName shadingName) {
        failed = true;
    }
}

package stirling.software.officeconvert.pdfa;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType3CharProc;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

final class Type3Outlines extends PDFGraphicsStreamEngine {

    private static final double MIN_STROKE = 0.004;

    private static final long MAX_IMAGE_PIXELS = 4L << 20;

    private static final int MAX_RECTANGLES = 20_000;

    private static final int MAX_NESTING = 4;

    private static final int MAX_SHAPES = 5_000;

    private final GeneralPath current = new GeneralPath();

    private final List<Shape> shapes = new ArrayList<>();

    private int nesting;

    private Type3Outlines() {
        super(new PDPage());
    }

    static GeneralPath glyph(PDType3CharProc proc) throws IOException {
        Type3Outlines engine = new Type3Outlines();
        engine.processChildStream(proc, engine.getPage());
        return engine.outline();
    }

    private GeneralPath outline() {
        Area area = new Area();
        for (Shape s : shapes) {
            area.add(s instanceof Area a ? a : new Area(s));
        }
        GeneralPath out = new GeneralPath(area);
        out.transform(AffineTransform.getScaleInstance(1000, 1000));
        return out;
    }

    private void paint(Shape s) {
        if (shapes.size() < MAX_SHAPES && !s.getBounds2D().isEmpty()) {
            shapes.add(s);
        }
    }

    private void fill(int windingRule) {
        GeneralPath p = new GeneralPath(current);
        p.setWindingRule(windingRule);
        paint(p);
    }

    private void stroke() {
        Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
        double scale = Math.sqrt(Math.abs(ctm.getScaleX() * ctm.getScaleY() - ctm.getShearX() * ctm.getShearY()));
        float width = (float) Math.max(MIN_STROKE, getGraphicsState().getLineWidth() * scale);
        int cap = Math.max(0, Math.min(2, getGraphicsState().getLineCap()));
        int join = Math.max(0, Math.min(2, getGraphicsState().getLineJoin()));
        float miter = Math.max(1, getGraphicsState().getMiterLimit());
        int[] caps = {BasicStroke.CAP_BUTT, BasicStroke.CAP_ROUND, BasicStroke.CAP_SQUARE};
        int[] joins = {BasicStroke.JOIN_MITER, BasicStroke.JOIN_ROUND, BasicStroke.JOIN_BEVEL};
        paint(new BasicStroke(width, caps[cap], joins[join], miter).createStrokedShape(current));
    }

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        current.moveTo(p0.getX(), p0.getY());
        current.lineTo(p1.getX(), p1.getY());
        current.lineTo(p2.getX(), p2.getY());
        current.lineTo(p3.getX(), p3.getY());
        current.closePath();
    }

    @Override
    public void drawImage(PDImage image) throws IOException {
        long pixels = (long) image.getWidth() * image.getHeight();
        if (pixels <= 0 || pixels > MAX_IMAGE_PIXELS) {
            return;
        }
        BufferedImage img = image.isStencil() ? image.getStencilImage(Color.BLACK) : image.getImage();
        if (img == null) {
            return;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        Path2D.Double ink = new Path2D.Double(Path2D.WIND_NON_ZERO);
        int rectangles = 0;
        Map<Long, Integer> open = new HashMap<>();
        for (int y = 0; y <= h && rectangles < MAX_RECTANGLES; y++) {
            Set<Long> runs = new HashSet<>();
            for (int x = 0; y < h && x < w; x++) {
                if (inked(img.getRGB(x, y), image.isStencil())) {
                    int end = x;
                    while (end < w && inked(img.getRGB(end, y), image.isStencil())) {
                        end++;
                    }
                    runs.add((long) x << 32 | end);
                    x = end;
                }
            }
            for (Iterator<Map.Entry<Long, Integer>> it = open.entrySet().iterator(); it.hasNext();) {
                Map.Entry<Long, Integer> e = it.next();
                if (!runs.contains(e.getKey())) {
                    rectangle(ink, (int) (e.getKey() >>> 32), (int) (long) e.getKey(), e.getValue(), y, w, h);
                    rectangles++;
                    it.remove();
                }
            }
            for (long run : runs) {
                open.putIfAbsent(run, y);
            }
        }
        if (rectangles == 0) {
            return;
        }
        Shape glyph = getGraphicsState().getCurrentTransformationMatrix().createAffineTransform()
                .createTransformedShape(ink);
        paint(glyph);
    }

    private static void rectangle(Path2D.Double out, int x0, int x1, int y0, int y1, int w, int h) {
        out.append(new Rectangle2D.Double((double) x0 / w, 1 - (double) y1 / h, (double) (x1 - x0) / w,
                (double) (y1 - y0) / h), false);
    }

    private static boolean inked(int argb, boolean stencil) {
        int alpha = argb >>> 24;
        if (stencil) {
            return alpha >= 128;
        }
        int r = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        return alpha >= 128 && r * 299 + g * 587 + b * 114 < 128_000;
    }

    @Override
    protected void showFontGlyph(Matrix trm, PDFont font, int code, Vector displacement) throws IOException {
        if (!(font instanceof PDVectorFont v)) {
            return;
        }
        GeneralPath p = v.getNormalizedPath(code);
        if (p == null) {
            return;
        }
        AffineTransform at = trm.createAffineTransform();
        at.scale(0.001, 0.001);
        paint(at.createTransformedShape(p));
    }

    @Override
    protected void showType3Glyph(Matrix trm, PDType3Font font, int code, Vector displacement) throws IOException {
        if (nesting >= MAX_NESTING) {
            return;
        }
        nesting++;
        try {
            super.showType3Glyph(trm, font, code, displacement);
        } finally {
            nesting--;
        }
    }

    @Override
    public void clip(int windingRule) {
    }

    @Override
    public void moveTo(float x, float y) {
        current.moveTo(x, y);
    }

    @Override
    public void lineTo(float x, float y) {
        if (current.getCurrentPoint() == null) {
            current.moveTo(x, y);
        } else {
            current.lineTo(x, y);
        }
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        if (current.getCurrentPoint() == null) {
            current.moveTo(x3, y3);
        } else {
            current.curveTo(x1, y1, x2, y2, x3, y3);
        }
    }

    @Override
    public Point2D getCurrentPoint() {
        return current.getCurrentPoint();
    }

    @Override
    public void closePath() {
        if (current.getCurrentPoint() != null) {
            current.closePath();
        }
    }

    @Override
    public void endPath() {
        current.reset();
    }

    @Override
    public void strokePath() {
        stroke();
        current.reset();
    }

    @Override
    public void fillPath(int windingRule) {
        fill(windingRule);
        current.reset();
    }

    @Override
    public void fillAndStrokePath(int windingRule) {
        fill(windingRule);
        stroke();
        current.reset();
    }

    @Override
    public void shadingFill(COSName shadingName) {
    }
}

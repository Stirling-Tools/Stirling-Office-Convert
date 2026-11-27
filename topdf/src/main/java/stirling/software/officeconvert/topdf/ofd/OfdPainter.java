package stirling.software.officeconvert.topdf.ofd;

import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;

import org.w3c.dom.Element;

import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.io.Dom;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.pdf.Crop;
import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.Gradient;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.Stroke;

final class OfdPainter {

    static final int MAX_DEPTH = 32;

    static final int MAX_OBJECTS = 1_000_000;

    static final int MAX_CLIP_CHARS = 200_000;

    static final double DEFAULT_LINE_WIDTH = 0.353;

    static final int MAX_TILES = 4_096;

    private final PdfCanvas canvas;

    private final OfdResources res;

    private final OfdResources.ColorSpace defaultSpace;

    private final OfdText text;

    private final OfdImages images;

    private int objects;

    OfdPainter(PdfCanvas canvas, OfdResources res, OfdResources.ColorSpace defaultSpace, OfdText text,
            OfdImages images) {
        this.canvas = canvas;
        this.res = res;
        this.defaultSpace = defaultSpace;
        this.text = text;
        this.images = images;
    }

    void block(Element container, Element drawParam, int depth) throws IOException {
        if (container == null || depth > MAX_DEPTH) {
            return;
        }
        for (Element e : Dom.kids(container)) {
            if (++objects > MAX_OBJECTS) {
                return;
            }
            if ((objects & 255) == 0) {
                OfficeZip.checkNotInterrupted();
            }
            String kind = Dom.local(e);
            switch (kind) {
                case "PageBlock" -> unit(e, drawParam, depth, true);
                case "PathObject", "TextObject", "ImageObject", "CompositeObject" -> unit(e, drawParam, depth, false);
                default -> {}
            }
        }
    }

    private void unit(Element e, Element inherited, int depth, boolean pageBlock) throws IOException {
        if ("false".equalsIgnoreCase(e.getAttribute("Visible"))) {
            return;
        }
        Element dp = res.drawParam(e.getAttribute("DrawParam"));
        Element param = dp != null ? dp : inherited;
        if (pageBlock) {
            boolean clipped = clips(e);
            block(e, param, depth + 1);
            if (clipped) {
                canvas.restore();
            }
            return;
        }
        Rectangle2D.Double box = OfdNumbers.box(e.getAttribute("Boundary"));
        if (box == null) {
            return;
        }
        AffineTransform ctm = OfdNumbers.ctm(e.getAttribute("CTM"));
        if (e.hasAttribute("CTM") && ctm == null) {
            return;
        }
        double alpha = Math.clamp(OfdNumbers.number(e.getAttribute("Alpha"), 255), 0, 255) / 255.0;
        canvas.save();
        try {
            canvas.translate((float) box.x, (float) box.y);
            clipsIn(e, ctm);
            switch (Dom.local(e)) {
                case "PathObject" -> path(e, param, ctm, alpha, depth);
                case "TextObject" -> text.draw(canvas, e, param, ctm, alpha, this);
                case "ImageObject" -> image(e, ctm, alpha, box);
                case "CompositeObject" -> composite(e, param, ctm, depth);
                default -> {}
            }
        } finally {
            canvas.restore();
        }
    }

    private boolean clips(Element e) throws IOException {
        Element clips = Dom.kid(e, "Clips");
        if (clips == null) {
            return false;
        }
        canvas.save();
        clipsIn(e, null);
        return true;
    }

    private void clipsIn(Element e, AffineTransform ctm) throws IOException {
        Element clips = Dom.kid(e, "Clips");
        if (clips == null) {
            return;
        }
        boolean inObject = ctm != null && "true".equalsIgnoreCase(clips.getAttribute("TransFlag").trim());
        for (Element clip : Dom.kids(clips, "Clip")) {
            OfficeZip.checkNotInterrupted();
            Shape first = null;
            Area union = null;
            for (Element area : Dom.kids(clip, "Area")) {
                Shape s = clipArea(area);
                if (s == null) {
                    continue;
                }
                Shape placed = inObject ? ctm.createTransformedShape(s) : s;
                if (first == null) {
                    first = placed;
                } else {
                    if (union == null) {
                        union = new Area(first);
                    }
                    union.add(new Area(placed));
                }
            }
            if (first != null) {
                canvas.clip(union != null ? union : first);
            }
        }
    }

    private Shape clipArea(Element area) {
        AffineTransform t = OfdNumbers.ctm(area.getAttribute("CTM"));
        Shape shape = null;
        Element path = Dom.kid(area, "Path");
        String data = path == null ? null : Dom.text(Dom.kid(path, "AbbreviatedData"));
        if (data != null && data.length() > MAX_CLIP_CHARS) {
            return null;
        }
        if (path != null) {
            Path2D.Double p = OfdPath.parse(data);
            if (p.getCurrentPoint() == null) {
                return null;
            }
            if ("Even-Odd".equalsIgnoreCase(path.getAttribute("Rule"))) {
                p.setWindingRule(Path2D.WIND_EVEN_ODD);
            }
            AffineTransform pt = OfdNumbers.ctm(path.getAttribute("CTM"));
            Rectangle2D.Double pb = OfdNumbers.box(path.getAttribute("Boundary"));
            AffineTransform local = new AffineTransform();
            if (pb != null) {
                local.translate(pb.x, pb.y);
            }
            if (pt != null) {
                local.concatenate(pt);
            }
            shape = local.createTransformedShape(p);
        }
        Element textClip = Dom.kid(area, "Text");
        if (shape == null && textClip != null) {
            Rectangle2D.Double tb = OfdNumbers.box(textClip.getAttribute("Boundary"));
            shape = tb;
        }
        if (shape == null) {
            return null;
        }
        return t == null ? shape : t.createTransformedShape(shape);
    }

    private void path(Element e, Element param, AffineTransform ctm, double alpha, int depth) throws IOException {
        Path2D.Double shape = OfdPath.parse(Dom.text(Dom.kid(e, "AbbreviatedData")));
        if (shape.getCurrentPoint() == null) {
            return;
        }
        if ("Even-Odd".equalsIgnoreCase(e.getAttribute("Rule"))) {
            shape.setWindingRule(Path2D.WIND_EVEN_ODD);
        }
        boolean fill = flag(e, "Fill", false);
        boolean stroke = flag(e, "Stroke", true);
        if (ctm != null) {
            canvas.transform(ctm);
        }
        Element colour = fill ? colourElement(e, param, "FillColor") : null;
        Stroke s = stroke ? stroke(e, param, alpha) : null;
        Element pattern = colour == null ? null : Dom.kid(colour, "Pattern");
        Element mesh = colour == null ? null : OfdMesh.element(colour);
        if (pattern == null && mesh == null) {
            canvas.draw(shape, colour != null ? fill(e, param, alpha) : null, s);
            return;
        }
        canvas.save();
        try {
            canvas.clip(shape);
            if (pattern != null) {
                tile(pattern, shape, param, depth);
            } else {
                OfdMesh.paint(canvas, mesh, res, defaultSpace, alpha);
            }
        } finally {
            canvas.restore();
        }
        if (s != null) {
            canvas.draw(shape, null, s);
        }
    }

    private void tile(Element pattern, Shape shape, Element param, int depth) throws IOException {
        Element cell = Dom.kid(pattern, "CellContent");
        double w = OfdNumbers.number(pattern.getAttribute("Width"), 0);
        double h = OfdNumbers.number(pattern.getAttribute("Height"), 0);
        double xs = OfdNumbers.number(pattern.getAttribute("XStep"), w);
        double ys = OfdNumbers.number(pattern.getAttribute("YStep"), h);
        if (cell == null || depth >= MAX_DEPTH || !(w > 0 && h > 0 && xs > 0.01 && ys > 0.01)) {
            return;
        }
        AffineTransform pt = OfdNumbers.ctm(pattern.getAttribute("CTM"));
        if (pt == null) {
            pt = new AffineTransform();
        }
        Rectangle2D b;
        try {
            b = pt.createInverse().createTransformedShape(shape.getBounds2D()).getBounds2D();
        } catch (NoninvertibleTransformException ex) {
            return;
        }
        double i0 = Math.floor(b.getMinX() / xs);
        double i1 = Math.ceil(b.getMaxX() / xs);
        double j0 = Math.floor(b.getMinY() / ys);
        double j1 = Math.ceil(b.getMaxY() / ys);
        if ((i1 - i0 + 1) * (j1 - j0 + 1) > MAX_TILES) {
            return;
        }
        boolean mirrorColumns = pattern.getAttribute("ReflectMethod").matches("Column|RowAndColumn");
        boolean mirrorRows = pattern.getAttribute("ReflectMethod").matches("Row|RowAndColumn");
        canvas.transform(pt);
        for (double j = j0; j <= j1; j++) {
            for (double i = i0; i <= i1; i++) {
                OfficeZip.checkNotInterrupted();
                canvas.save();
                try {
                    canvas.translate((float) (i * xs), (float) (j * ys));
                    boolean flipX = mirrorColumns && Math.floorMod((long) i, 2) == 1;
                    boolean flipY = mirrorRows && Math.floorMod((long) j, 2) == 1;
                    if (flipX || flipY) {
                        canvas.translate(flipX ? (float) w : 0, flipY ? (float) h : 0);
                        canvas.scale(flipX ? -1 : 1, flipY ? -1 : 1);
                    }
                    canvas.clipRect(0, 0, (float) w, (float) h);
                    block(cell, param, depth + 1);
                } finally {
                    canvas.restore();
                }
            }
        }
    }

    Fill fill(Element e, Element param, double alpha) {
        Element c = colourElement(e, param, "FillColor");
        Gradient g = OfdColors.shading(c, res, defaultSpace, (int) Math.round(alpha * 255));
        if (g != null) {
            return Fill.of(g);
        }
        Color col = OfdColors.color(c, res, defaultSpace, Color.BLACK);
        return Fill.solid(withAlpha(col, alpha));
    }

    Color fillColor(Element e, Element param, double alpha) {
        return withAlpha(OfdColors.color(colourElement(e, param, "FillColor"), res, defaultSpace, Color.BLACK), alpha);
    }

    Color strokeColor(Element e, Element param, double alpha) {
        return withAlpha(OfdColors.color(colourElement(e, param, "StrokeColor"), res, defaultSpace, Color.BLACK), alpha);
    }

    Stroke stroke(Element e, Element param, double alpha) {
        Color col = strokeColor(e, param, alpha);
        double width = OfdNumbers.number(attr(e, param, "LineWidth"), DEFAULT_LINE_WIDTH);
        width = Math.clamp(width, 0, 1000);
        Stroke s = Stroke.solid((float) width, col);
        s = s.cap(switch (String.valueOf(attr(e, param, "Cap"))) {
            case "Round" -> Stroke.Cap.ROUND;
            case "Square" -> Stroke.Cap.SQUARE;
            default -> Stroke.Cap.BUTT;
        });
        s = s.join(switch (String.valueOf(attr(e, param, "Join"))) {
            case "Round" -> Stroke.Join.ROUND;
            case "Bevel" -> Stroke.Join.BEVEL;
            default -> Stroke.Join.MITER;
        });
        double miter = OfdNumbers.number(attr(e, param, "MiterLimit"), 3.528);
        s = s.miterLimit((float) Math.clamp(miter, 1, 1000));
        double[] dash = OfdNumbers.list(attr(e, param, "DashPattern"));
        if (dash.length > 0 && dash.length <= 64) {
            float[] pattern = new float[dash.length];
            float total = 0;
            boolean ok = true;
            for (int i = 0; i < dash.length; i++) {
                if (!(dash[i] >= 0 && dash[i] <= 10_000)) {
                    ok = false;
                    break;
                }
                pattern[i] = (float) dash[i];
                total += pattern[i];
            }
            if (ok && total > 0) {
                double phase = OfdNumbers.number(attr(e, param, "DashOffset"), 0);
                s = s.dash((float) phase, pattern);
            }
        }
        return s;
    }

    private void image(Element e, AffineTransform ctm, double alpha, Rectangle2D.Double box) throws IOException {
        DecodedPicture picture = e.hasAttribute("ImageMask")
                ? images.masked(e.getAttribute("ResourceID"), e.getAttribute("ImageMask"))
                : images.picture(e.getAttribute("ResourceID"));
        if (picture == null) {
            return;
        }
        if (ctm != null) {
            canvas.transform(ctm);
            canvas.image(picture, 0, 0, 1, 1, Crop.NONE, 0, false, false,
                    (float) alpha);
        } else {
            canvas.image(picture, 0, 0, (float) box.width, (float) box.height,
                    Crop.NONE, 0, false, false, (float) alpha);
        }
    }

    private void composite(Element e, Element param, AffineTransform ctm, int depth) throws IOException {
        Element unit = res.unit(e.getAttribute("ResourceID").trim());
        if (unit == null || depth >= MAX_DEPTH) {
            return;
        }
        if (ctm != null) {
            canvas.transform(ctm);
        }
        block(unit, param, depth + 1);
    }

    private Element colourElement(Element e, Element param, String name) {
        Element own = Dom.kid(e, name);
        if (own != null) {
            return own;
        }
        int hops = 0;
        for (Element p = param; p != null && hops++ < 16; p = res.drawParam(p.getAttribute("Relative"))) {
            Element c = Dom.kid(p, name);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    String attr(Element e, Element param, String name) {
        if (e.hasAttribute(name)) {
            return e.getAttribute(name);
        }
        int hops = 0;
        for (Element p = param; p != null && hops++ < 16; p = res.drawParam(p.getAttribute("Relative"))) {
            if (p.hasAttribute(name)) {
                return p.getAttribute(name);
            }
        }
        return null;
    }

    static boolean flag(Element e, String name, boolean fallback) {
        if (!e.hasAttribute(name)) {
            return fallback;
        }
        return "true".equalsIgnoreCase(e.getAttribute(name).trim());
    }

    static Color withAlpha(Color c, double alpha) {
        if (alpha >= 1) {
            return c;
        }
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(c.getAlpha() * alpha));
    }
}

package stirling.software.officeconvert.topdf.pdf;

import java.awt.Color;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.shading.PDShading;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.util.Matrix;

import stirling.software.officeconvert.model.FormField;
import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontRun;
import stirling.software.officeconvert.topdf.font.GlyphRun;
import stirling.software.officeconvert.topdf.io.DecodedPicture;

public final class PdfCanvas implements Closeable {

    public static final float ITALIC_SKEW = 0.2126f;

    public static final float BOLD_STROKE = 0.035f;

    private final PdfOutput output;

    private final PDPage page;

    private final PDAppearanceStream form;

    private final int index;

    private final float width;

    private final float height;

    private final PDPageContentStream cs;

    private final Deque<AffineTransform> saved = new ArrayDeque<>();

    private final AffineTransform ctm = new AffineTransform();

    private final CanvasLinks links;

    private final CanvasText writer;

    private boolean closed;

    private final String marked;

    PdfCanvas(PdfOutput output, PDPage page, int index) throws IOException {
        this.output = output;
        this.marked = null;
        this.page = page;
        this.form = null;
        this.index = index;
        this.width = page.getMediaBox().getWidth();
        this.height = page.getMediaBox().getHeight();
        this.links = new CanvasLinks(output, page, index, height, ctm);
        this.cs = new PDPageContentStream(output.document(), page, AppendMode.OVERWRITE, true);
        this.writer = new CanvasText(output, page, null, cs, ctm, width, height);
        cs.saveGraphicsState();
        cs.transform(new Matrix(1, 0, 0, -1, 0, height));
    }

    // A canvas whose drawing becomes a form XObject: the same coordinates as a page, links and destinations left out
    PdfCanvas(PdfOutput output, float width, float height, float margin) throws IOException {
        this(output, width, height, margin, null);
    }

    PdfCanvas(PdfOutput output, float width, float height, float margin, String marked) throws IOException {
        this.output = output;
        this.marked = marked;
        this.page = null;
        this.form = new PDAppearanceStream(output.document());
        this.index = -1;
        this.width = width;
        this.height = height;
        this.links = new CanvasLinks(output, null, -1, height, ctm);
        form.setBBox(new PDRectangle(-margin, -margin, width + 2 * margin, height + 2 * margin));
        form.setResources(new PDResources());
        this.cs = new PDPageContentStream(output.document(), form,
                form.getContentStream().createOutputStream(COSName.FLATE_DECODE));
        this.writer = new CanvasText(output, null, form, cs, ctm, width, height);
        if (marked != null) {
            cs.beginMarkedContent(COSName.getPDFName(marked));
        }
        cs.saveGraphicsState();
        cs.transform(new Matrix(1, 0, 0, -1, 0, height));
    }

    /** The form XObject a canvas made by {@link PdfOutput#newForm} draws into, or null for a page. */
    public PDFormXObject form() {
        return form;
    }

    public PdfOutput output() {
        return output;
    }

    public PDPage page() {
        return page;
    }

    public int pageIndex() {
        return index;
    }

    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    public AffineTransform transform() {
        return new AffineTransform(ctm);
    }

    // Text written while hidden is kept for search and copy but not painted; returns the previous setting
    public boolean hideText(boolean hide) {
        return writer.hideText(hide);
    }

    public boolean skipOffPage(boolean skip) {
        return writer.skipOffPage(skip);
    }

    public void save() throws IOException {
        ready();
        cs.saveGraphicsState();
        saved.push(new AffineTransform(ctm));
    }

    public void restore() throws IOException {
        ready();
        if (saved.isEmpty()) {
            throw new IllegalStateException("restore() without a matching save()");
        }
        cs.restoreGraphicsState();
        ctm.setTransform(saved.pop());
    }

    public void transform(AffineTransform t) throws IOException {
        Objects.requireNonNull(t, "transform");
        ready();
        if (t.isIdentity()) {
            return;
        }
        cs.transform(new Matrix(t));
        ctm.concatenate(t);
    }

    public void translate(float x, float y) throws IOException {
        transform(AffineTransform.getTranslateInstance(x, y));
    }

    public void scale(float sx, float sy) throws IOException {
        transform(AffineTransform.getScaleInstance(sx, sy));
    }

    public void rotate(float degrees, float cx, float cy) throws IOException {
        transform(AffineTransform.getRotateInstance(Math.toRadians(degrees), cx, cy));
    }

    public void clip(Shape shape) throws IOException {
        Objects.requireNonNull(shape, "shape");
        ready();
        boolean evenOdd = append(shape);
        if (evenOdd) {
            cs.clipEvenOdd();
        } else {
            cs.clip();
        }
    }

    public void clipRect(float x, float y, float w, float h) throws IOException {
        clip(new Rectangle2D.Float(x, y, w, h));
    }

    public float text(String text, float x, float baseline, TextStyle style) throws IOException {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(style, "style");
        open();
        return writer.text(text, x, baseline, style);
    }

    public float emoji(String text, float x, float baseline, TextStyle style, float each, boolean mono)
            throws IOException {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(style, "style");
        open();
        return writer.emoji(text, x, baseline, style, each, mono);
    }

    public boolean visible(float x0, float x1, float baseline, float size) {
        return writer.visible(x0, x1, baseline, size);
    }

    public float drawGlyphs(GlyphRun run, float x, float baseline, TextStyle style) throws IOException {
        return drawGlyphs(run, x, baseline, style, false);
    }

    public float drawGlyphs(GlyphRun run, float x, float baseline, TextStyle style, boolean actualText)
            throws IOException {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(style, "style");
        open();
        return writer.drawGlyphs(run, x, baseline, style, actualText);
    }

    private void flush() throws IOException {
        writer.flush();
    }

    public float text(List<FontRun> runs, float x, float baseline, TextStyle style) throws IOException {
        Objects.requireNonNull(runs, "runs");
        float at = x;
        for (FontRun r : runs) {
            at += text(r.text(), at, baseline, style.face(r.face()));
        }
        return at - x;
    }

    public void underline(float x, float baseline, float length, TextStyle style) throws IOException {
        FontFace face = style.face();
        float top = baseline - style.points(face.metrics().underlinePosition());
        float thick = Math.max(0.25f, style.points(face.metrics().underlineThickness()));
        rect(x, top, length, thick, Fill.solid(style.color()), null);
    }

    public void strikeout(float x, float baseline, float length, TextStyle style) throws IOException {
        FontFace face = style.face();
        float top = baseline - style.points(face.metrics().strikeoutPosition());
        float thick = Math.max(0.25f, style.points(face.metrics().strikeoutSize()));
        rect(x, top, length, thick, Fill.solid(style.color()), null);
    }

    public void line(float x1, float y1, float x2, float y2, Stroke stroke) throws IOException {
        draw(new Line2D.Float(x1, y1, x2, y2), null, Objects.requireNonNull(stroke, "stroke"));
    }

    public void rect(float x, float y, float w, float h, Fill fill, Stroke stroke) throws IOException {
        draw(new Rectangle2D.Float(x, y, w, h), fill, stroke);
    }

    public void roundRect(float x, float y, float w, float h, float rx, float ry, Fill fill, Stroke stroke)
            throws IOException {
        draw(new RoundRectangle2D.Float(x, y, w, h, 2 * Math.max(0, rx), 2 * Math.max(0, ry)), fill, stroke);
    }

    public void ellipse(float x, float y, float w, float h, Fill fill, Stroke stroke) throws IOException {
        draw(new Ellipse2D.Float(x, y, w, h), fill, stroke);
    }

    public void draw(Shape shape, Fill fill, Stroke stroke) throws IOException {
        Objects.requireNonNull(shape, "shape");
        ready();
        if (fill == null && stroke == null) {
            return;
        }
        if (fill != null && fill.gradient() != null) {
            gradient(shape, fill.gradient());
            fill = null;
            if (stroke == null) {
                return;
            }
        }
        cs.saveGraphicsState();
        try {
            float fa = fill == null ? 1 : fill.color().getAlpha() / 255f;
            float sa = stroke == null ? 1 : stroke.color().getAlpha() / 255f;
            alpha(fa, sa);
            if (fill != null) {
                setFill(fill.color());
            }
            if (stroke != null) {
                applyStroke(stroke);
            }
            boolean evenOdd = append(shape);
            if (fill != null && stroke != null) {
                if (evenOdd) {
                    cs.fillAndStrokeEvenOdd();
                } else {
                    cs.fillAndStroke();
                }
            } else if (fill != null) {
                if (evenOdd) {
                    cs.fillEvenOdd();
                } else {
                    cs.fill();
                }
            } else {
                cs.stroke();
            }
        } finally {
            cs.restoreGraphicsState();
        }
    }

    public void image(DecodedPicture picture, float x, float y, float w, float h) throws IOException {
        image(picture, x, y, w, h, Crop.NONE, 0, false, false, 1);
    }

    public void image(DecodedPicture picture, float x, float y, float w, float h, Crop crop, float rotation,
            boolean flipH, boolean flipV, float alpha) throws IOException {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(crop, "crop");
        ready();
        if (!(w > 0 && h > 0) || !(alpha > 0)) {
            return;
        }
        cs.saveGraphicsState();
        try {
            alpha(Math.min(1, alpha), 1);
            AffineTransform t = new AffineTransform();
            if (rotation != 0 || flipH || flipV) {
                double cx = x + w / 2.0;
                double cy = y + h / 2.0;
                t.translate(cx, cy);
                t.rotate(Math.toRadians(rotation));
                t.scale(flipH ? -1 : 1, flipV ? -1 : 1);
                t.translate(-cx, -cy);
                cs.transform(new Matrix(t));
            }
            if (crop.clips()) {
                cs.addRect(x, y, w, h);
                cs.clip();
            }
            float fw = w / (1 - crop.left() - crop.right());
            float fh = h / (1 - crop.top() - crop.bottom());
            float fx = x - crop.left() * fw;
            float fy = y - crop.top() * fh;
            if (picture.vector()) {
                placeForm(picture.form(), picture.naturalWidth(), picture.naturalHeight(), fx, fy, fw, fh);
            } else {
                cs.drawImage(picture.image(), new Matrix(fw, 0, 0, -fh, fx, fy + fh));
            }
        } finally {
            cs.restoreGraphicsState();
        }
    }

    public void form(PDFormXObject form, float x, float y, float w, float h) throws IOException {
        Objects.requireNonNull(form, "form");
        ready();
        PDRectangle box = form.getBBox();
        float bw = box == null ? w : box.getWidth();
        float bh = box == null ? h : box.getHeight();
        if (!(w > 0 && h > 0 && bw > 0 && bh > 0)) {
            return;
        }
        cs.saveGraphicsState();
        try {
            placeForm(form, bw, bh, x, y, w, h);
        } finally {
            cs.restoreGraphicsState();
        }
    }

    public boolean link(float x, float y, float w, float h, String url) throws IOException {
        open();
        return links.link(x, y, w, h, url);
    }

    public void field(float x, float y, float w, float h, FormField field, float size, Color color,
            PDFormXObject look) {
        Objects.requireNonNull(field, "field");
        open();
        links.field(x, y, w, h, field, size, color, look);
    }

    public void linkTo(float x, float y, float w, float h, String destination) {
        Objects.requireNonNull(destination, "destination");
        open();
        links.linkTo(x, y, w, h, destination);
    }

    public void linkToPage(float x, float y, float w, float h, int pageIndex, float yTop) {
        open();
        links.linkToPage(x, y, w, h, pageIndex, yTop);
    }

    public void destination(String name, float x, float yTop) {
        Objects.requireNonNull(name, "name");
        open();
        links.destination(name, x, yTop);
    }

    public PDPageContentStream stream() {
        open();
        try {
            flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return cs;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            flush();
            while (!saved.isEmpty()) {
                saved.pop();
                cs.restoreGraphicsState();
            }
            cs.restoreGraphicsState();
            if (marked != null) {
                cs.endMarkedContent();
            }
        } finally {
            cs.close();
            output.closed(this);
        }
    }

    private void placeForm(PDFormXObject form, float bw, float bh, float x, float y, float w, float h)
            throws IOException {
        PDRectangle box = form.getBBox();
        float bx = box == null ? 0 : box.getLowerLeftX();
        float by = box == null ? 0 : box.getLowerLeftY();
        cs.transform(new Matrix(w / bw, 0, 0, -h / bh, x - bx * w / bw, y + h + by * h / bh));
        cs.drawForm(form);
    }

    private void open() {
        if (closed) {
            throw new IllegalStateException("The page was closed");
        }
    }

    private void ready() throws IOException {
        open();
        flush();
    }


    private void alpha(float fill, float stroke) throws IOException {
        if (fill < 1 || stroke < 1) {
            cs.setGraphicsStateParameters(output.alpha(Math.max(0, fill), Math.max(0, stroke)));
        }
    }

    private void applyStroke(Stroke stroke) throws IOException {
        setStroke(stroke.color());
        cs.setLineWidth(stroke.width());
        cs.setLineCapStyle(stroke.cap().ordinal());
        cs.setLineJoinStyle(stroke.join().ordinal());
        cs.setMiterLimit(stroke.miterLimit());
        float[] dash = stroke.dashPattern();
        if (dash.length > 0) {
            cs.setLineDashPattern(dash, stroke.dashPhase());
        }
    }

    private boolean append(Shape shape) throws IOException {
        PathIterator it = shape.getPathIterator(null);
        float[] c = new float[6];
        float lastX = 0;
        float lastY = 0;
        while (!it.isDone()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO -> {
                    cs.moveTo(c[0], c[1]);
                    lastX = c[0];
                    lastY = c[1];
                }
                case PathIterator.SEG_LINETO -> {
                    cs.lineTo(c[0], c[1]);
                    lastX = c[0];
                    lastY = c[1];
                }
                case PathIterator.SEG_QUADTO -> {
                    cs.curveTo(lastX + 2f / 3f * (c[0] - lastX), lastY + 2f / 3f * (c[1] - lastY),
                            c[2] + 2f / 3f * (c[0] - c[2]), c[3] + 2f / 3f * (c[1] - c[3]), c[2], c[3]);
                    lastX = c[2];
                    lastY = c[3];
                }
                case PathIterator.SEG_CUBICTO -> {
                    cs.curveTo(c[0], c[1], c[2], c[3], c[4], c[5]);
                    lastX = c[4];
                    lastY = c[5];
                }
                case PathIterator.SEG_CLOSE -> cs.closePath();
                default -> {
                }
            }
            it.next();
        }
        return it.getWindingRule() == PathIterator.WIND_EVEN_ODD;
    }

    private void gradient(Shape shape, Gradient g) throws IOException {
        PDShading shading;
        try {
            shading = Shadings.shading(g);
        } catch (IOException | RuntimeException e) {
            shading = null;
        }
        if (shading == null) {
            draw(shape, Fill.solid(g.average()), null);
            return;
        }
        cs.saveGraphicsState();
        try {
            float a = 0;
            for (Gradient.Stop s : g.stops()) {
                a += s.color().getAlpha() / 255f;
            }
            alpha(a / g.stops().size(), 1);
            boolean evenOdd = append(shape);
            if (evenOdd) {
                cs.clipEvenOdd();
            } else {
                cs.clip();
            }
            cs.shadingFill(shading);
        } finally {
            cs.restoreGraphicsState();
        }
    }

    /** Fills with a gradient whose stops differ in opacity, the opacities drawn as a luminosity soft mask. */
    public void gradientWithAlpha(Shape shape, Gradient g) throws IOException {
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(g, "gradient");
        ready();
        Rectangle2D b = shape.getBounds2D();
        PDShading colour;
        PDShading mask;
        try {
            colour = Shadings.shading(Shadings.recolour(g, false));
            mask = Shadings.shading(Shadings.recolour(g, true));
        } catch (IOException | RuntimeException e) {
            colour = null;
            mask = null;
        }
        if (colour == null || mask == null || b.isEmpty()) {
            draw(shape, Fill.of(g), null);
            return;
        }
        PDExtendedGraphicsState state = Shadings.softMask(output.document(), b, mask);
        cs.saveGraphicsState();
        try {
            cs.setGraphicsStateParameters(state);
            if (append(shape)) {
                cs.clipEvenOdd();
            } else {
                cs.clip();
            }
            cs.shadingFill(colour);
        } finally {
            cs.restoreGraphicsState();
        }
    }

    private void setFill(Color c) throws IOException {
        cs.setNonStrokingColor(new PDColor(Shadings.office(c), PDDeviceRGB.INSTANCE));
    }

    private void setStroke(Color c) throws IOException {
        cs.setStrokingColor(new PDColor(Shadings.office(c), PDDeviceRGB.INSTANCE));
    }
}

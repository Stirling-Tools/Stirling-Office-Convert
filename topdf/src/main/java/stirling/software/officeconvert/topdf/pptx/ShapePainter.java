package stirling.software.officeconvert.topdf.pptx;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.sl.usermodel.PaintStyle;
import org.apache.poi.sl.usermodel.PaintStyle.PaintModifier;
import org.apache.poi.xslf.usermodel.XSLFColor;
import org.apache.poi.xslf.usermodel.XSLFConnectorShape;
import org.apache.poi.xslf.usermodel.XSLFGraphicFrame;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFHyperlink;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.apache.poi.xslf.usermodel.XSLFSimpleShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.drawingml.x2006.main.CTBlipFillProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTGroupShapeProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTShapeProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTShapeStyle;
import org.openxmlformats.schemas.presentationml.x2006.main.CTConnector;
import org.openxmlformats.schemas.presentationml.x2006.main.CTGroupShape;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShape;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.Relationship;
import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.Stroke;

final class ShapePainter {

    private final Deck deck;

    private final PdfCanvas canvas;

    private final int slideNumber;

    private final PicturePainter pictures;

    private final TablePainter tables;

    private XSLFSheet sheet;

    private XSLFSlide slide;

    ShapePainter(Deck deck, PdfCanvas canvas, int slideNumber) {
        this.deck = deck;
        this.canvas = canvas;
        this.slideNumber = slideNumber;
        this.pictures = new PicturePainter(deck, canvas);
        this.tables = new TablePainter(deck, canvas, this);
    }

    XSLFSheet sheet() {
        return sheet;
    }

    void sheet(XSLFSheet s) {
        this.sheet = s;
    }

    void slide(XSLFSlide s) {
        this.slide = s;
    }

    int slideNumber() {
        return slideNumber;
    }

    PdfCanvas canvas() {
        return canvas;
    }

    void paint(XSLFShape shape, Space space, boolean onSlide) throws IOException {
        deck.job().checkpoint();
        if (space.depth() > Space.MAX_DEPTH || hidden(shape.getXmlObject())) {
            return;
        }
        try {
            if (shape instanceof XSLFGroupShape g) {
                group(g, space, onSlide);
            } else if (shape instanceof XSLFTable t) {
                tables.paint(t, space);
            } else if (shape instanceof XSLFGraphicFrame f) {
                Frames.paint(deck, this, f, space);
            } else if (shape instanceof XSLFPictureShape p) {
                if (onSlide && p.isPlaceholder() && pictures.isEmpty(p)) {
                    return;
                }
                pictures.paint(p, space);
                link(p, space);
            } else if (shape instanceof XSLFSimpleShape s) {
                simple(s, space, onSlide);
                link(s, space);
            }
        } catch (RenderJob.PageLimitReached e) {
            throw e;
        } catch (RuntimeException e) {
            deck.job().warn("A shape on slide " + slideNumber + " could not be drawn (" + shape.getShapeName() + "): "
                    + e.getMessage());
        }
    }

    void tree(XmlObject container, List<XSLFShape> shapes, Space space, boolean onSlide, boolean skipPlaceholders)
            throws IOException {
        List<Object> ordered = new ArrayList<>();
        if (container == null) {
            ordered.addAll(shapes);
        } else {
            Map<XmlObject, XSLFShape> byXml = new IdentityHashMap<>();
            for (XSLFShape s : shapes) {
                byXml.put(s.getXmlObject(), s);
            }
            try (XmlCursor c = container.newCursor()) {
                for (boolean more = c.toFirstChild(); more; more = c.toNextSibling()) {
                    XmlObject o = c.getObject();
                    XSLFShape s = byXml.remove(o);
                    if (s != null) {
                        ordered.add(s);
                    } else if (Fallbacks.isAlternateContent(c.getName())) {
                        ordered.add(o);
                    }
                }
            }
        }
        for (Object o : ordered) {
            if (o instanceof XSLFShape s) {
                if (!skipPlaceholders || !s.isPlaceholder()) {
                    paint(s, space, onSlide);
                }
            } else {
                Fallbacks.paint(deck, this, (XmlObject) o, space);
            }
        }
    }

    // Shapes hidden in the selection pane are left out of slide shows, print and PDF
    static boolean hidden(XmlObject xml) {
        try (XmlCursor c = xml.newCursor()) {
            if (!c.toFirstChild() || !c.getName().getLocalPart().startsWith("nv") || !c.toFirstChild()
                    || !"cNvPr".equals(c.getName().getLocalPart())) {
                return false;
            }
            String h = c.getAttributeText(new javax.xml.namespace.QName("", "hidden"));
            return "1".equals(h) || "true".equals(h);
        }
    }

    PicturePainter pictures() {
        return pictures;
    }

    void group(XSLFGroupShape g, Space space, boolean onSlide) throws IOException {
        Rectangle2D exterior = g.getAnchor();
        Rectangle2D interior = g.getInteriorAnchor();
        if (exterior == null || interior == null) {
            return;
        }
        Space child = space.group(exterior, interior, g.getRotation(), g.getFlipHorizontal(), g.getFlipVertical(),
                groupFill(g));
        tree(g.getXmlObject(), g.getShapes(), child, onSlide, false);
    }

    private void simple(XSLFSimpleShape s, Space space, boolean onSlide) throws IOException {
        Rectangle2D anchor = s.getAnchor();
        if (anchor == null) {
            return;
        }
        XSLFTextShape text = s instanceof XSLFTextShape t ? t : null;
        if (onSlide && s.isPlaceholder() && !ownFormatting(s) && (text == null || !hasText(text))) {
            return;
        }
        Frame f = space.place(anchor, s.getRotation(), s.getFlipHorizontal(), s.getFlipVertical())
                .viewed(Cameras.view(spPr(s)));
        Rectangle2D box = f.bounds();
        List<Geometry.Outline> outlines = Geometry.outlines(s, box);
        PaintStyle paint = fillPaint(s);
        Stroke stroke = Strokes.of(s.getStrokeStyle());
        Shadows.Shadow shadow = Shadows.of(s);
        java.awt.Shape cast = shadow == null ? null : Shadows.silhouette(outlines, casts(s, paint), stroke);
        if (cast != null) {
            Shadows.draw(deck, canvas, shadow, cast, f.shapeTransform(), box);
        }
        canvas.save();
        try {
            canvas.transform(f.shapeTransform());
            if (usesBackground(s)) {
                background(outlines, f.shapeTransform());
            } else {
                fill(s, paint, outlines, box, space);
            }
            if (stroke != null) {
                Geometry.Outline firstStroked = null;
                for (Geometry.Outline o : outlines) {
                    if (o.stroked()) {
                        canvas.draw(o.shape(), null, stroke);
                        if (firstStroked == null) {
                            firstStroked = o;
                        }
                    }
                }
                if (firstStroked != null && (s instanceof XSLFConnectorShape || hasDecoration(s))) {
                    Strokes.decorations(canvas, firstStroked.shape(), s.getLineDecoration(), stroke);
                }
            }
        } finally {
            canvas.restore();
        }
        if (text != null) {
            text(text, f, box, Geometry.textBox(s, box), space, cast == null ? shadow : null);
        }
    }

    private static boolean casts(XSLFSimpleShape s, PaintStyle paint) {
        if (usesBackground(s) || paint instanceof PaintStyle.TexturePaint || paint instanceof PaintStyle.GradientPaint) {
            return true;
        }
        Color c = Paints.solid(paint);
        return c != null && c.getAlpha() > 0;
    }

    private static boolean usesBackground(XSLFSimpleShape s) {
        return s.getXmlObject() instanceof CTShape sp && sp.isSetUseBgFill() && sp.getUseBgFill();
    }

    // useBgFill shows the slide background through the shape, whatever its style says
    private void background(List<Geometry.Outline> outlines, AffineTransform shape) throws IOException {
        if (slide == null) {
            return;
        }
        for (Geometry.Outline o : outlines) {
            if (!o.filled()) {
                continue;
            }
            canvas.save();
            try {
                canvas.clip(o.shape());
                canvas.transform(shape.createInverse());
                Backgrounds.paint(deck, canvas, slide);
            } catch (NoninvertibleTransformException e) {
                return;
            } finally {
                canvas.restore();
            }
            return;
        }
    }

    private void link(XSLFSimpleShape s, Space space) throws IOException {
        String id;
        Rectangle2D anchor;
        try {
            XSLFHyperlink h = s.getHyperlink();
            id = h == null ? null : h.getXmlObject().getId();
            anchor = s.getAnchor();
        } catch (RuntimeException e) {
            return;
        }
        if (id == null || id.isBlank() || anchor == null) {
            return;
        }
        Relationship r = deck.pictures().relationship(space.relsPart(), id);
        String url = r == null ? null : ActiveContent.hyperlink(r);
        if (url == null) {
            return;
        }
        Frame f = space.place(anchor, s.getRotation(), s.getFlipHorizontal(), s.getFlipVertical());
        canvas.save();
        try {
            canvas.transform(f.shapeTransform());
            canvas.link((float) f.x(), (float) f.y(), (float) f.width(), (float) f.height(), url);
        } finally {
            canvas.restore();
        }
    }

    private static boolean hasDecoration(XSLFSimpleShape s) {
        try {
            var d = s.getLineDecoration();
            return d != null && (d.getHeadShape() != null && d.getHeadShape().ooxmlId > 1
                    || d.getTailShape() != null && d.getTailShape().ooxmlId > 1);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Color groupFill(XSLFGroupShape g) {
        if (!(g.getXmlObject() instanceof CTGroupShape x) || x.getGrpSpPr() == null) {
            return null;
        }
        CTGroupShapeProperties p = x.getGrpSpPr();
        if (p.isSetSolidFill()) {
            return TableStyles.color(p.getSolidFill(), g.getSheet());
        }
        if (p.isSetGradFill() && p.getGradFill().getGsLst() != null && p.getGradFill().getGsLst().sizeOfGsArray() > 0) {
            return TableStyles.color(p.getGradFill().getGsLst().getGsArray(0), g.getSheet());
        }
        return null;
    }

    private PaintStyle fillPaint(XSLFSimpleShape s) {
        if (s instanceof XSLFConnectorShape) {
            return null;
        }
        try {
            return s.getFillStyle().getPaint();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void fill(XSLFSimpleShape s, PaintStyle paint, List<Geometry.Outline> outlines, Rectangle2D box,
            Space space) throws IOException {
        CTShapeProperties spPr = spPr(s);
        if (spPr != null && spPr.isSetGrpFill()) {
            Color c = space.groupFill();
            for (Geometry.Outline o : outlines) {
                if (c != null && c.getAlpha() > 0 && o.filled()) {
                    canvas.draw(o.shape(), Fill.solid(c), null);
                }
            }
            return;
        }
        if (paint instanceof PaintStyle.TexturePaint) {
            CTBlipFillProperties blip = spPr == null ? null : spPr.getBlipFill();
            if (blip != null) {
                for (Geometry.Outline o : outlines) {
                    if (o.filled()) {
                        List<Color> duotone = BlipFills.duotone(blip.getBlip(), s.getSheet());
                        BlipFills.draw(deck, canvas, blip, space.relsPart(), o.shape(), box,
                                duotone != null ? duotone : BlipFills.duotone(paint), s.getSheet());
                        break;
                    }
                }
            }
            return;
        }
        if (paint == null) {
            return;
        }
        for (Geometry.Outline o : outlines) {
            if (!o.filled()) {
                continue;
            }
            Fill f = Paints.fill(paint, box, o.fill() == PaintModifier.NORM ? null : o.fill());
            if (f != null && Paints.fading(f)) {
                canvas.gradientWithAlpha(o.shape(), f.gradient());
            } else if (f != null) {
                canvas.draw(o.shape(), f, null);
            }
        }
    }

    static CTShapeProperties spPr(XSLFShape s) {
        XmlObject x = s.getXmlObject();
        if (x instanceof CTShape sp) {
            return sp.getSpPr();
        }
        if (x instanceof CTConnector c) {
            return c.getSpPr();
        }
        return null;
    }

    private static boolean ownFormatting(XSLFSimpleShape s) {
        CTShapeProperties spPr = spPr(s);
        return spPr != null && (spPr.isSetSolidFill() || spPr.isSetGradFill() || spPr.isSetBlipFill()
                || spPr.isSetPattFill() || spPr.isSetLn() && !spPr.getLn().isSetNoFill());
    }

    static boolean hasText(XSLFTextShape t) {
        String s = t.getText();
        return s != null && !s.isBlank();
    }

    void text(XSLFTextShape t, Frame f, Rectangle2D textBox, Space space) throws IOException {
        text(t, f, textBox, textBox, space, null);
    }

    // A shape that shows neither fill nor line gives its shadow to its text
    private void text(XSLFTextShape t, Frame f, Rectangle2D box, Rectangle2D textBox, Space space,
            Shadows.Shadow shadow) throws IOException {
        TextFrame frame = TextFrame.of(deck, t, new TextStyles.Scope(space.relsPart(), slideNumber,
                defaults(t).withShadow(shadow)), null, null);
        if (frame == null) {
            return;
        }
        canvas.save();
        try {
            canvas.transform(f.textTransform());
            frame.draw(canvas, frame.spun() ? box : textBox);
        } finally {
            canvas.restore();
        }
    }

    private TextStyles.Defaults defaults(XSLFTextShape t) {
        XmlObject x = t.getXmlObject();
        CTShapeStyle style = x instanceof CTShape sp ? sp.getStyle() : null;
        if (style == null || style.getFontRef() == null) {
            return TextStyles.Defaults.NONE;
        }
        Color c = null;
        try {
            c = Paints.color(new XSLFColor(style.getFontRef(), t.getSheet().getTheme(),
                    null, t.getSheet()).getColorStyle());
        } catch (RuntimeException e) {
            c = null;
        }
        return new TextStyles.Defaults(c, null, null, null);
    }
}

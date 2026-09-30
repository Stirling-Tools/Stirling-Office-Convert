package stirling.software.officeconvert.topdf.pptx;

import java.awt.geom.Rectangle2D;
import java.io.IOException;

import javax.xml.namespace.QName;

import org.apache.poi.xslf.usermodel.XSLFDiagram;
import org.apache.poi.xslf.usermodel.XSLFDiagramDrawing;
import org.apache.poi.xslf.usermodel.XSLFGraphicFrame;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFTheme;
import org.apache.xmlbeans.XmlCursor;

import stirling.software.officeconvert.topdf.docx.Charts;
import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.DecodedPicture;
import stirling.software.officeconvert.topdf.io.Relationship;
import stirling.software.officeconvert.topdf.pdf.Crop;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;

final class Frames {

    private static final String DRAWINGML = "http://schemas.openxmlformats.org/drawingml/2006/main";

    private static final QName EMBED = new QName("http://schemas.openxmlformats.org/officeDocument/2006/relationships",
            "embed");

    private static final int MAX_TOKENS = 200_000;

    private static final String CHART = "http://schemas.openxmlformats.org/drawingml/2006/chart";

    private static final QName ID = new QName("http://schemas.openxmlformats.org/officeDocument/2006/relationships",
            "id");

    private Frames() {}

    static void paint(Deck deck, ShapePainter shapes, XSLFGraphicFrame frame, Space space) throws IOException {
        if (frame instanceof XSLFDiagram d) {
            XSLFGroupShape g = d.getGroupShape();
            XSLFDiagramDrawing drawing = d.getDiagramDrawing();
            if (g == null || drawing == null || g.getShapes().isEmpty()) {
                SmartArtLayout.paint(deck, shapes, d, space);
            } else {
                String part = drawing.getPackagePart().getPartName().getName();
                shapes.group(unscaled(g, space), space.onSlide().withRelsPart(part), true);
            }
            return;
        }
        if (frame.hasChart()) {
            chart(deck, shapes, frame, space);
            return;
        }
        String id = previewId(frame);
        Rectangle2D anchor = frame.getAnchor();
        if (id == null || anchor == null) {
            return;
        }
        DecodedPicture picture = deck.pictures().picture(space.relsPart(), id);
        if (picture == null) {
            return;
        }
        Frame f = space.place(anchor, frame.getRotation(), frame.getFlipHorizontal(), frame.getFlipVertical());
        PdfCanvas canvas = shapes.canvas();
        canvas.save();
        try {
            canvas.transform(f.shapeTransform());
            canvas.image(picture, (float) f.x(), (float) f.y(), (float) f.width(), (float) f.height(), Crop.NONE, 0,
                    false, false, 1);
        } finally {
            canvas.restore();
        }
    }

    // The stored drawing is laid out for the frame as it appears on the slide, so outer groups move it but do not scale it
    private static XSLFGroupShape unscaled(XSLFGroupShape g, Space space) {
        Rectangle2D anchor = g.getAnchor();
        if (anchor == null) {
            return g;
        }
        Frame f = space.place(anchor, g.getRotation(), g.getFlipHorizontal(), g.getFlipVertical());
        g.setAnchor(new Rectangle2D.Double(f.x(), f.y(), f.width(), f.height()));
        g.setInteriorAnchor(new Rectangle2D.Double(0, 0, f.width(), f.height()));
        g.setRotation(f.rotation());
        g.setFlipHorizontal(f.flipH());
        g.setFlipVertical(f.flipV());
        return g;
    }

    // Charts are drawn from their cached values; the embedded workbook is never opened
    private static void chart(Deck deck, ShapePainter shapes, XSLFGraphicFrame frame, Space space) throws IOException {
        String id = chartId(frame);
        Rectangle2D anchor = frame.getAnchor();
        if (id == null || anchor == null) {
            return;
        }
        Relationship r = deck.job().zip().relationships(space.relsPart()).get(id);
        if (r == null || !ActiveContent.mayFollow(r)) {
            return;
        }
        String theme = null;
        try {
            XSLFTheme t = frame.getSheet().getTheme();
            theme = t == null ? null : t.getPackagePart().getPartName().getName();
        } catch (RuntimeException e) {
            theme = null;
        }
        Frame f = space.place(anchor, frame.getRotation(), frame.getFlipHorizontal(), frame.getFlipVertical());
        PdfCanvas canvas = shapes.canvas();
        canvas.save();
        try {
            canvas.transform(f.shapeTransform());
            if (!Charts.draw(deck.job(), r.part(), theme, canvas, (float) f.x(), (float) f.y(), (float) f.width(),
                    (float) f.height())) {
                deck.job().warn("A chart on slide " + shapes.slideNumber() + " could not be read");
            }
        } finally {
            canvas.restore();
        }
    }

    private static String chartId(XSLFGraphicFrame frame) {
        try (XmlCursor c = frame.getXmlObject().newCursor()) {
            int tokens = 0;
            while (c.hasNextToken() && tokens++ < MAX_TOKENS) {
                if (c.toNextToken() != XmlCursor.TokenType.START) {
                    continue;
                }
                QName name = c.getName();
                if (CHART.equals(name.getNamespaceURI()) && "chart".equals(name.getLocalPart())) {
                    String id = c.getAttributeText(ID);
                    return id == null || id.isBlank() ? null : id.strip();
                }
            }
        }
        return null;
    }

    static String previewId(XSLFGraphicFrame frame) {
        try (XmlCursor c = frame.getXmlObject().newCursor()) {
            int tokens = 0;
            while (c.hasNextToken() && tokens++ < MAX_TOKENS) {
                if (c.toNextToken() != XmlCursor.TokenType.START) {
                    continue;
                }
                QName name = c.getName();
                if (DRAWINGML.equals(name.getNamespaceURI()) && "blip".equals(name.getLocalPart())) {
                    String id = c.getAttributeText(EMBED);
                    if (id != null && !id.isBlank()) {
                        return id.strip();
                    }
                }
            }
        }
        return null;
    }
}

package stirling.software.officeconvert.topdf.pptx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFSlideMaster;
import org.apache.poi.xslf.usermodel.XSLFTheme;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;
import org.openxmlformats.schemas.drawingml.x2006.main.CTBaseStyles;
import org.openxmlformats.schemas.drawingml.x2006.main.CTColorScheme;
import org.openxmlformats.schemas.presentationml.x2006.main.CTCommonSlideData;
import org.openxmlformats.schemas.presentationml.x2006.main.CTSlide;
import org.openxmlformats.schemas.presentationml.x2006.main.CTSlideLayout;
import org.openxmlformats.schemas.presentationml.x2006.main.CTSlideMaster;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.Relationship;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;

final class SlidePainter {

    private final Deck deck;

    private final PdfCanvas canvas;

    private final XSLFSlide slide;

    private final int number;

    SlidePainter(Deck deck, PdfCanvas canvas, XSLFSlide slide, int number) {
        this.deck = deck;
        this.canvas = canvas;
        this.slide = slide;
        this.number = number;
    }

    void paint() throws IOException {
        CTBaseStyles elements = null;
        CTColorScheme saved = null;
        CTColorScheme override = override();
        if (override != null) {
            try {
                XSLFTheme theme = slide.getTheme();
                elements = theme == null ? null : theme.getXmlObject().getThemeElements();
                if (elements != null && elements.getClrScheme() != null) {
                    saved = (CTColorScheme) elements.getClrScheme().copy();
                    elements.setClrScheme(override);
                }
            } catch (RuntimeException e) {
                saved = null;
            }
        }
        try {
            paintShapes();
        } finally {
            if (saved != null) {
                elements.setClrScheme(saved);
            }
        }
    }

    // A theme override of the slide, else of its layout, replaces the theme colours for this slide
    private CTColorScheme override() {
        try {
            List<String> parts = new ArrayList<>();
            parts.add(slide.getPackagePart().getPartName().getName());
            XSLFSlideLayout layout = slide.getSlideLayout();
            if (layout != null) {
                parts.add(layout.getPackagePart().getPartName().getName());
            }
            for (String part : parts) {
                Relationship r = deck.job().zip().relationships(part).first("themeOverride");
                if (r == null || !ActiveContent.mayFollow(r)) {
                    continue;
                }
                Document d = deck.job().zip().xml(r);
                for (Node n = d.getDocumentElement().getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n instanceof Element e && "clrScheme".equals(e.getLocalName())) {
                        return CTColorScheme.Factory.parse(e, new XmlOptions().setLoadReplaceDocumentElement(null));
                    }
                }
            }
        } catch (IOException | XmlException | RuntimeException e) {
            return null;
        }
        return null;
    }

    private void paintShapes() throws IOException {
        ShapePainter shapes = new ShapePainter(deck, canvas, number);
        shapes.slide(slide);
        Backgrounds.paint(deck, canvas, slide);
        if (slide.getFollowMasterGraphics()) {
            XSLFSlideLayout layout = slide.getSlideLayout();
            if (layout != null) {
                XSLFSlideMaster master = layout.getSlideMaster();
                if (master != null && layout.getFollowMasterGraphics()) {
                    sheet(shapes, master, true);
                }
                sheet(shapes, layout, true);
            }
        }
        sheet(shapes, slide, false);
        LegacyShapes.paint(deck, canvas, slide, number);
    }

    private void sheet(ShapePainter shapes, XSLFSheet sheet, boolean master) throws IOException {
        List<XSLFShape> list;
        try {
            list = sheet.getShapes();
        } catch (RuntimeException e) {
            deck.job().warn("The shapes of slide " + number + " could not be read: " + e.getMessage());
            return;
        }
        String part = sheet.getPackagePart().getPartName().getName();
        Space space = Space.SLIDE.withRelsPart(part);
        shapes.sheet(sheet);
        shapes.tree(tree(sheet), list, space, !master, master);
    }

    private static XmlObject tree(XSLFSheet sheet) {
        XmlObject x = sheet.getXmlObject();
        CTCommonSlideData data = null;
        if (x instanceof CTSlide s) {
            data = s.getCSld();
        } else if (x instanceof CTSlideLayout l) {
            data = l.getCSld();
        } else if (x instanceof CTSlideMaster m) {
            data = m.getCSld();
        }
        return data == null ? null : data.getSpTree();
    }
}

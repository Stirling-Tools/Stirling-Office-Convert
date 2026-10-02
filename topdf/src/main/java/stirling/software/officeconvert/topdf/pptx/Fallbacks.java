package stirling.software.officeconvert.topdf.pptx;

import java.awt.geom.Rectangle2D;
import java.io.IOException;

import javax.xml.namespace.QName;

import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.apache.poi.xslf.usermodel.XSLFSimpleShape;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;
import org.openxmlformats.schemas.drawingml.x2006.main.CTBlipFillProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTShapeProperties;
import org.openxmlformats.schemas.presentationml.x2006.main.CTApplicationNonVisualDrawingProps;
import org.openxmlformats.schemas.presentationml.x2006.main.CTPicture;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShape;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

final class Fallbacks {

    static final String MC = "http://schemas.openxmlformats.org/markup-compatibility/2006";

    private static final String P = "http://schemas.openxmlformats.org/presentationml/2006/main";

    private Fallbacks() {}

    // A fallback placeholder without its own position takes the layout's, then the master's
    static Rectangle2D placeholder(XSLFSheet sheet, CTApplicationNonVisualDrawingProps nv) {
        if (sheet == null || nv == null || !nv.isSetPh()) {
            return null;
        }
        XSLFSheet s = sheet;
        for (int depth = 0; depth < 2; depth++) {
            if (!(s.getMasterSheet() instanceof XSLFSheet parent) || parent == s) {
                return null;
            }
            XSLFSimpleShape shape = parent.getPlaceholder(nv.getPh());
            Rectangle2D anchor = shape == null ? null : shape.getAnchor();
            if (anchor != null && anchor.getWidth() > 0 && anchor.getHeight() > 0) {
                return anchor;
            }
            s = parent;
        }
        return null;
    }

    static boolean isAlternateContent(QName name) {
        return name != null && MC.equals(name.getNamespaceURI()) && "AlternateContent".equals(name.getLocalPart());
    }

    static void paint(Deck deck, ShapePainter shapes, XmlObject alternate, Space space) throws IOException {
        Node fallback = null;
        for (Node n = alternate.getDomNode().getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && MC.equals(e.getNamespaceURI()) && "Fallback".equals(e.getLocalName())) {
                fallback = e;
            }
        }
        if (fallback == null) {
            return;
        }
        XmlOptions fragment = new XmlOptions().setLoadReplaceDocumentElement(null);
        for (Node n = fallback.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element e) || !P.equals(e.getNamespaceURI())) {
                continue;
            }
            try {
                if ("pic".equals(e.getLocalName())) {
                    CTPicture pic = CTPicture.Factory.parse(e, fragment);
                    CTApplicationNonVisualDrawingProps nv = pic.getNvPicPr() == null ? null : pic.getNvPicPr().getNvPr();
                    shapes.pictures().paint(pic.getSpPr(), PicturePainter.blipFill(pic), space,
                            placeholder(shapes.sheet(), nv));
                } else if ("sp".equals(e.getLocalName())) {
                    CTShape sp = CTShape.Factory.parse(e, fragment);
                    CTShapeProperties spPr = sp.getSpPr();
                    CTBlipFillProperties fill = spPr == null ? null : spPr.getBlipFill();
                    if (fill != null) {
                        CTApplicationNonVisualDrawingProps nv = sp.getNvSpPr() == null ? null : sp.getNvSpPr().getNvPr();
                        shapes.pictures().paint(spPr, fill, space, placeholder(shapes.sheet(), nv));
                    }
                }
            } catch (XmlException | RuntimeException ex) {
                deck.job().warn("A fallback picture could not be drawn: " + ex.getMessage());
            }
        }
    }
}

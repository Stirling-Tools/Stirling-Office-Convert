package stirling.software.officeconvert.topdf.pptx;

import java.util.ArrayList;
import java.util.List;

import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import stirling.software.officeconvert.topdf.pdf.Units;

public final class SlideSizes {

    public static final String URI = "urn:stirling-office-convert:slide-size";

    private static final String P = "http://schemas.openxmlformats.org/presentationml/2006/main";

    private SlideSizes() {}

    public static String ext(long cx, long cy) {
        return "<p:extLst><p:ext uri=\"" + URI + "\"><s:sldSz xmlns:s=\"" + URI + "\" cx=\"" + cx + "\" cy=\"" + cy
                + "\"/></p:ext></p:extLst>";
    }

    static float[] of(XSLFSlide slide, float width, float height) {
        Element size = size(slide);
        if (size == null) {
            return new float[] {width, height};
        }
        return new float[] {side(size.getAttribute("cx"), width), side(size.getAttribute("cy"), height)};
    }

    private static Element size(XSLFSlide slide) {
        Node root;
        try {
            root = slide.getXmlObject().getDomNode();
        } catch (RuntimeException e) {
            return null;
        }
        for (Node ext : kids(root, P, "extLst")) {
            for (Node e : kids(ext, P, "ext")) {
                if (e instanceof Element el && URI.equals(el.getAttribute("uri"))) {
                    for (Node s : kids(el, URI, "sldSz")) {
                        return (Element) s;
                    }
                }
            }
        }
        return null;
    }

    private static List<Node> kids(Node parent, String ns, String local) {
        List<Node> out = new ArrayList<>();
        for (Node k = parent == null ? null : parent.getFirstChild(); k != null; k = k.getNextSibling()) {
            if (k instanceof Element && ns.equals(k.getNamespaceURI()) && local.equals(k.getLocalName())) {
                out.add(k);
            }
        }
        return out;
    }

    private static float side(String emu, float fallback) {
        try {
            float v = Units.emu(Long.parseLong(emu.trim()));
            return v > 0 && Float.isFinite(v) ? Math.max(1, Math.min(14_400, v)) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}

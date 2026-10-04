package stirling.software.officeconvert.topdf.pptx;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.openxmlformats.schemas.drawingml.x2006.main.CTFontReference;
import org.openxmlformats.schemas.drawingml.x2006.main.CTShapeStyle;
import org.openxmlformats.schemas.drawingml.x2006.main.STSchemeColorVal;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.Relationship;
import stirling.software.officeconvert.topdf.io.Relationships;

final class DiagramTextFills {

    private static final String DGM = "http://schemas.openxmlformats.org/drawingml/2006/diagram";

    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";

    static final int MAX_POINTS = 20_000;

    private final Map<String, Element> byModel;

    private DiagramTextFills(Map<String, Element> byModel) {
        this.byModel = byModel;
    }

    static final DiagramTextFills NONE = new DiagramTextFills(Map.of());

    static DiagramTextFills read(OfficeZip zip, Relationships rels, Document data, String colorsId)
            throws InterruptedIOException {
        try {
            Relationship cs = colorsId == null ? null : rels.get(colorsId);
            if (cs == null || !ActiveContent.mayFollow(cs) || cs.part() == null || !zip.exists(cs.part())) {
                return NONE;
            }
            Map<String, Element[]> lists = textFills(zip.xml(cs));
            if (lists.isEmpty()) {
                return NONE;
            }
            Map<String, Element> byModel = new HashMap<>();
            int seen = 0;
            for (Node n = data.getDocumentElement().getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element list) || !DGM.equals(list.getNamespaceURI())
                        || !"ptLst".equals(list.getLocalName())) {
                    continue;
                }
                for (Node p = list.getFirstChild(); p != null && seen < MAX_POINTS; p = p.getNextSibling()) {
                    if (p instanceof Element pt && "pt".equals(pt.getLocalName())) {
                        seen++;
                        place(pt, lists, byModel);
                    }
                }
            }
            return new DiagramTextFills(byModel);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return NONE;
        }
    }

    private static void place(Element pt, Map<String, Element[]> lists, Map<String, Element> byModel) {
        Element pr = child(pt, DGM, "prSet");
        String model = pt.getAttribute("modelId");
        if (pr == null || model.isEmpty()) {
            return;
        }
        Element[] fills = lists.get(pr.getAttribute("presStyleLbl"));
        if (fills == null) {
            return;
        }
        int idx = 0;
        try {
            idx = Math.max(0, Integer.parseInt(pr.getAttribute("presStyleIdx").strip()));
        } catch (NumberFormatException e) {
            idx = 0;
        }
        byModel.put(model.strip(), fills[idx % fills.length]);
    }

    private static Map<String, Element[]> textFills(Document colors) {
        Map<String, Element[]> out = new HashMap<>();
        for (Node n = colors.getDocumentElement().getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element label) || !"styleLbl".equals(label.getLocalName())) {
                continue;
            }
            Element list = child(label, DGM, "txFillClrLst");
            if (list == null) {
                continue;
            }
            List<Element> colours = new ArrayList<>();
            for (Node c = list.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (c instanceof Element e && A.equals(e.getNamespaceURI())
                        && ("schemeClr".equals(e.getLocalName()) || "srgbClr".equals(e.getLocalName()))) {
                    colours.add(e);
                }
            }
            if (!colours.isEmpty()) {
                out.putIfAbsent(label.getAttribute("name"), colours.toArray(new Element[0]));
            }
        }
        return out;
    }

    void apply(String modelId, CTShapeStyle style) {
        Element colour = modelId == null ? null : byModel.get(modelId.strip());
        if (colour == null || style == null || style.getFontRef() == null) {
            return;
        }
        CTFontReference ref = style.getFontRef();
        if (ref.isSetSchemeClr() || ref.isSetSrgbClr() || ref.isSetScrgbClr() || ref.isSetHslClr()
                || ref.isSetSysClr() || ref.isSetPrstClr()) {
            return;
        }
        String val = colour.getAttribute("val").strip();
        if ("schemeClr".equals(colour.getLocalName())) {
            STSchemeColorVal.Enum scheme = STSchemeColorVal.Enum.forString(val);
            if (scheme != null) {
                ref.addNewSchemeClr().setVal(scheme);
            }
        } else if (val.matches("[0-9A-Fa-f]{6}")) {
            ref.addNewSrgbClr().setVal(HexFormat.of().parseHex(val));
        }
    }

    private static Element child(Element parent, String ns, String local) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }
}

package stirling.software.officeconvert.topdf.xlsx;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.io.ActiveContent;
import stirling.software.officeconvert.topdf.io.OfficeZip;
import stirling.software.officeconvert.topdf.io.Relationship;
import stirling.software.officeconvert.topdf.io.Relationships;

// Pictures for &G codes: VML shapes named LH, CH, RH (LF, CF, RF for footers, FIRST or EVEN appended)
final class HeaderPictures {

    record Picture(String part, double width, double height) {}

    static final HeaderPictures NONE = new HeaderPictures(Map.of());

    private final Map<String, Picture> shapes;

    private HeaderPictures(Map<String, Picture> shapes) {
        this.shapes = shapes;
    }

    Picture get(String id) {
        return shapes.get(id);
    }

    static HeaderPictures read(RenderJob job, String sheetPart, String relId) throws InterruptedIOException {
        if (relId == null) {
            return NONE;
        }
        try {
            OfficeZip zip = job.zip();
            Relationship r = zip.relationships(sheetPart).get(relId);
            if (r == null || !ActiveContent.mayFollow(r)) {
                return NONE;
            }
            Element root = zip.xml(r).getDocumentElement();
            Relationships rels = zip.relationships(r.part());
            Map<String, Picture> out = new HashMap<>();
            NodeList list = root.getElementsByTagNameNS("*", "shape");
            for (int i = 0; i < list.getLength() && out.size() < 18; i++) {
                Element shape = (Element) list.item(i);
                String id = shape.getAttribute("id");
                NodeList data = shape.getElementsByTagNameNS("*", "imagedata");
                if (id.isEmpty() || data.getLength() == 0) {
                    continue;
                }
                String rid = Dml.attrNs((Element) data.item(0), "relid");
                Relationship pic = rid == null ? null : rels.get(rid);
                if (pic == null || !ActiveContent.mayFollow(pic)) {
                    continue;
                }
                String style = shape.getAttribute("style");
                out.put(id.toUpperCase(Locale.ROOT), new Picture(pic.part(), length(style, "width"),
                        length(style, "height")));
            }
            return out.isEmpty() ? NONE : new HeaderPictures(out);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            job.warn("A header or footer picture could not be read");
            return NONE;
        }
    }

    private static double length(String style, String name) {
        for (String part : style.split(";")) {
            int colon = part.indexOf(':');
            if (colon > 0 && part.substring(0, colon).trim().equalsIgnoreCase(name)) {
                String v = part.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
                double k = v.endsWith("in") ? 72 : v.endsWith("cm") ? 72 / 2.54 : v.endsWith("mm") ? 72 / 25.4
                        : v.endsWith("px") ? 0.75 : 1;
                String num = v.replaceAll("[a-z%]+$", "");
                try {
                    return Math.max(0, Math.min(2000, Double.parseDouble(num) * k));
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        return 0;
    }
}

package stirling.software.officeconvert.topdf.pptx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFSlideMaster;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.presentationml.x2006.main.CTGroupShape;
import org.openxmlformats.schemas.presentationml.x2006.main.CTPlaceholder;
import org.openxmlformats.schemas.presentationml.x2006.main.CTShape;

// PowerPoint gives a slide placeholder without an index the first layout placeholder of its type; POI takes the last,
// so that first one is moved behind the others before POI indexes them
final class PlaceholderOrder {

    private static final int MAX_SHAPES = 5000;

    private PlaceholderOrder() {}

    static void fix(XMLSlideShow ppt) {
        try {
            for (XSLFSlideMaster m : ppt.getSlideMasters()) {
                fix(m.getXmlObject().getCSld().getSpTree());
                for (XSLFSlideLayout l : m.getSlideLayouts()) {
                    fix(l.getXmlObject().getCSld().getSpTree());
                }
            }
        } catch (RuntimeException e) {
            return;
        }
    }

    private static void fix(CTGroupShape tree) {
        if (tree == null || tree.sizeOfSpArray() > MAX_SHAPES) {
            return;
        }
        Map<Integer, List<CTShape>> byType = new LinkedHashMap<>();
        for (CTShape sp : tree.getSpList()) {
            CTPlaceholder ph = sp.getNvSpPr() == null || sp.getNvSpPr().getNvPr() == null
                    || !sp.getNvSpPr().getNvPr().isSetPh() ? null : sp.getNvSpPr().getNvPr().getPh();
            if (ph != null && ph.isSetType()) {
                byType.computeIfAbsent(ph.getType().intValue(), k -> new ArrayList<>()).add(sp);
            }
        }
        for (List<CTShape> group : byType.values()) {
            CTShape primary = null;
            for (CTShape sp : group) {
                CTPlaceholder ph = sp.getNvSpPr().getNvPr().getPh();
                if (!ph.isSetIdx() || ph.getIdx() == 0) {
                    primary = sp;
                    break;
                }
            }
            CTShape last = group.get(group.size() - 1);
            if (group.size() < 2 || primary == null || primary == last) {
                continue;
            }
            try (XmlCursor from = primary.newCursor(); XmlCursor to = last.newCursor()) {
                to.toEndToken();
                to.toNextToken();
                from.moveXml(to);
            }
        }
    }
}

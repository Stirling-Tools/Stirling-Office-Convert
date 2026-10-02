package stirling.software.officeconvert.topdf.pptx;

import java.util.ArrayList;
import java.util.List;

import org.apache.poi.xslf.model.ParagraphPropertyFetcher;
import org.apache.poi.xslf.usermodel.XSLFSlideMaster;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextCharacterProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextParagraphProperties;

record StyleChain(List<CTTextParagraphProperties> paragraphs, List<CTTextCharacterProperties> runs,
        RuntimeException end, boolean master, Inherited<CTTextParagraphProperties> masterStyle) {

    boolean sameLevels(StyleChain o) {
        if (master != o.master || end != null || o.end != null || paragraphs.size() != o.paragraphs.size()) {
            return false;
        }
        for (int i = 0; i < paragraphs.size(); i++) {
            if (paragraphs.get(i) != o.paragraphs.get(i)) {
                return false;
            }
        }
        return true;
    }

    int levelsHash() {
        int h = Boolean.hashCode(master);
        for (CTTextParagraphProperties p : paragraphs) {
            h = 31 * h + System.identityHashCode(p);
        }
        return h;
    }

    static StyleChain of(XSLFTextParagraph p) {
        XSLFTextShape shape = p.getParentShape();
        List<CTTextParagraphProperties> seen = new ArrayList<>();
        RuntimeException end = null;
        try {
            new ParagraphPropertyFetcher<Void>(p, (props, val) -> seen.add(props)).fetchProperty(shape);
        } catch (RuntimeException e) {
            end = e;
        }
        if (p.getXmlObject().getPPr() != null && !seen.isEmpty()) {
            seen.remove(0);
        }
        List<CTTextCharacterProperties> runs = new ArrayList<>(seen.size());
        for (CTTextParagraphProperties props : seen) {
            CTTextCharacterProperties def = props.getDefRPr();
            if (def != null) {
                runs.add(def);
            }
        }
        return new StyleChain(List.copyOf(seen), List.copyOf(runs), end, shape.getSheet() instanceof XSLFSlideMaster,
                shape instanceof XSLFTableCell ? Inherited.of(p::getDefaultMasterStyle) : null);
    }
}

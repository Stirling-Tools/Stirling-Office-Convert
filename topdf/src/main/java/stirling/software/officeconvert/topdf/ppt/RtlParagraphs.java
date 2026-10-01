package stirling.software.officeconvert.topdf.ppt;

import java.util.List;

import org.apache.poi.hslf.model.textproperties.TextProp;
import org.apache.poi.hslf.model.textproperties.TextPropCollection;
import org.apache.poi.hslf.usermodel.HSLFGroupShape;
import org.apache.poi.hslf.usermodel.HSLFMasterSheet;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSheet;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;

final class RtlParagraphs {

    private static final String DIRECTION = "textDirection";

    private static final int MAX_DEPTH = 64;

    private RtlParagraphs() {}

    static void apply(HSLFSlideShow ppt) {
        for (HSLFSheet sheet : ppt.getSlides()) {
            shapes(sheet.getShapes(), 0);
        }
        for (HSLFSheet sheet : ppt.getSlideMasters()) {
            shapes(sheet.getShapes(), 0);
        }
        for (HSLFSheet sheet : ppt.getTitleMasters()) {
            shapes(sheet.getShapes(), 0);
        }
    }

    private static void shapes(List<HSLFShape> list, int depth) {
        if (list == null || depth > MAX_DEPTH) {
            return;
        }
        for (HSLFShape s : list) {
            if (s instanceof HSLFGroupShape g) {
                shapes(g.getShapes(), depth + 1);
            } else if (s instanceof HSLFTextShape t) {
                paragraphs(t.getTextParagraphs());
            }
        }
    }

    private static void paragraphs(List<HSLFTextParagraph> list) {
        if (list == null) {
            return;
        }
        for (HSLFTextParagraph p : list) {
            try {
                if (!rightToLeft(p)) {
                    continue;
                }
                TextAlign align = p.getTextAlign();
                if (align == null || align == TextAlign.LEFT) {
                    p.setTextAlign(TextAlign.RIGHT);
                } else if (align == TextAlign.RIGHT) {
                    p.setTextAlign(TextAlign.LEFT);
                }
            } catch (RuntimeException ignored) {
                continue;
            }
        }
    }

    static boolean rightToLeft(HSLFTextParagraph p) {
        TextProp own = p.getParagraphStyle() == null ? null : p.getParagraphStyle().findByName(DIRECTION);
        if (own != null) {
            return own.getValue() == 1;
        }
        HSLFSheet sheet = p.getSheet();
        HSLFMasterSheet master = sheet instanceof HSLFMasterSheet m ? m : sheet == null ? null : sheet.getMasterSheet();
        if (master == null) {
            return false;
        }
        TextPropCollection inherited = master.getPropCollection(p.getRunType(), p.getIndentLevel(), DIRECTION, false);
        TextProp prop = inherited == null ? null : inherited.findByName(DIRECTION);
        return prop != null && prop.getValue() == 1;
    }
}

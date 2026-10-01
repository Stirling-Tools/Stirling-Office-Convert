package stirling.software.officeconvert.topdf.ppt;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.hslf.record.SlideAtomLayout;
import org.apache.poi.hslf.usermodel.HSLFMasterSheet;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.hslf.usermodel.HSLFTextRun;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.sl.usermodel.Placeholder;

final class SlideFooters {

    private static final Set<SlideAtomLayout.SlideLayoutType> TITLES = Set.of(
            SlideAtomLayout.SlideLayoutType.TITLE_SLIDE, SlideAtomLayout.SlideLayoutType.TITLE_ONLY,
            SlideAtomLayout.SlideLayoutType.MASTER_TITLE);

    private final Map<HSLFTextRun, String> footerRuns = new IdentityHashMap<>();

    void writeFooter(HSLFSlide slide) {
        String footer = slide.getHeadersFooters().getFooterText();
        for (HSLFTextShape shape : placeholders(slide.getMasterSheet(), Placeholder.FOOTER)) {
            for (HSLFTextParagraph p : shape.getTextParagraphs()) {
                for (HSLFTextRun run : p.getTextRuns()) {
                    String original = footerRuns.computeIfAbsent(run, HSLFTextRun::getRawText);
                    if ("*".equals(original)) {
                        run.setText(footer == null ? "" : footer);
                    }
                }
            }
        }
    }

    List<HSLFTextShape> slideNumbers(HSLFSlide slide) {
        if (title(slide)) {
            return List.of();
        }
        if (!slide.getHeadersFooters().isSlideNumberVisible() || ownsSlideNumber(slide)) {
            return List.of();
        }
        return placeholders(slide.getMasterSheet(), Placeholder.SLIDE_NUMBER);
    }

    private static boolean ownsSlideNumber(HSLFSlide slide) {
        for (HSLFShape shape : slide.getShapes()) {
            if (shape instanceof HSLFTextShape t && t.getPlaceholder() == Placeholder.SLIDE_NUMBER) {
                return true;
            }
        }
        return false;
    }

    private static boolean title(HSLFSlide slide) {
        try {
            return TITLES.contains(slide.getSlideRecord().getSlideAtom().getSSlideLayoutAtom().getGeometryType());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static List<HSLFTextShape> placeholders(HSLFMasterSheet master, Placeholder kind) {
        List<HSLFTextShape> out = new ArrayList<>();
        if (master == null) {
            return out;
        }
        for (HSLFShape shape : master.getShapes()) {
            if (shape instanceof HSLFTextShape t && t.getPlaceholder() == kind) {
                out.add(t);
            }
        }
        return out;
    }
}

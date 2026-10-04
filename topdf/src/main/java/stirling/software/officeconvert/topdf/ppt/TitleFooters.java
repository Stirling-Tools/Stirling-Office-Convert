package stirling.software.officeconvert.topdf.ppt;

import org.apache.poi.hslf.record.DocumentAtom;
import org.apache.poi.hslf.record.SlideAtomLayout;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;

final class TitleFooters {

    private TitleFooters() {}

    static void apply(HSLFSlideShow ppt) {
        boolean omit;
        try {
            DocumentAtom atom = ppt.getDocumentRecord().getDocumentAtom();
            omit = atom != null && atom.getOmitTitlePlace();
        } catch (RuntimeException e) {
            return;
        }
        for (HSLFSlide slide : ppt.getSlides()) {
            try {
                SlideAtomLayout layout = slide.getSlideRecord().getSlideAtom().getSSlideLayoutAtom();
                SlideAtomLayout.SlideLayoutType type = layout.getGeometryType();
                boolean title = type == SlideAtomLayout.SlideLayoutType.TITLE_SLIDE;
                boolean titleLike = type == SlideAtomLayout.SlideLayoutType.TITLE_ONLY
                        || type == SlideAtomLayout.SlideLayoutType.MASTER_TITLE;
                if (titleLike || title && !omit) {
                    layout.setGeometryType(SlideAtomLayout.SlideLayoutType.TITLE_BODY);
                }
            } catch (RuntimeException ignored) {
                continue;
            }
        }
    }
}

package stirling.software.officeconvert.topdf.ppt;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.hslf.model.HeadersFooters;
import org.apache.poi.hslf.usermodel.HSLFGroupShape;
import org.apache.poi.hslf.usermodel.HSLFShape;
import org.apache.poi.hslf.usermodel.HSLFSimpleShape;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.sl.usermodel.Placeholder;

final class SavedDates {

    private static final int MAX_DEPTH = 64;

    private SavedDates() {}

    static void apply(HSLFSlideShow ppt) {
        Date saved = saved(ppt);
        for (HSLFSlide slide : ppt.getSlides()) {
            try {
                HeadersFooters hf = slide.getHeadersFooters();
                if (!hf.isDateTimeVisible() || hf.isUserDateVisible() && hf.getUserDateAtom() != null) {
                    continue;
                }
                HSLFSimpleShape shape = dateShape(slide.getShapes(), 0);
                if (shape == null) {
                    continue;
                }
                hf.setDateTimeText(saved == null ? "" : text(saved, shape));
                hf.setTodayDateVisible(false);
            } catch (RuntimeException ignored) {
                continue;
            }
        }
    }

    private static String text(Date saved, HSLFSimpleShape shape) {
        LocalDateTime at = LocalDateTime.ofInstant(saved.toInstant(), ZoneOffset.UTC);
        try {
            DateTimeFormatter format = shape.getPlaceholderDetails().getDateFormat();
            return at.format(format);
        } catch (RuntimeException e) {
            return at.toLocalDate().toString();
        }
    }

    private static Date saved(HSLFSlideShow ppt) {
        try {
            SummaryInformation si = ppt.getSummaryInformation();
            if (si == null) {
                return null;
            }
            Date d = si.getLastSaveDateTime();
            return d != null ? d : si.getCreateDateTime();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static HSLFSimpleShape dateShape(List<HSLFShape> shapes, int depth) {
        if (shapes == null || depth > MAX_DEPTH) {
            return null;
        }
        for (HSLFShape s : shapes) {
            if (s instanceof HSLFSimpleShape simple && simple.getPlaceholder() == Placeholder.DATETIME) {
                return simple;
            }
            if (s instanceof HSLFGroupShape g) {
                HSLFSimpleShape found = dateShape(g.getShapes(), depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}

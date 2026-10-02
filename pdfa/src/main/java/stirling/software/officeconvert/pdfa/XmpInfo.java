package stirling.software.officeconvert.pdfa;

import java.util.Calendar;
import java.util.Set;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.util.DateConverter;

final class XmpInfo {

    private XmpInfo() {}

    static void fill(COSDictionary info, XmpProperties xmp) {
        text(info, COSName.TITLE, xmp.first(XmpProperties.DC, "title", "Alt Text"));
        xmp.authors(info);
        text(info, COSName.SUBJECT, xmp.first(XmpProperties.DC, "description", "Alt Text"));
        text(info, COSName.CREATOR, xmp.first(XmpProperties.XMP, "CreatorTool", "Text"));
        text(info, COSName.PRODUCER, xmp.first(XmpProperties.PDF, "Producer", "Text"));
        text(info, COSName.KEYWORDS, xmp.first(XmpProperties.PDF, "Keywords", "Text"));
        String original = info.getString(COSName.CREATION_DATE);
        if (original == null || DateConverter.toCalendar(original) == null) {
            String value = xmp.first(XmpProperties.XMP, "CreateDate", "Date");
            Calendar calendar = value == null ? null : DateConverter.toCalendar(value);
            if (calendar != null) {
                info.setDate(COSName.CREATION_DATE, calendar);
            }
        }
        COSName trapped = info.getCOSName(COSName.TRAPPED);
        if (trapped == null || !Set.of("True", "False", "Unknown").contains(trapped.getName())) {
            info.removeItem(COSName.TRAPPED);
            String value = xmp.first(XmpProperties.PDF, "Trapped", "Text");
            if (value != null && Set.of("True", "False", "Unknown").contains(value)) {
                info.setName(COSName.TRAPPED, value);
            }
        }
    }

    private static void text(COSDictionary info, COSName key, String value) {
        if (!(info.getDictionaryObject(key) instanceof COSString text) || Metadata.clean(text.getString()).isEmpty()) {
            if (value != null && !value.isEmpty()) {
                info.setString(key, value);
            }
        }
    }
}

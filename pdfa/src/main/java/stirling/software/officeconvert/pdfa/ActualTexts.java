package stirling.software.officeconvert.pdfa;

import java.io.IOException;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;

final class ActualTexts {

    private static final COSName ACTUAL_TEXT = COSName.getPDFName("ActualText");

    private ActualTexts() {}

    static void run(PDDocument doc, Report report) throws IOException {
        boolean[] changed = {false};
        CosWalk.walk(doc, b -> {
            if (b instanceof COSDictionary d && stripPrivateUse(d)) {
                changed[0] = true;
            }
        });
        if (changed[0]) {
            report.warn("Removed private use characters from ActualText, which PDF/A-4 does not allow");
        }
    }

    static boolean stripPrivateUse(COSDictionary d) {
        if (!(d.getDictionaryObject(ACTUAL_TEXT) instanceof COSString s)) {
            return false;
        }
        String text = s.getString();
        StringBuilder kept = new StringBuilder(text.length());
        text.codePoints().filter(cp -> !privateUse(cp)).forEach(kept::appendCodePoint);
        if (kept.length() == text.length()) {
            return false;
        }
        if (kept.isEmpty()) {
            d.removeItem(ACTUAL_TEXT);
        } else {
            d.setItem(ACTUAL_TEXT, new COSString(kept.toString()));
        }
        return true;
    }

    private static boolean privateUse(int cp) {
        return cp >= 0xE000 && cp <= 0xF8FF || cp >= 0xF0000 && cp <= 0xFFFFD || cp >= 0x100000 && cp <= 0x10FFFD;
    }
}

package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.cmap.CMapParser;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.font.PDCIDSystemInfo;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

final class CollectionText {

    private static final List<String> ORDERINGS = List.of("Japan1", "GB1", "CNS1", "Korea1");

    private final PDType0Font font;

    private final CMap toUnicode;

    private final CMap ucs2;

    private CollectionText(PDType0Font font, CMap toUnicode, CMap ucs2) {
        this.font = font;
        this.toUnicode = toUnicode;
        this.ucs2 = ucs2;
    }

    static CollectionText of(PDType0Font font) {
        PDCIDSystemInfo info = font.getDescendantFont().getCIDSystemInfo();
        if (info == null || !"Adobe".equals(info.getRegistry()) || !ORDERINGS.contains(info.getOrdering())) {
            return null;
        }
        try {
            CMap toUnicode = null;
            if (font.getCOSObject().getDictionaryObject(COSName.TO_UNICODE) instanceof COSStream stream) {
                try (InputStream in = stream.createInputStream()) {
                    toUnicode = new CMapParser().parse(new RandomAccessReadBuffer(in));
                }
            }
            return new CollectionText(font, toUnicode,
                    new CMapParser().parsePredefined("Adobe-" + info.getOrdering() + "-UCS2"));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    String text(int code, String unicode) {
        if (unicode.codePointCount(0, unicode.length()) != 1 || unicode.codePointAt(0) != code) {
            return null;
        }
        if (toUnicode != null && toUnicode.toUnicode(code) != null) {
            return null;
        }
        int cid = font.codeToCID(code);
        String text = cid > 0 ? ucs2.toUnicode(cid, 2) : null;
        return text == null || UnicodeRecovery.privateUse(text) ? null : text;
    }
}

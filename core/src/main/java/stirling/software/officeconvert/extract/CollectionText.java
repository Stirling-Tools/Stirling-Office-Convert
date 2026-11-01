package stirling.software.officeconvert.extract;

import java.io.IOException;
import java.io.InputStream;

import org.apache.fontbox.cmap.CMap;
import org.apache.fontbox.cmap.CMapParser;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

final class CollectionText {

    private final PDType0Font font;

    private final CMap toUnicode;

    private CollectionText(PDType0Font font, CMap toUnicode) {
        this.font = font;
        this.toUnicode = toUnicode;
    }

    static CollectionText of(PDType0Font font) {
        if (!CidLookup.adobe(font)) {
            return null;
        }
        try {
            CMap toUnicode = null;
            if (font.getCOSObject().getDictionaryObject(COSName.TO_UNICODE) instanceof COSStream stream) {
                try (InputStream in = stream.createInputStream()) {
                    toUnicode = new CMapParser().parse(new RandomAccessReadBuffer(in));
                }
            }
            return new CollectionText(font, toUnicode);
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
        String text = CidLookup.text(font, code);
        return text == null || UnicodeRecovery.privateUse(text) ? null : text;
    }
}

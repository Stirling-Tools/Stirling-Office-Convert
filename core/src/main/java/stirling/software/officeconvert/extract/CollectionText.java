package stirling.software.officeconvert.extract;

import org.apache.fontbox.cmap.CMap;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import stirling.software.officeconvert.fonts.CodeReader;

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
            COSBase declared = font.getCOSObject().getDictionaryObject(COSName.TO_UNICODE);
            return new CollectionText(font, declared instanceof COSStream ? CodeReader.cmap(declared) : null);
        } catch (RuntimeException e) {
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

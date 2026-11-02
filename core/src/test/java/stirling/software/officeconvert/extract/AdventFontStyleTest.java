package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

class AdventFontStyleTest {

    private static FontInfo resolve(String name) throws IOException {
        COSDictionary dict = new COSDictionary();
        dict.setItem(COSName.TYPE, COSName.FONT);
        dict.setItem(COSName.SUBTYPE, COSName.TYPE1);
        dict.setName(COSName.BASE_FONT, name);
        return new FontResolver().resolve(new PDType1Font(dict));
    }

    @Test
    void anAdventFontNamedWithABoldSuffixIsBold() throws IOException {
        FontInfo bold = resolve("AdvTT2ffda40f.B");
        assertTrue(bold.bold());
        assertFalse(bold.italic());
    }

    @Test
    void anAdventFontNamedWithAnItalicSuffixIsItalic() throws IOException {
        FontInfo italic = resolve("AdvTT3f84ef53.I");
        assertTrue(italic.italic());
        assertFalse(italic.bold());
        FontInfo both = resolve("AdvP4C4E74.BI");
        assertTrue(both.bold() && both.italic());
    }

    @Test
    void anAdventFontWithoutASuffixIsRegular() throws IOException {
        FontInfo plain = resolve("AdvTT7b515deb");
        assertFalse(plain.bold() || plain.italic());
    }
}

package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

class CamelStyleFontTest {

    private static FontInfo resolve(String name) throws IOException {
        COSDictionary dict = new COSDictionary();
        dict.setItem(COSName.TYPE, COSName.FONT);
        dict.setItem(COSName.SUBTYPE, COSName.TYPE1);
        dict.setName(COSName.BASE_FONT, name);
        return new FontResolver().resolve(new PDType1Font(dict));
    }

    @Test
    void aStyleWordJoinedToTheFamilyNameSetsTheWeightAndSlant() throws IOException {
        FontInfo bold = resolve("QGDAAA+VerdanaBold");
        assertEquals("Verdana", bold.family());
        assertTrue(bold.bold());
        assertFalse(bold.italic());
        FontInfo italic = resolve("VerdanaItalic");
        assertTrue(italic.italic());
        assertFalse(italic.bold());
        assertTrue(resolve("ArialBoldItalicMT").bold());
    }

    @Test
    void aJoinedRegularStyleWordStaysRegular() throws IOException {
        FontInfo regular = resolve("VerdanaRegular");
        assertEquals("Verdana", regular.family());
        assertFalse(regular.bold() || regular.italic());
        assertFalse(resolve("TimesRoman").bold());
    }
}

package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

class LigatureNamesTest {

    @Test
    void underscoreLigatureNamesReadAsTheirComponents() {
        assertEquals("ffi", LigatureNames.text("f_f_i", TextPositions.GLYPHS));
        assertEquals("Th", LigatureNames.text("T_h", TextPositions.GLYPHS));
        assertEquals("ft", LigatureNames.text("f_t.alt", TextPositions.GLYPHS));
        assertEquals("Ая", LigatureNames.text("uni0410_uni044F", TextPositions.GLYPHS));
    }

    @Test
    void namesWithAnUnknownPartStayUnmapped() {
        assertNull(LigatureNames.text("f_zzz", TextPositions.GLYPHS));
        assertNull(LigatureNames.text("_f", TextPositions.GLYPHS));
        assertNull(LigatureNames.text("f_", TextPositions.GLYPHS));
        assertNull(LigatureNames.text("fi", TextPositions.GLYPHS));
    }

    @Test
    void aFontEncodingThatNamesLigaturesReadsAsText() throws IOException {
        COSArray differences = new COSArray();
        differences.add(COSInteger.get(1));
        differences.add(COSName.getPDFName("f_f_i"));
        differences.add(COSName.getPDFName("f_l"));
        COSDictionary encoding = new COSDictionary();
        encoding.setItem(COSName.TYPE, COSName.ENCODING);
        encoding.setItem(COSName.DIFFERENCES, differences);
        COSDictionary dict = new COSDictionary();
        dict.setItem(COSName.TYPE, COSName.FONT);
        dict.setItem(COSName.SUBTYPE, COSName.TYPE1);
        dict.setName(COSName.BASE_FONT, "MinionPro-Regular");
        dict.setItem(COSName.ENCODING, encoding);
        PDType1Font font = new PDType1Font(dict);
        assertEquals("ffi", LigatureNames.text(font, 1, TextPositions.GLYPHS));
        assertEquals("fl", LigatureNames.text(font, 2, TextPositions.GLYPHS));
    }
}

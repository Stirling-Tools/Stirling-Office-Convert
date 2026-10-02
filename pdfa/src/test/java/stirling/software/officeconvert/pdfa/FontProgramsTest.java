package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.fontbox.cff.CFFParser;
import org.apache.fontbox.type1.Type1Font;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.junit.jupiter.api.Test;

class FontProgramsTest {

    @Test
    void theGeneratedProgramsParse() throws Exception {
        FontPrograms.Type1 t1 = FontPrograms.type1("TestType", 3);
        byte[] a = java.util.Arrays.copyOfRange(t1.data(), 0, t1.length1());
        byte[] b = java.util.Arrays.copyOfRange(t1.data(), t1.length1(), t1.length1() + t1.length2());
        Type1Font f = Type1Font.createWithSegments(a, b);
        assertEquals(FontPrograms.NAMES.size() + 1, f.getCharStringsDict().size());
        assertTrue(f.getPath("A").getBounds2D().getWidth() > 0);
        var cff = new CFFParser().parse(new RandomAccessReadBuffer(FontPrograms.cff("TestCff"))).get(0);
        assertEquals(FontPrograms.NAMES.size() + 1, cff.getNumCharStrings());
        assertTrue(((org.apache.fontbox.cff.CFFType1Font) cff).getType2CharString(cff.getCharset().getGIDForSID(34))
                .getPath().getBounds2D().getWidth() > 0);
    }
}

package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class UnmappedGlyphsTest {

    @Test
    void aLatinLetterWinsATieWithAnotherScript() {
        assertEquals("v", UnmappedGlyphs.preferred(ref("ν"), ref("v")).text());
        assertEquals("o", UnmappedGlyphs.preferred(ref("o"), ref("ö")).text());
    }

    @Test
    void aDashWinsATieWithAnUnderscore() {
        assertEquals("–", UnmappedGlyphs.preferred(ref("_"), ref("–")).text());
        assertEquals("-", UnmappedGlyphs.preferred(ref("-"), ref("_")).text());
    }

    private static GlyphRef ref(String text) {
        return new GlyphRef(text, 0, new GlyphPrint(new float[GlyphPrint.GRID * GlyphPrint.GRID], 0, 0, 1, 1, 1));
    }
}

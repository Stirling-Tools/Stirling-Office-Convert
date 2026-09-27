package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class GlyphCollectorTest {

    @Test
    void mapsWindows1252BytesPassedThroughAsC1Controls() {
        assertEquals("’", GlyphCollector.clean("\u0092"));
        assertEquals("“A”", GlyphCollector.clean("\u0093A\u0094"));
        assertEquals("–", GlyphCollector.clean("\u0096"));
    }

    @Test
    void dropsUndefinedAndControlCharacters() {
        assertNull(GlyphCollector.clean("\u0081"));
        assertEquals("a b", GlyphCollector.clean("a\tb"));
        assertNull(GlyphCollector.clean(""));
    }

    @Test
    void decomposesLigatures() {
        assertEquals("fi", GlyphCollector.clean("ﬁ"));
    }
}

package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.topdf.font.FontLibrary;

class GlyphSourceTest {

    @Test
    void fallbackGlyphsFromOneFontOpenThatFontOnce() throws Exception {
        FontLibrary library = PdfToPdfA.Options.defaults().fontLibrary();
        assumeTrue(library.fallback(0x4E00, false, false) != null, "no CJK fallback font installed");
        try (GlyphSource source = GlyphSource.open(library, BaseFontName.parse("MSMincho", 4, 400))) {
            int drawn = 0;
            for (int cp = 0x4E00; cp < 0x4E00 + 300; cp++) {
                if (source.fallback(new String(Character.toChars(cp)), 1000) != null) {
                    drawn++;
                }
            }
            assertTrue(drawn > 0);
            assertTrue(source.fontsOpen() <= 4, "fonts open: " + source.fontsOpen());
        }
    }
}

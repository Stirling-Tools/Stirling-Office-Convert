package stirling.software.officeconvert.topdf.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

class PdfFontsLookupTest {

    @TempDir Path dir;

    @ParameterizedTest
    @ValueSource(ints = {1, 128})
    void laterRegistrationsAndCollidingGlyphsKeepTheirTextAssociations(int start) throws Exception {
        FontLibrary library = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("no-fonts"))));
        try (PDDocument document = new PDDocument(); PdfFonts fonts = new PdfFonts(document, library)) {
            PdfFonts.Embedded embedded = fonts.font(library.find("Arial", false, false));
            FontProgram program = embedded.face().program();
            int first = start;
            while (first + 256 < program.glyphCount()
                    && (program.codePointOf(first) <= 0 || program.codePointOf(first + 256) <= 0)) {
                first++;
            }
            assertTrue(first + 256 < program.glyphCount());
            int second = first + 256;
            int canonical = program.codePointOf(first);
            assertEquals(first, fonts.encoded(embedded, first, canonical));
            fonts.used(embedded, new int[] {first, second}, new int[] {'X', 'Y'}, 2);
            assertEquals(first, fonts.encoded(embedded, first, 'X'));
            assertEquals(second, fonts.encoded(embedded, second, 'Y'));
            int alias = fonts.encoded(embedded, first, canonical);
            assertNotEquals(first, alias);
            assertEquals(alias, fonts.encoded(embedded, first, canonical));
            assertEquals(first, fonts.encoded(embedded, first, 'X'));
        }
    }
}

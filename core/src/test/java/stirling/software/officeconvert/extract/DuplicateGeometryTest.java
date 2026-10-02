package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class DuplicateGeometryTest {
    @Test
    void oversizedGlyphCannotExpandTheSpatialSearchWithoutLimit() throws IOException {
        try (PDDocument document = sample(1_000_000)) {
            IOException error = assertThrows(IOException.class,
                    () -> new PageReader(document).read(0, 0, false, page -> {}));
            assertTrue(error.getMessage().contains("duplicate-detection work limit"));
        }
    }

    @Test
    void normalOverprintedTextStillDeduplicates() throws IOException {
        try (PDDocument document = sample(12)) {
            var pages = new ArrayList<PageData>();
            new PageReader(document).read(0, 0, false, pages::add);
            assertEquals(1, pages.size());
            assertEquals(1, pages.getFirst().glyphs().size());
            assertEquals("A", pages.getFirst().glyphs().getFirst().text);
        }
    }

    private static PDDocument sample(float size) throws IOException {
        PDDocument document = new PDDocument();
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            for (int i = 0; i < 2; i++) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), size);
                content.newLineAtOffset(72, 700);
                content.showText("A");
                content.endText();
            }
        }
        return document;
    }
}

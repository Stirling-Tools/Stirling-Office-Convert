package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.topdf.font.FontLibrary;

class LazyFontLibraryTest {

    private static int libraryLoads(PDFont font, PDDocument d) throws Exception {
        PDPage p = Samples.page(d);
        try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
            Samples.text(cs, font, 12, 72, 700, "Fonts");
        }
        ContentGraph graph = ContentGraph.of(d);
        FontUsage usage = new FontUsage();
        ContentFixer.run(graph, PdfALevel.A2B, new Report(), usage, new DeviceColours());
        AtomicInteger loads = new AtomicInteger();
        FontFixer.run(d, usage, PdfALevel.A2B, () -> {
            loads.incrementAndGet();
            return FontLibrary.withSystem(java.util.List.of());
        }, new Report());
        return loads.get();
    }

    @Test
    void theFontLibraryLoadsOnlyWhenAFontNeedsAStandIn() throws Exception {
        try (PDDocument d = new PDDocument();
                InputStream f = PDDocument.class.getResourceAsStream(
                        "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            assertEquals(0, libraryLoads(PDType0Font.load(d, f, false), d));
        }
        try (PDDocument d = new PDDocument()) {
            assertTrue(libraryLoads(Samples.std(Standard14Fonts.FontName.HELVETICA), d) == 1);
        }
    }
}

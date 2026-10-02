package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HiddenLayersTest {

    @TempDir
    Path dir;

    @Test
    void hiddenQuoteUsesItsNewWordAndCharacterSpacing() throws Exception {
        RawPdf pdf = RawPdf.page("/Font<</F<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>>>/Properties<</Hidden 5 0 R>>",
                "BT /F 24 Tf 72 700 Td /OC /Hidden BDC 10 3 (A B) \" EMC (Visible) Tj ET");
        pdf.add("<</Type/OCG/Name(Hidden)>>");
        pdf.set(1, "<</Type/Catalog/Pages 2 0 R/OCProperties<</OCGs[5 0 R]/D<</OFF[5 0 R]>>>>>>");
        Path output = dir.resolve("quote.pdf");
        PdfToPdfA.convert(Hostile.write(dir, "quote", pdf), output,
                PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        VeraPdf.assertCompliant(output, PdfALevel.A1B);
        float width = new PDType1Font(Standard14Fonts.FontName.HELVETICA).getStringWidth("A B") / 1000 * 24;
        assertEquals(72 + width + 3 * 3 + 10, Converted.glyphs(output).getFirst()[0], 0.1f);
        assertEquals("Visible", Converted.text(output).replace(" ", "").strip());
    }

    @Test
    void removingAHiddenLayerKeepsWhereTheVisibleContentIsDrawnAndInWhatColour() throws Exception {
        Path in = Hostile.write(dir, "hidden", Hostile.hiddenLayer());
        Path out = dir.resolve("hidden-1b.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
        String text = Converted.text(out);
        assertFalse(text.contains("HIDDEN"), text);
        assertTrue(text.contains("Visible"), text);
        float skipped = new PDType1Font(Standard14Fonts.FontName.HELVETICA).getStringWidth("HIDDEN WORDS ") / 1000 * 24;
        List<float[]> glyphs = Converted.glyphs(out);
        assertEquals(72 + skipped, glyphs.get(0)[0], 1.0f);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            BufferedImage img = new PDFRenderer(d).renderImage(0);
            assertEquals(0xFF0000FF, img.getRGB(322, 842 - 550));
            assertEquals(0xFFFFFFFF, img.getRGB(100, 842 - 550));
            assertEquals(0xFFFFFFFF, img.getRGB(235, 842 - 35));
        }
    }
}

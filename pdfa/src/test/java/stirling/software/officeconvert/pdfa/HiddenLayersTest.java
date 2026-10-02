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

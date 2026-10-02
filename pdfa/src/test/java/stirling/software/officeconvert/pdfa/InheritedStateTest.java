package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InheritedStateTest {

    @TempDir
    Path dir;

    @Test
    void aFormDrawingInItsCallersDeviceNSpaceGetsTheConvertedColour() throws Exception {
        Path in = Hostile.write(dir, "devicen", Hostile.deviceNInForm());
        Path out = dir.resolve("devicen-1b.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            BufferedImage img = new PDFRenderer(d).renderImage(0);
            assertGrey(img.getRGB(150, 842 - 550), 51);
            assertGrey(img.getRGB(350, 842 - 550), 204);
        }
    }

    private static void assertGrey(int rgb, int level) {
        for (int shift : new int[] {16, 8, 0}) {
            int v = (rgb >> shift) & 0xFF;
            assertTrue(Math.abs(v - level) <= 3, Integer.toHexString(rgb) + " is not grey " + level);
        }
    }

    @Test
    void textInAFormThatInheritsItsCallersFontKeepsItsGlyphs() throws Exception {
        Path in = Hostile.write(dir, "inherit", Hostile.inheritedFont());
        for (PdfALevel level : new PdfALevel[] {PdfALevel.A1B, PdfALevel.A2B}) {
            Path out = dir.resolve("inherit-" + level + ".pdf");
            PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
            VeraPdf.assertCompliant(out, level);
            String text = Converted.text(out);
            assertTrue(text.contains("XYZQWK") && text.contains("AB"), text);
        }
    }
}

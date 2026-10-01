package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransparencyTest {

    @TempDir
    Path dir;

    @Test
    void partOneDrawsOnlyTheTransparentPageAsAPictureAndKeepsItsText() throws Exception {
        PdfToPdfA.Result r = Converted.convert(dir, "s04_transparency", PdfALevel.A1B);
        Path in = dir.resolve("s04_transparency.pdf");
        Path out = Converted.out(dir, "s04_transparency", PdfALevel.A1B);
        assertEquals(List.of(1), r.flattenedPages());
        assertEquals(Converted.text(in), Converted.text(out));
        assertTrue(similarity(in, out, 0) > 0.98);
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
    }

    @Test
    void partTwoKeepsTransparencyAsItIs() throws Exception {
        PdfToPdfA.Result r = Converted.convert(dir, "s04_transparency", PdfALevel.A2B);
        assertTrue(r.flattenedPages().isEmpty());
    }

    @Test
    void aTranslucentHighlightIsDrawnIntoThePageForPartOne() throws Exception {
        Converted.convert(dir, "s09_annotations_no_appearance", PdfALevel.A1B);
        Path out = Converted.out(dir, "s09_annotations_no_appearance", PdfALevel.A1B);
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            BufferedImage img = new PDFRenderer(d).renderImage(0, 1);
            int rgb = img.getRGB(100, (int) (d.getPage(0).getMediaBox().getHeight() - 597));
            assertTrue((rgb & 0xFF) < 200, "the highlight should still tint the line yellow");
        }
    }

    static double similarity(Path a, Path b, int page) throws Exception {
        try (PDDocument x = Loader.loadPDF(a.toFile()); PDDocument y = Loader.loadPDF(b.toFile())) {
            BufferedImage i = new PDFRenderer(x).renderImage(page, 0.5f);
            BufferedImage j = new PDFRenderer(y).renderImage(page, 0.5f);
            int same = 0;
            for (int yy = 0; yy < i.getHeight(); yy++) {
                for (int xx = 0; xx < i.getWidth(); xx++) {
                    int p = i.getRGB(xx, yy);
                    int q = j.getRGB(xx, yy);
                    int d = Math.max(Math.abs((p >> 16 & 255) - (q >> 16 & 255)),
                            Math.max(Math.abs((p >> 8 & 255) - (q >> 8 & 255)), Math.abs((p & 255) - (q & 255))));
                    if (d <= 24) {
                        same++;
                    }
                }
            }
            return same / (double) (i.getWidth() * i.getHeight());
        }
    }
}

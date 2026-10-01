package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
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

    @Test
    void aPictureIsNotClippedByTheLastTransparentObjectsClip() throws Exception {
        Path in = dir.resolve("clipped.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage p = new PDPage();
            d.addPage(p);
            PDExtendedGraphicsState half = new PDExtendedGraphicsState();
            half.setNonStrokingAlphaConstant(0.5f);
            PDFont f = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(d, p)) {
                cs.saveGraphicsState();
                cs.setGraphicsStateParameters(half);
                cs.setNonStrokingColor(Color.BLUE);
                cs.addRect(50, 300, 400, 300);
                cs.fill();
                cs.saveGraphicsState();
                cs.addRect(400, 550, 100, 100);
                cs.clip();
                cs.setNonStrokingColor(Color.RED);
                cs.addRect(400, 550, 100, 100);
                cs.fill();
                cs.restoreGraphicsState();
                cs.restoreGraphicsState();
                cs.beginText();
                cs.setFont(f, 14);
                cs.newLineAtOffset(60, 320);
                cs.showText("Drawn once, over the picture");
                cs.endText();
            }
            d.save(in.toFile());
        }
        Path out = dir.resolve("clipped-1b.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        assertTrue(similarity(in, out, 0) > 0.98, "similarity " + similarity(in, out, 0));
        assertTrue(Converted.text(out).contains("Drawn once, over the picture"));
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
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

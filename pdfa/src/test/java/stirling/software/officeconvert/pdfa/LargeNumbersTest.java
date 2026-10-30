package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LargeNumbersTest {

    @TempDir
    Path dir;

    @Test
    void partOneKeepsShapesDrawnInFineUnitsInPlace() throws Exception {
        Path in = dir.resolve("in.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(400, 400));
            d.addPage(page);
            PDStream s = new PDStream(d);
            try (OutputStream o = s.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(("q 0.002 0 0 0.002 0 0 cm 0 0 1 rg 50000.0 50000.0 m 150000.0 50000.0 l "
                        + "150000.0 150000.0 l 50000.0 150000.0 l h f "
                        + "1 0 0 rg 50000.25 160000.75 100000.5 25000.0 re f Q")
                        .getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(s);
            d.save(in.toFile());
        }
        Path out = dir.resolve("out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            BufferedImage image = new PDFRenderer(d).renderImage(0);
            assertEquals(0xFF0000FF, image.getRGB(200, 400 - 200));
            assertEquals(0xFFFF0000, image.getRGB(200, 400 - 345));
            assertEquals(0xFFFFFFFF, image.getRGB(390, 10));
        }
    }
}

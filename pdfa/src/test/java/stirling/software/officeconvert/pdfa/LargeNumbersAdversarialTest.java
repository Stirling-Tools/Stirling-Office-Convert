package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LargeNumbersAdversarialTest {

    @TempDir
    Path dir;

    private Path convert(String content, PdfALevel level) throws Exception {
        Path in = dir.resolve("in.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(400, 400));
            d.addPage(page);
            PDStream s = new PDStream(d);
            try (OutputStream o = s.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(content.getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(s);
            d.save(in.toFile());
        }
        Path out = dir.resolve("out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        return out;
    }

    private static BufferedImage render(Path pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            return new PDFRenderer(d).renderImage(0);
        }
    }

    @Test
    void negativeHugeCoordinatesUnderAFineScaleStayInPlace() throws Exception {
        Path out = convert("q 0.002 0 0 0.002 200 200 cm 0 0 1 rg -75000.5 -75000.5 m -25000.25 -75000.5 l "
                + "-25000.25 -25000.25 l -75000.5 -25000.25 l h f Q", PdfALevel.A1B);
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
        BufferedImage image = render(out);
        assertEquals(0xFF0000FF, image.getRGB(100, 400 - 100));
        assertEquals(0xFFFFFFFF, image.getRGB(300, 400 - 300));
    }

    @Test
    void coordinatesInTheMillionsUnderAMicroScaleStayInPlace() throws Exception {
        Path out = convert("q 0.00001 0 0 0.00001 0 0 cm 0 0 1 rg 10000000.5 10000000.5 m 30000000.5 10000000.5 l "
                + "30000000.5 30000000.5 l 10000000.5 30000000.5 l h f Q", PdfALevel.A1B);
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
        BufferedImage image = render(out);
        assertEquals(0xFF0000FF, image.getRGB(200, 400 - 200));
        assertEquals(0xFFFFFFFF, image.getRGB(350, 400 - 350));
    }

    @Test
    void coordinatesBeyondTheIntegerRangeStillMeetPartOne() throws Exception {
        Path out = convert("q 0.0000001 0 0 0.0000001 0 0 cm 0 0 1 rg 1000000000.5 1000000000.5 m "
                + "3000000000.5 1000000000.5 l 3000000000.5 3000000000.5 l -3000000000.5 3000000000.5 l h f Q",
                PdfALevel.A1B);
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
    }

    @Test
    void hugeRealsInsideArrayOperandsMeetPartOne() throws Exception {
        Path out = convert("q 0 0 1 RG 2 w [50000.5 70000.25] 0 d 10 10 m 390 390 l S Q", PdfALevel.A1B);
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
    }

    @Test
    void hugeRealsInsideDictionaryOperandsMeetPartOne() throws Exception {
        Path out = convert("/Artifact <</BBox [0 0 40000.5 10] /Attached [/Top]>> BDC 0 0 1 rg 10 10 50 50 re f EMC",
                PdfALevel.A1B);
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
    }

    @Test
    void hugeRealsInDictionariesAndArraysBecomeIntegersAtPartOne() throws Exception {
        RawPdf raw = RawPdf.page("", "0 0 1 rg 10 10 50 50 re f");
        raw.set(3, "<</Type/Page/Parent 2 0 R/MediaBox[0 0 400 400]/Resources<<>>/Contents 4 0 R"
                + "/Big 50000.5/Bigs[-123456.7 -2147483648.0 2147483000.0 5000000000.5 -5000000000.5]>>");
        Path in = dir.resolve("dict.pdf");
        Files.write(in, raw.bytes());
        Path out = dir.resolve("dict-out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            var p = d.getPage(0).getCOSObject();
            COSBase big = p.getDictionaryObject(COSName.getPDFName("Big"));
            assertInstanceOf(COSInteger.class, big);
            assertEquals(50001L, ((COSInteger) big).longValue());
            COSArray a = (COSArray) p.getDictionaryObject(COSName.getPDFName("Bigs"));
            assertEquals(-123457L, ((COSInteger) a.get(0)).longValue());
            assertEquals(2147483008L, ((COSInteger) a.get(2)).longValue());
            assertEquals(Integer.MAX_VALUE, ((COSInteger) a.get(3)).longValue());
            assertEquals(Integer.MIN_VALUE, ((COSInteger) a.get(4)).longValue());
            assertEquals(Integer.MIN_VALUE, ((COSNumber) a.get(1)).floatValue());
        }
    }

    @Test
    void partTwoKeepsHugeRealsAsReals() throws Exception {
        Path out = convert("q 0.002 0 0 0.002 0 0 cm 0 0 1 rg 50000.5 50000.5 100000.5 100000.5 re f Q",
                PdfALevel.A2B);
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
        BufferedImage image = render(out);
        assertEquals(0xFF0000FF, image.getRGB(200, 400 - 200));
    }
}

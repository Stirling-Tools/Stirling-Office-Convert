package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlideRobustnessTest {

    @TempDir Path dir;

    private Path pdf(PDRectangle size, String content) throws IOException {
        Path out = dir.resolve("in.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(size);
            PDResources res = new PDResources();
            res.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            COSDictionary broken = new COSDictionary();
            broken.setItem(COSName.TYPE, COSName.FONT);
            broken.setItem(COSName.SUBTYPE, COSName.TYPE0);
            broken.setName(COSName.BASE_FONT, "Broken");
            broken.setItem(COSName.ENCODING, COSName.IDENTITY_H);
            res.getCOSObject().getCOSDictionary(COSName.FONT).setItem(COSName.getPDFName("F2"), broken);
            page.setResources(res);
            PDStream stream = new PDStream(doc);
            try (OutputStream os = stream.createOutputStream()) {
                os.write(content.getBytes(StandardCharsets.ISO_8859_1));
            }
            page.setContents(stream);
            doc.addPage(page);
            doc.save(out.toFile());
        }
        return out;
    }

    private Map<String, byte[]> convert(Path pdf) throws IOException {
        Path pptx = dir.resolve("out.pptx");
        PdfToPptx.convert(pdf, pptx, PdfToPptx.Options.defaults());
        return SlideFixtures.parts(Files.readAllBytes(pptx));
    }

    private static long[] slideSize(Map<String, byte[]> parts) {
        Matcher m = Pattern.compile("<p:sldSz cx=\"(\\d+)\" cy=\"(\\d+)\"").matcher(SlideFixtures.text(parts, "ppt/presentation.xml"));
        assertTrue(m.find());
        return new long[] {Long.parseLong(m.group(1)), Long.parseLong(m.group(2))};
    }

    @Test
    void brokenFontLosesOnlyItsOwnText() throws IOException {
        String slide = SlideFixtures.text(convert(pdf(PDRectangle.A4, "BT /F1 12 Tf 72 700 Td (Hello) Tj ET\n"
                + "BT /F2 12 Tf 72 680 Td <0001> Tj ET\nBT /F1 12 Tf 72 660 Td (World) Tj ET\n")), "ppt/slides/slide1.xml");
        assertTrue(slide.contains("Hello") && slide.contains("World"));
    }

    @Test
    void truncatedStreamKeepsWhatCameBefore() throws IOException {
        String slide = SlideFixtures.text(convert(pdf(PDRectangle.A4,
                "BT /F1 12 Tf 72 700 Td (Before the break) Tj ET\nBI /W 1 /H 1 Infinity")), "ppt/slides/slide1.xml");
        assertTrue(slide.contains("Before the break"));
    }

    @Test
    void slidesStayWithinPowerPointsSizeLimits() throws IOException {
        long[] poster = slideSize(convert(pdf(PDRectangle.A0, "BT /F1 40 Tf 100 3000 Td (Poster title) Tj ET")));
        assertTrue(poster[0] <= 51206400L && poster[1] <= 51206400L, "at most 56 inches");
        long[] stamp = slideSize(convert(pdf(new PDRectangle(30, 20), "BT /F1 4 Tf 2 8 Td (Tiny) Tj ET")));
        assertEquals(914400L, stamp[0], "at least one inch");
        assertEquals(914400L, stamp[1], "at least one inch");
    }

    @Test
    void anInterruptedThreadStops() throws IOException {
        Path in = pdf(PDRectangle.A4, "BT /F1 12 Tf 72 700 Td (Hello) Tj ET");
        try (PDDocument doc = Loader.loadPDF(in.toFile())) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class,
                        () -> PdfToPptx.convert(doc, new ByteArrayOutputStream(), PdfToPptx.Options.defaults()));
            } finally {
                Thread.interrupted();
            }
        }
    }
}

package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import stirling.software.officeconvert.OfficeConvert;
import stirling.software.officeconvert.jpx.JpxImageIO;

class JpxBudgetTest {

    static byte[] headerOnly(int width, int height) throws IOException {
        byte[] sample;
        try (InputStream in = JpxBudgetTest.class.getResourceAsStream("/jpx/gray-lossless.j2k")) {
            sample = in.readAllBytes();
        }
        int sot = 0;
        while (!((sample[sot] & 0xFF) == 0xFF && (sample[sot + 1] & 0xFF) == 0x90)) {
            sot++;
        }
        byte[] head = Arrays.copyOf(sample, sot + 16);
        ByteBuffer.wrap(head).putInt(8, width).putInt(12, height).putInt(24, width).putInt(28, height)
                .putInt(sot + 6, 0).putShort(sot + 12, (short) 0xFF93).putShort(sot + 14, (short) 0xFFD9);
        return head;
    }

    static PDImageXObject image(PDDocument doc, byte[] jpx, int width, int height) throws IOException {
        COSStream s = doc.getDocument().createCOSStream();
        try (OutputStream o = s.createRawOutputStream()) {
            o.write(jpx);
        }
        s.setItem(COSName.TYPE, COSName.XOBJECT);
        s.setItem(COSName.SUBTYPE, COSName.IMAGE);
        s.setItem(COSName.FILTER, COSName.JPX_DECODE);
        s.setInt(COSName.WIDTH, width);
        s.setInt(COSName.HEIGHT, height);
        return new PDImageXObject(new PDStream(s), null);
    }

    @Test
    void aHeaderOnlyHugeCodestreamIsRefusedWithoutDecoding() throws IOException {
        JpxImageIO.install();
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = image(doc, headerOnly(11_000, 11_000), 11_000, 11_000);
            com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
            long before = threads.getCurrentThreadAllocatedBytes();
            boolean affordable = ImageBudget.affordable(image);
            long allocated = threads.getCurrentThreadAllocatedBytes() - before;
            assertFalse(affordable);
            assertTrue(allocated < 16L << 20, "allocated " + (allocated >> 20) + " MB");
        }
    }

    @Test
    void anOrdinaryCodestreamStaysAffordable() throws IOException {
        JpxImageIO.install();
        try (PDDocument doc = new PDDocument()) {
            assertTrue(ImageBudget.affordable(image(doc, headerOnly(2_000, 1_500), 2_000, 1_500)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"docx", "pptx", "xlsx"})
    void aPdfWithAHeaderOnlyHugeCodestreamConvertsWithoutDecodingIt(String format, @TempDir Path dir)
            throws IOException {
        Path pdf = dir.resolve("huge.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDImageXObject image = image(doc, headerOnly(11_000, 11_000), 11_000, 11_000);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(image, 72, 72, 400, 400);
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("Caption");
                content.endText();
            }
            doc.save(pdf.toFile());
        }
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getTotalThreadAllocatedBytes();
        OfficeConvert.convert(pdf, dir.resolve("huge." + format), OfficeConvert.Settings.defaults());
        long allocated = threads.getTotalThreadAllocatedBytes() - before;
        assertTrue(allocated < 200L << 20, "allocated " + (allocated >> 20) + " MB");
    }
}

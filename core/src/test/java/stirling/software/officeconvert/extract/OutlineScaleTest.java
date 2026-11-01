package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;

class OutlineScaleTest {

    private static final int LIBERATION_CAP_HEIGHT = 1409;

    @Test
    void capitalsDrawnSmallerThanTheFontsOwnCapHeightShrinkTheText() throws IOException {
        float size = size(font(Math.round(LIBERATION_CAP_HEIGHT * 1.25f)));
        assertEquals(12f * 0.8f, size, 0.05f);
    }

    @Test
    void capitalsAsTallAsTheFontsOwnCapHeightKeepTheFontSize() throws IOException {
        assertEquals(12f, size(font(LIBERATION_CAP_HEIGHT)), 0.001f);
    }

    @Test
    void aSmallDifferenceFromTheCapHeightKeepsTheFontSize() throws IOException {
        assertEquals(12f, size(font(Math.round(LIBERATION_CAP_HEIGHT * 1.05f))), 0.001f);
    }

    private static float size(byte[] font) throws IOException {
        byte[] pdf;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            PDType0Font type0 = PDType0Font.load(document, new ByteArrayInputStream(font), false);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(type0, 12);
                content.newLineAtOffset(72, 600);
                content.showText("THE HELMET WINDER");
                content.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            pdf = out.toByteArray();
        }
        try (PDDocument document = Loader.loadPDF(pdf)) {
            List<PageData> pages = new ArrayList<>();
            new PageReader(document).read(0, 0, false, pages::add);
            List<Glyph> glyphs = pages.getFirst().glyphs();
            assertTrue(glyphs.size() > 10);
            return glyphs.getFirst().size;
        }
    }

    private static byte[] font(int capHeight) throws IOException {
        byte[] bytes;
        try (InputStream in = PDDocument.class.getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            bytes = in.readAllBytes();
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int tables = buffer.getShort(4) & 0xFFFF;
        for (int i = 0; i < tables; i++) {
            int record = 12 + 16 * i;
            String tag = new String(bytes, record, 4, StandardCharsets.US_ASCII);
            if (tag.equals("OS/2")) {
                int offset = buffer.getInt(record + 8);
                assertTrue((buffer.getShort(offset) & 0xFFFF) >= 2);
                buffer.putShort(offset + 88, (short) capHeight);
            }
        }
        return bytes;
    }
}

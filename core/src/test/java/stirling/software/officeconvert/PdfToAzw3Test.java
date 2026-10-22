package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfToAzw3Test {

    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private static final String[] CHAPTERS = {"Getting Started", "Daily Use", "Troubleshooting"};

    @TempDir static Path dir;
    static byte[] book;
    static List<byte[]> records;

    @BeforeAll
    static void convert() throws IOException {
        Path pdf = dir.resolve("manual.pdf");
        try (PDDocument doc = new PDDocument()) {
            BufferedImage img = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(Color.ORANGE);
            g.fillRect(0, 0, 120, 80);
            g.dispose();
            var picture = LosslessFactory.createFromImage(doc, img);
            for (int c = 0; c < CHAPTERS.length; c++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    text(cs, BOLD, 24, 72, 700, CHAPTERS[c]);
                    float y = 660;
                    for (int line = 0; line < 12; line++, y -= 16) {
                        text(cs, REGULAR, 12, 72, y, "Chapter " + (c + 1) + " body line " + (line + 1)
                                + " explains the steps & checks <carefully> – in order.");
                    }
                    if (c == 1) {
                        cs.drawImage(picture, 72, 300, 180, 120);
                    }
                }
            }
            doc.getDocumentInformation().setTitle("Field Manual");
            doc.getDocumentInformation().setAuthor("Ops Team");
            doc.save(pdf.toFile());
        }
        Path azw3 = dir.resolve("manual.azw3");
        OfficeConvert.convert(pdf, azw3);
        book = Files.readAllBytes(azw3);
        records = new ArrayList<>();
        int n = u16(book, 76);
        for (int i = 0; i < n; i++) {
            int from = (int) u32(book, 78 + 8 * i);
            int to = i + 1 < n ? (int) u32(book, 78 + 8 * (i + 1)) : book.length;
            byte[] r = new byte[to - from];
            System.arraycopy(book, from, r, 0, r.length);
            records.add(r);
        }
    }

    private static void text(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String s)
            throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    private static int u16(byte[] b, int i) {
        return (b[i] & 0xFF) << 8 | (b[i + 1] & 0xFF);
    }

    private static long u32(byte[] b, int i) {
        return (long) (b[i] & 0xFF) << 24 | (b[i + 1] & 0xFF) << 16 | (b[i + 2] & 0xFF) << 8 | (b[i + 3] & 0xFF);
    }

    private static Map<Integer, String> exth() {
        byte[] r0 = records.get(0);
        int at = 16 + (int) u32(r0, 20);
        assertEquals("EXTH", new String(r0, at, 4, StandardCharsets.US_ASCII));
        Map<Integer, String> out = new HashMap<>();
        long count = u32(r0, at + 8);
        int p = at + 12;
        for (int i = 0; i < count; i++) {
            int type = (int) u32(r0, p);
            int size = (int) u32(r0, p + 4);
            out.put(type, new String(r0, p + 8, size - 8, StandardCharsets.UTF_8));
            p += size;
        }
        return out;
    }

    private static byte[] palmDoc(byte[] in, int end) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int o = 0;
        int i = 0;
        while (i < end) {
            int c = in[i++] & 0xFF;
            if (c >= 1 && c <= 8) {
                System.arraycopy(in, i, buf, o, c);
                i += c;
                o += c;
            } else if (c < 0x80) {
                buf[o++] = (byte) c;
            } else if (c < 0xC0) {
                int m = c << 8 | (in[i++] & 0xFF);
                int distance = (m >> 3) & 0x7FF;
                for (int k = 0; k < (m & 7) + 3; k++, o++) {
                    buf[o] = buf[o - distance];
                }
            } else {
                buf[o++] = ' ';
                buf[o++] = (byte) (c ^ 0x80);
            }
        }
        out.write(buf, 0, o);
        return out.toByteArray();
    }

    private static String text() {
        byte[] r0 = records.get(0);
        int count = u16(r0, 8);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (int i = 1; i <= count; i++) {
            byte[] r = records.get(i);
            int tbs = 0;
            int shift = 0;
            for (int p = r.length - 1; p >= 0; p--) {
                tbs |= (r[p] & 0x7F) << shift;
                shift += 7;
                if ((r[p] & 0x80) != 0) {
                    break;
                }
            }
            int end = r.length - tbs;
            end -= (r[end - 1] & 3) + 1;
            all.writeBytes(palmDoc(r, end));
        }
        assertEquals(u32(r0, 4), all.size());
        return all.toString(StandardCharsets.UTF_8);
    }

    @Test
    void headersDescribeAKf8Book() {
        assertEquals("BOOKMOBI", new String(book, 60, 8, StandardCharsets.US_ASCII));
        byte[] r0 = records.get(0);
        assertEquals(2, u16(r0, 0));
        assertEquals(0, u16(r0, 12));
        assertEquals("MOBI", new String(r0, 16, 4, StandardCharsets.US_ASCII));
        assertEquals(8, u32(r0, 36));
        assertEquals(65001, u32(r0, 28));
        assertEquals(3, u32(r0, 240));
        Map<Integer, String> exth = exth();
        assertEquals("Field Manual", exth.get(503));
        assertEquals("Ops Team", exth.get(100));
        assertEquals("und", exth.get(524));
        assertEquals("FDST", new String(records.get((int) u32(r0, 192)), 0, 4, StandardCharsets.US_ASCII));
        assertEquals("FLIS", new String(records.get((int) u32(r0, 208)), 0, 4, StandardCharsets.US_ASCII));
        assertEquals("FCIS", new String(records.get((int) u32(r0, 200)), 0, 4, StandardCharsets.US_ASCII));
        for (int at : new int[] {0xF4, 0xF8, 0xFC}) {
            assertEquals("INDX", new String(records.get((int) u32(r0, at)), 0, 4, StandardCharsets.US_ASCII));
        }
        byte[] eof = records.get(records.size() - 1);
        assertEquals(4, eof.length);
        assertEquals(0xE9, eof[0] & 0xFF);
    }

    @Test
    void textHoldsEveryChapterWithKindleReferences() {
        String text = text();
        int last = -1;
        for (int c = 0; c < CHAPTERS.length; c++) {
            int at = text.indexOf(CHAPTERS[c]);
            assertTrue(at > last, CHAPTERS[c]);
            last = at;
            assertTrue(text.contains("Chapter " + (c + 1) + " body line 12"));
        }
        assertTrue(text.contains("steps &amp; checks &lt;carefully&gt; – in order"));
        assertTrue(text.contains("kindle:flow:0001?mime=text/css"));
        assertTrue(text.contains("kindle:embed:0001?mime=image/"));
        assertTrue(text.contains(" aid=\""));
        assertTrue(!text.contains("../images/") && !text.contains("epub:type"));
    }

    @Test
    void pictureIsAResourceRecord() {
        byte[] r0 = records.get(0);
        byte[] first = records.get((int) u32(r0, 108));
        assertTrue((first[0] & 0xFF) == 0x89 && first[1] == 'P' || (first[0] & 0xFF) == 0xFF, "a picture");
    }
}

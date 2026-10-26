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
import java.util.Arrays;
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

    private static final String SECTION = "Weekly Checks";

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
                        text(cs, BOLD, 16, 72, 260, SECTION);
                        text(cs, REGULAR, 12, 72, 236, "The section body follows its heading.");
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
        Map<Integer, String> out = new HashMap<>();
        exthBytes().forEach((type, value) -> out.put(type, new String(value, StandardCharsets.UTF_8)));
        return out;
    }

    private static Map<Integer, byte[]> exthBytes() {
        byte[] r0 = records.get(0);
        int at = 16 + (int) u32(r0, 20);
        assertEquals("EXTH", new String(r0, at, 4, StandardCharsets.US_ASCII));
        Map<Integer, byte[]> out = new HashMap<>();
        long count = u32(r0, at + 8);
        int p = at + 12;
        for (int i = 0; i < count; i++) {
            int type = (int) u32(r0, p);
            int size = (int) u32(r0, p + 4);
            byte[] value = new byte[size - 8];
            System.arraycopy(r0, p + 8, value, 0, value.length);
            out.put(type, value);
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
        for (int at : new int[] {0xF4, 0xF8, 0xFC, 0x104}) {
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

    private record NcxEntry(String title, long offset, long depth, long parent, long firstChild) {}

    private static List<NcxEntry> ncx() {
        byte[] r0 = records.get(0);
        int head = (int) u32(r0, 244);
        byte[] header = records.get(head);
        int cncx = (int) u32(header, 52);
        int dataRecords = (int) u32(header, 24);
        byte[] strings = records.get(head + 1 + dataRecords);
        List<NcxEntry> out = new ArrayList<>();
        for (int d = 0; d < dataRecords; d++) {
            byte[] r = records.get(head + 1 + d);
            int idxt = (int) u32(r, 20);
            int count = (int) u32(r, 24);
            for (int e = 0; e < count; e++) {
                int p = u16(r, idxt + 4 + 2 * e);
                p += 1 + (r[p] & 0xFF);
                int control = r[p] & 0xFF;
                p += 2;
                long[] values = new long[9];
                int[] tags = {1, 2, 3, 4, 21, 22, 23, 6};
                int[] counts = {1, 1, 1, 1, 1, 1, 1, 2};
                Arrays.fill(values, -1);
                for (int t = 0; t < tags.length; t++) {
                    if ((control & (1 << t)) == 0) {
                        continue;
                    }
                    for (int k = 0; k < counts[t]; k++) {
                        long v = 0;
                        int b;
                        do {
                            b = r[p++] & 0xFF;
                            v = v << 7 | (b & 0x7F);
                        } while ((b & 0x80) == 0);
                        if (k == 0) {
                            values[t] = v;
                        }
                    }
                }
                int at = (int) values[2];
                int len = strings[at] & 0x7F;
                String title = new String(strings, at + 1, len, StandardCharsets.UTF_8);
                out.add(new NcxEntry(title, values[0], values[3], values[4], values[5]));
            }
        }
        assertTrue(cncx >= 1);
        return out;
    }

    @Test
    void tableOfContentsNestsSectionsUnderTheirChapter() {
        List<NcxEntry> entries = ncx();
        NcxEntry section = entries.stream().filter(e -> e.title().equals(SECTION)).findFirst().orElseThrow();
        assertEquals(1, section.depth());
        NcxEntry chapter = entries.get((int) section.parent());
        assertEquals(CHAPTERS[1], chapter.title());
        assertEquals(0, chapter.depth());
        assertEquals(entries.indexOf(section), chapter.firstChild());
        for (int i = 1; i < entries.size(); i++) {
            NcxEntry a = entries.get(i - 1);
            NcxEntry b = entries.get(i);
            assertTrue(a.depth() < b.depth() || a.depth() == b.depth() && a.offset() <= b.offset());
        }
    }

    @Test
    void startReadingPointsAtTheFirstChapter() {
        byte[] guide = records.get((int) u32(records.get(0), 0x104) + 1);
        int idxt = (int) u32(guide, 20);
        int entry = u16(guide, idxt + 4);
        assertEquals("text", new String(guide, entry + 1, guide[entry], StandardCharsets.US_ASCII));
        long start = u32(exthBytes().get(116), 0);
        NcxEntry first = ncx().stream().filter(e -> e.title().equals(CHAPTERS[0])).findFirst().orElseThrow();
        assertTrue(start > 0 && start <= first.offset(), start + " vs " + first.offset());
    }

    @Test
    void pictureIsAResourceRecord() {
        byte[] r0 = records.get(0);
        byte[] first = records.get((int) u32(r0, 108));
        assertTrue((first[0] & 0xFF) == 0x89 && first[1] == 'P' || (first[0] & 0xFF) == 0xFF, "a picture");
    }
}

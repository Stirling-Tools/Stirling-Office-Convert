package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf.Format;

class EbookToPdfTest {

    @TempDir
    Path dir;

    private static final OfficeToPdf.Options OPTIONS = OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2));

    private static byte[] png(Color c) throws IOException {
        BufferedImage img = new BufferedImage(60, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, 60, 80);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private Path write(String name, byte[] bytes) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    private record Converted(String text, int pages, List<Integer> images, String title, String author) {}

    private Converted convert(Path in) throws IOException {
        Path out = dir.resolve(in.getFileName() + ".pdf");
        OfficeToPdf.convert(in, out, OPTIONS);
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            List<Integer> images = new ArrayList<>();
            for (PDPage page : doc.getPages()) {
                int n = 0;
                for (var name : page.getResources().getXObjectNames()) {
                    if (page.getResources().getXObject(name) instanceof PDImageXObject) {
                        n++;
                    }
                }
                images.add(n);
            }
            return new Converted(new PDFTextStripper().getText(doc), doc.getNumberOfPages(), images,
                    doc.getDocumentInformation().getTitle(), doc.getDocumentInformation().getAuthor());
        }
    }

    private static String mobi6Text() {
        return "<html><head><guide><reference type=\"toc\" title=\"Contents\" filepos=0000000000 /></guide></head>"
                + "<body><h1>Chapter One</h1><p>First chapter opening line with <b>bold</b> words.</p>"
                + "<p><a filepos=FILEPOS>Jump to chapter two</a></p>"
                + "<p><img recindex=\"00002\" hirecindex=\"00003\"></p>"
                + "<p width=\"20pt\">Indented paragraph text.</p>"
                + "<mbp:pagebreak/><h1>Chapter Two</h1><p>Second chapter closing line été.</p>"
                + "</body></html>";
    }

    private static byte[] mobi6(int compression, int encryption) throws IOException {
        String text = mobi6Text().replace("FILEPOS", "##########");
        int target = text.indexOf("<mbp:pagebreak/>") + 16;
        text = text.replace("##########", String.format("%010d", target));
        assertTrue(text.substring(target).startsWith("<h1>Chapter Two"));
        byte[] bytes = utf8(text);
        List<byte[]> chunks = MobiWriter.chunks(bytes);
        MobiWriter w = MobiWriter.create();
        for (byte[] c : chunks) {
            byte[] stored = compression == MobiHeader.PALMDOC ? MobiWriter.palmDoc(c) : c;
            w.add(MobiWriter.withTrailing(stored));
        }
        w.add(new byte[] {0, 0});
        int firstImage = w.add(png(Color.RED));
        w.add(png(Color.BLUE));
        w.add(utf8("FLIS"));
        byte[] exth = MobiWriter.exth(MobiHeader.EXTH_AUTHOR, "A. Writer", MobiHeader.EXTH_TITLE, "The Mobi Book",
                MobiHeader.EXTH_COVER, 0);
        w.records.set(0, MobiWriter.header(compression, bytes.length, chunks.size(), encryption, 6, firstImage, -1, 0,
                3, -1, 0, -1, -1, exth));
        return w.build("BOOKMOBI");
    }

    @Test
    void mobiReadsInOrderWithPicturesLinksAndBreaks() throws IOException {
        Converted c = convert(write("book.mobi", mobi6(MobiHeader.PALMDOC, 0)));
        int one = c.text().indexOf("Chapter One");
        int two = c.text().indexOf("Chapter Two");
        assertTrue(one >= 0 && two > one, c.text());
        assertTrue(c.text().contains("bold words"), c.text());
        assertTrue(c.text().contains("Second chapter closing line été"), c.text());
        assertFalse(c.text().contains("xyz"), c.text());
        assertFalse(c.text().contains("Contents"), c.text());
        assertEquals("The Mobi Book", c.title());
        assertEquals("A. Writer", c.author());
        assertEquals(3, c.pages(), c.text());
        assertEquals(List.of(1, 1, 0), c.images());
    }

    @Test
    void uncompressedMobiReadsTheSame() throws IOException {
        Converted c = convert(write("plain.azw", mobi6(MobiHeader.NONE, 0)));
        assertTrue(c.text().contains("Jump to chapter two"), c.text());
        assertEquals(3, c.pages());
    }

    @Test
    void mobiLinkPointsAtTheTargetPosition() throws IOException {
        Path in = write("links.mobi", mobi6(MobiHeader.PALMDOC, 0));
        Ebook book = MobiBook.read(in);
        String html = book.book().html();
        int anchor = html.indexOf("id=\"epub-c0-filepos");
        assertTrue(anchor >= 0 && anchor < html.indexOf("Chapter Two") && anchor > html.indexOf("Chapter One"), html);
        String id = html.substring(anchor + 4, html.indexOf('"', anchor + 4));
        assertTrue(html.contains("href=\"#" + id + "\""), html);
        assertTrue(html.contains("text-indent:20pt"), html);
    }

    @Test
    void missingPalmDocRecordsOrTextCannotReturnPartialBooks() throws IOException {
        byte[] text = utf8("Only the first text chunk remains.");
        for (int records : List.of(1, 2)) {
            MobiWriter writer = MobiWriter.create();
            byte[] header = new byte[16];
            MobiWriter.put16(header, 0, MobiHeader.NONE);
            MobiWriter.put32(header, 4, text.length + 10);
            MobiWriter.put16(header, 8, records);
            MobiWriter.put16(header, 10, 4096);
            writer.records.set(0, header);
            writer.add(text);
            Path source = write("truncated-" + records + ".pdb", writer.build("TEXtREAd"));
            assertThrows(IOException.class, () -> MobiBook.read(source));
        }
    }

    @Test
    void anEmptyFlushTerminatedZTxtBookIsAccepted() throws IOException {
        Deflater deflater = new Deflater();
        byte[] buffer = new byte[64];
        int length;
        try {
            length = deflater.deflate(buffer, 0, buffer.length, Deflater.FULL_FLUSH);
        } finally {
            deflater.end();
        }
        MobiWriter writer = MobiWriter.create();
        byte[] header = new byte[32];
        MobiWriter.put16(header, 0, 0x012c);
        MobiWriter.put16(header, 2, 1);
        MobiWriter.put16(header, 8, 8192);
        header[18] = 1;
        writer.records.set(0, header);
        writer.add(Arrays.copyOf(buffer, length));
        assertTrue(MobiBook.read(write("empty-flush.pdb", writer.build("zTXTGPlm"))).book().html().contains("<body>"));
    }

    @Test
    void fullFlushZTxtRecordsAreAcceptedAndTruncatedOnesRejected() throws IOException {
        List<byte[]> text = List.of(utf8("The first paragraph of a flush-terminated book.\n"),
                utf8("The second paragraph preserves the complete text.\n"));
        List<byte[]> records = new ArrayList<>();
        Deflater deflater = new Deflater();
        try {
            byte[] buffer = new byte[4096];
            for (byte[] chunk : text) {
                deflater.setInput(chunk);
                int n = deflater.deflate(buffer, 0, buffer.length, Deflater.FULL_FLUSH);
                assertTrue(deflater.needsInput());
                records.add(Arrays.copyOf(buffer, n));
            }
        } finally {
            deflater.end();
        }
        for (int change : List.of(0, 1, 2, 3)) {
            MobiWriter writer = MobiWriter.create();
            byte[] header = new byte[32];
            MobiWriter.put16(header, 0, 0x012c);
            MobiWriter.put16(header, 2, records.size());
            MobiWriter.put32(header, 4, text.stream().mapToInt(a -> a.length).sum() + (change == 1 ? 1 : 0));
            MobiWriter.put16(header, 8, 8192);
            header[18] = 1;
            writer.records.set(0, header);
            writer.add(records.get(0));
            if (change != 3) {
                byte[] last = records.get(1);
                writer.add(change == 2 ? Arrays.copyOf(last, last.length - 1) : last);
            }
            Path input = write("flush-" + change + ".pdb", writer.build("zTXTGPlm"));
            if (change == 0) {
                String html = MobiBook.read(input).book().html();
                assertTrue(html.contains("first paragraph") && html.contains("second paragraph"), html);
            } else {
                assertThrows(IOException.class, () -> MobiBook.read(input));
            }
        }
    }

    @Test
    void truncatedZTxtCannotReturnPartialText() throws IOException {
        Deflater deflater = new Deflater();
        byte[] compressed = new byte[1024];
        int size;
        try {
            deflater.setInput(utf8("A complete paragraph in a truncated compressed book."));
            deflater.finish();
            size = deflater.deflate(compressed);
        } finally {
            deflater.end();
        }
        MobiWriter writer = MobiWriter.create();
        byte[] header = new byte[20];
        header[0] = 1;
        header[1] = 0x44;
        header[3] = 1;
        writer.records.set(0, header);
        writer.add(Arrays.copyOf(compressed, size - 2));
        Path source = write("truncated.pdb", writer.build("zTXTGPlm"));
        assertThrows(IOException.class, () -> MobiBook.read(source));
    }

    @Test
    void zTxtBooksReadAsText() throws IOException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            text.append("Line ").append(i).append(" of the zTXT story with caf\u00e9.\n");
        }
        byte[] raw = text.toString().getBytes(Charset.forName("windows-1252"));
        Deflater d = new Deflater();
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) {
            z.write(buf, 0, d.deflate(buf));
        }
        d.end();
        byte[] all = z.toByteArray();
        MobiWriter w = MobiWriter.create();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int parts = (all.length + 999) / 1000;
        head.writeBytes(new byte[] {1, 0x44, (byte) (parts >> 8), (byte) parts});
        head.writeBytes(new byte[16]);
        w.records.set(0, head.toByteArray());
        for (int i = 0; i < all.length; i += 1000) {
            w.add(Arrays.copyOfRange(all, i, Math.min(all.length, i + 1000)));
        }
        Converted c = convert(write("story.pdb", w.build("zTXTGPlm")));
        assertTrue(c.text().contains("Line 0 of the zTXT story with caf\u00e9.") && c.text().contains("Line 399"), c.text());
    }

    @Test
    void eReaderBooksAreRefusedByName() throws IOException {
        MobiWriter w = MobiWriter.create();
        w.records.set(0, new byte[16]);
        Path in = write("reader.pdb", w.build("PNRdPPrs"));
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("reader.pdf")));
        assertTrue(e.getMessage().contains("eReader"), e.getMessage());
    }

    @Test
    void drmProtectedBookIsRefusedClearly() throws IOException {
        Path in = write("locked.mobi", mobi6(MobiHeader.PALMDOC, 2));
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("locked.pdf")));
        assertTrue(e.getMessage().contains("DRM"), e.getMessage());
    }

    @Test
    void notAMobiBookIsRefused() throws IOException {
        Path in = write("fake.mobi", utf8("<html><body>not a book</body></html>"));
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("fake.pdf")));
        assertTrue(e.getMessage().contains("Mobipocket"), e.getMessage());
    }

    @Test
    void palmDocTextReadsAsParagraphs() throws IOException {
        byte[] text = utf8("First line of the memo\nSecond line of the memo\n");
        MobiWriter w = MobiWriter.create();
        w.add(MobiWriter.palmDoc(text));
        byte[] r0 = new byte[16];
        MobiWriter.put16(r0, 0, MobiHeader.PALMDOC);
        MobiWriter.put32(r0, 4, text.length);
        MobiWriter.put16(r0, 8, 1);
        w.records.set(0, r0);
        Converted c = convert(write("memo.pdb", w.build("TEXtREAd")));
        assertTrue(c.text().indexOf("First line") < c.text().indexOf("Second line"), c.text());
    }

    @Test
    void palmDocDecompressesEveryCodeKind() throws IOException {
        byte[] in = {'a', 'b', 'c', (byte) 0x80, 0x1A, 2, (byte) 0xE9, (byte) 0x80, (byte) 0xE1, 'x', 'y', 'z',
            'z', 'y'};
        byte[] out = MobiText.palmDoc(in, in.length - 2);
        assertEquals("abcabcabé\u0080 axyz", new String(out, StandardCharsets.ISO_8859_1));
        assertThrows(IOException.class, () -> MobiText.palmDoc(new byte[] {(byte) 0x80, 0x18}, 2));
        byte[] encoded = MobiWriter.palmDoc(utf8("the cat sat on the mat, the cat sat on the mat"));
        assertEquals("the cat sat on the mat, the cat sat on the mat",
                new String(MobiText.palmDoc(encoded, encoded.length), StandardCharsets.UTF_8));
    }

    @Test
    void trailingEntriesAreStripped() {
        byte[] r = MobiWriter.withTrailing(utf8("body"));
        assertEquals(5, MobiText.trailing(r, 3));
        assertEquals(4, MobiText.trailing(r, 2));
        assertEquals(0, MobiText.trailing(r, 0));
    }

    private static byte[] huffRecord() {
        byte[] r = new byte[24 + 256 * 4 + 64 * 8];
        r[0] = 'H';
        r[1] = 'U';
        r[2] = 'F';
        r[3] = 'F';
        MobiWriter.put32(r, 4, 0x18);
        MobiWriter.put32(r, 8, 24);
        MobiWriter.put32(r, 12, 24 + 256 * 4);
        for (int i = 0; i < 256; i++) {
            MobiWriter.put32(r, 24 + 4 * i, 255L << 8 | 0x80 | 8);
        }
        return r;
    }

    private static byte[] cdicRecord(boolean selfReferencing) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[] offsets = new int[256];
        int base = 2 * 256;
        for (int r = 0; r < 256; r++) {
            offsets[r] = base + body.size();
            int c = 255 - r;
            if (selfReferencing && c == 'Q') {
                MobiWriter.u16(body, 1);
                body.write('Q');
            } else {
                MobiWriter.u16(body, 0x8000 | 1);
                body.write(c);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(utf8("CDIC"));
        MobiWriter.u32(out, 0x10);
        MobiWriter.u32(out, 256);
        MobiWriter.u32(out, 8);
        for (int o : offsets) {
            MobiWriter.u16(out, o);
        }
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    private static byte[] huffBook(String text, boolean selfReferencing) {
        MobiWriter w = MobiWriter.create();
        byte[] bytes = utf8(text);
        w.add(bytes);
        int huff = w.add(huffRecord());
        w.add(cdicRecord(selfReferencing));
        w.records.set(0, MobiWriter.header(MobiHeader.HUFF_CDIC, bytes.length, 1, 0, 6, -1, huff, 2, 0, -1, 0, -1, -1,
                null));
        return w.build("BOOKMOBI");
    }

    @Test
    void huffCdicTextDecompresses() throws IOException {
        Converted c = convert(write("huff.mobi", huffBook("<html><body><p>Huffman coded words here.</p></body></html>",
                false)));
        assertTrue(c.text().contains("Huffman coded words here."), c.text());
    }

    @Test
    void huffCdicPhraseThatRefersToItselfIsRefused() throws IOException {
        Path in = write("loop.mobi", huffBook("<p>Q</p>", true));
        assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("loop.pdf"))));
    }

    private static byte[] cdic(List<byte[]> phrases, List<Boolean> plain) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[] offsets = new int[phrases.size()];
        int base = 2 * phrases.size();
        for (int i = 0; i < phrases.size(); i++) {
            offsets[i] = base + body.size();
            MobiWriter.u16(body, (plain.get(i) ? 0x8000 : 0) | phrases.get(i).length);
            body.writeBytes(phrases.get(i));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(utf8("CDIC"));
        MobiWriter.u32(out, 0x10);
        MobiWriter.u32(out, phrases.size());
        MobiWriter.u32(out, 8);
        for (int o : offsets) {
            MobiWriter.u16(out, o);
        }
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    private static byte[] huffText(List<byte[]> textRecords, List<byte[]> phrases, List<Boolean> plain) {
        MobiWriter w = MobiWriter.create();
        long length = 0;
        for (byte[] r : textRecords) {
            w.add(r);
            length += r.length;
        }
        int huff = w.add(huffRecord());
        w.add(cdic(phrases, plain));
        w.records.set(0, MobiWriter.header(MobiHeader.HUFF_CDIC, 0, textRecords.size(), 0, 6, -1, huff, 2, 0, -1, 0,
                -1, -1, null));
        return w.build("BOOKMOBI");
    }

    private static byte[] readText(byte[] book) throws IOException {
        PalmDatabase pdb = new PalmDatabase(book);
        return MobiText.read(pdb, 0, MobiHeader.parse(pdb.record(0), true));
    }

    @Test
    void oversizedCompressedTextRecordIsRefused() {
        byte[] record = new byte[2 << 20];
        java.util.Arrays.fill(record, (byte) 255);
        byte[] book = huffText(List.of(record), List.of(new byte[0]), List.of(true));
        assertThrows(IOException.class, () -> readText(book));
    }

    @Test
    void expandedHuffPhrasesStayWithinTheTextBudget() {
        List<byte[]> phrases = new ArrayList<>();
        List<Boolean> plain = new ArrayList<>();
        List<byte[]> records = new ArrayList<>();
        for (int chain = 0; chain < 3; chain++) {
            int first = phrases.size();
            phrases.add("x".repeat(1 << 14).getBytes(StandardCharsets.ISO_8859_1));
            plain.add(true);
            byte[] wide = new byte[64];
            java.util.Arrays.fill(wide, (byte) (255 - first));
            phrases.add(wide);
            plain.add(false);
            for (int level = 2; level <= 32; level++) {
                phrases.add(new byte[] {(byte) (255 - (phrases.size() - 1))});
                plain.add(false);
            }
            records.add(new byte[] {(byte) (255 - (phrases.size() - 1))});
        }
        byte[] book = huffText(records, phrases, plain);
        assertThrows(IOException.class, () -> readText(book));
    }

    @Test
    void unclosedGuidesStayLinear() {
        byte[] text = ("<html><body><p>Text</p>" + "<guide>".repeat(200_000) + "</body></html>")
                .getBytes(StandardCharsets.ISO_8859_1);
        String html = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> MobiBook.html(text, StandardCharsets.ISO_8859_1));
        assertTrue(html.contains("<p>Text</p>"), html.substring(0, 200));
    }

    private static final int[][] SKEL_TAGS = {{1, 1, 0x03, 0}, {6, 2, 0x0C, 0}, {0, 0, 0, 1}};

    private static final int[][] FRAG_TAGS = {{2, 1, 0x01, 0}, {3, 1, 0x02, 0}, {4, 1, 0x04, 0}, {6, 2, 0x08, 0},
        {0, 0, 0, 1}};

    private static byte[] kf8(boolean joint) throws IOException {
        return kf8(joint, false);
    }

    private static byte[] kf8(boolean joint, boolean overlapping) throws IOException {
        return kf8(joint, overlapping, "");
    }

    private static byte[] kf8(boolean joint, boolean overlapping, String padding) throws IOException {
        return kf8(joint, overlapping, padding, "id=\"one\" aid=\"1\"");
    }

    private static byte[] kf8(boolean joint, boolean overlapping, String padding, String heading) throws IOException {
        String skel0 = "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head>"
                + "<link rel=\"stylesheet\" type=\"text/css\" href=\"kindle:flow:0001?mime=text/css\"/></head>"
                + "<body aid=\"0\"></body></html>";
        String frag0 = "<h1 " + heading + ">Chapter One</h1><p aid=\"2\">Opening line of KF8 text.</p>"
                + "<p class=\"secret\">Hidden by the flow style sheet</p>"
                + "<p><img src=\"kindle:embed:0002?mime=image/png\"/></p>" + padding;
        String skel1 = "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head></head>"
                + "<body aid=\"3\"></body></html>";
        String frag1 = "<h1 aid=\"4\">Chapter Two</h1><p aid=\"5\"><a href=\"kindle:pos:fid:0000:off:0000000003\">"
                + "Back to chapter one</a></p>";
        String flow0 = skel0 + frag0 + skel1 + frag1;
        String css = ".secret { display: none }";
        byte[] text = utf8(flow0 + css);
        int skel0Insert = skel0.indexOf("</body>");
        int start1 = skel0.length() + frag0.length();
        int skel1Insert = start1 + skel1.indexOf("</body>");

        MobiWriter w = MobiWriter.create();
        int base = 0;
        int mobi6Images = -1;
        if (joint) {
            byte[] old = utf8("<html><body><p>Old format text</p></body></html>");
            w.add(old);
            mobi6Images = w.add(png(Color.GREEN));
            w.add(png(Color.BLUE));
            base = w.add(new byte[0]);
            w.records.set(0, MobiWriter.header(MobiHeader.NONE, old.length, 1, 0, 6, mobi6Images, -1, 0, 0, -1, 0, -1,
                    -1, MobiWriter.exth(MobiHeader.EXTH_KF8_BOUNDARY, base, MobiHeader.EXTH_TITLE, "Old title")));
        }
        List<byte[]> chunks = MobiWriter.chunks(text);
        for (byte[] c : chunks) {
            w.add(MobiWriter.palmDoc(c));
        }
        int firstImage = joint ? w.records.size() : w.add(png(Color.GREEN));
        if (!joint) {
            w.add(png(Color.BLUE));
        }
        ByteArrayOutputStream fdst = new ByteArrayOutputStream();
        fdst.writeBytes(utf8("FDST"));
        MobiWriter.u32(fdst, 12);
        MobiWriter.u32(fdst, 2);
        MobiWriter.u32(fdst, 0);
        MobiWriter.u32(fdst, flow0.length());
        MobiWriter.u32(fdst, overlapping ? 0 : flow0.length());
        MobiWriter.u32(fdst, text.length);
        int fdstAt = w.add(fdst.toByteArray());
        int skelAt = w.add(MobiWriter.indx(SKEL_TAGS, List.of(), true, 1));
        w.add(MobiWriter.indx(SKEL_TAGS, List.of(
                MobiWriter.entry("SKEL0000000000", 0x05, 1, 0, skel0.length()),
                MobiWriter.entry("SKEL0000000001", 0x05, 1, start1, skel1.length())), false, 0));
        int fragAt = w.add(MobiWriter.indx(FRAG_TAGS, List.of(), true, 1));
        w.add(MobiWriter.indx(FRAG_TAGS, List.of(
                MobiWriter.entry(Integer.toString(skel0Insert), 0x0F, 0, 0, 0, 0, frag0.length()),
                MobiWriter.entry(Integer.toString(skel1Insert), 0x0F, 0, 1, 1, 0, frag1.length())), false, 0));
        byte[] exth = MobiWriter.exth(MobiHeader.EXTH_TITLE, "The KF8 Book", MobiHeader.EXTH_AUTHOR, "K. Writer");
        w.records.set(base, MobiWriter.header(MobiHeader.PALMDOC, text.length, chunks.size(), 0, 8, firstImage - base,
                -1, 0, 0, fdstAt - base, 2, fragAt - base, skelAt - base, exth));
        return w.build("BOOKMOBI");
    }

    @Test
    void azw3ReadsSkeletonsFragmentsFlowsAndPictures() throws IOException {
        Path in = write("book.azw3", kf8(false));
        Converted c = convert(in);
        int one = c.text().indexOf("Chapter One");
        int two = c.text().indexOf("Chapter Two");
        assertTrue(one >= 0 && two > one, c.text());
        assertTrue(c.text().contains("Opening line of KF8 text."), c.text());
        assertFalse(c.text().contains("Hidden by the flow style sheet"), c.text());
        assertEquals("The KF8 Book", c.title());
        assertEquals("K. Writer", c.author());
        assertEquals(List.of(1, 0), c.images());
        String html = MobiBook.read(in).book().html();
        assertTrue(html.contains("href=\"#epub-c0-one\""), html);
    }

    @Test
    void kf8IdAfterTheAidKeepsItsLinks() throws IOException {
        Path in = write("aid-first.azw3", kf8(false, false, "", "aid=\"1\" id=\"one\""));
        String html = MobiBook.read(in).book().html();
        assertTrue(html.contains("href=\"#epub-c0-one\""), html);
        assertTrue(html.contains("id=\"epub-c0-one\""), html);
    }

    @Test
    void kf8LinkIntoAPartFullOfOpenBracketsStaysLinear() throws IOException {
        Path in = write("brackets.azw3", kf8(false, false, "<".repeat(200_000)));
        String html = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> MobiBook.read(in).book().html());
        assertTrue(html.contains("Chapter Two"));
    }

    @Test
    void overlappingKf8FlowsAreRefused() throws IOException {
        Path in = write("overlap.azw3", kf8(false, true));
        assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("overlap.pdf")));
    }

    @Test
    void filePositionsInTaglessTextStayLinear() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 150_000; i++) {
            sb.append("filepos=").append(i * 20).append(' ');
        }
        sb.append("x".repeat(4_000_000));
        byte[] text = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        long start = System.nanoTime();
        String html = MobiBook.html(text, StandardCharsets.ISO_8859_1);
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000);
        assertEquals(MobiBook.MAX_TARGETS, html.split("<a id=\"filepos", -1).length - 1);
    }

    @Test
    void jointMobiReadsItsKf8Part() throws IOException {
        Converted c = convert(write("joint.mobi", kf8(true)));
        assertTrue(c.text().contains("Opening line of KF8 text."), c.text());
        assertFalse(c.text().contains("Old format text"), c.text());
        assertEquals(List.of(1, 0), c.images());
    }

    @Test
    void damagedIndexIsRefused() throws IOException {
        byte[] book = kf8(false);
        byte[] cut = new byte[book.length - 30];
        System.arraycopy(book, 0, cut, 0, cut.length);
        Path in = write("cut.azw3", cut);
        assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("cut.pdf")));
    }

    private static String fb2(String extraBody) throws IOException {
        String cover = Base64.getMimeEncoder().encodeToString(png(Color.RED));
        String picture = Base64.getEncoder().encodeToString(png(Color.BLUE));
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\" "
                + "xmlns:l=\"http://www.w3.org/1999/xlink\"><description><title-info><genre>prose</genre>"
                + "<author><first-name>Anna</first-name><last-name>Writer</last-name></author>"
                + "<book-title>The FB2 Book</book-title><annotation><p>A short annotation.</p></annotation>"
                + "<coverpage><image l:href=\"#cover.png\"/></coverpage></title-info></description>"
                + "<body><title><p>The FB2 Book</p></title>"
                + "<epigraph><p>An epigraph line.</p><text-author>Someone Wise</text-author></epigraph>"
                + "<section id=\"c1\"><title><p>Chapter One</p></title><p>First <emphasis>chapter</emphasis> "
                + "<strong>opening</strong> line<a l:href=\"#n1\" type=\"note\">1</a>.</p><empty-line/>"
                + "<image l:href=\"#pic.png\"/><poem><stanza><v>Roses are red</v><v>Violets are blue</v></stanza>"
                + "</poem><cite><p>A cited line.</p></cite>"
                + "<table><tr><th>Name</th><th>Value</th></tr><tr><td>alpha</td><td>beta</td></tr></table></section>"
                + "<section><title><p>Chapter Two</p></title><subtitle>A subtitle</subtitle>"
                + "<p>Second chapter <a l:href=\"#c1\">back to one</a>.</p>" + extraBody + "</section></body>"
                + "<body name=\"notes\"><title><p>Notes</p></title><section id=\"n1\"><title><p>1</p></title>"
                + "<p>The footnote text.</p></section></body>"
                + "<binary id=\"cover.png\" content-type=\"image/png\">" + cover + "</binary>"
                + "<binary id=\"pic.png\" content-type=\"image/png\">" + picture + "</binary></FictionBook>";
    }

    @Test
    void utf32FictionBooksPreserveUnicodeAndAreDetectedWithoutTheirExtension() throws IOException {
        String words = "caf\u00e9 \u65E5\u672C\u8A9E \uD801\uDC37";
        for (String encoding : new String[] {"UTF-32BE", "UTF-32LE"}) {
            String xml = fb2("<p>" + words + "</p>").replace("encoding=\"UTF-8\"", "encoding=\"" + encoding + "\"");
            for (boolean bom : new boolean[] {false, true}) {
                byte[] bytes = ((bom ? "\uFEFF" : "") + xml).getBytes(java.nio.charset.Charset.forName(encoding));
                assertTrue(Fb2Book.read(bytes).book().html().contains(words));
                assertTrue(Fb2Book.root(new java.io.ByteArrayInputStream(bytes)));
                assertTrue(Fb2Book.is(write(encoding + "-" + bom + ".bin", bytes)));
            }
        }
    }

    @Test
    void utf32FictionBookPackagesRenderLikeUtf8() throws IOException {
        Converted baseline = convert(write("reference.fb2", utf8(fb2("<p>caf\u00e9 ending.</p>"))));
        for (String encoding : new String[] {"UTF-32BE", "UTF-32LE"}) {
            String xml = "\uFEFF" + fb2("<p>caf\u00e9 ending.</p>")
                    .replace("encoding=\"UTF-8\"", "encoding=\"" + encoding + "\"");
            byte[] bytes = xml.getBytes(java.nio.charset.Charset.forName(encoding));
            Path input = dir.resolve(encoding + ".fbz");
            try (OutputStream out = Files.newOutputStream(input); ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("book.fb2"));
                zip.write(bytes);
                zip.closeEntry();
            }
            Converted converted = convert(input);
            assertEquals(baseline.text(), converted.text());
            assertEquals(baseline.title(), converted.title());
            assertEquals(baseline.author(), converted.author());
            assertEquals(baseline.images(), converted.images());
            try (PDDocument reference = Loader.loadPDF(dir.resolve("reference.fb2.pdf").toFile());
                    PDDocument actual = Loader.loadPDF(dir.resolve(encoding + ".fbz.pdf").toFile())) {
                assertEquals(reference.getNumberOfPages(), actual.getNumberOfPages());
                org.apache.pdfbox.rendering.PDFRenderer a = new org.apache.pdfbox.rendering.PDFRenderer(reference);
                org.apache.pdfbox.rendering.PDFRenderer b = new org.apache.pdfbox.rendering.PDFRenderer(actual);
                for (int page = 0; page < reference.getNumberOfPages(); page++) {
                    BufferedImage expected = a.renderImage(page, 1);
                    BufferedImage found = b.renderImage(page, 1);
                    assertEquals(expected.getWidth(), found.getWidth());
                    assertEquals(expected.getHeight(), found.getHeight());
                    assertArrayEquals(expected.getRGB(0, 0, expected.getWidth(), expected.getHeight(), null, 0,
                            expected.getWidth()), found.getRGB(0, 0, found.getWidth(), found.getHeight(), null, 0,
                                    found.getWidth()));
                }
            }
        }
    }

    @Test
    void wideFictionBookProbingRejectsOtherRootsEntitiesAndMalformedCharacters() throws IOException {
        for (String encoding : new String[] {"UTF-32BE", "UTF-32LE"}) {
            java.nio.charset.Charset charset = java.nio.charset.Charset.forName(encoding);
            byte[] other = ("\uFEFF<?xml version=\"1.0\" encoding=\"" + encoding + "\"?><html/>").getBytes(charset);
            assertFalse(Fb2Book.root(new java.io.ByteArrayInputStream(other)));
            byte[] entities = ("\uFEFF<?xml version=\"1.0\" encoding=\"" + encoding + "\"?>"
                    + "<!DOCTYPE FictionBook [<!ENTITY x SYSTEM 'file:///must-not-read'>]>"
                    + "<FictionBook><body><p>&x;</p></body></FictionBook>").getBytes(charset);
            assertFalse(Fb2Book.root(new java.io.ByteArrayInputStream(entities)));
            assertThrows(IOException.class, () -> Fb2Book.read(entities));
            byte[] malformed = ("\uFEFF" + fb2("")).replace("UTF-8", encoding).getBytes(charset);
            byte[] truncated = java.util.Arrays.copyOf(malformed, malformed.length - 1);
            assertThrows(IOException.class, () -> Fb2Book.read(truncated));
        }
    }

    @Test
    void fictionBookReadsSectionsNotesAndPictures() throws IOException {
        Converted c = convert(write("book.fb2", utf8(fb2(""))));
        String t = c.text();
        String[] order = {"A short annotation", "An epigraph line", "Chapter One", "First chapter opening line",
            "Roses are red", "Violets are blue", "A cited line", "alpha", "Chapter Two", "A subtitle",
            "back to one", "Notes", "The footnote text"};
        int last = -1;
        for (String s : order) {
            int at = t.indexOf(s);
            assertTrue(at > last, s + " in " + t);
            last = at;
        }
        assertEquals("The FB2 Book", c.title());
        assertEquals("Anna Writer", c.author());
        assertEquals(1, c.images().get(0), c.images().toString());
        assertEquals(2, c.images().stream().mapToInt(Integer::intValue).sum(), c.images().toString());
        String html = Fb2Book.read(utf8(fb2(""))).book().html();
        assertTrue(html.contains("href=\"#epub-c0-n1\"") && html.contains("id=\"epub-c0-n1\""), html);
    }

    private Path zippedFictionBook(String name) throws IOException {
        Path in = dir.resolve(name);
        try (OutputStream os = Files.newOutputStream(in); ZipOutputStream zip = new ZipOutputStream(os)) {
            zip.putNextEntry(new ZipEntry("book.fb2"));
            zip.write(utf8(fb2("")));
            zip.closeEntry();
        }
        return in;
    }

    @Test
    void zippedFictionBookReads() throws IOException {
        assertTrue(convert(zippedFictionBook("book.fb2.zip")).text().contains("The footnote text"));
        assertTrue(convert(zippedFictionBook("book.fbz")).text().contains("The footnote text"));
    }

    @Test
    void aFictionBookInAnyOtherZipIsAPackage() throws IOException {
        Path in = zippedFictionBook("library.zip");
        IOException e = assertThrows(IOException.class,
                () -> OfficeToPdf.convert(in, dir.resolve("library.pdf"), OPTIONS));
        assertTrue(e.getMessage().contains("--allow-packages"), e.getMessage());
        IOException streamed = assertThrows(IOException.class, () -> {
            try (var bytes = Files.newInputStream(in)) {
                OfficeToPdf.convert(bytes, Format.HTML, new ByteArrayOutputStream(), OPTIONS);
            }
        });
        assertTrue(streamed.getMessage().contains("--allow-packages"), streamed.getMessage());
        OfficeToPdf.convert(in, dir.resolve("library.pdf"), OPTIONS.allowPackages(true));
        try (PDDocument doc = Loader.loadPDF(dir.resolve("library.pdf").toFile())) {
            assertTrue(new PDFTextStripper().getText(doc).contains("The footnote text"));
        }
    }

    @Test
    void anEpubNamedFbzNeedsAllowPackages() throws IOException {
        Path in = dir.resolve("book.fbz");
        try (OutputStream os = Files.newOutputStream(in); ZipOutputStream zip = new ZipOutputStream(os)) {
            zip.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zip.write(utf8("<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles>"
                    + "<rootfile full-path=\"c.opf\"/></rootfiles></container>"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("c.opf"));
            zip.write(utf8("<package xmlns=\"http://www.idpf.org/2007/opf\"><manifest><item id=\"a\" href=\"a.xhtml\""
                    + " media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"a\"/></spine></package>"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("a.xhtml"));
            zip.write(utf8("<html><body><p>Inside the EPUB</p></body></html>"));
            zip.closeEntry();
        }
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("fbz.pdf"), OPTIONS));
        assertTrue(e.getMessage().contains("--allow-packages"), e.getMessage());
    }

    @Test
    void fictionBookIsFoundByItsRootElement() throws IOException {
        Path in = write("book.html", utf8(fb2("")));
        assertTrue(convert(in).text().contains("Violets are blue"));
        assertTrue(Fb2Book.is(in));
        assertFalse(Fb2Book.is(write("page.html", utf8("<?xml version=\"1.0\"?><html><body>x</body></html>"))));
    }

    @Test
    void fictionBookWithADoctypeIsRefused() throws IOException {
        Path secret = write("secret.txt", utf8("TOP SECRET"));
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE FictionBook [<!ENTITY x SYSTEM \"" + secret.toUri()
                + "\">]><FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\"><body><section><p>&x;</p>"
                + "</section></body></FictionBook>";
        Path in = write("xxe.fb2", utf8(xml));
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("xxe.pdf")));
        assertFalse(e.getMessage().contains("TOP SECRET"), e.getMessage());
    }

    @Test
    void notAFictionBookIsRefused() throws IOException {
        Path in = write("fake.fb2", utf8("<html><body>no</body></html>"));
        IOException e = assertThrows(IOException.class, () -> OfficeToPdf.convert(in, dir.resolve("fake.pdf")));
        assertTrue(e.getMessage().contains("FictionBook"), e.getMessage());
    }

    @Test
    void ebookExtensionsAreRecognised() {
        for (String ext : new String[] {"mobi", "azw3", "azw", "prc", "fb2", "fbz"}) {
            assertEquals(Format.HTML, Format.of(Path.of("a." + ext)), ext);
            assertTrue(Format.recognises(Path.of("a." + ext)), ext);
        }
        assertEquals(Format.HTML, Format.of(Path.of("a.pdb")));
        assertFalse(Format.recognises(Path.of("a.pdb")));
    }

    @Test
    void streamedMobiIsFoundByItsHeader() throws IOException {
        byte[] book = mobi6(MobiHeader.PALMDOC, 0);
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        OfficeToPdf.convert(new java.io.ByteArrayInputStream(book), Format.HTML, pdf, OPTIONS);
        try (PDDocument doc = Loader.loadPDF(pdf.toByteArray())) {
            assertTrue(new PDFTextStripper().getText(doc).contains("Chapter Two"));
        }
    }

    @Test
    void base32PositionsDecode() {
        assertEquals(0, Kf8Book.base32("0000"));
        assertEquals(31, Kf8Book.base32("000V"));
        assertEquals(32 * 32 + 10, Kf8Book.base32("010A"));
        assertArrayEquals(new byte[] {(byte) 0x81}, MobiWriter.varint(1));
    }

    @Test
    void mobiFontSizesFollowKindleSteps() {
        String html = MobiBook.html("<p><font size=\"7\">T</font><font size=\"+2\">H</font><font size=10>X</font></p>"
                .getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1);
        assertTrue(html.contains("<font style=\"font-size:1.83333rem\">T"), html);
        assertTrue(html.contains("<font style=\"font-size:1.41667rem\">H"), html);
        assertTrue(html.contains("<font size=10>X"), html);
    }

    @Test
    void nestedMobiFontSizesDoNotCompound() throws IOException {
        String html = MobiBook.html("<html><body><h2><font size=\"6\">Nested</font></h2></body></html>"
                .getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1);
        Path page = Files.writeString(dir.resolve("nested.html"), html);
        Path pdf = dir.resolve("nested.pdf");
        OfficeToPdf.convert(page, pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            float[] size = {0};
            new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<org.apache.pdfbox.text.TextPosition> positions) {
                    if (text.contains("Nested")) {
                        size[0] = positions.getFirst().getFontSizeInPt();
                    }
                }
            }.getText(doc);
            assertEquals(20, size[0], 0.6f);
        }
    }

    @Test
    void mobiClosingBreaksAreNotSecondBreaks() {
        String html = MobiBook.html("<p>a<br></br>b<BR></BR >c</p>".getBytes(StandardCharsets.ISO_8859_1),
                StandardCharsets.ISO_8859_1);
        assertTrue(html.contains("a<br>b<BR>c"), html);
    }
}

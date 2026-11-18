package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SfntTest {

    private static final COSName LENGTH1 = COSName.getPDFName("Length1");

    @TempDir
    Path dir;

    private static byte[] liberation() throws Exception {
        try (InputStream in = PDTrueTypeFont.class.getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            return in.readAllBytes();
        }
    }

    private static byte[] withLeftovers(byte[] font) {
        byte[] padded = Arrays.copyOf(font, font.length + 50_000);
        Arrays.fill(padded, font.length, padded.length, (byte) 0x5A);
        return padded;
    }

    private static Map<String, byte[]> tables(byte[] font) {
        ByteBuffer b = ByteBuffer.wrap(font);
        Map<String, byte[]> tables = new HashMap<>();
        for (int i = 0; i < (b.getShort(4) & 0xFFFF); i++) {
            int r = 12 + 16 * i;
            byte[] data = Arrays.copyOfRange(font, b.getInt(r + 8), b.getInt(r + 8) + b.getInt(r + 12));
            String tag = new String(font, r, 4, StandardCharsets.ISO_8859_1);
            if (tag.equals("head")) {
                Arrays.fill(data, 8, 12, (byte) 0);
            }
            tables.put(tag, data);
        }
        return tables;
    }

    private static int checksum(byte[] font) {
        ByteBuffer b = ByteBuffer.wrap(Arrays.copyOf(font, font.length + 3 & ~3));
        int sum = 0;
        while (b.hasRemaining()) {
            sum += b.getInt();
        }
        return sum;
    }

    @Test
    void bytesNoTableNamesAreDroppedAndEveryTableIsKept() throws Exception {
        byte[] font = withLeftovers(liberation());
        byte[] tidy = Sfnt.tidy(font);
        assertNotNull(tidy);
        assertTrue(tidy.length <= liberation().length, tidy.length + " vs " + liberation().length);
        Map<String, byte[]> before = tables(font);
        Map<String, byte[]> after = tables(tidy);
        before.keySet().removeAll(java.util.Set.of("DSIG", "meta"));
        assertEquals(before.keySet(), after.keySet());
        for (String tag : before.keySet()) {
            assertArrayEquals(before.get(tag), after.get(tag), tag);
        }
        assertEquals(0xB1B0AFBA, checksum(tidy));
        try (TrueTypeFont parsed = new TTFParser(true).parse(new RandomAccessReadBuffer(tidy))) {
            assertTrue(parsed.getNumberOfGlyphs() > 100);
        }
    }

    @Test
    void aFontWithNothingToDropOrThatIsNotTrueTypeIsLeftAlone() throws Exception {
        byte[] tidy = Sfnt.tidy(withLeftovers(liberation()));
        assertNull(Sfnt.tidy(tidy));
        assertNull(Sfnt.tidy(Arrays.copyOf(tidy, tidy.length + 200)));
        assertNull(Sfnt.tidy("OTTO and some bytes".getBytes(StandardCharsets.ISO_8859_1)));
        byte[] broken = withLeftovers(liberation());
        ByteBuffer.wrap(broken).putInt(12 + 8, broken.length);
        assertNull(Sfnt.tidy(broken));
    }

    @Test
    void anOptimizedPdfCarriesTheTidyFontWithItsLength1() throws Exception {
        Path in = dir.resolve("in.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A6);
            d.addPage(page);
            PDTrueTypeFont font = PDTrueTypeFont.load(d, new java.io.ByteArrayInputStream(liberation()),
                    WinAnsiEncoding.INSTANCE);
            COSStream file = font.getFontDescriptor().getFontFile2().getCOSObject();
            byte[] padded = withLeftovers(liberation());
            try (OutputStream out = file.createOutputStream(COSName.FLATE_DECODE)) {
                out.write(padded);
            }
            file.setInt(LENGTH1, padded.length);
            try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(20, 200);
                cs.showText("Tidy fonts");
                cs.endText();
            }
            d.save(in.toFile());
        }
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults());
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDTrueTypeFont font = (PDTrueTypeFont) d.getPage(0).getResources()
                    .getFont(d.getPage(0).getResources().getFontNames().iterator().next());
            COSStream file = font.getFontDescriptor().getFontFile2().getCOSObject();
            byte[] program;
            try (InputStream s = file.createInputStream()) {
                program = s.readAllBytes();
            }
            assertTrue(program.length <= liberation().length, program.length + " bytes");
            assertEquals(program.length, file.getInt(LENGTH1));
            assertEquals("Tidy fonts", new PDFTextStripper().getText(d).strip());
        }
    }

    private static byte[] font(Map<String, byte[]> tables, int aliasesOfGlyf) {
        int n = tables.size() + aliasesOfGlyf;
        int at = 12 + 16 * n;
        ByteBuffer out = ByteBuffer.allocate(at + tables.values().stream().mapToInt(t -> t.length + 3 & ~3).sum());
        out.putInt(0, 0x00010000).putShort(4, (short) n);
        int r = 12;
        int glyfAt = 0;
        for (Map.Entry<String, byte[]> e : tables.entrySet()) {
            out.put(r, e.getKey().getBytes(StandardCharsets.ISO_8859_1)).putInt(r + 8, at)
                    .putInt(r + 12, e.getValue().length).put(at, e.getValue());
            glyfAt = e.getKey().equals("glyf") ? at : glyfAt;
            r += 16;
            at += e.getValue().length + 3 & ~3;
        }
        for (int i = 0; i < aliasesOfGlyf; i++) {
            String id = Integer.toString(i, 36);
            out.put(r, ("z00" + id).substring(id.length()).getBytes(StandardCharsets.ISO_8859_1))
                    .putInt(r + 8, glyfAt).putInt(r + 12, tables.get("glyf").length);
            r += 16;
        }
        return out.array();
    }

    private static Map<String, byte[]> glyphTables(int glyphs, byte[] loca, int glyfLength) {
        Map<String, byte[]> t = new LinkedHashMap<>();
        t.put("head", new byte[54]);
        t.put("hhea", ByteBuffer.allocate(36).putShort(34, (short) 1).array());
        t.put("maxp", ByteBuffer.allocate(6).putInt(0, 0x00005000).putShort(4, (short) glyphs).array());
        t.put("hmtx", new byte[4]);
        t.put("loca", loca);
        t.put("glyf", new byte[glyfLength]);
        return t;
    }

    @Test
    void aSubsetRefusesLocaOffsetsThatRunBackwards() {
        int glyphs = 32_766;
        ByteBuffer loca = ByteBuffer.allocate((glyphs + 1) * 2);
        for (int g = 0; g <= glyphs; g++) {
            loca.putShort(2 * g, (short) (g % 2 == 0 ? 0 : 0xFFFF));
        }
        BitSet used = new BitSet();
        used.set(0, glyphs);

        assertNull(Sfnt.subset(font(glyphTables(glyphs, loca.array(), 0x1FFFE), 0), used, true));
    }

    @Test
    void aRepairRefusesTablesThatOverlapIntoAHugeFont() {
        byte[] font = font(glyphTables(1, new byte[4], 500_000), 4_000);

        assertNull(Sfnt.complete(font));
    }
}

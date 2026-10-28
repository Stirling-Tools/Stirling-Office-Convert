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
import java.util.HashMap;
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
        assertNull(Sfnt.tidy(Sfnt.tidy(withLeftovers(liberation()))));
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
}

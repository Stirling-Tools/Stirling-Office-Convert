package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.filter.Filter;
import org.apache.pdfbox.filter.FilterFactory;
import org.junit.jupiter.api.Test;

class SyntaxTest {

    @Test
    void numbersTakeTheirShortestSpelling() throws IOException {
        assertEquals("1", Lexical.number("1.000"));
        assertEquals(".5", Lexical.number("0.50"));
        assertEquals("-.25", Lexical.number("-0.250"));
        assertEquals("0", Lexical.number("-0.0"));
        assertEquals("12", Lexical.number("+0012."));
        assertEquals("595.27559", Lexical.number("595.27559"));
        assertEquals("100000", Lexical.number("1e5"));
        assertEquals("340282350000000000000000000000000000000.", Lexical.number("3.4028235E38"));
        assertEquals("12345678901.", Lexical.number("12345678901.000"));
        assertEquals("12345678901", Lexical.number("12345678901"));
        assertTrue(Syntax.number(new org.apache.pdfbox.cos.COSFloat("9999999999999999999999999999999999999999.0"))
                .endsWith("."));
        assertNull(Lexical.plainNumber("1.2.3"));
        assertNull(Lexical.plainNumber("-"));
    }

    @Test
    void stringsTakeTheShorterOfLiteralAndHex() {
        assertEquals("(a(b)c)", string("a(b)c"));
        assertEquals("(a\\)b)", string("a)b"));
        assertEquals("(\\\\\\r)", string("\\\r"));
        assertEquals("(\u0000\u0001)", string("\u0000\u0001"));
    }

    @Test
    void contentKeepsEveryTokenWithLessSpace() {
        assertEquals("q\n1 0 0 1 72.5 .25 cm\n/F1 12 Tf\n[(a)-20(b)]TJ\nQ",
                minify("q  1.0 0 0 1.000 72.50 0.25 cm % note\n /F1  12.0 Tf [ (a) -20 (b) ] TJ Q"));
        assertEquals("\nBT\n<</MCID 3>>BDC\n(x\\))Tj\nEMC\nET\n",
                minify("\nBT <</MCID 3>> BDC (x\\051) Tj EMC ET\n"));
    }

    @Test
    void contentEdgesWithoutSpaceAreLeftAsTheyAre() {
        assertEquals("1.0 0 0 rg\n0 0 1.50", minify("1.0 0 0 rg 0 0 1.50"));
    }

    @Test
    void contentItCannotReadIsLeftAlone() {
        assertNull(Content.minify(bytes("BI /W 1 /H 1 /BPC 8 /CS /G ID x EI")));
        assertNull(Content.minify(bytes("0 0 m 1..2 l S")));
        assertNull(Content.minify(bytes("(unterminated")));
        assertNull(Content.minify(bytes("q Q % comment without an end")));
    }

    @Test
    void xmpPaddingIsDroppedButThePacketStaysWhole() {
        String head = "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>";
        String padded = head + "\n" + " ".repeat(2000) + "\n<?xpacket end=\"w\"?>";
        assertEquals(head + "\n<?xpacket end=\"w\"?>", new String(Xmp.withoutPadding(bytes(padded)),
                StandardCharsets.ISO_8859_1));
        byte[] tight = bytes(head + "\n<?xpacket end=\"w\"?>");
        assertArrayEquals(tight, Xmp.withoutPadding(tight));
    }

    @Test
    void resourceNamesAreFoundInTheContent() {
        Content.Usage u = Content.scan(bytes("/GS1 gs /Cs1 cs /P1 scn /Span <</MCID 1>> BDC /OC /L1 BDC "
                + "BT /F#31 9 Tf ET /Im0 Do /Sh1 sh"));
        assertEquals(java.util.Set.of(COSName.getPDFName("F1")), u.names().get(Content.Category.FONT));
        assertEquals(java.util.Set.of(COSName.getPDFName("L1")), u.names().get(Content.Category.PROPERTIES));
        assertEquals(java.util.Set.of(COSName.getPDFName("P1")), u.names().get(Content.Category.PATTERN));
        assertEquals(java.util.Set.of(COSName.getPDFName("Im0")), u.names().get(Content.Category.XOBJECT));
    }

    @Test
    void generalizedFiltersDecodeWhatPdfBoxEncodes() throws Exception {
        byte[] data = new byte[20_000];
        Random r = new Random(3);
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (r.nextInt(6) + 'a');
        }
        for (COSName f : new COSName[] {COSName.LZW_DECODE, COSName.ASCII85_DECODE, COSName.ASCII_HEX_DECODE,
                COSName.RUN_LENGTH_DECODE, COSName.FLATE_DECODE}) {
            Filter filter = FilterFactory.INSTANCE.getFilter(f);
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            filter.encode(new ByteArrayInputStream(data), encoded, new COSDictionary(), 0);
            assertArrayEquals(data, Filters.decode(f, null, encoded.toByteArray(), 1 << 20), f.getName());
        }
    }

    @Test
    void decodingStopsAtTheSizeLimit() throws IOException {
        byte[] zeros = new byte[1 << 20];
        byte[] packed = Deflate.zlib(zeros, 9, 0);
        assertThrows(Filters.Undecodable.class, () -> Filters.decode(COSName.FLATE_DECODE, null, packed, 1000));
    }

    @Test
    void textFiltersStopAtTheSizeLimit() {
        byte[] zeros = "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz~>"
                .getBytes(StandardCharsets.US_ASCII);
        assertThrows(Filters.TooLarge.class, () -> Filters.decode(COSName.ASCII85_DECODE, null, zeros, 100));
        byte[] groups = "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!~>"
                .getBytes(StandardCharsets.US_ASCII);
        assertThrows(Filters.TooLarge.class, () -> Filters.decode(COSName.ASCII85_DECODE, null, groups, 40));
        byte[] hex = "00112233445566778899".getBytes(StandardCharsets.US_ASCII);
        assertThrows(Filters.TooLarge.class, () -> Filters.decode(COSName.ASCII_HEX_DECODE, null, hex, 4));
        assertArrayEquals(new byte[400], assertDoesNotThrow(
                () -> Filters.decode(COSName.ASCII85_DECODE, null, zeros, 400)));
    }

    @Test
    void pngPredictionRoundTrips() throws Exception {
        Random r = new Random(5);
        byte[] image = new byte[37 * 3 * 21];
        for (int i = 0; i < image.length; i++) {
            image[i] = (byte) (i % 37 + r.nextInt(4));
        }
        Predictors.Layout layout = new Predictors.Layout(3, 8, 37);
        COSDictionary parms = new COSDictionary();
        parms.setInt(COSName.PREDICTOR, 15);
        parms.setInt(COSName.COLORS, 3);
        parms.setInt(COSName.COLUMNS, 37);
        for (int f : new int[] {Predictors.ADAPTIVE, Predictors.NONE, Predictors.SUB, Predictors.UP,
                Predictors.AVERAGE, Predictors.PAETH}) {
            byte[] predicted = Predictors.encodePng(image, layout, f);
            assertArrayEquals(image, Predictors.decode(predicted, parms, 1 << 20));
        }
    }

    private static String string(String s) {
        Bytes b = new Bytes();
        Lexical.string(s.getBytes(StandardCharsets.ISO_8859_1), b);
        return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static String minify(String s) {
        return new String(Content.minify(bytes(s)), StandardCharsets.ISO_8859_1);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }
}

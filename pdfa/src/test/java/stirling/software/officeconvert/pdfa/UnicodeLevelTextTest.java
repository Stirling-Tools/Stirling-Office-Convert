package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.TreeMap;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.fonts.CodeReader;

class UnicodeLevelTextTest {

    @TempDir
    Path dir;

    @Test
    void numericGlyphNamesReadAsTheCodeTheyNameInDecimalOrHex() {
        assertEquals(73, UnicodeGuess.numeric("a73", false));
        assertEquals(110, UnicodeGuess.numeric("C110", false));
        assertEquals(0x4B, UnicodeGuess.numeric("G4B", true));
        assertEquals(0x2C, UnicodeGuess.numeric("C2c", true));
        assertEquals(-1, UnicodeGuess.numeric("BT", false));
        assertEquals(-1, UnicodeGuess.numeric("a100", true));
    }

    @Test
    void ligatureAndSequenceGlyphNamesSpellTheirComponents() {
        assertEquals("ffi", UnicodeGuess.glyphName("f_f_i"));
        assertEquals("Th", UnicodeGuess.glyphName("T_h"));
        assertEquals("AB", UnicodeGuess.glyphName("uni00410042"));
        assertEquals("ó", UnicodeGuess.glyphName("oacute.sc"));
        assertNull(UnicodeGuess.glyphName("square"));
    }

    @Test
    void aToUnicodeMapThatUsesAnotherIsNotSelfContained() throws Exception {
        try (PDDocument d = new PDDocument()) {
            COSStream uses = d.getDocument().createCOSStream();
            try (OutputStream o = uses.createOutputStream()) {
                o.write("begincmap /Adobe-Korea1-UCS2 usecmap 1 beginbfchar <46FD> <0081> endbfchar endcmap"
                        .getBytes(StandardCharsets.US_ASCII));
            }
            assertTrue(ToUnicodeWriter.usesOther(uses));
            assertFalse(ToUnicodeWriter.usesOther(ToUnicodeWriter.write(d, new TreeMap<>(java.util.Map.of(1, "A")), 1)));
        }
    }

    @Test
    void aToUnicodeMapWithADamagedDeflateTailStillGivesItsEntries() throws Exception {
        try (PDDocument d = new PDDocument()) {
            String text = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap 1 begincodespacerange <00> <FF>"
                    + " endcodespacerange 2 beginbfchar <41> <0048> <42> <0069> endbfchar endcmap"
                    + " CMapName currentdict /CMap defineresource pop end end\n" + "%".repeat(4000);
            java.io.ByteArrayOutputStream packed = new java.io.ByteArrayOutputStream();
            try (var z = new java.util.zip.DeflaterOutputStream(packed)) {
                z.write(text.getBytes(StandardCharsets.US_ASCII));
            }
            byte[] cut = java.util.Arrays.copyOf(packed.toByteArray(), packed.size() - 6);
            cut[cut.length - 1] ^= 0x55;
            COSStream map = d.getDocument().createCOSStream();
            try (OutputStream o = map.createRawOutputStream()) {
                o.write(cut);
            }
            map.setItem(COSName.FILTER, new COSArray(java.util.List.of(COSName.FLATE_DECODE)));
            PDFont font = FontSamples.trueType(d);
            font.getCOSObject().setItem(COSName.TO_UNICODE, map);
            assertEquals("H", UnicodeGuess.declared(font, 0x41));
            assertEquals("i", UnicodeGuess.declared(font, 0x42));
            COSStream plain = d.getDocument().createCOSStream();
            try (OutputStream o = plain.createRawOutputStream()) {
                o.write(text.getBytes(StandardCharsets.US_ASCII));
            }
            plain.setItem(COSName.FILTER, COSName.FLATE_DECODE);
            PDFont other = FontSamples.trueType(d);
            other.getCOSObject().setItem(COSName.TO_UNICODE, plain);
            assertEquals("H", UnicodeGuess.declared(other, 0x41));
        }
    }

    @Test
    void presentationFormLigaturesAreWrittenAsTheirLetters() {
        assertEquals("fi", ToUnicodeWriter.plain("ﬁ"));
        assertEquals("ffl", ToUnicodeWriter.plain("ﬄ"));
        assertEquals("é", ToUnicodeWriter.plain("é"));
    }

    @Test
    void aType3FontWithNumericOrUnknownNamesKeepsItsTextAtTheUnicodeLevel() throws Exception {
        Path in = dir.resolve("t3.pdf");
        try (PDDocument d = new PDDocument()) {
            PDFont font = type3(d, true, "a73", "square", "a75");
            d.addPage(FontSamples.page(d, "BT /A 12 Tf 72 700 Td <414243> Tj ET", font));
            d.save(in.toFile());
        }
        Path out = dir.resolve("t3-u.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2U));
        VeraPdf.assertCompliant(out, PdfALevel.A2U);
        assertEquals("IBK", Converted.text(out).strip());
    }

    @Test
    void aRepairedType3GlyphKeepsTheTextItsOldNameGave() throws Exception {
        Path in = dir.resolve("t3b.pdf");
        try (PDDocument d = new PDDocument()) {
            PDFont font = type3(d, false, "eacute", "Aring", "C");
            d.addPage(FontSamples.page(d, "BT /A 12 Tf 72 700 Td <414243> Tj ET", font));
            d.save(in.toFile());
        }
        Path out = dir.resolve("t3-b.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
        assertEquals("éÅC", Converted.text(out).strip());
    }

    @Test
    void anIdentityCidFontWithoutToUnicodeReadsItsCidsAsViewersDo() throws Exception {
        try (PDDocument d = new PDDocument()) {
            PDType0Font identity = cidFont(d, "Identity", null);
            assertEquals("V", UnicodeGuess.extracted(identity, 0x56));
            assertEquals("ř", UnicodeGuess.extracted(identity, 0x159));
            PDType0Font japanese = cidFont(d, "Japan1", null);
            assertEquals("T", UnicodeGuess.extracted(japanese, 53));
            PDType0Font korean = cidFont(d, "Korea1", null);
            assertEquals("\uC548", UnicodeGuess.extracted(korean, 2441));
            assertEquals("\uC548", UnicodeGuess.of(korean, 2441, 2));
            COSStream declared = ToUnicodeWriter.write(d, new TreeMap<>(java.util.Map.of(0x41, "A")), 2);
            PDType0Font mapped = cidFont(d, "Identity", declared);
            assertNull(UnicodeGuess.extracted(mapped, 0x56));
        }
    }

    @Test
    void anUnembeddedIdentityCidFontGetsNoTextFromItsStandIn() throws Exception {
        try (PDDocument d = new PDDocument()) {
            PDType0Font font = cidFont(d, "Identity", null);
            assertTrue(UnicodeGuess.standIn(font));
            assertNull(UnicodeGuess.text(font, 0x44, 2));
        }
    }

    @Test
    void codesOfAMixedWidthCMapAreWrittenAtTheirOwnWidth() throws Exception {
        try (PDDocument d = new PDDocument()) {
            COSStream encoding = d.getDocument().createCOSStream();
            encoding.setItem(COSName.TYPE, COSName.getPDFName("CMap"));
            String body = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /Utf8 def "
                    + "/CMapType 1 def 3 begincodespacerange <00> <7F> <E08080> <EFBFBF> <F0808080> <F7BFBFBF> "
                    + "endcodespacerange 3 begincidrange <00> <7F> 0 <E08080> <EFBFBF> 200 "
                    + "<F0808080> <F0808090> 300 endcidrange endcmap CMapName currentdict /CMap defineresource pop end end";
            try (OutputStream o = encoding.createOutputStream()) {
                o.write(body.getBytes(StandardCharsets.US_ASCII));
            }
            PDType0Font font = cidFont(d, "Identity", null);
            font.getCOSObject().setItem(COSName.ENCODING, encoding);
            font = (PDType0Font) PDFontFactory.createFont(font.getCOSObject());
            TreeMap<Integer, String> map = new TreeMap<>();
            map.put(0x61, "a");
            map.put(0xE38081, "、");
            map.put(0xF0808081, "😀");
            COSStream written = ToUnicodeWriter.write(d, map, font, 4);
            String text = new String(written.createInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(text.contains("<61> <0061>"), text);
            assertTrue(text.contains("<E38081> <3001>"), text);
            assertTrue(text.contains("<F0808081> <D83DDE00>"), text);
            assertFalse(text.contains("<00000061>"), text);
            var cmap = CodeReader.cmap(written);
            assertEquals("a", cmap.toUnicode(0x61));
            assertEquals("、", cmap.toUnicode(0xE38081));
            font.getCOSObject().setItem(COSName.TO_UNICODE, written);
            assertEquals("😀", UnicodeGuess.declared(font, 0xF0808081));
        }
    }

    private static PDType0Font cidFont(PDDocument d, String ordering, COSStream toUnicode) throws Exception {
        COSDictionary info = new COSDictionary();
        info.setString(COSName.REGISTRY, "Adobe");
        info.setString(COSName.ORDERING, ordering);
        info.setInt(COSName.SUPPLEMENT, 0);
        COSDictionary fd = new COSDictionary();
        fd.setItem(COSName.TYPE, COSName.FONT_DESC);
        fd.setName(COSName.FONT_NAME, "Unknown");
        fd.setInt(COSName.FLAGS, 32);
        COSDictionary cid = new COSDictionary();
        cid.setItem(COSName.TYPE, COSName.FONT);
        cid.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2);
        cid.setName(COSName.BASE_FONT, "Unknown");
        cid.setItem(COSName.CIDSYSTEMINFO, info);
        cid.setItem(COSName.FONT_DESC, fd);
        COSDictionary font = new COSDictionary();
        font.setItem(COSName.TYPE, COSName.FONT);
        font.setItem(COSName.SUBTYPE, COSName.TYPE0);
        font.setName(COSName.BASE_FONT, "Unknown");
        font.setItem(COSName.ENCODING, COSName.IDENTITY_H);
        font.setItem(COSName.DESCENDANT_FONTS, new COSArray(java.util.List.of(cid)));
        if (toUnicode != null) {
            font.setItem(COSName.TO_UNICODE, toUnicode);
        }
        return (PDType0Font) PDFontFactory.createFont(font);
    }

    private static PDType3Font type3(PDDocument d, boolean sized, String... names) throws Exception {
        COSDictionary font = new COSDictionary();
        font.setItem(COSName.TYPE, COSName.FONT);
        font.setItem(COSName.SUBTYPE, COSName.TYPE3);
        font.setItem(COSName.FONT_BBOX, new COSArray(java.util.List.of(COSInteger.ZERO, COSInteger.ZERO,
                COSInteger.get(1000), COSInteger.get(1000))));
        COSArray matrix = new COSArray();
        for (String v : new String[] {"0.001", "0", "0", "0.001", "0", "0"}) {
            matrix.add(new org.apache.pdfbox.cos.COSFloat(Float.parseFloat(v)));
        }
        font.setItem(COSName.FONT_MATRIX, matrix);
        COSArray differences = new COSArray();
        differences.add(COSInteger.get(0x41));
        COSDictionary procs = new COSDictionary();
        COSArray widths = new COSArray();
        for (String name : names) {
            differences.add(COSName.getPDFName(name));
            COSStream glyph = d.getDocument().createCOSStream();
            try (OutputStream o = glyph.createOutputStream()) {
                o.write(((sized ? "500 0 d0 " : "") + "0 0 400 600 re f").getBytes(StandardCharsets.US_ASCII));
            }
            procs.setItem(COSName.getPDFName(name), glyph);
            widths.add(COSInteger.get(500));
        }
        COSDictionary encoding = new COSDictionary();
        encoding.setItem(COSName.TYPE, COSName.ENCODING);
        encoding.setItem(COSName.DIFFERENCES, differences);
        font.setItem(COSName.ENCODING, encoding);
        font.setItem(COSName.CHAR_PROCS, procs);
        font.setInt(COSName.FIRST_CHAR, 0x41);
        font.setInt(COSName.LAST_CHAR, 0x40 + names.length);
        font.setItem(COSName.WIDTHS, widths);
        font.setItem(COSName.RESOURCES, new COSDictionary());
        return (PDType3Font) PDFontFactory.createFont(font);
    }
}

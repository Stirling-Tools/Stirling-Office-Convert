package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDVectorFont;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.FontSet;
import stirling.software.officeconvert.topdf.font.GlyphOutlines;

class FontEmbedderCjkTest {

    private static final String TEXT = "日本語の文章";

    @TempDir
    Path dir;

    @Test
    void aPredefinedCjkCMapIsDrawnFromAFallbackFont() throws Exception {
        FontSet fonts = FontSet.system();
        assumeTrue(fonts.library().fallback(0x65E5, false, false) != null, "no CJK fallback font installed");
        Path out = embed(predefined(), fonts, true);
        assertDrawn(out);
    }

    @Test
    void aCjkFallbackWithOnlyCffOutlinesDrawsItsGlyphs() throws Exception {
        Path cff = cffCjkDirectory();
        assumeTrue(cff != null, "no CFF-outline CJK font found");
        FontSet fonts = FontSet.builder().systemFonts(false).font(liberation()).directory(cff).build();
        assertDrawn(embed(identity(), fonts, true));
        assertDrawn(embed(predefined(), fonts, true));
    }

    @Test
    void askingForTheSameCffOutlineTwiceDoesNotScaleItTwice() throws Exception {
        Path cff = cffCjkDirectory();
        assumeTrue(cff != null, "no CFF-outline CJK font found");
        FontSet fonts = FontSet.builder().systemFonts(false).font(liberation()).directory(cff).build();
        try (GlyphOutlines outlines = GlyphOutlines.open(fonts.library().fallback(0x65E5, false, false))) {
            Rectangle2D first = outlines.path(0x65E5, 2048).getBounds2D();
            Rectangle2D second = outlines.path(0x65E5, 2048).getBounds2D();
            assertFalse(first.isEmpty());
            assertEquals(first, second);
        }
    }

    @Test
    void charactersNoInstalledFontHasAreReportedNotHidden() throws Exception {
        FontSet latinOnly = FontSet.builder().systemFonts(false).font(liberation()).build();
        for (byte[] pdf : new byte[][] {predefined(), identity(), identityWithoutUnicode()}) {
            try (PDDocument d = Loader.loadPDF(pdf)) {
                FontEmbedder.Result r = FontEmbedder.embedMissing(d, latinOnly);
                assertEquals(1, r.embedded().size(), r.embedded().toString());
                assertEquals(1, r.incomplete().size(), "silent loss: " + r.embedded());
                assertTrue(r.incomplete().get(0).contains("not in it"), r.incomplete().toString());
            }
        }
    }

    @Test
    void aUnicodeCMapCodeIsItsOwnTextWithoutTheLossyCidRoundTrip() throws Exception {
        COSDictionary cid = cidFont(COSName.CID_FONT_TYPE0, "KozMinPr6N-Regular", "Adobe", "Japan1", 6);
        try (PDDocument d = Loader.loadPDF(document(type0("K-UniJIS-UTF16-H", "UniJIS-UTF16-H", cid, null),
                new byte[] {0x20, 0x14}))) {
            PDType0Font font = (PDType0Font) d.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
            assertEquals("—", UnicodeGuess.of(font, 0x2014));
            assertEquals("−", UnicodeGuess.of(font, 0x2212));
            assertEquals("…", UnicodeGuess.of(font, 0x2026));
            assertEquals(new String(Character.toChars(0x20000)), UnicodeGuess.of(font, 0xD840DC00));
            assertEquals(null, UnicodeGuess.of(font, 0xD840), "a lone surrogate is not text");
        }
    }

    @Test
    void koreanThroughALegacyCMapIsNotGuessedFromAShiftedMap() throws Exception {
        for (COSName subtype : new COSName[] {COSName.CID_FONT_TYPE0, COSName.CID_FONT_TYPE2}) {
            COSDictionary cid = cidFont(subtype, "Batang", "Adobe", "Korea1", 2);
            try (PDDocument d = Loader.loadPDF(document(type0("Batang-KSCms-UHC-H", "KSCms-UHC-H", cid, null),
                    new byte[] {(byte) 0xBE, (byte) 0xC8}))) {
                PDType0Font font = (PDType0Font) d.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
                assertEquals(null, UnicodeGuess.of(font, 0xBEC8), subtype.getName());
                assertEquals("A", UnicodeGuess.of(font, 0x41), subtype.getName());
            }
            COSDictionary identity = cidFont(subtype, "Batang", "Adobe", "Korea1", 2);
            try (PDDocument d = Loader.loadPDF(document(type0("Batang", "Identity-H", identity, null),
                    new byte[] {0x00, 0x41}))) {
                PDType0Font font = (PDType0Font) d.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
                assertEquals(null, UnicodeGuess.of(font, 0x41), "an Identity code is a CID, not ASCII");
            }
        }
        COSDictionary uni = cidFont(COSName.CID_FONT_TYPE0, "Batang", "Adobe", "Korea1", 2);
        try (PDDocument d = Loader.loadPDF(document(type0("Batang-UniKS-UTF16-H", "UniKS-UTF16-H", uni, null),
                new byte[] {(byte) 0xC5, 0x48}))) {
            PDType0Font font = (PDType0Font) d.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
            assertEquals("안", UnicodeGuess.of(font, 0xC548));
        }
    }

    private Path embed(byte[] pdf, FontSet fonts, boolean complete) throws IOException {
        Path out = Files.createTempFile(dir, "cjk", ".pdf");
        try (PDDocument d = Loader.loadPDF(pdf)) {
            FontEmbedder.Result r = FontEmbedder.embedMissing(d, fonts);
            assertEquals(1, r.embedded().size(), r.embedded() + " / " + r.failed());
            assertEquals(complete, r.incomplete().isEmpty(), r.incomplete().toString());
            d.save(out.toFile());
        }
        return out;
    }

    /** Every shown code must draw a non-empty glyph from the embedded program. */
    private static void assertDrawn(Path pdf) throws IOException {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            PDResources res = d.getPage(0).getResources();
            PDType0Font font = (PDType0Font) res.getFont(COSName.getPDFName("F1"));
            assertTrue(font.isEmbedded());
            byte[] codes = shownBytes(d.getPage(0));
            int drawn = 0;
            for (int i = 0; i + 1 < codes.length; i += 2) {
                int code = (codes[i] & 0xFF) << 8 | codes[i + 1] & 0xFF;
                Rectangle2D box = ((PDVectorFont) font).getPath(code).getBounds2D();
                assertFalse(box.isEmpty(), "code " + Integer.toHexString(code) + " draws nothing");
                drawn++;
            }
            assertEquals(TEXT.length(), drawn);
            assertEquals(TEXT, new org.apache.pdfbox.text.PDFTextStripper().getText(d).strip());
        }
    }

    private static byte[] shownBytes(PDPage page) throws IOException {
        try (InputStream in = page.getContents()) {
            String s = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            String hex = s.substring(s.indexOf('<') + 1, s.indexOf('>'));
            byte[] b = new byte[hex.length() / 2];
            for (int i = 0; i < b.length; i++) {
                b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
            }
            return b;
        }
    }

    /** Acrobat's Asian font pack style: UniJIS-UTF16-H over Adobe-Japan1, no program, no ToUnicode. */
    private static byte[] predefined() throws IOException {
        COSDictionary cid = cidFont(COSName.CID_FONT_TYPE0, "KozMinPr6N-Regular", "Adobe", "Japan1", 6);
        return document(type0("KozMinPr6N-Regular-UniJIS-UTF16-H", "UniJIS-UTF16-H", cid, null),
                TEXT.getBytes(StandardCharsets.UTF_16BE));
    }

    /** Identity-H over glyph ids of a font that is not there, with the ToUnicode a word processor writes. */
    private static byte[] identity() throws IOException {
        COSDictionary cid = cidFont(COSName.CID_FONT_TYPE2, "MS-Gothic", "Adobe", "Identity", 0);
        cid.setItem(COSName.CID_TO_GID_MAP, COSName.IDENTITY);
        return identityDocument(cid, true);
    }

    private static byte[] identityWithoutUnicode() throws IOException {
        COSDictionary cid = cidFont(COSName.CID_FONT_TYPE2, "MS-Gothic", "Adobe", "Identity", 0);
        cid.setItem(COSName.CID_TO_GID_MAP, COSName.IDENTITY);
        return identityDocument(cid, false);
    }

    private static byte[] identityDocument(COSDictionary cid, boolean toUnicode) throws IOException {
        byte[] codes = new byte[TEXT.length() * 2];
        StringBuilder map = new StringBuilder();
        for (int i = 0; i < TEXT.length(); i++) {
            int gid = 1000 + i;
            codes[2 * i] = (byte) (gid >> 8);
            codes[2 * i + 1] = (byte) gid;
            map.append(String.format("<%04X> <%04X>%n", gid, (int) TEXT.charAt(i)));
        }
        String cmap = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n"
                + "/CMapName /Test def 1 begincodespacerange <0000> <FFFF> endcodespacerange\n"
                + TEXT.length() + " beginbfchar\n" + map + "endbfchar endcmap CMapName currentdict /CMap defineresource pop end end";
        return document(type0("MS-Gothic", "Identity-H", cid, toUnicode ? cmap : null), codes);
    }

    private static COSDictionary cidFont(COSName subtype, String name, String registry, String ordering, int supplement) {
        COSDictionary info = new COSDictionary();
        info.setString(COSName.REGISTRY, registry);
        info.setString(COSName.ORDERING, ordering);
        info.setInt(COSName.SUPPLEMENT, supplement);
        COSDictionary fd = new COSDictionary();
        fd.setItem(COSName.TYPE, COSName.FONT_DESC);
        fd.setName(COSName.FONT_NAME, name);
        fd.setInt(COSName.FLAGS, 6);
        COSArray box = new COSArray();
        for (int v : new int[] {-195, -272, 1110, 1075}) {
            box.add(COSInteger.get(v));
        }
        fd.setItem(COSName.FONT_BBOX, box);
        fd.setInt(COSName.ITALIC_ANGLE, 0);
        fd.setInt(COSName.ASCENT, 880);
        fd.setInt(COSName.DESCENT, -120);
        fd.setInt(COSName.CAP_HEIGHT, 763);
        fd.setInt(COSName.STEM_V, 80);
        COSDictionary cid = new COSDictionary();
        cid.setItem(COSName.TYPE, COSName.FONT);
        cid.setItem(COSName.SUBTYPE, subtype);
        cid.setName(COSName.BASE_FONT, name);
        cid.setItem(COSName.CIDSYSTEMINFO, info);
        cid.setItem(COSName.FONT_DESC, fd);
        cid.setInt(COSName.DW, 1000);
        return cid;
    }

    private static COSDictionary type0(String name, String encoding, COSDictionary cid, String toUnicode)
            throws IOException {
        COSDictionary t0 = new COSDictionary();
        t0.setItem(COSName.TYPE, COSName.FONT);
        t0.setItem(COSName.SUBTYPE, COSName.TYPE0);
        t0.setName(COSName.BASE_FONT, name);
        t0.setName(COSName.ENCODING, encoding);
        COSArray desc = new COSArray();
        desc.add(cid);
        t0.setItem(COSName.DESCENDANT_FONTS, desc);
        if (toUnicode != null) {
            COSStream s = new COSStream();
            try (OutputStream o = s.createOutputStream()) {
                o.write(toUnicode.getBytes(StandardCharsets.US_ASCII));
            }
            t0.setItem(COSName.TO_UNICODE, s);
        }
        return t0;
    }

    private static byte[] document(COSDictionary font, byte[] codes) throws IOException {
        try (PDDocument d = new PDDocument()) {
            PDPage page = new PDPage();
            d.addPage(page);
            PDResources res = new PDResources();
            COSDictionary fonts = new COSDictionary();
            fonts.setItem("F1", font);
            res.getCOSObject().setItem(COSName.FONT, fonts);
            page.setResources(res);
            StringBuilder hex = new StringBuilder();
            for (byte b : codes) {
                hex.append(String.format("%02X", b & 0xFF));
            }
            PDStream content = new PDStream(d);
            try (OutputStream o = content.createOutputStream()) {
                o.write(("BT /F1 20 Tf 72 700 Td <" + hex + "> Tj ET").getBytes(StandardCharsets.US_ASCII));
            }
            page.setContents(content);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            d.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] liberation() throws IOException {
        try (InputStream in = PDDocument.class.getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            return in.readAllBytes();
        }
    }

    /** A directory holding a CFF-outline CJK font: OFFICECONVERT_TEST_CFF_CJK, else Debian's Noto CJK. */
    private static Path cffCjkDirectory() throws IOException {
        String env = System.getenv("OFFICECONVERT_TEST_CFF_CJK");
        Path d = Path.of(env != null ? env : "/usr/share/fonts/opentype/noto");
        if (!Files.isDirectory(d)) {
            return null;
        }
        try (Stream<Path> s = Files.list(d)) {
            return s.anyMatch(p -> p.getFileName().toString().startsWith("NotoSansCJK")) ? d : null;
        }
    }
}

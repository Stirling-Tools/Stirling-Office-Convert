package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.apache.fontbox.cff.CFFParser;
import org.apache.fontbox.cff.CFFType1Font;
import org.apache.fontbox.ttf.GlyphData;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FontsTest {

    @TempDir
    Path dir;

    private static byte[] liberation() throws Exception {
        try (InputStream in = PDTrueTypeFont.class.getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            return in.readAllBytes();
        }
    }

    private static PDPage page(PDDocument d) {
        PDPage page = new PDPage(PDRectangle.A6);
        d.addPage(page);
        return page;
    }

    private static void show(PDDocument d, PDPage page, PDFont font, String text) throws Exception {
        try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
            cs.beginText();
            cs.setFont(font, 12);
            cs.newLineAtOffset(20, 200);
            cs.showText(text);
            cs.endText();
        }
    }

    private Path optimized(Path in) throws Exception {
        Path out = dir.resolve("out-" + in.getFileName());
        PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults().alwaysRewrite(true));
        return out;
    }

    private static PDFont onlyFont(PDDocument d) throws Exception {
        PDResources res = d.getPage(0).getResources();
        return res.getFont(res.getFontNames().iterator().next());
    }

    private static TrueTypeFont program(PDFontDescriptor fd) throws Exception {
        byte[] data;
        try (InputStream in = fd.getFontFile2().createInputStream()) {
            data = in.readAllBytes();
        }
        return new TTFParser(true).parse(new RandomAccessReadBuffer(data));
    }

    private static boolean outlined(TrueTypeFont font, int gid) throws Exception {
        GlyphData g = font.getGlyph().getGlyph(gid);
        return g != null && g.getNumberOfContours() != 0;
    }

    @Test
    void aFullyEmbeddedCidFontKeepsOnlyTheGlyphsItShows() throws Exception {
        Path in = dir.resolve("cid.pdf");
        int glyphs;
        try (PDDocument d = new PDDocument()) {
            PDPage page = page(d);
            PDType0Font font = PDType0Font.load(d, new ByteArrayInputStream(liberation()), false);
            show(d, page, font, "Hello");
            d.save(in.toFile());
        }
        try (TrueTypeFont whole = new TTFParser().parse(new RandomAccessReadBuffer(liberation()))) {
            glyphs = whole.getNumberOfGlyphs();
        }
        Path out = optimized(in);
        assertTrue(Files.size(out) * 2 < Files.size(in), Files.size(out) + " vs " + Files.size(in));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDType0Font font = (PDType0Font) onlyFont(d);
            assertEquals("Hello", new PDFTextStripper().getText(d).strip());
            try (TrueTypeFont kept = program(font.getDescendantFont().getFontDescriptor());
                    TrueTypeFont whole = new TTFParser().parse(new RandomAccessReadBuffer(liberation()))) {
                assertEquals(glyphs, kept.getNumberOfGlyphs());
                for (char c : "Helo".toCharArray()) {
                    assertTrue(outlined(kept, whole.getUnicodeCmapLookup().getGlyphId(c)), String.valueOf(c));
                }
                assertFalse(outlined(kept, whole.getUnicodeCmapLookup().getGlyphId('Z')));
                assertFalse(kept.getTableMap().containsKey("name"));
                assertFalse(kept.getTableMap().containsKey("cmap"));
                assertTrue(kept.getTableMap().containsKey("fpgm"));
            }
        }
    }

    @Test
    void aFontProgramLargerThanTheWindowBudgetIsStillSubset() throws Exception {
        Path in = dir.resolve("big.pdf");
        byte[] font = liberation();
        byte[] padded = java.util.Arrays.copyOf(font, (int) Rewrite.WINDOW_DECODED_BYTES + (1 << 20));
        try (PDDocument d = new PDDocument()) {
            PDPage page = page(d);
            PDType0Font f = PDType0Font.load(d, new ByteArrayInputStream(font), false);
            COSStream file = f.getDescendantFont().getFontDescriptor().getFontFile2().getCOSObject();
            try (OutputStream o = file.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(padded);
            }
            file.setInt(COSName.LENGTH1, padded.length);
            show(d, page, f, "Hello");
            d.save(in.toFile());
        }
        Path out = optimized(in);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDType0Font f = (PDType0Font) onlyFont(d);
            assertEquals("Hello", new PDFTextStripper().getText(d).strip());
            try (TrueTypeFont kept = program(f.getDescendantFont().getFontDescriptor());
                    TrueTypeFont whole = new TTFParser().parse(new RandomAccessReadBuffer(font))) {
                assertTrue(outlined(kept, whole.getUnicodeCmapLookup().getGlyphId('H')));
                assertFalse(outlined(kept, whole.getUnicodeCmapLookup().getGlyphId('Z')));
            }
        }
    }

    @Test
    void aSimpleTrueTypeFontKeepsItsCmapAndShownGlyphs() throws Exception {
        Path in = dir.resolve("simple.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage page = page(d);
            PDTrueTypeFont font = PDTrueTypeFont.load(d, new ByteArrayInputStream(liberation()),
                    WinAnsiEncoding.INSTANCE);
            show(d, page, font, "Hi there");
            d.save(in.toFile());
        }
        Path out = optimized(in);
        assertTrue(Files.size(out) * 2 < Files.size(in), Files.size(out) + " vs " + Files.size(in));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDTrueTypeFont font = (PDTrueTypeFont) onlyFont(d);
            assertEquals("Hi there", new PDFTextStripper().getText(d).strip());
            try (TrueTypeFont kept = program(font.getFontDescriptor())) {
                assertTrue(kept.getTableMap().containsKey("cmap"));
                for (char c : "Hiter".toCharArray()) {
                    assertTrue(outlined(kept, font.codeToGID(c)), String.valueOf(c));
                }
                assertFalse(outlined(kept, kept.getUnicodeCmapLookup().getGlyphId('Z')));
            }
        }
    }

    @Test
    void aFontTheFormDefaultsAlsoNameIsLeftWhole() throws Exception {
        Path in = dir.resolve("form.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage page = page(d);
            PDType0Font font = PDType0Font.load(d, new ByteArrayInputStream(liberation()), false);
            show(d, page, font, "Hello");
            PDAcroForm form = new PDAcroForm(d);
            PDResources dr = new PDResources();
            dr.put(COSName.getPDFName("F9"), font);
            form.setDefaultResources(dr);
            d.getDocumentCatalog().setAcroForm(form);
            d.save(in.toFile());
        }
        assertWhole(optimized(in), 'Z');
    }

    @Test
    void textAFormShowsWithTheCallersFontLeavesFontsWhole() throws Exception {
        Path in = dir.resolve("inherit.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage page = page(d);
            PDType0Font font = PDType0Font.load(d, new ByteArrayInputStream(liberation()), false);
            PDFormXObject form = new PDFormXObject(d);
            form.setBBox(new PDRectangle(200, 200));
            form.setResources(new PDResources());
            try (OutputStream o = form.getContentStream().createOutputStream()) {
                o.write("BT 10 10 Td <003D> Tj ET".getBytes(StandardCharsets.ISO_8859_1));
            }
            try (PDPageContentStream cs = new PDPageContentStream(d, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.showText("Hello");
                cs.endText();
                cs.drawForm(form);
            }
            d.save(in.toFile());
        }
        assertWhole(optimized(in), 'Z');
    }

    private static void assertWhole(Path out, char unused) throws Exception {
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDType0Font font = (PDType0Font) onlyFont(d);
            try (TrueTypeFont kept = program(font.getDescendantFont().getFontDescriptor());
                    TrueTypeFont whole = new TTFParser().parse(new RandomAccessReadBuffer(liberation()))) {
                assertTrue(outlined(kept, whole.getUnicodeCmapLookup().getGlyphId(unused)));
            }
        }
    }

    @Test
    void anEmbeddedType1FontBecomesASmallerCffWithTheShownGlyphs() throws Exception {
        Type1 program = type1("TestType", 40);
        Path in = dir.resolve("type1.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage page = page(d);
            COSStream file = d.getDocument().createCOSStream();
            try (OutputStream o = file.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(program.data());
            }
            file.setInt(COSName.LENGTH1, program.length1());
            file.setInt(COSName.LENGTH2, program.length2());
            file.setInt(COSName.LENGTH3, program.length3());
            COSDictionary fd = new COSDictionary();
            fd.setItem(COSName.TYPE, COSName.FONT_DESC);
            fd.setName(COSName.FONT_NAME, "TestType");
            fd.setInt(COSName.FLAGS, 32);
            fd.setItem(COSName.FONT_BBOX, new PDRectangle(0, -10, 900, 770).getCOSArray());
            fd.setInt(COSName.ITALIC_ANGLE, 0);
            fd.setInt(COSName.ASCENT, 760);
            fd.setInt(COSName.DESCENT, -10);
            fd.setInt(COSName.CAP_HEIGHT, 700);
            fd.setInt(COSName.STEM_V, 80);
            fd.setItem(COSName.FONT_FILE, file);
            COSDictionary dict = new COSDictionary();
            dict.setItem(COSName.TYPE, COSName.FONT);
            dict.setItem(COSName.SUBTYPE, COSName.TYPE1);
            dict.setName(COSName.BASE_FONT, "TestType");
            dict.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
            dict.setInt(COSName.FIRST_CHAR, 32);
            dict.setInt(COSName.LAST_CHAR, 126);
            COSArray widths = new COSArray();
            for (int c = 32; c <= 126; c++) {
                widths.add(COSInteger.get(600));
            }
            dict.setItem(COSName.WIDTHS, widths);
            dict.setItem(COSName.FONT_DESC, fd);
            PDType1Font font = new PDType1Font(dict);
            show(d, page, font, "HELLO");
            d.save(in.toFile());
        }
        Path out = optimized(in);
        assertTrue(Files.size(out) < Files.size(in), Files.size(out) + " vs " + Files.size(in));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDFont font = onlyFont(d);
            assertEquals("HELLO", new PDFTextStripper().getText(d).strip());
            PDFontDescriptor fd = font.getFontDescriptor();
            assertNull(fd.getFontFile());
            assertNotNull(fd.getFontFile3());
            assertEquals("Type1C", fd.getFontFile3().getCOSObject().getNameAsString(COSName.SUBTYPE));
            byte[] cff;
            try (InputStream s = fd.getFontFile3().createInputStream()) {
                cff = s.readAllBytes();
            }
            CFFType1Font parsed = (CFFType1Font) new CFFParser().parse(new RandomAccessReadBuffer(cff)).get(0);
            for (String g : List.of("H", "E", "L", "O")) {
                assertFalse(parsed.getPath(g).getBounds2D().isEmpty(), g);
            }
            for (String g : List.of("A", "Z", "n")) {
                assertTrue(parsed.getPath(g).getBounds2D().isEmpty(), g);
            }
        }
    }

    @Test
    void aFontProgramCutOffByTheEndOfTheFileIsRecovered() throws Exception {
        byte[] font = liberation();
        int cut = essentialEnd(font) + 16;
        assertTrue(cut < font.length - 64, cut + " of " + font.length);
        RawPdf raw = new RawPdf();
        raw.add("<</Type/Catalog/Pages 2 0 R>>");
        raw.add("<</Type/Pages/Kids[3 0 R]/Count 1>>");
        raw.add("<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]/Resources<</Font<</F1 5 0 R>>>>/Contents 4 0 R>>");
        raw.add(RawPdf.stream("", "BT /F1 24 Tf 20 100 Td <0024> Tj ET"));
        raw.add("<</Type/Font/Subtype/Type0/BaseFont/LiberationSans/Encoding/Identity-H/DescendantFonts[6 0 R]>>");
        raw.add("<</Type/Font/Subtype/CIDFontType2/BaseFont/LiberationSans/CIDSystemInfo<</Registry(Adobe)"
                + "/Ordering(Identity)/Supplement 0>>/FontDescriptor 7 0 R/CIDToGIDMap/Identity>>");
        raw.add("<</Type/FontDescriptor/FontName/LiberationSans/Flags 32/FontBBox[-200 -300 1200 1000]"
                + "/ItalicAngle 0/Ascent 900/Descent -200/CapHeight 700/StemV 80/FontFile2 8 0 R>>");
        String whole = new String(raw.bytes(), StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.writeBytes(whole.substring(0, whole.indexOf("xref\n")).getBytes(StandardCharsets.ISO_8859_1));
        file.writeBytes(("8 0 obj\n<</Length1 " + font.length + "/Length 9 0 R>>\nstream\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        file.write(font, 0, cut);
        Path in = dir.resolve("cut.pdf");
        Files.write(in, file.toByteArray());
        Path out = dir.resolve("cut-out.pdf");
        PdfOptimizer.Result result = PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("1 font program cut off by the end of the file"
                + " was recovered")), String.valueOf(result.warnings()));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDType0Font f = (PDType0Font) onlyFont(d);
            assertTrue(f.isEmbedded());
            try (TrueTypeFont kept = program(f.getDescendantFont().getFontDescriptor())) {
                assertTrue(outlined(kept, 0x24));
            }
        }
    }

    @Test
    void aFontProgramCutBeforeItsOutlinesIsNotRecovered() throws Exception {
        byte[] font = liberation();
        int glyf = table(font, "glyf")[0];
        assertNull(Sfnt.complete(java.util.Arrays.copyOf(font, glyf + 100)));
        assertNotNull(Sfnt.complete(java.util.Arrays.copyOf(font, essentialEnd(font))));
    }

    private static int[] table(byte[] font, String tag) {
        ByteBuffer b = ByteBuffer.wrap(font);
        for (int i = 0; i < (b.getShort(4) & 0xFFFF); i++) {
            int r = 12 + 16 * i;
            if (new String(font, r, 4, StandardCharsets.ISO_8859_1).equals(tag)) {
                return new int[] {b.getInt(r + 8), b.getInt(r + 12)};
            }
        }
        throw new AssertionError(tag);
    }

    private static int essentialEnd(byte[] font) {
        int end = 0;
        for (String tag : Set.of("head", "hhea", "maxp", "hmtx", "loca", "glyf")) {
            int[] t = table(font, tag);
            end = Math.max(end, t[0] + t[1]);
        }
        return end;
    }

    private record Type1(byte[] data, int length1, int length2, int length3) {}

    private static Type1 type1(String fontName, int fillers) {
        String clear = "%!PS-AdobeFont-1.0: " + fontName + " 001\n12 dict begin\n/FontInfo 2 dict dup begin"
                + " /FullName (" + fontName + ") readonly def end readonly def\n/FontName /" + fontName + " def\n"
                + "/Encoding StandardEncoding def\n/PaintType 0 def\n/FontType 1 def\n"
                + "/FontMatrix [0.001 0 0 0.001 0 0] readonly def\n/FontBBox {0 -10 900 760} readonly def\n"
                + "currentdict end\ncurrentfile eexec\n";
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        write(plain, "dup /Private 9 dict dup begin\n/RD{string currentfile exch readstring pop}executeonly def\n"
                + "/ND{noaccess def}executeonly def\n/NP{noaccess put}executeonly def\n/lenIV 4 def\n"
                + "/BlueValues [] def\n/MinFeature{16 16}def\n/password 5839 def\n/Subrs 1 array\n");
        byte[] ret = charstring(new byte[] {11});
        write(plain, "dup 0 " + ret.length + " RD ");
        plain.writeBytes(ret);
        write(plain, " NP\nND\n2 index /CharStrings " + (fillers + 1) + " dict dup begin\n");
        glyph(plain, ".notdef", concat(num(0), num(250), new byte[] {13, 14}));
        for (int i = 0; i < fillers; i++) {
            String name = i < 26 ? String.valueOf((char) ('A' + i)) : String.valueOf((char) ('a' + i - 26));
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.writeBytes(concat(num(0), num(500 + i), new byte[] {13}, num(50), num(0), new byte[] {21}));
            for (int k = 0; k < 60; k++) {
                body.writeBytes(concat(num((k * 7 + i) % 90 - 45), num((k * 13 + i) % 80 - 40), new byte[] {5}));
            }
            body.writeBytes(new byte[] {9, 14});
            glyph(plain, name, body.toByteArray());
        }
        write(plain, "end\nend\nreadonly put\nnoaccess put\ndup/FontName get exch definefont pop\n"
                + "mark currentfile closefile\n");
        byte[] head = clear.getBytes(StandardCharsets.ISO_8859_1);
        byte[] encrypted = encrypt(concat(new byte[4], plain.toByteArray()), 55665);
        byte[] tail = (("0".repeat(64) + "\n").repeat(8) + "cleartomark\n").getBytes(StandardCharsets.ISO_8859_1);
        return new Type1(concat(head, encrypted, tail), head.length, encrypted.length, tail.length);
    }

    private static void glyph(ByteArrayOutputStream plain, String name, byte[] body) {
        byte[] enc = charstring(body);
        write(plain, "/" + name + " " + enc.length + " RD ");
        plain.writeBytes(enc);
        write(plain, " ND\n");
    }

    private static byte[] charstring(byte[] plain) {
        return encrypt(concat(new byte[4], plain), 4330);
    }

    private static byte[] num(int v) {
        if (v >= -107 && v <= 107) {
            return new byte[] {(byte) (v + 139)};
        }
        if (v >= 108 && v <= 1131) {
            int w = v - 108;
            return new byte[] {(byte) (w / 256 + 247), (byte) (w % 256)};
        }
        int w = -v - 108;
        return new byte[] {(byte) (w / 256 + 251), (byte) (w % 256)};
    }

    private static byte[] encrypt(byte[] plain, int key) {
        int r = key;
        byte[] out = new byte[plain.length];
        for (int i = 0; i < plain.length; i++) {
            int c = (plain[i] & 0xFF) ^ (r >> 8);
            out[i] = (byte) c;
            r = ((c + r) * 52845 + 22719) & 0xFFFF;
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            o.writeBytes(p);
        }
        return o.toByteArray();
    }

    private static void write(ByteArrayOutputStream o, String s) {
        o.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}

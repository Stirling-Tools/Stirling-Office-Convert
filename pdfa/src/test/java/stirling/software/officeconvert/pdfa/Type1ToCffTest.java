package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.cff.CFFParser;
import org.apache.fontbox.cff.CFFType1Font;
import org.apache.fontbox.type1.Type1Font;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class Type1ToCffTest {

    private static final int FILLERS = 40;

    @TempDir
    Path dir;

    private record Program(byte[] data, int length1, int length2, int length3) {}

    private static byte[] ops(Object... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (Object p : parts) {
            if (p instanceof Integer i) {
                o.writeBytes(FontPrograms.num(i));
            } else {
                o.writeBytes((byte[]) p);
            }
        }
        return o.toByteArray();
    }

    private static final byte[] HSBW = {13};
    private static final byte[] HSTEM = {1};
    private static final byte[] VSTEM = {3};
    private static final byte[] RMOVETO = {21};
    private static final byte[] RLINETO = {5};
    private static final byte[] CLOSEPATH = {9};
    private static final byte[] ENDCHAR = {14};
    private static final byte[] DIV = {12, 12};
    private static final byte[] SEAC = {12, 6};

    private static final Map<String, byte[]> GLYPHS = glyphs();

    private static byte[] box(int w, int h) {
        return ops(w, 0, RLINETO, 0, h, RLINETO, -w, 0, RLINETO, CLOSEPATH);
    }

    private static Map<String, byte[]> glyphs() {
        Map<String, byte[]> g = new LinkedHashMap<>();
        g.put(".notdef", ops(0, 250, HSBW, ENDCHAR));
        g.put("A", ops(20, 600, HSBW, 0, 0, RMOVETO, 280, 700, RLINETO, 280, -700, RLINETO, CLOSEPATH, ENDCHAR));
        g.put("acute", ops(100, 300, HSBW, 0, 750, RMOVETO, 100, 0, RLINETO, 60, 120, RLINETO, CLOSEPATH, ENDCHAR));
        g.put("Aacute", ops(20, 600, HSBW, 100, 220, 40, 65, 194, SEAC));
        g.put("H", ops(40, 700, HSBW, 0, 50, HSTEM, 600, 50, HSTEM, 40, 60, VSTEM, 500, 60, VSTEM, 0, 0, RMOVETO,
                box(560, 650), ENDCHAR));
        g.put("I", ops(30, 300, HSBW, 0, 30, HSTEM, 10, 0, RMOVETO, 101, 2, DIV, 0, RLINETO, 0, 650, RLINETO, -50, 0,
                RLINETO, CLOSEPATH, ENDCHAR));
        g.put("O", ops(30, 650, HSBW, 0, 100, HSTEM, 50, 100, HSTEM, 0, 0, RMOVETO, box(590, 700), ENDCHAR));
        g.put("L", ops(30, 550, HSBW, 0, 0, RMOVETO, 0, 40, HSTEM, box(480, 680), ENDCHAR));
        for (int i = 0; i < FILLERS; i++) {
            byte[] body = new byte[0];
            for (int k = 0; k < 60; k++) {
                body = ops(body, (k * 7 + i) % 90 - 45, (k * 13 + i) % 80 - 40, RLINETO);
            }
            g.put("g" + i, ops(10, 500, HSBW, 0, 0, RMOVETO, body, CLOSEPATH, ENDCHAR));
        }
        return g;
    }

    private static Program program(String fontName) {
        String clear = "%!PS-AdobeFont-1.0: " + fontName + " 001\n12 dict begin\n/FontInfo 4 dict dup begin"
                + " /FullName (" + fontName + " Regular) readonly def /FamilyName (" + fontName + ") readonly def"
                + " /ItalicAngle -12.5 def end readonly def\n/FontName /" + fontName + " def\n"
                + "/Encoding StandardEncoding def\n/PaintType 0 def\n/FontType 1 def\n"
                + "/FontMatrix [0.001 0 0 0.001 0 0] readonly def\n/FontBBox {0 -10 900 900} readonly def\n"
                + "currentdict end\ncurrentfile eexec\n";
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        plain.writeBytes(("dup /Private 12 dict dup begin\n/RD{string currentfile exch readstring pop}executeonly def\n"
                + "/ND{noaccess def}executeonly def\n/NP{noaccess put}executeonly def\n/lenIV 4 def\n"
                + "/BlueValues [-10 0 700 710] def\n/StdHW [50] def\n/StdVW [60] def\n/BlueScale 0.0437917 def\n"
                + "/StemSnapH [50 100] def\n/MinFeature{16 16}def\n/password 5839 def\n/Subrs 4 array\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        for (int i = 0; i < 4; i++) {
            byte[] enc = FontPrograms.charstring(new byte[] {11});
            plain.writeBytes(("dup " + i + " " + enc.length + " RD ").getBytes(StandardCharsets.ISO_8859_1));
            plain.writeBytes(enc);
            plain.writeBytes(" NP\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        plain.writeBytes(("ND\n2 index /CharStrings " + GLYPHS.size() + " dict dup begin\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        for (Map.Entry<String, byte[]> e : GLYPHS.entrySet()) {
            byte[] enc = FontPrograms.charstring(e.getValue());
            plain.writeBytes(("/" + e.getKey() + " " + enc.length + " RD ").getBytes(StandardCharsets.ISO_8859_1));
            plain.writeBytes(enc);
            plain.writeBytes(" ND\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        plain.writeBytes(("end\nend\nreadonly put\nnoaccess put\ndup/FontName get exch definefont pop\n"
                + "mark currentfile closefile\n").getBytes(StandardCharsets.ISO_8859_1));
        byte[] head = clear.getBytes(StandardCharsets.ISO_8859_1);
        byte[] encrypted = FontPrograms.eexec(plain.toByteArray());
        byte[] tail = (("0".repeat(64) + "\n").repeat(8) + "cleartomark\n").getBytes(StandardCharsets.ISO_8859_1);
        return new Program(FontPrograms.concat(head, encrypted, tail), head.length, encrypted.length, tail.length);
    }

    private static Type1Font type1(Program p) throws Exception {
        return Type1Font.createWithSegments(Arrays.copyOfRange(p.data(), 0, p.length1()),
                Arrays.copyOfRange(p.data(), p.length1(), p.length1() + p.length2()));
    }

    private static CFFType1Font parse(byte[] cff) throws Exception {
        List<CFFFont> fonts = new CFFParser().parse(new RandomAccessReadBuffer(cff));
        assertEquals(1, fonts.size());
        return (CFFType1Font) fonts.get(0);
    }

    private static List<Integer> numbers(Object list) {
        return ((List<?>) list).stream().map(n -> ((Number) n).intValue()).toList();
    }

    private static byte[] charString(CFFType1Font cff, String name) {
        return cff.getCharStringBytes().get(cff.nameToGID(name));
    }

    private static boolean hasStems(byte[] cs) {
        for (int i = 0; i < cs.length;) {
            int v = cs[i] & 0xFF;
            if (v == 28) {
                i += 3;
            } else if (v >= 32 && v <= 246) {
                i++;
            } else if (v >= 247 && v <= 254) {
                i += 2;
            } else if (v == 255) {
                i += 5;
            } else {
                if (v == 1 || v == 3) {
                    return true;
                }
                i += v == 12 ? 2 : 1;
            }
        }
        return false;
    }

    private static boolean hasFixed(byte[] cs) {
        for (int i = 0; i < cs.length;) {
            int v = cs[i] & 0xFF;
            if (v == 255) {
                return true;
            }
            i += v == 28 ? 3 : v >= 247 && v <= 254 ? 2 : v == 12 ? 2 : 1;
        }
        return false;
    }

    @Test
    void outlinesWidthsCharsetAndEncodingSurvive() throws Exception {
        Type1Font t1 = type1(program("TestT1C"));
        Map<Integer, String> codes = Map.of(65, "A", 72, "H", 73, "I", 76, "L", 79, "O", 200, "Aacute", 201, "H");
        Set<String> used = Set.of("A", "H", "I", "L", "O", "Aacute");
        CFFType1Font cff = parse(Type1ToCff.convert(t1, codes, used));
        assertEquals("TestT1C", cff.getName());
        assertEquals(GLYPHS.size(), cff.getNumCharStrings());
        assertEquals(0, cff.nameToGID(".notdef"));
        for (String g : GLYPHS.keySet()) {
            assertTrue(".notdef".equals(g) || cff.hasGlyph(g), g);
            assertEquals(t1.getWidth(g), cff.getWidth(g), 0.001, g);
            Rectangle2D want = t1.getPath(g).getBounds2D();
            Rectangle2D got = cff.getPath(g).getBounds2D();
            if (used.contains(g)) {
                assertEquals(want.getMinX(), got.getMinX(), 1e-4, g);
                assertEquals(want.getMinY(), got.getMinY(), 1e-4, g);
                assertEquals(want.getMaxX(), got.getMaxX(), 1e-4, g);
                assertEquals(want.getMaxY(), got.getMaxY(), 1e-4, g);
            } else {
                assertTrue(got.isEmpty(), g + " keeps an outline it does not use");
            }
        }
        assertEquals("H", cff.getEncoding().getName(72));
        assertEquals("H", cff.getEncoding().getName(201));
        assertEquals("Aacute", cff.getEncoding().getName(200));
        assertTrue(cff.getPath("Aacute").getBounds2D().getMaxY() > 800, "accent of the seac glyph is drawn");
        assertEquals(-12.5, ((Number) cff.getTopDict().get("ItalicAngle")).doubleValue(), 1e-6);
        assertEquals(List.of(-10, 0, 700, 710), numbers(cff.getPrivateDict().get("BlueValues")));
        assertEquals(List.of(50, 100), numbers(cff.getPrivateDict().get("StemSnapH")));
        assertEquals(List.of(50), numbers(List.of(cff.getPrivateDict().get("StdHW"))));
        assertEquals(0.0437917, ((Number) cff.getPrivateDict().get("BlueScale")).doubleValue(), 1e-7);
    }

    @Test
    void stemHintsAreKeptOnlyWhereType2CanCarryThem() throws Exception {
        Type1Font t1 = type1(program("TestHints"));
        CFFType1Font cff = parse(Type1ToCff.convert(t1, Map.of(72, "H", 73, "I", 76, "L", 79, "O"),
                Set.of("H", "I", "L", "O")));
        byte[] h = charString(cff, "H");
        byte[] prefix = ops(700, 0, 50, 550, 50, HSTEM, 80, 60, 400, 60, VSTEM);
        assertArrayEquals(prefix, Arrays.copyOf(h, prefix.length));
        assertTrue(hasStems(charString(cff, "I")));
        assertTrue(hasFixed(charString(cff, "I")), "the div result is kept as a fraction");
        assertFalse(hasStems(charString(cff, "O")), "overlapping stems need a hintmask");
        assertFalse(hasStems(charString(cff, "L")), "stems set after drawing are hint replacement");
    }

    @Test
    void unsupportedFontsAreLeftAlone() throws Exception {
        assertNull(Type1ToCff.convert(null, Map.of(), Set.of()));
        Type1Font t1 = type1(program("TestMissing"));
        assertNotNull(Type1ToCff.convert(t1, Map.of(65, "A"), Set.of("A")));
    }

    private Path pdf(Program p) throws Exception {
        Path in = dir.resolve("t1.pdf");
        try (PDDocument d = new PDDocument()) {
            COSStream file = d.getDocument().createCOSStream();
            try (OutputStream o = file.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(p.data());
            }
            file.setInt(COSName.LENGTH1, p.length1());
            file.setInt(COSName.LENGTH2, p.length2());
            file.setInt(COSName.LENGTH3, p.length3());
            COSDictionary f = new COSDictionary();
            f.setItem(COSName.TYPE, COSName.FONT);
            f.setItem(COSName.SUBTYPE, COSName.TYPE1);
            f.setName(COSName.BASE_FONT, "TestT1C");
            COSDictionary enc = new COSDictionary();
            COSArray diffs = new COSArray();
            diffs.add(COSInteger.get(200));
            diffs.add(COSName.getPDFName("Aacute"));
            enc.setItem(COSName.DIFFERENCES, diffs);
            f.setItem(COSName.ENCODING, enc);
            f.setInt(COSName.FIRST_CHAR, 65);
            f.setInt(COSName.LAST_CHAR, 200);
            COSArray widths = new COSArray();
            Type1Font t1 = type1(p);
            for (int c = 65; c <= 200; c++) {
                String g = c == 200 ? "Aacute" : org.apache.fontbox.encoding.StandardEncoding.INSTANCE.getName(c);
                widths.add(COSInteger.get(t1.hasGlyph(g) ? Math.round(t1.getWidth(g)) : 0));
            }
            f.setItem(COSName.WIDTHS, widths);
            PDFontDescriptor fd = new PDFontDescriptor(new COSDictionary());
            fd.setFontName("TestT1C");
            fd.setFlags(32);
            fd.setFontBoundingBox(new PDRectangle(0, -10, 900, 910));
            fd.setItalicAngle(-12.5f);
            fd.setAscent(900);
            fd.setDescent(-10);
            fd.setCapHeight(700);
            fd.setStemV(60);
            fd.getCOSObject().setItem(COSName.FONT_FILE, file);
            f.setItem(COSName.FONT_DESC, fd.getCOSObject());
            PDPage page = Samples.page(d);
            PDResources res = new PDResources();
            res.put(COSName.getPDFName("T"), PDFontFactory.createFont(f));
            page.setResources(res);
            Samples.raw(page, d, "BT /T 40 Tf 50 700 Td (AHIOL) Tj <C8> Tj ET");
            d.save(in.toFile());
        }
        return in;
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1B", "A2B", "A3B"})
    void pdfaWritesType1ProgramsAsCompactCff(PdfALevel level) throws Exception {
        Path in = pdf(program("TestT1C"));
        Path out = dir.resolve("out-" + level + ".pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(out, level);
        long before = OutlineCompactionTest.program(in);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSDictionary f = d.getPage(0).getResources().getFont(COSName.getPDFName("T")).getCOSObject();
            COSDictionary fd = (COSDictionary) f.getDictionaryObject(COSName.FONT_DESC);
            assertNull(fd.getDictionaryObject(COSName.FONT_FILE));
            COSStream file3 = (COSStream) fd.getDictionaryObject(COSName.FONT_FILE3);
            assertEquals(COSName.getPDFName("Type1C"), file3.getCOSName(COSName.SUBTYPE));
            try (InputStream s = file3.createInputStream()) {
                byte[] cff = s.readAllBytes();
                assertTrue(cff.length < before / 3, before + " to " + cff.length);
                assertEquals(GLYPHS.size(), parse(cff).getNumCharStrings());
            }
            assertEquals(new PDFTextStripper().getText(Loader.loadPDF(in.toFile())), new PDFTextStripper().getText(d));
        }
        assertTrue(ColourRenderingTest.dark(ColourRenderingTest.render(in)) > 100);
        assertEquals(0, ColourRenderingTest.differing(ColourRenderingTest.render(in), ColourRenderingTest.render(out)),
                0.0005);
    }
}

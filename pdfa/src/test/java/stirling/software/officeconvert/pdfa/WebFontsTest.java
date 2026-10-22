package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.cff.CFFParser;
import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.junit.jupiter.api.Test;

class WebFontsTest {

    private static TrueTypeFont parse(byte[] ttf) throws IOException {
        return new TTFParser().parse(new RandomAccessReadBuffer(ttf));
    }

    private static Font awt(byte[] ttf) throws Exception {
        return Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(ttf));
    }

    private static int codepoint(String glyph) {
        return GlyphList.getAdobeGlyphList().toUnicode(glyph).codePointAt(0);
    }

    @Test
    void cffKeepsEveryGlyphItsAdvanceAndTheGlyphNameUnicode() throws Exception {
        byte[] ttf = WebFonts.toTrueType(FontPrograms.cff("TestCff"), WebFonts.FontKind.CFF, Map.of());
        assertTrue(TrueTypeSanity.sane(ttf));
        assertEquals(0x00010000, java.nio.ByteBuffer.wrap(ttf).getInt(0));
        try (TrueTypeFont t = parse(ttf)) {
            assertEquals(FontPrograms.NAMES.size() + 1, t.getNumberOfGlyphs());
            CmapLookup cmap = t.getUnicodeCmapLookup();
            for (int gid = 1; gid <= FontPrograms.NAMES.size(); gid++) {
                String name = FontPrograms.NAMES.get(gid - 1);
                assertEquals(gid, cmap.getGlyphId(codepoint(name)), name);
                int expected = "Aacute".equals(name) ? FontPrograms.width("A") : FontPrograms.width(name);
                assertEquals(expected, t.getAdvanceWidth(gid), name);
            }
            assertEquals(1000, t.getUnitsPerEm());
            assertTrue(t.getTableMap().containsKey("OS/2"));
        }
        Font f = awt(ttf);
        assertTrue(f.canDisplayUpTo("ABCxyz") < 0);
    }

    @Test
    void explicitMappingWinsAndReachesBeyondTheBmp() throws Exception {
        int a = FontPrograms.NAMES.indexOf("A") + 1;
        int b = FontPrograms.NAMES.indexOf("B") + 1;
        Map<Integer, Integer> mapping = Map.of(a, 0x1F600, b, 0x0042);
        byte[] ttf = WebFonts.toTrueType(FontPrograms.cff("TestCff"), WebFonts.FontKind.CFF, mapping);
        try (TrueTypeFont t = parse(ttf)) {
            assertEquals(a, t.getUnicodeCmapLookup().getGlyphId(0x1F600));
            assertEquals(a, t.getUnicodeCmapLookup().getGlyphId('A'));
            assertEquals(b, t.getUnicodeCmapLookup().getGlyphId('B'));
            assertTrue(t.getCmap().getSubtable(3, 10) != null);
        }
        assertTrue(awt(ttf).canDisplay(0x1F600));
    }

    @Test
    void cidKeyedCffMapsByGlyphAndKeepsTheFontDictWidths() throws Exception {
        byte[] cff = FontPrograms.cidCff("TestCid");
        Map<Integer, Integer> mapping = new HashMap<>();
        for (int gid = 1; gid <= 26; gid++) {
            mapping.put(gid, 'a' + gid - 1);
        }
        byte[] ttf = WebFonts.toTrueType(cff, WebFonts.FontKind.CFF, mapping);
        try (TrueTypeFont t = parse(ttf)) {
            assertEquals(FontPrograms.NAMES.size() + 1, t.getNumberOfGlyphs());
            assertEquals(3, t.getUnicodeCmapLookup().getGlyphId('c'));
            assertEquals(500, t.getAdvanceWidth(3));
            assertEquals(0, t.getUnicodeCmapLookup().getGlyphId('A'));
        }
        assertTrue(awt(ttf).canDisplayUpTo("abcxyz") < 0);
    }

    @Test
    void type1ProgramsConvertWithOrWithoutSegmentLengths() throws Exception {
        FontPrograms.Type1 t1 = FontPrograms.type1("TestType", 3);
        byte[] ttf = WebFonts.toTrueType(t1.data(), WebFonts.FontKind.TYPE1, null);
        try (TrueTypeFont t = parse(ttf)) {
            assertEquals(FontPrograms.NAMES.size() + 1, t.getNumberOfGlyphs());
            int g = t.getUnicodeCmapLookup().getGlyphId('Q');
            assertTrue(g > 0);
            assertEquals(FontPrograms.width("Q"), t.getAdvanceWidth(g));
            assertEquals(250, t.getAdvanceWidth(t.getUnicodeCmapLookup().getGlyphId(' ')));
        }
        try (PDDocument d = new PDDocument()) {
            PDFont font = PDFontFactory.createFont(OutlineCompactionTest.font(d, false));
            assertArrayEquals(ttf, WebFonts.toTrueType(font));
        }
    }

    @Test
    void pdfBoxSimpleCffFontsTakeTheirCmapFromTheFontEncoding() throws Exception {
        try (PDDocument d = new PDDocument()) {
            COSDictionary dict = OutlineCompactionTest.font(d, true);
            byte[] ttf = WebFonts.toTrueType(PDFontFactory.createFont(dict));
            try (TrueTypeFont t = parse(ttf)) {
                int gid = t.getUnicodeCmapLookup().getGlyphId(0xC1);
                assertEquals(FontPrograms.NAMES.indexOf("Aacute") + 1, gid);
                assertEquals(FontPrograms.width("A"), t.getAdvanceWidth(gid));
            }
        }
    }

    @Test
    void pdfBoxType0FontsMapCodesThroughTheirToUnicode() throws Exception {
        try (PDDocument d = new PDDocument()) {
            COSStream file = d.getDocument().createCOSStream();
            try (OutputStream o = file.createOutputStream(COSName.FLATE_DECODE)) {
                o.write(FontPrograms.cidCff("TestCid"));
            }
            file.setItem(COSName.SUBTYPE, COSName.getPDFName("CIDFontType0C"));
            PDFontDescriptor fd = new PDFontDescriptor(new COSDictionary());
            fd.setFontName("TestCid");
            fd.setFlags(4);
            fd.getCOSObject().setItem(COSName.FONT_FILE3, file);
            COSDictionary cid = new COSDictionary();
            cid.setItem(COSName.TYPE, COSName.FONT);
            cid.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE0);
            cid.setName(COSName.BASE_FONT, "TestCid");
            COSDictionary info = new COSDictionary();
            info.setString(COSName.REGISTRY, "Adobe");
            info.setString(COSName.ORDERING, "Identity");
            info.setInt(COSName.SUPPLEMENT, 0);
            cid.setItem(COSName.CIDSYSTEMINFO, info);
            cid.setItem(COSName.FONT_DESC, fd.getCOSObject());
            COSDictionary t0 = new COSDictionary();
            t0.setItem(COSName.TYPE, COSName.FONT);
            t0.setItem(COSName.SUBTYPE, COSName.TYPE0);
            t0.setName(COSName.BASE_FONT, "TestCid");
            t0.setItem(COSName.ENCODING, COSName.IDENTITY_H);
            COSArray kids = new COSArray();
            kids.add(cid);
            t0.setItem(COSName.DESCENDANT_FONTS, kids);
            COSStream toUnicode = d.getDocument().createCOSStream();
            try (OutputStream o = toUnicode.createOutputStream()) {
                o.write(("/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n"
                        + "1 begincodespacerange <0000> <FFFF> endcodespacerange\n"
                        + "2 beginbfchar <0002> <0058> <0007> <D83DDE00> endbfchar\n"
                        + "1 beginbfrange <0003> <0005> <0061> endbfrange\n"
                        + "endcmap CMapName currentdict /CMap defineresource pop end end\n")
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            t0.setItem(COSName.TO_UNICODE, toUnicode);
            byte[] ttf = WebFonts.toTrueType(PDFontFactory.createFont(t0));
            try (TrueTypeFont t = parse(ttf)) {
                CmapLookup cmap = t.getUnicodeCmapLookup();
                assertEquals(2, cmap.getGlyphId('X'));
                assertEquals(3, cmap.getGlyphId('a'));
                assertEquals(5, cmap.getGlyphId('c'));
                assertEquals(7, cmap.getGlyphId(0x1F600));
            }
        }
    }

    @Test
    void outlinesRenderLikeTheSourceProgram() throws Exception {
        byte[] cff = FontPrograms.cff("TestCff");
        CFFFont source = new CFFParser().parse(new RandomAccessReadBuffer(cff)).get(0);
        Font f = awt(WebFonts.toTrueType(cff, WebFonts.FontKind.CFF, Map.of())).deriveFont(200f);
        for (String name : List.of("A", "Q", "g", "Aacute")) {
            GeneralPath p = source.getType2CharString(source.getCharset().getGIDForSID(source.getCharset().getSID(name)))
                    .getPath();
            BufferedImage expected = blank();
            Graphics2D g = graphics(expected);
            AffineTransform at = new AffineTransform(0.2, 0, 0, -0.2, 50, 250);
            g.fill(at.createTransformedShape(p));
            g.dispose();
            BufferedImage actual = blank();
            g = graphics(actual);
            g.setFont(f);
            g.drawString(new String(Character.toChars(codepoint(name))), 50, 250);
            g.dispose();
            assertTrue(differing(expected, blank()) > 0.02, name + " draws nothing");
            assertTrue(differing(expected, actual) < 0.01, name + " differs by " + differing(expected, actual));
        }
    }

    @Test
    void pfbAndRawSegmentsGiveTheSameFont() throws Exception {
        FontPrograms.Type1 t1 = FontPrograms.type1("TestType", 0);
        byte[] fromPfb = WebFonts.toTrueType(pfb(t1), WebFonts.FontKind.TYPE1, null);
        byte[] fromRaw = WebFonts.toTrueType(t1.data(), WebFonts.FontKind.TYPE1, null);
        assertArrayEquals(fromRaw, fromPfb);
    }

    @Test
    void unreadableProgramsFailWithAnIoException() throws Exception {
        assertThrows(IOException.class, () -> WebFonts.toTrueType(new byte[0], WebFonts.FontKind.CFF, null));
        assertThrows(IOException.class, () -> WebFonts.toTrueType(new byte[] {1, 2, 3, 4, 5}, WebFonts.FontKind.CFF,
                null));
        assertThrows(IOException.class, () -> WebFonts.toTrueType("no eexec here".getBytes(), WebFonts.FontKind.TYPE1,
                null));
        assertThrows(IOException.class, () -> WebFonts.toTrueType(new PDType1Font(Standard14Fonts.FontName.HELVETICA)));
    }

    private static byte[] pfb(FontPrograms.Type1 t) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        int[] lengths = {t.length1(), t.length2(), t.length3()};
        int at = 0;
        for (int i = 0; i < 3; i++) {
            o.write(0x80);
            o.write(i == 1 ? 2 : 1);
            int n = lengths[i];
            o.write(n);
            o.write(n >> 8);
            o.write(n >> 16);
            o.write(n >> 24);
            o.write(t.data(), at, n);
            at += n;
        }
        o.write(0x80);
        o.write(3);
        return o.toByteArray();
    }

    private static BufferedImage blank() {
        BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_BYTE_GRAY);
        graphics(img).dispose();
        return img;
    }

    private static Graphics2D graphics(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g;
    }

    private static double differing(BufferedImage a, BufferedImage b) {
        int diff = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if ((a.getRGB(x, y) & 0xFF) < 128 != (b.getRGB(x, y) & 0xFF) < 128) {
                    diff++;
                }
            }
        }
        return (double) diff / (a.getWidth() * a.getHeight());
    }
}

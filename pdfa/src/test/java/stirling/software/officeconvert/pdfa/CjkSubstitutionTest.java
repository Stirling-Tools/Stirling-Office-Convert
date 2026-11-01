package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.FontSet;

class CjkSubstitutionTest {

    private static final int HIRAGANA_A = 0x82A0;

    private static final int LONG_VOWEL = 0x815B;

    @TempDir
    Path dir;

    private PdfToPdfA.Options options(PdfALevel level) throws IOException {
        Path fonts = Files.createDirectories(dir.resolve("fonts"));
        TrueTypeWriter w = new TrueTypeWriter(1000);
        TreeMap<Integer, Integer> cmap = new TreeMap<>();
        cmap.put(0x41, w.addOutline(square(100), 600));
        cmap.put(0x3042, w.addOutline(square(200), 1000));
        cmap.put(0x30FC, w.addOutline(square(300), 1000));
        Files.write(fonts.resolve("IPAexMincho.ttf"), w.build("IPAexMincho", Cmaps.format4(cmap, 1),
                Map.of("OS/2", os2())));
        return PdfToPdfA.Options.defaults().level(level)
                .fonts(FontSet.builder().systemFonts(false).directory(fonts).build());
    }

    private static GeneralPath square(int size) {
        GeneralPath p = new GeneralPath();
        p.moveTo(100, 0);
        p.lineTo(100 + size, 0);
        p.lineTo(100 + size, size);
        p.lineTo(100, size);
        p.closePath();
        return p;
    }

    private static byte[] os2() {
        ByteBuffer b = ByteBuffer.allocate(96);
        b.putShort((short) 4);
        b.putShort((short) 500);
        b.putShort((short) 400);
        b.putShort((short) 5);
        b.putShort(62, (short) 0x40);
        return b.array();
    }

    private Path japanese(String encoding, String content) throws IOException {
        return Hostile.write(dir, "jp-" + encoding, d -> {
            PDPage page = new PDPage();
            d.addPage(page);
            COSDictionary type0 = new COSDictionary();
            type0.setItem(COSName.TYPE, COSName.FONT);
            type0.setItem(COSName.SUBTYPE, COSName.TYPE0);
            type0.setName(COSName.BASE_FONT, "KozMinPr6N-Regular");
            type0.setName(COSName.ENCODING, encoding);
            COSDictionary cid = new COSDictionary();
            cid.setItem(COSName.TYPE, COSName.FONT);
            cid.setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE0);
            cid.setName(COSName.BASE_FONT, "KozMinPr6N-Regular");
            COSDictionary info = new COSDictionary();
            info.setString(COSName.REGISTRY, "Adobe");
            info.setString(COSName.ORDERING, "Japan1");
            info.setInt(COSName.SUPPLEMENT, 6);
            cid.setItem(COSName.CIDSYSTEMINFO, info);
            COSDictionary fd = new COSDictionary();
            fd.setItem(COSName.TYPE, COSName.FONT_DESC);
            fd.setName(COSName.FONT_NAME, "KozMinPr6N-Regular");
            fd.setInt(COSName.FLAGS, 6);
            COSArray box = new COSArray();
            for (int v : new int[] {-437, -340, 1147, 1317}) {
                box.add(org.apache.pdfbox.cos.COSInteger.get(v));
            }
            fd.setItem(COSName.FONT_BBOX, box);
            fd.setInt(COSName.ITALIC_ANGLE, 0);
            fd.setInt(COSName.ASCENT, 880);
            fd.setInt(COSName.DESCENT, -120);
            fd.setInt(COSName.CAP_HEIGHT, 740);
            fd.setInt(COSName.STEM_V, 80);
            cid.setItem(COSName.FONT_DESC, fd);
            COSArray desc = new COSArray();
            desc.add(cid);
            type0.setItem(COSName.DESCENDANT_FONTS, desc);
            PDResources res = new PDResources();
            res.getCOSObject().setItem(COSName.FONT, new COSDictionary());
            ((COSDictionary) res.getCOSObject().getDictionaryObject(COSName.FONT)).setItem(COSName.getPDFName("F1"),
                    type0);
            page.setResources(res);
            Samples.raw(page, d, content);
        });
    }

    private static PDType0Font font(PDDocument d) throws IOException {
        COSDictionary fonts = (COSDictionary) d.getPage(0).getResources().getCOSObject()
                .getDictionaryObject(COSName.FONT);
        return (PDType0Font) PDFontFactory.createFont((COSDictionary) fonts.getDictionaryObject(COSName.getPDFName("F1")));
    }

    private static int gid(PDType0Font font, int cid) throws IOException {
        COSStream map = (COSStream) font.getDescendantFont().getCOSObject().getDictionaryObject(COSName.CID_TO_GID_MAP);
        byte[] b = map.createInputStream().readAllBytes();
        return cid * 2 + 1 < b.length ? (b[2 * cid] & 0xFF) << 8 | (b[2 * cid + 1] & 0xFF) : 0;
    }

    @Test
    void anUnembeddedJapaneseFontIsDrawnWithAJapaneseFaceThroughItsPredefinedCMap() throws Exception {
        Path in = japanese("90ms-RKSJ-H", "BT /F1 24 Tf 72 700 Td <82A0> Tj ET");
        Path out = dir.resolve("h.pdf");
        PdfToPdfA.Result r = PdfToPdfA.convert(in, out, options(PdfALevel.A2B));
        assertTrue(r.substitutedFonts().toString().contains("IPAexMincho"), r.substitutedFonts().toString());
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDType0Font f = font(d);
            assertTrue(f.isEmbedded());
            assertTrue(f.getDescendantFont() instanceof PDCIDFontType2);
            int cid = f.codeToCID(HIRAGANA_A);
            assertEquals(843, cid);
            int gid = gid(f, cid);
            assertTrue(gid > 0);
            GeneralPath p = ((PDCIDFontType2) f.getDescendantFont()).getTrueTypeFont().getGlyph().getGlyph(gid).getPath();
            assertFalse(p.getBounds2D().isEmpty());
        }
        assertEquals("あ", Converted.text(out).strip());
    }

    @Test
    void aVerticalCMapGlyphIsStoredUnderTheCidTheVerticalCMapGivesIt() throws Exception {
        Path in = japanese("90ms-RKSJ-V", "BT /F1 24 Tf 300 700 Td <815B82A0> Tj ET");
        Path out = dir.resolve("v.pdf");
        PdfToPdfA.convert(in, out, options(PdfALevel.A2B));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDType0Font f = font(d);
            assertTrue(gid(f, 7891) > 0, "vertical CID");
            assertTrue(gid(f, 843) > 0, "inherited CID");
            assertEquals(gid(f, 7891), gid(f, f.codeToCID(LONG_VOWEL)));
        }
    }

    @Test
    void cidLookupPrefersTheVerticalCMapOverTheOneItUses() throws Exception {
        Path in = japanese("90ms-RKSJ-V", "BT /F1 24 Tf 300 700 Td <815B> Tj ET");
        try (PDDocument d = Loader.loadPDF(in.toFile())) {
            PDType0Font f = font(d);
            assertEquals(7891, CidLookup.cid(f, LONG_VOWEL));
            assertEquals(843, CidLookup.cid(f, HIRAGANA_A));
        }
    }

    @Test
    void theTextOfAPredefinedCMapFontComesFromItsCharacterCollection() throws Exception {
        Path in = japanese("UniJIS-UTF16-H", "BT /F1 24 Tf 72 700 Td <00410042> Tj ET");
        try (PDDocument d = Loader.loadPDF(in.toFile())) {
            PDType0Font f = font(d);
            assertEquals("A", UnicodeGuess.of(f, 0x0041, 2));
            assertEquals("あ", UnicodeGuess.of(f, 0x3042, 2));
        }
    }

    @Test
    void aLatinParagraphInAJapaneseCidFontKeepsItsGlyphsAndText() throws Exception {
        Path in = japanese("UniJIS-UTF16-H", "BT /F1 24 Tf 72 700 Td <0041> Tj ET");
        for (PdfALevel level : List.of(PdfALevel.A2B, PdfALevel.A2U)) {
            Path out = dir.resolve("latin-" + level + ".pdf");
            PdfToPdfA.convert(in, out, options(level));
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                PDType0Font f = font(d);
                assertTrue(gid(f, f.codeToCID(0x41)) > 0, level.toString());
            }
            assertEquals("A", Converted.text(out).strip(), level.toString());
        }
    }

    @Test
    void anEmbeddedPredefinedVerticalCMapUsesAHorizontalCMapWithItsOwnWritingMode() throws Exception {
        Path in = japanese("90ms-RKSJ-V", "BT /F1 24 Tf 300 700 Td <82A0> Tj ET");
        Path out = dir.resolve("1b.pdf");
        PdfToPdfA.convert(in, out, options(PdfALevel.A1B));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSStream cmap = (COSStream) font(d).getCOSObject().getDictionaryObject(COSName.ENCODING);
            assertEquals(1, cmap.getInt(COSName.getPDFName("WMode")));
            COSStream parent = (COSStream) cmap.getDictionaryObject(COSName.getPDFName("UseCMap"));
            assertNotNull(parent);
            assertEquals(0, parent.getInt(COSName.getPDFName("WMode")));
        }
    }

    @Test
    void anEastAsianOrderingNamesTheStyleOfItsStandIn() {
        assertEquals("MS Mincho", GlyphSource.eastAsianFamily("Japan1", "KozMinPr6N-Regular", 0));
        assertEquals("MS Gothic", GlyphSource.eastAsianFamily("Japan1", "cid0jp", 0));
        assertEquals("SimSun", GlyphSource.eastAsianFamily("GB1", "STSong-Light", 0));
        assertEquals("SimHei", GlyphSource.eastAsianFamily("GB1", "SimHei", 2));
        assertEquals("PMingLiU", GlyphSource.eastAsianFamily("CNS1", "MingLiU", 0));
        assertEquals("Malgun Gothic", GlyphSource.eastAsianFamily("Korea1", "Dotum", 0));
        assertEquals(null, GlyphSource.eastAsianFamily("Identity", "Arial", 0));
        assertEquals("MS Mincho", GlyphSource.eastAsianFamily("Japan1", "HeiseiMin-W3", 0));
        assertEquals("MS Gothic", GlyphSource.eastAsianFamily("Japan1", "HeiseiKakuGo-W5", 0));
        assertEquals("Batang", GlyphSource.eastAsianFamily("Korea1", "HYSMyeongJo-Medium", 0));
        assertEquals("Malgun Gothic", GlyphSource.eastAsianFamily("Korea1", "HYGoThic-Medium", 0));
    }
}

package stirling.software.officeconvert.topdf.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.fontbox.cff.CFFCIDFont;
import org.apache.fontbox.cff.CFFFont;
import org.apache.fontbox.cff.CFFParser;
import org.apache.fontbox.cff.CFFType1Font;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType0;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.pdf.PageSize;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;
import stirling.software.officeconvert.topdf.pdf.TextStyle;
import stirling.software.officeconvert.topdf.testing.PostScriptFonts;
import stirling.software.officeconvert.topdf.testing.TestFonts;

class PostScriptFontTest {

    private static final int[] CHARS = {'A', 'B', 'C', 'D', 'E', 'F', 0x4E00, 0x3042, 0xAC00};

    @TempDir
    Path dir;

    @Test
    void postScriptOutlinesAreUsable() throws Exception {
        Path fonts = Files.createDirectories(dir.resolve("otf"));
        Files.write(fonts.resolve("Ps.otf"), PostScriptFonts.font("Ps Sans", 400, false, CHARS));
        FontLibrary lib = FontLibrary.of(List.of(fonts));
        FontFace face = lib.find("Ps Sans", false, false);
        assertEquals("Ps Sans", face.family());
        assertNull(face.note());
        assertTrue(face.covers(0x4E00));
        assertEquals(PostScriptFonts.ADVANCE, face.advance('A'));
    }

    @Test
    void nameKeyedFontsAreSubsetAsCidFonts() throws Exception {
        verify(PostScriptFonts.font("Name Keyed", 400, false, CHARS), "Name Keyed");
    }

    @Test
    void cidKeyedFontsKeepOnlyTheirDrawnGlyphsAndSubroutines() throws Exception {
        verify(PostScriptFonts.font("Cid Keyed", 400, true, CHARS), "Cid Keyed");
    }

    @Test
    void facesOfACollectionEmbed() throws Exception {
        byte[] ttc = PostScriptFonts.collection(PostScriptFonts.font("Pair One", 400, true, CHARS),
                PostScriptFonts.font("Pair Two", 400, false, CHARS));
        assertEquals(2, FontScanner.scan(ttc).size());
        verify(ttc, "Pair One");
        verify(ttc, "Pair Two");
    }

    private void verify(byte[] font, String family) throws Exception {
        Path fonts = Files.createDirectories(dir.resolve(family.replace(' ', '-')));
        Files.write(fonts.resolve(family.replace(" ", "") + (font[0] == 't' ? ".ttc" : ".otf")), font);
        FontLibrary lib = FontLibrary.of(List.of(fonts));
        FontFace face = lib.exact(family, false, false);
        assertNotNull(face, family);
        String text = "ABCD\u4E00\u3042 F\uAC00";
        byte[] pdf;
        try (PdfOutput output = new PdfOutput(lib)) {
            try (PdfCanvas canvas = output.newPage(PageSize.A4)) {
                canvas.text(text, 72, 100, TextStyle.of(face, 40));
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            output.save(bytes);
            pdf = bytes.toByteArray();
        }
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertEquals(text, new PDFTextStripper().getText(doc).strip());
            PDFont pdFont = null;
            for (COSName n : doc.getPage(0).getResources().getFontNames()) {
                pdFont = doc.getPage(0).getResources().getFont(n);
            }
            PDCIDFontType0 cid = (PDCIDFontType0) ((PDType0Font) pdFont).getDescendantFont();
            PDStream file = cid.getFontDescriptor().getFontFile3();
            assertEquals("CIDFontType0C", file.getCOSObject().getNameAsString(COSName.SUBTYPE));
            CFFFont cff = new CFFParser().parse(new RandomAccessReadBuffer(file.toByteArray())).get(0);
            assertTrue(cff instanceof CFFCIDFont);
            assertEquals(9, cff.getNumCharStrings());
            CFFCIDFont cidFont = (CFFCIDFont) cff;
            assertTrue(cidFont.getFontDicts().size() <= 2);
            assertEquals(1, cff.getGlobalSubrIndex().size());
            for (Map<String, Object> priv : cidFont.getPrivDicts()) {
                assertTrue(((byte[][]) priv.get("Subrs")).length <= 2, priv.toString());
            }
            assertTrue(file.toByteArray().length < 1200, file.toByteArray().length + " bytes");
            BufferedImage page = new PDFRenderer(doc).renderImage(0, 1f);
            int y = 100 - 2;
            int ink = 0;
            for (int x = 72; x < 72 + 8 * 24; x++) {
                ink += (page.getRGB(x, y) & 0xFF) < 128 ? 1 : 0;
            }
            assertTrue(ink > 7 * 14, "ink " + ink);
        }
    }

    @Test
    void aThinFaceGivesWayToARegularOneForTheSameScript() throws Exception {
        Path fonts = Files.createDirectories(dir.resolve("weights"));
        Files.write(fonts.resolve("Thin.otf"), PostScriptFonts.font("Noto Sans SC", 100, false, CHARS));
        Files.write(fonts.resolve("Cjk.otf"), PostScriptFonts.font("Noto Sans CJK SC", 400, true, CHARS));
        FontLibrary lib = FontLibrary.of(List.of(fonts));
        assertEquals("Noto Sans CJK SC", lib.fallback(0x4E00, false, false).family());
        assertEquals("Noto Sans CJK SC", lib.find("Microsoft YaHei", false, false).family());
        assertEquals("Noto Sans SC", lib.find("Noto Sans SC", false, false).family());
        Path thinOnly = Files.createDirectories(dir.resolve("thin"));
        Files.write(thinOnly.resolve("Thin.otf"), PostScriptFonts.font("Noto Sans SC", 100, false, CHARS));
        FontLibrary thin = FontLibrary.of(List.of(thinOnly));
        assertEquals("Noto Sans SC", thin.fallback(0x4E00, false, false).family());
        FontFace yahei = thin.find("Microsoft YaHei", false, false);
        assertEquals("Noto Sans SC", yahei.family());
        assertTrue(yahei.embolden() > 0);
    }

    @Test
    void symbolCodesInAPlainRunDrawAsTheirSymbolLookAlike() throws Exception {
        FontLibrary lib = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("none"))))
                .withFonts(List.of(TestFonts.withGlyphs("Body Face", 'o', 0x2022)));
        FontFace body = lib.exact("Body Face", false, false);
        assertFalse(body.covers(0xF0B7));
        FontFace bullet = lib.fallback(0xF0B7, body);
        assertNotNull(bullet);
        assertTrue(bullet.covers(0xF0B7));
        assertEquals(body.glyph(0x2022), bullet.glyph(0xF0B7));
    }

    @Test
    void aSymbolStandInBorrowsALookAlikeItLacks() throws Exception {
        FontLibrary lib = FontLibrary.of(List.of(Files.createDirectories(dir.resolve("none2"))))
                .withFonts(List.of(TestFonts.withGlyphs("Dings Face", 'A', 0x2779)));
        FontFace standIn = lib.find("Wingdings 2", false, false);
        assertTrue(standIn.symbolStandIn());
        assertFalse(standIn.covers(0xF09B));
        FontFace borrowed = lib.fallback(0xF09B, standIn);
        assertNotNull(borrowed);
        assertEquals("Dings Face", borrowed.family());
        assertTrue(borrowed.covers(0xF09B));
    }

    @Test
    void printerAndMonotypeSymbolFontsAreKnown() {
        assertEquals(0x2714, FontLibrary.symbolUnicode("Monotype Sorts", 0xF034));
        assertEquals(0x2605, FontLibrary.symbolUnicode("ZapfDingbats", 0x48));
        assertEquals(0x2217, FontLibrary.symbolUnicode("Symbol (PCL6)", 0xF02A));
    }

    @Test
    void accentedGlyphsBuiltFromTheirPartsAreDrawn() throws Exception {
        byte[] cff = PostScriptFonts.accentedCff("AccentSans");
        Path fonts = Files.createDirectories(dir.resolve("accent"));
        Files.write(fonts.resolve("Accent.otf"), PostScriptFonts.font("Accent Sans", cff, 4, 'A', 0xB4, 0xC1));
        FontLibrary lib = FontLibrary.of(List.of(fonts));
        FontFace face = lib.exact("Accent Sans", false, false);
        assertNotNull(face);
        byte[] pdf;
        try (PdfOutput output = new PdfOutput(lib)) {
            try (PdfCanvas canvas = output.newPage(PageSize.A4)) {
                canvas.text("\u00C1A", 72, 100, TextStyle.of(face, 40));
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            output.save(bytes);
            pdf = bytes.toByteArray();
            assertTrue(output.fonts().substitutions().isEmpty(), output.fonts().substitutions().toString());
        }
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertEquals("\u00C1A", new PDFTextStripper().getText(doc).strip());
            PDFontDescriptor fd = only(doc).getFontDescriptor();
            CFFCIDFont subset = (CFFCIDFont) new CFFParser()
                    .parse(new RandomAccessReadBuffer(fd.getFontFile3().toByteArray())).get(0);
            CFFType1Font source = (CFFType1Font) new CFFParser().parse(new RandomAccessReadBuffer(cff)).get(0);
            Area drawn = new Area(subset.getType2CharString(3).getPath());
            assertFalse(drawn.isEmpty());
            assertTrue(new Area(source.getType2CharString(3).getPath()).equals(drawn));
            assertEquals(source.getType2CharString(3).getWidth(), subset.getType2CharString(3).getWidth());
            BufferedImage page = new PDFRenderer(doc).renderImage(0, 1f);
            assertTrue(ink(page, 72, 96, 63, 71) > 10, "accent");
            assertEquals(0, ink(page, 96, 120, 63, 71));
            assertTrue(ink(page, 96, 120, 80, 99) > 10, "letter");
        }
    }

    @Test
    void aFontTheSubsetterRefusesGivesWayToAnother() throws Exception {
        Path fonts = Files.createDirectories(dir.resolve("refused"));
        Files.write(fonts.resolve("Refused.otf"), PostScriptFonts.font("Refused Sans",
                PostScriptFonts.largePrivateCff("RefusedSans", 4), 4, 'A', 'B', 'C'));
        FontLibrary lib = FontLibrary.of(List.of(fonts));
        FontFace face = lib.find("Refused Sans", false, false);
        assertNotEquals("Refused Sans", face.family());
        byte[] pdf;
        try (PdfOutput output = new PdfOutput(lib)) {
            try (PdfCanvas canvas = output.newPage(PageSize.A4)) {
                canvas.text("ABC", 72, 100, TextStyle.of(face, 40));
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            output.save(bytes);
            pdf = bytes.toByteArray();
        }
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertEquals("ABC", new PDFTextStripper().getText(doc).strip());
            PDFontDescriptor fd = only(doc).getFontDescriptor();
            assertTrue(fd.getFontFile2() != null || fd.getFontFile3() != null);
            assertTrue(ink(new PDFRenderer(doc).renderImage(0, 1f), 72, 140, 70, 99) > 40);
        }
    }

    private static PDCIDFont only(PDDocument doc) throws IOException {
        return ((PDType0Font) only0(doc)).getDescendantFont();
    }

    private static PDFont only0(PDDocument doc) throws IOException {
        PDFont font = null;
        for (COSName n : doc.getPage(0).getResources().getFontNames()) {
            assertNull(font, "one font");
            font = doc.getPage(0).getResources().getFont(n);
        }
        return font;
    }

    private static int ink(BufferedImage page, int x0, int x1, int y0, int y1) {
        int n = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x < x1; x++) {
                n += (page.getRGB(x, y) & 0xFF) < 128 ? 1 : 0;
            }
        }
        return n;
    }
}

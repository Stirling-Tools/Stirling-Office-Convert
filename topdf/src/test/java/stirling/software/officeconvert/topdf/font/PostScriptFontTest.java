package stirling.software.officeconvert.topdf.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType0;
import org.apache.pdfbox.pdmodel.font.PDFont;
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
}

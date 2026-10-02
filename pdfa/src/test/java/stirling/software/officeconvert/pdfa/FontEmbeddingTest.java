package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.FontSet;

class FontEmbeddingTest {

    @TempDir
    Path dir;

    @Test
    void standardFontsAreEmbeddedWithoutMovingAGlyph() throws Exception {
        PdfToPdfA.Result r = Converted.convert(dir, "s01_std14_unembedded", PdfALevel.A2B);
        Path in = dir.resolve("s01_std14_unembedded.pdf");
        Path out = Converted.out(dir, "s01_std14_unembedded", PdfALevel.A2B);
        assertEquals(Converted.text(in), Converted.text(out));
        List<float[]> before = Converted.glyphs(in);
        List<float[]> after = Converted.glyphs(out);
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i)[0], after.get(i)[0], 0.01, "glyph " + i + " moved");
            assertEquals(before.get(i)[2], after.get(i)[2], 0.01, "glyph " + i + " changed width");
        }
        assertEmbedded(out);
        assertTrue(r.substitutedFonts().size() >= 13, r.substitutedFonts().toString());
        assertTrue(r.substitutedFonts().stream().anyMatch(s -> s.startsWith("Helvetica as ")), r.substitutedFonts().toString());
    }

    @Test
    void theHostsFontSetPicksTheEmbeddedFonts() throws Exception {
        Path in = Samples.write(dir, "s01_std14_unembedded");
        Path out = dir.resolve("hosted.pdf");
        PdfToPdfA.Options options = PdfToPdfA.Options.defaults()
                .fonts(FontSet.builder().systemFonts(false).build());
        PdfToPdfA.Result r = PdfToPdfA.convert(in, out, options);
        assertEmbedded(out);
        assertFalse(r.substitutedFonts().isEmpty());
        for (String s : r.substitutedFonts()) {
            assertTrue(!s.contains(" as ") || s.contains(" as Liberation Sans "), r.substitutedFonts().toString());
        }
        assertTrue(r.substitutedFonts().stream().anyMatch(s -> s.startsWith("Times-Roman as Liberation Sans ")));
        assertTrue(options.toString().contains("systemFonts=false"), options.toString());
    }

    @Test
    void unembeddedTrueTypeFontsKeepTheirWidths() throws Exception {
        Converted.convert(dir, "s02_truetype_unembedded", PdfALevel.A1B);
        Path in = dir.resolve("s02_truetype_unembedded.pdf");
        Path out = Converted.out(dir, "s02_truetype_unembedded", PdfALevel.A1B);
        assertEquals(Converted.text(in), Converted.text(out));
        List<float[]> before = Converted.glyphs(in);
        List<float[]> after = Converted.glyphs(out);
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i)[0], after.get(i)[0], 0.01);
        }
        assertEmbedded(out);
    }

    @Test
    void anEmbeddedProgramIsMadeToAgreeWithTheWidthsArray() throws Exception {
        Converted.convert(dir, "s14_widths_mismatch", PdfALevel.A2B);
        Path in = dir.resolve("s14_widths_mismatch.pdf");
        Path out = Converted.out(dir, "s14_widths_mismatch", PdfALevel.A2B);
        assertEquals(Converted.text(in), Converted.text(out));
        int code;
        try (PDDocument d = Loader.loadPDF(in.toFile())) {
            PDFont f = d.getPage(0).getResources().getFont(d.getPage(0).getResources().getFontNames().iterator().next());
            byte[] e = f.encode("E");
            code = (e[0] & 0xFF) << 8 | e[1] & 0xFF;
            assertEquals(600, f.getWidth(code), 0.01);
            assertTrue(Math.abs(f.getWidthFromFont(code) - 600) > 1);
        }
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            PDFont f = d.getPage(0).getResources().getFont(d.getPage(0).getResources().getFontNames().iterator().next());
            PDType0Font t0 = (PDType0Font) f;
            assertTrue(t0.getDescendantFont() instanceof PDCIDFontType2);
            assertEquals(600, t0.getWidth(code), 0.01);
            assertEquals(600, t0.getWidthFromFont(code), 1);
        }
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
    }

    @Test
    void anUnembeddedCidFontIsDrawnFromItsToUnicodeMap() throws Exception {
        Converted.convert(dir, "s16_cid_unembedded", PdfALevel.A2U);
        Path out = Converted.out(dir, "s16_cid_unembedded", PdfALevel.A2U);
        assertEquals("CID font, no program", Converted.text(out).strip());
        assertEmbedded(out);
        VeraPdf.assertCompliant(out, PdfALevel.A2U);
    }

    @Test
    void typeThreeFontsGetUnicodeForTheULevels() throws Exception {
        Converted.convert(dir, "s03_type3", PdfALevel.A3U);
        Path out = Converted.out(dir, "s03_type3", PdfALevel.A3U);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            COSDictionary t3 = (COSDictionary) d.getPage(0).getResources().getCOSObject()
                    .getCOSDictionary(COSName.FONT).getDictionaryObject(COSName.getPDFName("T3"));
            assertNotNull(t3.getDictionaryObject(COSName.TO_UNICODE));
        }
        VeraPdf.assertCompliant(out, PdfALevel.A3U);
    }

    private static void assertEmbedded(Path pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            for (PDPage p : d.getPages()) {
                for (COSName n : p.getResources().getFontNames()) {
                    PDFont f = p.getResources().getFont(n);
                    COSDictionary fd = f instanceof PDType0Font t0
                            ? t0.getDescendantFont().getFontDescriptor().getCOSObject()
                            : f.getFontDescriptor().getCOSObject();
                    assertFalse(fd.getDictionaryObject(COSName.FONT_FILE2) == null
                            && fd.getDictionaryObject(COSName.FONT_FILE) == null
                            && fd.getDictionaryObject(COSName.FONT_FILE3) == null, f.getName() + " is not embedded");
                    if (!(f instanceof PDType0Font)) {
                        assertTrue(f.getCOSObject().getDictionaryObject(COSName.WIDTHS) instanceof COSArray);
                    }
                }
            }
        }
    }
}

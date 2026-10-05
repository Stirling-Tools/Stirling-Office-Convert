package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
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

    @Test
    void aFontThatShowsOnlyEmptyStringsGetsAWidthsArray() throws Exception {
        Path in = dir.resolve("empty-field.pdf");
        try (PDDocument d = new PDDocument()) {
            PDPage page = new PDPage();
            d.addPage(page);
            COSArray widths = new COSArray();
            for (int c = 0; c < 256; c++) {
                widths.add(COSInteger.get(500));
            }
            COSDictionary font = new COSDictionary();
            font.setItem(COSName.TYPE, COSName.FONT);
            font.setItem(COSName.SUBTYPE, COSName.TYPE1);
            font.setName(COSName.BASE_FONT, "HelveticaLTStd-Bold");
            font.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
            font.setInt(COSName.FIRST_CHAR, 0);
            font.setInt(COSName.LAST_CHAR, 255);
            font.setItem(COSName.WIDTHS, widths);
            PDResources res = new PDResources();
            res.put(COSName.getPDFName("F1"), PDFontFactory.createFont(font));
            PDFormXObject ap = new PDFormXObject(d);
            ap.setBBox(new PDRectangle(88, 11));
            ap.setResources(res);
            try (OutputStream o = ap.getContentStream().createOutputStream()) {
                o.write("/Tx BMC BT /F1 8 Tf 44 2.6 Td () Tj ET EMC".getBytes(StandardCharsets.US_ASCII));
            }
            PDAnnotationWidget widget = new PDAnnotationWidget();
            widget.setRectangle(new PDRectangle(228, 732, 88, 11));
            widget.setPrinted(true);
            PDAppearanceDictionary appearance = new PDAppearanceDictionary();
            appearance.setNormalAppearance(new PDAppearanceStream(ap.getCOSObject()));
            widget.setAppearance(appearance);
            page.getAnnotations().add(widget);
            try (PDPageContentStream c = new PDPageContentStream(d, page)) {
                c.beginText();
                c.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                c.newLineAtOffset(72, 700);
                c.showText("Form");
                c.endText();
            }
            d.save(in.toFile());
        }
        for (PdfALevel level : new PdfALevel[] {PdfALevel.A1B, PdfALevel.A2B}) {
            Path out = dir.resolve("empty-field-" + level + ".pdf");
            PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
            assertEquals("Form", Converted.text(out).strip());
            VeraPdf.assertCompliant(out, level);
        }
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

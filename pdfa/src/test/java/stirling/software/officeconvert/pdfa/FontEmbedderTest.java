package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.FontSet;

class FontEmbedderTest {

    @TempDir
    Path dir;

    private Path embed(String sample, int expected) throws Exception {
        Path in = Samples.write(dir, sample);
        Path out = dir.resolve(sample + "-embedded.pdf");
        try (PDDocument d = Loader.loadPDF(in.toFile())) {
            d.getDocumentCatalog().getCOSObject().setItem(COSName.STRUCT_TREE_ROOT, new COSDictionary());
            FontEmbedder.Result r = FontEmbedder.embedMissing(d);
            assertEquals(expected, r.embedded().size(), r.embedded().toString());
            assertTrue(r.failed().isEmpty(), r.failed().toString());
            d.save(out.toFile());
        }
        assertEquals(Converted.text(in), Converted.text(out));
        List<float[]> before = Converted.glyphs(in);
        List<float[]> after = Converted.glyphs(out);
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i)[0], after.get(i)[0], 0.01, "glyph " + i + " moved");
            assertEquals(before.get(i)[2], after.get(i)[2], 0.01, "glyph " + i + " changed width");
        }
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertNotNull(d.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.STRUCT_TREE_ROOT));
            for (PDPage p : d.getPages()) {
                for (COSName n : p.getResources().getFontNames()) {
                    PDFont f = p.getResources().getFont(n);
                    if (!(f instanceof PDType3Font)) {
                        assertTrue(f.isEmbedded(), f.getName() + " is not embedded");
                    }
                }
            }
            new PDFRenderer(d).renderImage(0, 0.5f);
        }
        return out;
    }

    @Test
    void standardFontsAreEmbeddedWithoutMovingAGlyphOrTouchingTheRest() throws Exception {
        embed("s01_std14_unembedded", 14);
    }

    @Test
    void unembeddedTrueTypeAndCidFontsKeepTheirWidths() throws Exception {
        embed("s02_truetype_unembedded", 4);
        embed("s16_cid_unembedded", 1);
    }

    @Test
    void embeddingKeepsControlCharactersAndPrivateUseMappings() throws Exception {
        RawPdf pdf = RawPdf.page("/Font<</F1<</Type/Font/Subtype/Type1/BaseFont/Helvetica/Encoding/WinAnsiEncoding"
                + "/ToUnicode 5 0 R>>>>", "BT /F1 12 Tf 50 700 Td <414243> Tj ET");
        pdf.add(RawPdf.stream("", "/CIDInit /ProcSet findresource begin 12 dict begin begincmap "
                + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def "
                + "/CMapName /Original def /CMapType 2 def 1 begincodespacerange <00> <FF> endcodespacerange "
                + "3 beginbfchar <41> <0000> <42> <E045> <43> <0043> endbfchar endcmap "
                + "CMapName currentdict /CMap defineresource pop end end"));
        try (PDDocument d = Loader.loadPDF(pdf.bytes())) {
            String before = new org.apache.pdfbox.text.PDFTextStripper().getText(d);
            assertTrue(before.contains("\u0000\uE045C"));
            assertTrue(FontEmbedder.embedMissing(d).failed().isEmpty());
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            d.save(out);
            try (PDDocument saved = Loader.loadPDF(out.toByteArray())) {
                assertEquals(before, new org.apache.pdfbox.text.PDFTextStripper().getText(saved));
                assertTrue(saved.getPage(0).getResources().getFont(COSName.getPDFName("F1")).isEmbedded());
            }
        }
    }

    @Test
    void embeddingKeepsInferredPrivateUseMappings() throws Exception {
        RawPdf pdf = RawPdf.page("/Font<</F1<</Type/Font/Subtype/Type1/BaseFont/Symbol/Encoding"
                + "<</Type/Encoding/Differences[65/apple]>>>>>>", "BT /F1 12 Tf 50 700 Td (A) Tj ET");
        try (PDDocument d = Loader.loadPDF(pdf.bytes())) {
            String before = new org.apache.pdfbox.text.PDFTextStripper().getText(d);
            assertTrue(before.contains("\uF8FF"));
            assertTrue(FontEmbedder.embedMissing(d).failed().isEmpty());
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            d.save(out);
            try (PDDocument saved = Loader.loadPDF(out.toByteArray())) {
                assertEquals(before, new org.apache.pdfbox.text.PDFTextStripper().getText(saved));
            }
        }
    }

    @Test
    void embeddingKeepsTheSpaceWidthOfAnUnmappedCidFont() throws Exception {
        RawPdf pdf = RawPdf.page("/Font<</F1<</Type/Font/Subtype/Type0/BaseFont/Ryumin-Light/Encoding/Identity-H"
                + "/DescendantFonts[<</Type/Font/Subtype/CIDFontType0/BaseFont/Ryumin-Light"
                + "/CIDSystemInfo<</Registry(Adobe)/Ordering(Japan1)/Supplement 0>>/DW 1000"
                + "/FontDescriptor<</Type/FontDescriptor/FontName/Ryumin-Light/Flags 4/FontBBox[0 -200 1000 1000]"
                + "/ItalicAngle 0/Ascent 800/Descent -200/CapHeight 700/StemV 80>>>>]>>>>",
                "BT /F1 12 Tf 50 700 Td <002400250026> Tj ET");
        Path input = dir.resolve("cid-space-before.pdf");
        java.nio.file.Files.write(input, pdf.bytes());
        List<float[]> positions = Converted.glyphs(input);
        try (PDDocument d = Loader.loadPDF(pdf.bytes())) {
            String before = new org.apache.pdfbox.text.PDFTextStripper().getText(d);
            float space = d.getPage(0).getResources().getFont(COSName.getPDFName("F1")).getSpaceWidth();
            assertTrue(FontEmbedder.embedMissing(d).failed().isEmpty());
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            d.save(out);
            Path output = dir.resolve("cid-space-after.pdf");
            java.nio.file.Files.write(output, out.toByteArray());
            List<float[]> after = Converted.glyphs(output);
            assertEquals(positions.size(), after.size());
            for (int i = 0; i < positions.size(); i++) {
                assertArrayEquals(positions.get(i), after.get(i), 0.01f);
            }
            try (PDDocument saved = Loader.loadPDF(out.toByteArray())) {
                assertEquals(before, new org.apache.pdfbox.text.PDFTextStripper().getText(saved));
                PDFont font = saved.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
                assertTrue(font.isEmbedded());
                assertEquals(space, font.getSpaceWidth(), 0.01);
            }
        }
    }

    @Test
    void fontsTheResourcesNameButNothingShowsAreEmbeddedToo() throws Exception {
        RawPdf pdf = RawPdf.page("/Font<</F1<</Type/Font/Subtype/Type1/BaseFont/Helvetica/Encoding/WinAnsiEncoding>>"
                + "/F2<</Type/Font/Subtype/Type1/BaseFont/Times-Bold/Encoding/WinAnsiEncoding>>"
                + "/F3<</Type/Font/Subtype/Type0/BaseFont/Arial/Encoding/Identity-H/DescendantFonts[<</Type/Font"
                + "/Subtype/CIDFontType2/BaseFont/Arial/CIDSystemInfo<</Registry(Adobe)/Ordering(Identity)/Supplement 0>>"
                + "/FontDescriptor<</Type/FontDescriptor/FontName/Arial/Flags 32/FontBBox[0 0 1000 1000]/ItalicAngle 0"
                + "/Ascent 900/Descent -200/CapHeight 700/StemV 80>>>>]>>>>",
                "BT /F1 12 Tf 50 700 Td (Shown) Tj ET");
        try (PDDocument d = Loader.loadPDF(pdf.bytes())) {
            FontEmbedder.Result r = FontEmbedder.embedMissing(d);
            assertEquals(3, r.embedded().size(), r.embedded().toString());
            assertTrue(r.failed().isEmpty(), r.failed().toString());
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            d.save(out);
            try (PDDocument saved = Loader.loadPDF(out.toByteArray())) {
                PDPage p = saved.getPage(0);
                for (COSName n : p.getResources().getFontNames()) {
                    assertTrue(p.getResources().getFont(n).isEmbedded(), n.getName());
                }
                assertEquals("Shown", new org.apache.pdfbox.text.PDFTextStripper().getText(saved).strip());
            }
        }
    }

    @Test
    void equalFontDictionariesShareOneSubsetWithoutHinting() throws Exception {
        String helvetica = "<</Type/Font/Subtype/Type1/BaseFont/Helvetica/Encoding/WinAnsiEncoding>>";
        RawPdf pdf = RawPdf.page("/Font<</F1 " + helvetica + "/F2 " + helvetica + ">>",
                "BT /F1 12 Tf 50 700 Td (Hello) Tj /F2 12 Tf 0 -20 Td (World) Tj ET");
        try (PDDocument d = Loader.loadPDF(pdf.bytes())) {
            FontEmbedder.Result r = FontEmbedder.embedMissing(d);
            assertEquals(1, r.embedded().size(), r.embedded().toString());
            PDPage p = d.getPage(0);
            PDFont f1 = p.getResources().getFont(COSName.getPDFName("F1"));
            PDFont f2 = p.getResources().getFont(COSName.getPDFName("F2"));
            COSStream program = (COSStream) f1.getFontDescriptor().getCOSObject().getDictionaryObject(COSName.FONT_FILE2);
            assertSame(program, f2.getFontDescriptor().getCOSObject().getDictionaryObject(COSName.FONT_FILE2));
            try (InputStream in = program.createInputStream();
                    org.apache.fontbox.ttf.TrueTypeFont ttf = new org.apache.fontbox.ttf.TTFParser()
                            .parse(new org.apache.pdfbox.io.RandomAccessReadBuffer(in.readAllBytes()))) {
                assertNull(ttf.getTableMap().get("fpgm"));
                assertNull(ttf.getTableMap().get("prep"));
                for (char c : "HeloWrd".toCharArray()) {
                    int gid = ((org.apache.pdfbox.pdmodel.font.PDTrueTypeFont) f1).codeToGID(c);
                    assertTrue(gid > 0);
                    byte[] glyf = ttf.getTableBytes(ttf.getTableMap().get("glyf"));
                    java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(glyf);
                    int at = (int) ttf.getIndexToLocation().getOffsets()[gid];
                    int contours = b.getShort(at);
                    assertTrue(contours > 0);
                    assertEquals(0, b.getShort(at + 10 + 2 * contours));
                }
            }
            assertEquals("Hello\nWorld", new org.apache.pdfbox.text.PDFTextStripper().getText(d).strip());
        }
    }

    @Test
    void anUnreadableProgramIsReplacedAndARepairableOneKept() throws Exception {
        String font = "<</Type/Font/Subtype/TrueType/BaseFont/Arial/Encoding/WinAnsiEncoding/FontDescriptor"
                + "<</Type/FontDescriptor/FontName/Arial/Flags 32/FontBBox[0 0 1000 1000]/ItalicAngle 0/Ascent 900"
                + "/Descent -200/CapHeight 700/StemV 80/FontFile2 5 0 R>>>>";
        RawPdf pdf = RawPdf.page("/Font<</F1 " + font + ">>", "BT /F1 12 Tf 50 700 Td (Garbage) Tj ET");
        pdf.add(RawPdf.stream("", "this is not a font program at all, not even close"));
        try (PDDocument d = Loader.loadPDF(pdf.bytes())) {
            FontEmbedder.Result r = FontEmbedder.embedMissing(d);
            assertEquals(1, r.embedded().size(), r.embedded().toString());
            PDFont f = d.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            d.save(out);
            try (PDDocument saved = Loader.loadPDF(out.toByteArray())) {
                PDFont g = saved.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
                assertTrue(g.isEmbedded() && !g.isDamaged(), g.getName());
            }
            assertNotNull(f);
        }
        try (PDDocument d = Loader.loadPDF(Samples.write(dir, "s14_widths_mismatch").toFile())) {
            assertTrue(FontEmbedder.embedMissing(d).embedded().isEmpty());
        }
    }

    @Test
    void anEmptyCffProgramIsReplacedEvenWhenItIsNotMarkedDamaged() throws Exception {
        RawPdf pdf = RawPdf.page("/Font<</F1<</Type/Font/Subtype/Type1/BaseFont/Helvetica/Encoding/WinAnsiEncoding"
                + "/FontDescriptor<</Type/FontDescriptor/FontName/Helvetica/Flags 32/FontBBox[0 -200 1000 1000]"
                + "/ItalicAngle 0/Ascent 800/Descent -200/CapHeight 700/StemV 80/FontFile3 5 0 R>>>>>>",
                "BT /F1 12 Tf 50 700 Td (Empty CFF) Tj ET");
        pdf.add(RawPdf.stream("/Subtype /Type1C", ""));
        try (PDDocument d = Loader.loadPDF(pdf.bytes())) {
            PDFont before = d.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
            assertFalse(before.isEmbedded());
            assertFalse(before.isDamaged());
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(d);
            FontEmbedder.Result result = FontEmbedder.embedMissing(d);
            assertEquals(1, result.embedded().size());
            assertTrue(result.failed().isEmpty());
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            d.save(out);
            try (PDDocument saved = Loader.loadPDF(out.toByteArray())) {
                assertTrue(saved.getPage(0).getResources().getFont(COSName.getPDFName("F1")).isEmbedded());
                assertEquals(text, new org.apache.pdfbox.text.PDFTextStripper().getText(saved));
            }
        }
    }

    @Test
    void embeddedAndType3FontsAreLeftAlone() throws Exception {
        for (String sample : List.of("s03_type3", "s14_widths_mismatch")) {
            try (PDDocument d = Loader.loadPDF(Samples.write(dir, sample).toFile())) {
                PDPage page = d.getPage(0);
                Map<COSName, byte[]> before = programs(page);
                assertFalse(before.isEmpty(), sample);
                FontEmbedder.Result r = FontEmbedder.embedMissing(d, FontSet.builder().systemFonts(false).build());
                assertTrue(r.failed().isEmpty(), r.failed().toString());
                Map<COSName, byte[]> after = programs(page);
                assertEquals(before.keySet(), after.keySet());
                for (COSName n : before.keySet()) {
                    assertArrayEquals(before.get(n), after.get(n), sample + " " + n.getName());
                }
            }
        }
    }

    private static Map<COSName, byte[]> programs(PDPage page) throws Exception {
        Map<COSName, byte[]> out = new HashMap<>();
        for (COSName n : page.getResources().getFontNames()) {
            PDFont f = page.getResources().getFont(n);
            COSStream s = null;
            if (f instanceof PDType3Font t3) {
                s = t3.getCharProc(t3.getCOSObject().getInt(COSName.FIRST_CHAR)) == null ? null
                        : t3.getCharProc(t3.getCOSObject().getInt(COSName.FIRST_CHAR)).getCOSObject();
            } else if (f.isEmbedded()) {
                COSDictionary fd = (f instanceof PDType0Font t0 ? t0.getDescendantFont().getFontDescriptor()
                        : f.getFontDescriptor()).getCOSObject();
                for (COSName k : new COSName[] {COSName.FONT_FILE, COSName.FONT_FILE2, COSName.FONT_FILE3}) {
                    if (fd.getDictionaryObject(k) instanceof COSStream found) {
                        s = found;
                    }
                }
            }
            if (s != null) {
                try (InputStream in = s.createInputStream()) {
                    out.put(n, in.readAllBytes());
                }
            }
        }
        return out;
    }
}

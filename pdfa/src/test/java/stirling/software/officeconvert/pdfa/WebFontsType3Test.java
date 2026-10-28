package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.geom.GeneralPath;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;

import org.apache.fontbox.ttf.CmapSubtable;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

class WebFontsType3Test {

    private static final String GLYPHS = "<</a 5 0 R/ring 6 0 R/line 7 0 R/bits 8 0 R>>";

    private static RawPdf type3(String matrix, String widths, String... procs) {
        RawPdf pdf = RawPdf.page("/Font<</T3<</Type/Font/Subtype/Type3/Name/T3/FontBBox[0 0 0 0]/FontMatrix[" + matrix
                + "]/CharProcs" + GLYPHS + "/Encoding<</Type/Encoding/Differences[97/a/ring/line/bits]>>"
                + "/FirstChar 97/LastChar 100/Widths[" + widths + "]>>>>", "BT /T3 50 Tf 20 100 Td (abcd) Tj ET");
        for (String p : procs) {
            pdf.add(RawPdf.stream("", p));
        }
        return pdf;
    }

    private static TrueTypeFont convert(RawPdf pdf) throws IOException {
        return convert(pdf, false);
    }

    private static TrueTypeFont convert(RawPdf pdf, boolean resources) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf.bytes())) {
            PDFont font = doc.getPage(0).getResources().getFont(COSName.getPDFName("T3"));
            byte[] ttf = resources ? WebFonts.toTrueType(font, doc.getPage(0).getResources())
                    : WebFonts.toTrueType(font);
            assertTrue(TrueTypeSanity.sane(ttf));
            assertEquals(0x00010000, java.nio.ByteBuffer.wrap(ttf).getInt(0));
            try (PDDocument out = new PDDocument()) {
                PDType0Font.load(out, new ByteArrayInputStream(ttf), false);
            }
            try {
                Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(ttf));
            } catch (java.awt.FontFormatException e) {
                throw new IOException(e);
            }
            return new TTFParser().parse(new RandomAccessReadBuffer(ttf));
        }
    }

    private static int gid(TrueTypeFont ttf, int code) throws IOException {
        CmapSubtable symbol = ttf.getCmap().getSubtable(3, 0);
        return symbol.getGlyphId(0xF000 + code);
    }

    private static GeneralPath path(TrueTypeFont ttf, int code) throws IOException {
        return ttf.getGlyph().getGlyph(gid(ttf, code)).getPath();
    }

    private static void assertRendering(RawPdf pdf, boolean resources) throws IOException {
        try (PDDocument original = Loader.loadPDF(pdf.bytes()); PDDocument converted = new PDDocument()) {
            PDFont font = original.getPage(0).getResources().getFont(COSName.getPDFName("T3"));
            byte[] bytes = resources ? WebFonts.toTrueType(font, original.getPage(0).getResources())
                    : WebFonts.toTrueType(font);
            PDType0Font ttf = PDType0Font.load(converted, new ByteArrayInputStream(bytes), false);
            PDPage page = new PDPage(original.getPage(0).getMediaBox());
            converted.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(converted, page)) {
                content.beginText();
                content.setFont(ttf, 50);
                content.newLineAtOffset(20, 100);
                content.showText("a");
                content.endText();
            }
            BufferedImage before = new PDFRenderer(original).renderImage(0, 1);
            BufferedImage after = new PDFRenderer(converted).renderImage(0, 1);
            int ink = 0;
            for (int y = 0; y < before.getHeight(); y++) {
                for (int x = 0; x < before.getWidth(); x++) {
                    int expected = before.getRGB(x, y);
                    if ((expected & 0xFFFFFF) != 0xFFFFFF) {
                        ink++;
                    }
                    assertEquals(expected, after.getRGB(x, y), x + "," + y);
                }
            }
            assertTrue(ink > 100);
        }
    }

    @Test
    void fillsStrokesEvenOddHolesAndImageMasksBecomeOutlines() throws Exception {
        try (TrueTypeFont ttf = convert(type3("0.001 0 0 0.001 0 0", "600 700 500 500",
                "600 0 0 0 500 700 d1 100 0 300 700 re f",
                "700 0 d0 0 0 m 600 0 l 600 600 l 0 600 l h 200 200 m 400 200 l 400 400 l 200 400 l h f*",
                "500 0 d0 50 w 0 0 m 400 400 l S",
                "500 0 0 0 500 500 d1 q 400 0 0 400 50 50 cm BI /W 2 /H 2 /IM true /BPC 1 ID @\u0080 EI Q"))) {
            assertEquals(1000, ttf.getUnitsPerEm());
            assertEquals(5, ttf.getNumberOfGlyphs());
            GeneralPath a = path(ttf, 'a');
            Rectangle2D box = a.getBounds2D();
            assertEquals(100, box.getMinX(), 1);
            assertEquals(400, box.getMaxX(), 1);
            assertEquals(700, box.getMaxY(), 1);
            assertEquals(600, ttf.getAdvanceWidth(gid(ttf, 'a')));
            assertEquals(gid(ttf, 'a'), ttf.getUnicodeCmapLookup().getGlyphId('a'));

            GeneralPath ring = path(ttf, 'b');
            assertTrue(ring.contains(100, 100));
            assertFalse(ring.contains(300, 300));
            assertEquals(700, ttf.getAdvanceWidth(gid(ttf, 'b')));

            GeneralPath line = path(ttf, 'c');
            assertTrue(line.contains(200, 200));
            assertFalse(line.contains(300, 100));

            GeneralPath bits = path(ttf, 'd');
            assertTrue(bits.contains(150, 350));
            assertFalse(bits.contains(350, 350));
            assertTrue(bits.contains(350, 150));
            assertFalse(bits.contains(150, 150));
        }
    }

    @Test
    void scaledAndFlippedFontMatricesLandOnAThousandUnitEm() throws Exception {
        try (TrueTypeFont ttf = convert(type3("0.01 0 0 -0.01 0 0", "60 70 50 50",
                "60 0 d0 10 0 30 -70 re f", "70 0 d0", "50 0 d0", "50 0 d0"))) {
            Rectangle2D box = path(ttf, 'a').getBounds2D();
            assertEquals(100, box.getMinX(), 1);
            assertEquals(400, box.getMaxX(), 1);
            assertEquals(0, box.getMinY(), 1);
            assertEquals(700, box.getMaxY(), 1);
            assertEquals(600, ttf.getAdvanceWidth(gid(ttf, 'a')));
            assertEquals(700, ttf.getAdvanceWidth(gid(ttf, 'b')));
        }
    }

    @Test
    void clipsShadingsAndPatternsPaintWhatTheyCover() throws Exception {
        RawPdf pdf = RawPdf.page("/Font<</T3 9 0 R>>", "BT /T3 50 Tf 20 100 Td (efg) Tj ET");
        pdf.add(RawPdf.stream("", "500 0 d0 q 0 0 200 300 re W n 0 0 500 500 re f Q 400 400 50 50 re f"));
        pdf.add(RawPdf.stream("", "500 0 d0 q 100 100 300 200 re W n /Sh0 sh Q /Sh0 sh"));
        pdf.add(RawPdf.stream("", "500 0 d0 /Pattern cs /P0 scn 0 0 300 300 re f"));
        pdf.add(RawPdf.stream("/PatternType 1/PaintType 1/TilingType 1/BBox[0 0 10 10]/XStep 10/YStep 10/Resources<<>>",
                "0 0 5 5 re f"));
        pdf.add("<</Type/Font/Subtype/Type3/FontBBox[0 0 0 0]/FontMatrix[0.001 0 0 0.001 0 0]"
                + "/CharProcs<</e 5 0 R/f 6 0 R/g 7 0 R>>/Resources<</Pattern<</P0 8 0 R>>/Shading<</Sh0"
                + "<</ShadingType 2/ColorSpace/DeviceGray/Coords[0 0 500 0]/Function<</FunctionType 2/Domain[0 1]"
                + "/C0[0]/C1[1]/N 1>>>>>>>>/Encoding<</Type/Encoding/Differences[101/e/f/g]>>"
                + "/FirstChar 101/LastChar 103/Widths[500 500 500]>>");
        try (TrueTypeFont ttf = convert(pdf)) {
            Rectangle2D clipped = path(ttf, 'e').getBounds2D();
            assertEquals(0, clipped.getMinX(), 1);
            assertEquals(450, clipped.getMaxX(), 1);
            assertTrue(path(ttf, 'e').contains(100, 100));
            assertFalse(path(ttf, 'e').contains(300, 100));
            Rectangle2D shaded = path(ttf, 'f').getBounds2D();
            assertEquals(100, shaded.getMinX(), 1);
            assertEquals(400, shaded.getMaxX(), 1);
            assertEquals(300, shaded.getMaxY(), 1);
            Rectangle2D patterned = path(ttf, 'g').getBounds2D();
            assertEquals(300, patterned.getMaxX(), 1);
            assertEquals(300, patterned.getMaxY(), 1);
        }
    }

    @Test
    void softMasksCutTheShapeTheyLetThrough() throws Exception {
        RawPdf pdf = RawPdf.page("/Font<</T3 8 0 R>>", "BT /T3 50 Tf 20 100 Td (ab) Tj ET");
        pdf.add(RawPdf.stream("", "500 0 d0 /L gs 0 0 500 500 re f"));
        pdf.add(RawPdf.stream("", "500 0 d0 /A gs 0 0 500 500 re f"));
        pdf.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 1000 1000]/Group<</S/Transparency/CS/DeviceGray>>",
                "0 g 0 0 1000 1000 re f 1 g 0 0 200 300 re f"));
        pdf.add("<</Type/Font/Subtype/Type3/FontBBox[0 0 0 0]/FontMatrix[0.001 0 0 0.001 0 0]"
                + "/CharProcs<</a 5 0 R/b 6 0 R>>/Resources<</ExtGState<</L<</SMask<</S/Luminosity/G 7 0 R>>>>"
                + "/A<</SMask<</S/Alpha/G 9 0 R>>>>>>>>/Encoding<</Type/Encoding/Differences[97/a/b]>>"
                + "/FirstChar 97/LastChar 98/Widths[500 500]>>");
        pdf.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 1000 1000]/Group<</S/Transparency>>",
                "0 g 100 100 50 50 re f"));
        try (TrueTypeFont ttf = convert(pdf)) {
            Rectangle2D luminosity = path(ttf, 'a').getBounds2D();
            assertEquals(0, luminosity.getMinX(), 1);
            assertEquals(200, luminosity.getMaxX(), 1);
            assertEquals(300, luminosity.getMaxY(), 1);
            Rectangle2D alpha = path(ttf, 'b').getBounds2D();
            assertEquals(100, alpha.getMinX(), 1);
            assertEquals(150, alpha.getMaxX(), 1);
        }
    }

    @Test
    void softMaskGroupsInheritNestedFormResources() throws Exception {
        RawPdf pdf = RawPdf.page("/Font<</T3 8 0 R>>", "BT /T3 50 Tf 20 100 Td (a) Tj ET");
        pdf.add(RawPdf.stream("", "500 0 d0 /L gs 0 0 500 500 re f"));
        pdf.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 1000 1000]"
                + "/Group<</S/Transparency/CS/DeviceGray>>", "/Ink Do"));
        pdf.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 1000 1000]",
                "1 g 100 100 200 300 re f"));
        pdf.add("<</Type/Font/Subtype/Type3/FontBBox[0 0 500 500]/FontMatrix[0.001 0 0 0.001 0 0]"
                + "/CharProcs<</a 5 0 R>>/Resources<</XObject<</Ink 7 0 R>>"
                + "/ExtGState<</L<</SMask<</S/Luminosity/G 6 0 R>>>>>>>>"
                + "/Encoding<</Type/Encoding/Differences[97/a]>>/FirstChar 97/LastChar 97/Widths[500]>>");
        try (TrueTypeFont ttf = convert(pdf)) {
            Rectangle2D box = path(ttf, 'a').getBounds2D();
            assertEquals(100, box.getMinX(), 1);
            assertEquals(300, box.getMaxX(), 1);
            assertEquals(100, box.getMinY(), 1);
            assertEquals(400, box.getMaxY(), 1);
            assertEquals(500, ttf.getAdvanceWidth(gid(ttf, 'a')));
        }
        assertRendering(pdf, false);
    }

    @Test
    void glyphsCanInheritPageResourcesThroughNestedForms() throws Exception {
        RawPdf pdf = RawPdf.page("/Font<</T3 8 0 R>>/XObject<</Outer 6 0 R/Ink 7 0 R>>",
                "BT /T3 50 Tf 20 100 Td (a) Tj ET");
        pdf.add(RawPdf.stream("", "500 0 d0 /Outer Do"));
        pdf.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 500 500]", "/Ink Do"));
        pdf.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 500 500]", "100 100 200 300 re f"));
        pdf.add("<</Type/Font/Subtype/Type3/FontBBox[0 0 500 500]/FontMatrix[0.001 0 0 0.001 0 0]"
                + "/CharProcs<</a 5 0 R>>/Encoding<</Type/Encoding/Differences[97/a]>>"
                + "/FirstChar 97/LastChar 97/Widths[500]>>");
        try (TrueTypeFont ttf = convert(pdf, true)) {
            Rectangle2D box = path(ttf, 'a').getBounds2D();
            assertEquals(100, box.getMinX(), 1);
            assertEquals(300, box.getMaxX(), 1);
            assertEquals(100, box.getMinY(), 1);
            assertEquals(400, box.getMaxY(), 1);
        }
        assertRendering(pdf, true);
    }

    @Test
    void inheritedResourcesResolveNestedFontText() throws Exception {
        RawPdf pdf = RawPdf.page("/Font<</T3 6 0 R/F1<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>>>",
                "BT /T3 50 Tf 20 100 Td (a) Tj ET");
        pdf.add(RawPdf.stream("", "500 0 d0 BT /F1 600 Tf 100 100 Td (H) Tj ET"));
        pdf.add("<</Type/Font/Subtype/Type3/FontBBox[0 0 500 500]/FontMatrix[0.001 0 0 0.001 0 0]"
                + "/CharProcs<</a 5 0 R>>/Encoding<</Type/Encoding/Differences[97/a]>>"
                + "/FirstChar 97/LastChar 97/Widths[500]>>");
        try (TrueTypeFont ttf = convert(pdf, true)) {
            Rectangle2D box = path(ttf, 'a').getBounds2D();
            assertTrue(box.getWidth() > 100);
            assertTrue(box.getHeight() > 100);
            assertEquals(500, ttf.getAdvanceWidth(gid(ttf, 'a')));
        }
    }

    @Test
    void fontResourcesTakePrecedenceOverContainingPageResources() throws Exception {
        RawPdf pdf = RawPdf.page("/Font<</T3 8 0 R>>/XObject<</Ink 6 0 R>>",
                "BT /T3 50 Tf 20 100 Td (a) Tj ET");
        pdf.add(RawPdf.stream("", "500 0 d0 /Ink Do"));
        pdf.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 500 500]", "0 0 50 50 re f"));
        pdf.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 500 500]", "100 100 200 300 re f"));
        pdf.add("<</Type/Font/Subtype/Type3/FontBBox[0 0 500 500]/FontMatrix[0.001 0 0 0.001 0 0]"
                + "/CharProcs<</a 5 0 R>>/Resources<</XObject<</Ink 7 0 R>>>>"
                + "/Encoding<</Type/Encoding/Differences[97/a]>>/FirstChar 97/LastChar 97/Widths[500]>>");
        try (TrueTypeFont ttf = convert(pdf, true)) {
            Rectangle2D box = path(ttf, 'a').getBounds2D();
            assertEquals(100, box.getMinX(), 1);
            assertEquals(300, box.getMaxX(), 1);
            assertEquals(400, box.getMaxY(), 1);
        }
        assertRendering(pdf, true);
    }

    @Test
    void inheritedSelfReferencesRemainBoundedWithoutDroppingIndependentInk() throws Exception {
        RawPdf pdf = RawPdf.page("/Font<</T3 6 0 R>>", "BT /T3 50 Tf 20 100 Td (a) Tj ET");
        pdf.add(RawPdf.stream("", "500 0 d0 BT /T3 1000 Tf (a) Tj ET 100 100 200 300 re f"));
        pdf.add("<</Type/Font/Subtype/Type3/FontBBox[0 0 500 500]/FontMatrix[0.001 0 0 0.001 0 0]"
                + "/CharProcs<</a 5 0 R>>/Encoding<</Type/Encoding/Differences[97/a]>>"
                + "/FirstChar 97/LastChar 97/Widths[500]>>");
        try (TrueTypeFont ttf = convert(pdf, true)) {
            Rectangle2D box = path(ttf, 'a').getBounds2D();
            assertEquals(100, box.getMinX(), 1);
            assertEquals(300, box.getMaxX(), 1);
            assertEquals(400, box.getMaxY(), 1);
            assertEquals(500, ttf.getAdvanceWidth(gid(ttf, 'a')));
        }
    }

    @Test
    void sharedProceduresWithOneAdvanceShareAGlyph() throws Exception {
        RawPdf pdf = RawPdf.page("/Font<</T3<</Type/Font/Subtype/Type3/FontBBox[0 0 0 0]"
                + "/FontMatrix[0.001 0 0 0.001 0 0]/CharProcs<</a 5 0 R>>"
                + "/Encoding<</Type/Encoding/Differences[97/a/a/a]>>/FirstChar 97/LastChar 99/Widths[500 500 900]>>>>",
                "BT /T3 50 Tf 20 100 Td (abc) Tj ET");
        pdf.add(RawPdf.stream("", "500 0 d0 0 0 400 400 re f"));
        try (TrueTypeFont ttf = convert(pdf)) {
            assertEquals(3, ttf.getNumberOfGlyphs());
            assertEquals(gid(ttf, 'a'), gid(ttf, 'b'));
            assertEquals(900, ttf.getAdvanceWidth(gid(ttf, 'c')));
        }
    }

    @Test
    void selfReferencingGlyphsAndFontsWithoutProceduresAreBounded() throws Exception {
        RawPdf loop = RawPdf.page("/Font<</T3 9 0 R>>", "BT /T3 50 Tf 20 100 Td (a) Tj ET");
        loop.add(RawPdf.stream("", "500 0 d0 BT /T3 1 Tf (a) Tj ET 0 0 100 100 re f"));
        for (int i = 6; i < 9; i++) {
            loop.add("null");
        }
        loop.add("<</Type/Font/Subtype/Type3/FontBBox[0 0 0 0]/FontMatrix[0.001 0 0 0.001 0 0]"
                + "/CharProcs<</a 5 0 R>>/Resources<</Font<</T3 9 0 R>>>>"
                + "/Encoding<</Type/Encoding/Differences[97/a]>>/FirstChar 97/LastChar 97/Widths[500]>>");
        try (TrueTypeFont ttf = convert(loop)) {
            assertTrue(path(ttf, 'a').contains(50, 50));
        }
        RawPdf empty = RawPdf.page("/Font<</T3<</Type/Font/Subtype/Type3/FontBBox[0 0 0 0]"
                + "/FontMatrix[0.001 0 0 0.001 0 0]/CharProcs<<>>/Encoding<</Type/Encoding/Differences[97/a]>>"
                + "/FirstChar 97/LastChar 97/Widths[500]>>>>", "BT /T3 50 Tf 20 100 Td (a) Tj ET");
        try (PDDocument doc = Loader.loadPDF(empty.bytes())) {
            PDFont font = doc.getPage(0).getResources().getFont(COSName.getPDFName("T3"));
            assertThrows(IOException.class, () -> WebFonts.toTrueType(font));
        }
    }
    @Test
    void glyphsThatShowTheirOwnFontManyTimesStopAtTheWorkBudget() throws Exception {
        RawPdf pdf = RawPdf.page("/Font<</T3 6 0 R>>", "BT /T3 50 Tf 20 100 Td (a) Tj ET");
        pdf.add(RawPdf.stream("", "500 0 d0 0 0 100 100 re f BT /T3 1 Tf (" + "a".repeat(200) + ") Tj ET"));
        pdf.add("<</Type/Font/Subtype/Type3/FontBBox[0 0 0 0]/FontMatrix[0.001 0 0 0.001 0 0]"
                + "/CharProcs<</a 5 0 R>>/Resources<</Font<</T3 6 0 R>>>>"
                + "/Encoding<</Type/Encoding/Differences[97/a]>>/FirstChar 97/LastChar 97/Widths[500]>>");
        assertStopsQuickly(pdf);
    }

    @Test
    void formsThatDrawThemselvesTwiceStopAtTheWorkBudget() throws Exception {
        RawPdf pdf = RawPdf.page("/Font<</T3 7 0 R>>", "BT /T3 50 Tf 20 100 Td (a) Tj ET");
        pdf.add(RawPdf.stream("", "500 0 d0 /F Do /F Do"));
        pdf.add(RawPdf.stream("/Type/XObject/Subtype/Form/BBox[0 0 1000 1000]/Resources<</XObject<</F 6 0 R>>>>",
                "0 0 100 100 re f /F Do /F Do"));
        pdf.add("<</Type/Font/Subtype/Type3/FontBBox[0 0 0 0]/FontMatrix[0.001 0 0 0.001 0 0]"
                + "/CharProcs<</a 5 0 R>>/Resources<</XObject<</F 6 0 R>>>>"
                + "/Encoding<</Type/Encoding/Differences[97/a]>>/FirstChar 97/LastChar 97/Widths[500]>>");
        assertStopsQuickly(pdf);
    }

    private static void assertStopsQuickly(RawPdf pdf) {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            try (PDDocument doc = Loader.loadPDF(pdf.bytes())) {
                PDFont font = doc.getPage(0).getResources().getFont(COSName.getPDFName("T3"));
                IOException e = assertThrows(IOException.class, () -> WebFonts.toTrueType(font));
                assertTrue(e.getMessage().contains("operations"), e.getMessage());
            }
        });
    }

    @Test
    void manyCrossingShapesAreUnitedWithinABoundedAmountOfWork() throws Exception {
        StringBuilder proc = new StringBuilder("1000 0 0 0 1000 1000 d1 ");
        for (int i = 0; i < 5000; i++) {
            proc.append((i * 37) % 900).append(' ').append((i * 53) % 900).append(" m ").append((i * 71) % 900)
                    .append(' ').append((i * 29) % 900).append(" l ").append((i * 17) % 900).append(' ')
                    .append((i * 91) % 900).append(" l f ");
        }
        RawPdf pdf = RawPdf.page("/Font<</T3<</Type/Font/Subtype/Type3/FontBBox[0 0 0 0]"
                + "/FontMatrix[0.001 0 0 0.001 0 0]/CharProcs<</a 5 0 R>>/Encoding<</Type/Encoding/Differences[97/a]>>"
                + "/FirstChar 97/LastChar 97/Widths[1000]>>>>", "BT /T3 50 Tf 20 100 Td (a) Tj ET");
        pdf.add(RawPdf.stream("", proc.toString()));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (TrueTypeFont ttf = convert(pdf)) {
                assertEquals(2, ttf.getNumberOfGlyphs());
                assertEquals(1000, ttf.getAdvanceWidth(gid(ttf, 'a')));
            }
        });
    }
}

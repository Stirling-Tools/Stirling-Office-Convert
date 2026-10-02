package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.PdfOutput;

class MissingGlyphsTest {

    @Test
    void uncoveredCharactersTakeTheWidthOfficeGivesTheirScript() {
        assertEquals(1, MissingGlyphs.em('株'), 1e-12);
        assertEquals(1, MissingGlyphs.em('あ'), 1e-12);
        assertEquals(1, MissingGlyphs.em('한'), 1e-12);
        assertEquals(1, MissingGlyphs.em('（'), 1e-12);
        assertEquals(0.5, MissingGlyphs.em('ｱ'), 1e-12);
        assertEquals(0, MissingGlyphs.em(0x0301), 1e-12);
        assertEquals(0, MissingGlyphs.em(0x200D), 1e-12);
        assertEquals(MissingGlyphs.OTHER_EM, MissingGlyphs.em('क'), 1e-12);
        assertEquals(30, MissingGlyphs.width("株主資", 10), 1e-9);
    }

    @Test
    void layoutKeepsTheWidthOfCharactersNoFontCovers() {
        Typesetter t = new Typesetter(FontLibrary.of(List.of()));
        FontSpec f = new FontSpec("Calibri", 10, false, false, null, false, Color.BLACK, null);
        assertTrue(MissingGlyphs.missing(t.face(f), '株'));
        double latin = t.width("AB", f, 10);
        assertEquals(latin + 3 * t.grid(10), t.width("A株主資B", f, 10), 0.01);
        assertEquals(t.screenWidth("AB", f, 13) + 26, t.screenWidth("A株主B", f, 13), 0.01);
    }

    @Test
    void eastAsianTextBreaksBetweenCharacters() {
        assertTrue(MissingGlyphs.breakBetween('株', '主'));
        assertTrue(MissingGlyphs.breakBetween('a', '株'));
        assertFalse(MissingGlyphs.breakBetween('株', '。'));
        assertFalse(MissingGlyphs.breakBetween('（', '株'));
        assertFalse(MissingGlyphs.breakBetween('a', 'b'));
        FontSpec f = new FontSpec("Calibri", 10, false, false, null, false, Color.BLACK, null);
        List<CellLayout.Line> lines = CellLayout.wrap(List.of(new TextRun("株主資本以外。", f)), 35,
                (s, font) -> s.codePointCount(0, s.length()) * 10.0);
        assertEquals(3, lines.size());
        assertEquals("株主資", lines.get(0).runs().get(0).text());
        assertEquals("本以", lines.get(1).runs().get(0).text());
        assertEquals("外。", lines.get(2).runs().get(0).text());
    }

    @Test
    void charactersNoFontCoversShowAsEmptyBoxesInTheirWidth() throws Exception {
        FontLibrary fonts = FontLibrary.of(List.of());
        Typesetter t = new Typesetter(fonts);
        FontSpec f = new FontSpec("Calibri", 10, false, false, null, false, Color.BLACK, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        double drawn;
        try (PdfOutput pdf = new PdfOutput(fonts)) {
            try (PdfCanvas canvas = pdf.newPage(200, 100)) {
                drawn = t.draw(canvas, "A株 主B", f, 10, 10, 50);
            }
            pdf.save(out);
        }
        assertEquals(t.width("A株 主B", f, 10), drawn, 1e-6);
        try (PDDocument doc = Loader.loadPDF(out.toByteArray())) {
            int boxes = 0;
            for (Object token : new PDFStreamParser(doc.getPage(0)).parse()) {
                if (token instanceof Operator op && op.getName().equals("S")) {
                    boxes++;
                }
            }
            assertEquals(2, boxes);
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("A") && text.contains("B"), text);
        }
    }
}

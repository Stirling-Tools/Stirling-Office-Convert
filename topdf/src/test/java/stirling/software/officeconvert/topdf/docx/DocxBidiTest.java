package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.FontLibrary;

class DocxBidiTest {

    @TempDir
    Path dir;

    private record Glyph(String text, float x, float right, float y) {}

    private static String rtlParagraph(String jc, String text) {
        String align = jc == null ? "" : "<w:jc w:val=\"" + jc + "\"/>";
        return "<w:p><w:pPr><w:bidi/>" + align + "</w:pPr><w:r><w:rPr><w:rFonts w:cs=\"Arial\"/><w:rtl/></w:rPr>"
                + "<w:t xml:space=\"preserve\">" + DocxDoc.escape(text) + "</w:t></w:r></w:p>";
    }

    private List<Glyph> glyphs(String name, String body) throws IOException {
        DocxDoc.Rendered r = DocxDoc.render(dir, name, new DocxDoc().body(body).bytes());
        List<Glyph> out = new ArrayList<>();
        try (PDDocument d = r.open()) {
            PDFTextStripper s = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    for (TextPosition p : positions) {
                        out.add(new Glyph(p.getUnicode(), p.getXDirAdj(), p.getXDirAdj() + p.getWidthDirAdj(),
                                p.getYDirAdj()));
                    }
                }
            };
            s.getText(d);
        }
        return out;
    }

    private static float x(List<Glyph> glyphs, String text) {
        for (Glyph g : glyphs) {
            if (g.text().equals(text)) {
                return g.x();
            }
        }
        throw new AssertionError("no glyph " + text + " in " + glyphs);
    }

    @Test
    void digitsInsideRightToLeftRunsKeepTheirOrder() throws IOException {
        List<Glyph> g = glyphs("digits", rtlParagraph(null, "\u05E0\u05D5\u05DC\u05D3 \u05D1-1948 \u05D1\u05D9\u05E8\u05D5"
                + "\u05E9\u05DC\u05D9\u05DD"));
        assertTrue(x(g, "1") < x(g, "9") && x(g, "9") < x(g, "4") && x(g, "4") < x(g, "8"), g.toString());
        assertTrue(x(g, "\u05E0") > x(g, "8"), g.toString());
    }

    @Test
    void arabicNumberAfterAColonStaysLeftToRight() throws IOException {
        List<Glyph> g = glyphs("colon", rtlParagraph(null, "\u0631\u0642\u0645:12345"));
        assertTrue(x(g, "1") < x(g, "2") && x(g, "2") < x(g, "3") && x(g, "3") < x(g, "4") && x(g, "4") < x(g, "5"),
                g.toString());
        assertTrue(x(g, "5") < x(g, ":"), g.toString());
    }

    @Test
    void justifiedRightToLeftLinesEndAtTheRightMargin() throws IOException {
        String sentence = "\u05DB\u05DC \u05D1\u05E0\u05D9 \u05D0\u05D3\u05DD \u05E0\u05D5\u05DC\u05D3\u05D5 "
                + "\u05D1\u05E0\u05D9 \u05D7\u05D5\u05E8\u05D9\u05DF \u05D5\u05E9\u05D5\u05D5\u05D9\u05DD ";
        List<Glyph> g = glyphs("justified", rtlParagraph("both", sentence.repeat(9) + "\u05E1\u05D5\u05E3"));
        Map<Integer, Float> ends = new TreeMap<>();
        for (Glyph glyph : g) {
            if (!glyph.text().isBlank()) {
                ends.merge(Math.round(glyph.y()), glyph.right(), Math::max);
            }
        }
        assertTrue(ends.size() >= 2, ends.toString());
        for (float end : ends.values()) {
            assertEquals(540, end, 1.5f, ends.toString());
        }
    }

    @Test
    void chartTextInOtherScriptsFallsBackAndReadsInOrder() throws IOException {
        String title = "\u9500\u552E\u989D \u0627\u0644\u0645\u0628\u064A\u0639\u0627\u062A";
        String chart = DocxDoc.chartXml(title, "\u7CFB\u5217\u4E00", "\u5317\u4EAC", "\u0627\u0644\u0642\u0627\u0647\u0631\u0629");
        DocxDoc doc = new DocxDoc().chart("chart1.xml", "rIdChart", chart)
                .body("<w:p>" + DocxDoc.chartRun("rIdChart") + "</w:p>");
        String text = DocxDoc.render(dir, "chart", doc.bytes()).text();
        assertTrue(text.contains("\u9500\u552E\u989D") && text.contains("\u5317\u4EAC"), text);
        // PDFBox reads glyphs left to right: a right-to-left word drawn in visual order comes out reversed
        String arabic = "\u0627\u0644\u0645\u0628\u064A\u0639\u0627\u062A";
        assertTrue(text.contains(new StringBuilder(arabic).reverse()), text);
    }

    @Test
    void theStartIndentOfARightToLeftParagraphIsOnTheRight() throws IOException {
        String body = "<w:p><w:pPr><w:bidi/><w:ind w:left=\"1440\"/></w:pPr><w:r><w:rPr><w:rtl/></w:rPr><w:t>\u05E9\u05DC\u05D5\u05DD</w:t>"
                + "</w:r></w:p>";
        List<Glyph> g = glyphs("indent", body);
        float right = 0;
        for (Glyph glyph : g) {
            right = Math.max(right, glyph.right());
        }
        assertEquals(540 - 72, right, 1.5f, g.toString());
    }

    @Test
    void theThemeScriptFontServesComplexScriptRunsInTheirLanguage() throws IOException {
        assumeTrue(FontLibrary.system().exact("Times New Roman", false, false) != null, "no Times New Roman here");
        String theme = "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"T\">"
                + "<a:themeElements><a:fontScheme name=\"F\"><a:majorFont><a:latin typeface=\"Calibri\"/>"
                + "<a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/>"
                + "<a:ea typeface=\"\"/><a:cs typeface=\"\"/><a:font script=\"Arab\" typeface=\"Times New Roman\"/>"
                + "</a:minorFont></a:fontScheme></a:themeElements></a:theme>";
        String body = "<w:p><w:pPr><w:bidi/></w:pPr><w:r><w:rPr><w:rFonts w:cstheme=\"minorBidi\"/><w:rtl/>"
                + "<w:lang w:bidi=\"ar-SA\"/></w:rPr><w:t>\u0645\u0631\u062D\u0628\u0627\u0020\u0628\u0627\u0644\u0639\u0627\u0644\u0645</w:t></w:r></w:p>";
        DocxDoc doc = new DocxDoc().part("theme/theme1.xml", "theme",
                "application/vnd.openxmlformats-officedocument.theme+xml", theme).body(body);
        DocxDoc.Rendered r = DocxDoc.render(dir, "theme", doc.bytes());
        List<String> fonts = new ArrayList<>();
        try (PDDocument d = r.open()) {
            new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    positions.forEach(p -> fonts.add(p.getFont().getName()));
                }
            }.getText(d);
        }
        assertTrue(!fonts.isEmpty() && fonts.stream().allMatch(f -> f.contains("TimesNewRoman")), fonts.toString());
    }
}

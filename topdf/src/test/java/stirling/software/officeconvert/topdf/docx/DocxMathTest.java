package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocxMathTest {

    private static final String M = " xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"";

    private static final String STYLES = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Liberation Serif\""
            + " w:hAnsi=\"Liberation Serif\"/><w:sz w:val=\"24\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";

    @TempDir
    Path dir;

    private static String r(String t) {
        return "<m:r><m:t>" + t + "</m:t></m:r>";
    }

    private static String display(String math) {
        return "<w:p" + M + "><m:oMathPara><m:oMath>" + math + "</m:oMath></m:oMathPara></w:p>";
    }

    private static String inline(String before, String math, String after) {
        return "<w:p" + M + "><w:r><w:t xml:space=\"preserve\">" + before + "</w:t></w:r><m:oMath>" + math
                + "</m:oMath><w:r><w:t xml:space=\"preserve\">" + after + "</w:t></w:r></w:p>";
    }

    private record Glyph(String text, float x, float y, float size) {}

    private static List<Glyph> glyphs(DocxDoc.Rendered out) throws IOException {
        List<Glyph> list = new ArrayList<>();
        try (PDDocument d = out.open()) {
            PDFTextStripper s = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    for (TextPosition p : positions) {
                        list.add(new Glyph(p.getUnicode(), p.getXDirAdj(), p.getYDirAdj(), p.getFontSizeInPt()));
                    }
                }
            };
            s.getText(d);
        }
        return list;
    }

    // The first glyph of a run of characters spelling the text
    private static Glyph at(List<Glyph> all, String text) {
        for (int i = 0; i + text.length() <= all.size(); i++) {
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < text.length(); k++) {
                sb.append(all.get(i + k).text());
            }
            if (sb.toString().equals(text)) {
                return all.get(i);
            }
        }
        throw new AssertionError("no " + text + " in " + all);
    }

    private DocxDoc.Rendered render(String name, String body) throws IOException {
        return DocxDoc.render(dir, name, new DocxDoc().styles(STYLES).body(body).bytes());
    }

    @Test
    void aFractionStacksItsNumeratorOverItsDenominatorInTheMiddleOfTheLine() throws IOException {
        DocxDoc.Rendered out = render("fraction",
                display("<m:f><m:num>" + r("top") + "</m:num><m:den>" + r("bottomline") + "</m:den></m:f>"));
        List<Glyph> g = glyphs(out);
        Glyph top = at(g, "top");
        Glyph bottom = at(g, "bottomline");
        assertTrue(bottom.y() - top.y() > 10, top + " " + bottom);
        assertTrue(top.x() > bottom.x() + 5, "the shorter numerator is centred " + top + " " + bottom);
        assertTrue(bottom.x() > 200 && bottom.x() < 300, "a display equation is centred " + bottom);
    }

    @Test
    void scriptsAreRaisedAndLoweredBesideTheirBase() throws IOException {
        DocxDoc.Rendered out = render("scripts", inline("Area ", "<m:sSup><m:e>" + r("r") + "</m:e><m:sup>" + r("2")
                + "</m:sup></m:sSup><m:sSub><m:e>" + r("x") + "</m:e><m:sub>" + r("k") + "</m:sub></m:sSub>",
                " end"));
        List<Glyph> g = glyphs(out);
        Glyph area = at(g, "Area");
        Glyph sup = at(g, "2");
        Glyph sub = at(g, "k");
        assertTrue(sup.y() < area.y() - 2 && sup.size() < area.size(), "superscript " + sup + " line " + area);
        assertTrue(sub.y() > area.y() + 1 && sub.size() < area.size(), "subscript " + sub + " line " + area);
    }

    @Test
    void aMatrixSetsItsCellsInRowsAndColumns() throws IOException {
        String cell = "<m:e>%s</m:e>";
        String m = "<m:d><m:dPr><m:begChr m:val=\"[\"/><m:endChr m:val=\"]\"/></m:dPr><m:e><m:m><m:mr>"
                + String.format(cell, r("11")) + String.format(cell, r("22")) + "</m:mr><m:mr>"
                + String.format(cell, r("33")) + String.format(cell, r("44")) + "</m:mr></m:m></m:e></m:d>";
        DocxDoc.Rendered out = render("matrix", display(m));
        List<Glyph> g = glyphs(out);
        Glyph a = at(g, "11");
        Glyph b = at(g, "22");
        Glyph c = at(g, "33");
        assertEquals(a.y(), b.y(), 0.5f);
        assertEquals(a.x(), c.x(), 1f);
        assertTrue(c.y() > a.y() + 8 && b.x() > a.x() + 10, a + " " + b + " " + c);
    }

    @Test
    void largeOperatorsCarryTheirLimitsAboveAndBelowInDisplay() throws IOException {
        String sum = "<m:nary><m:naryPr><m:chr m:val=\"∑\"/></m:naryPr><m:sub>" + r("low") + "</m:sub><m:sup>"
                + r("high") + "</m:sup><m:e>" + r("body") + "</m:e></m:nary>";
        DocxDoc.Rendered out = render("nary", display(sum));
        List<Glyph> g = glyphs(out);
        Glyph low = at(g, "low");
        Glyph high = at(g, "high");
        Glyph body = at(g, "body");
        assertTrue(high.y() < body.y() - 8 && low.y() > body.y() + 6, high + " " + body + " " + low);
        assertTrue(body.x() > low.x() + 5, body + " " + low);
    }

    @Test
    void radicalsAndDelimitersKeepTheirContents() throws IOException {
        String math = "<m:rad><m:radPr><m:degHide m:val=\"1\"/></m:radPr><m:deg/><m:e>" + r("root") + "</m:e>"
                + "</m:rad><m:d><m:e><m:f><m:num>" + r("aa") + "</m:num><m:den>" + r("bb") + "</m:den></m:f></m:e>"
                + "</m:d>";
        DocxDoc.Rendered out = render("radical", display(math));
        List<Glyph> g = glyphs(out);
        assertTrue(at(g, "bb").y() > at(g, "aa").y() + 5, g.toString());
        at(g, "root");
    }

    @Test
    void aTallEquationMakesItsLineTallerButKeepsTheTextOnTheBaseline() throws IOException {
        String body = "<w:p><w:r><w:t>First</w:t></w:r></w:p>" + inline("Left ", "<m:f><m:num>" + r("n")
                + "</m:num><m:den>" + r("d") + "</m:den></m:f>", " right") + "<w:p><w:r><w:t>Next</w:t></w:r></w:p>";
        DocxDoc.Rendered out = render("tall", body);
        List<Glyph> g = glyphs(out);
        Glyph left = at(g, "Left");
        Glyph right = at(g, "right");
        assertEquals(left.y(), right.y(), 0.5f);
        float first = at(g, "First").y();
        float next = at(g, "Next").y();
        assertTrue(next - first > 2 * 13.8f + 4, "first " + first + " next " + next);
        assertTrue(at(g, "d").y() > left.y(), "the denominator hangs below the text line");
    }
}

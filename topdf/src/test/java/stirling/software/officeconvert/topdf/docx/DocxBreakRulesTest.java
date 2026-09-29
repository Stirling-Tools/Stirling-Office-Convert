package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Fixtures;

class DocxBreakRulesTest {

    private static final String FONT = "Liberation Sans";

    private static final String DEFAULTS = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"" + FONT
            + "\" w:hAnsi=\"" + FONT + "\"/><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";

    private static final String NUMBERING = "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:start"
            + " w:val=\"3\"/><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/><w:lvlJc w:val=\"left\"/><w:pPr>"
            + "<w:ind w:left=\"720\" w:hanging=\"720\"/></w:pPr></w:lvl></w:abstractNum><w:num w:numId=\"1\">"
            + "<w:abstractNumId w:val=\"0\"/></w:num>";

    private static final String NUMBERED = "<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>";

    private static final String PAGE_BREAK = "<w:r><w:br w:type=\"page\"/></w:r>";

    @TempDir
    Path dir;

    private DocxDoc.Rendered render(String name, String body) throws IOException {
        return DocxDoc.render(dir, name, new DocxDoc().styles(DEFAULTS).numbering(NUMBERING)
                .media("blue.png", Fixtures.png(4, 4, Color.BLUE), "rIdImg").body(body).bytes());
    }

    private static String para(String pPr, String runs) {
        return "<w:p><w:pPr>" + pPr + "</w:pPr>" + runs + "</w:p>";
    }

    private static String run(String text) {
        return "<w:r><w:t xml:space=\"preserve\">" + text + "</w:t></w:r>";
    }

    private static long emu(float pt) {
        return Math.round(pt * 12700);
    }

    private static String inline(float w, float h) {
        return "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"" + emu(w)
                + "\" cy=\"" + emu(h) + "\"/><wp:docPr id=\"1\" name=\"p\"/><a:graphic><a:graphicData"
                + " uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr"
                + " id=\"1\" name=\"p\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"rIdImg\"/>"
                + "<a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>"
                + "<a:ext cx=\"" + emu(w) + "\" cy=\"" + emu(h) + "\"/></a:xfrm><a:prstGeom prst=\"rect\"/></pic:spPr>"
                + "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>";
    }

    @Test
    void aPageBreakThatOpensANumberedParagraphTakesTheNumberAlong() throws IOException {
        String body = DocxDoc.p("Before") + para(NUMBERED + "<w:spacing w:before=\"480\"/>", PAGE_BREAK
                + run("Methodology"));
        DocxDoc.Rendered r = render("numbreak", body);
        DocxDoc.Word number = r.word("3.");
        DocxDoc.Word heading = r.word("Methodology");
        assertEquals(2, number.page(), "the number moves to the new page with the text");
        assertEquals(heading.y(), number.y(), 0.1);
        assertEquals(72, number.x(), 0.5, "the text after the break is the paragraph's first line");
        assertEquals(108, heading.x(), 0.5);
        assertEquals(24, heading.y() - r.word("Before").y(), 0.3, "the space before opens the new page");
    }

    @Test
    void aNumberedParagraphHoldingOnlyAPageBreakShowsNoNumber() throws IOException {
        String body = DocxDoc.p("Before") + para(NUMBERED, PAGE_BREAK) + DocxDoc.p("After");
        DocxDoc.Rendered r = render("onlybreak", body);
        assertFalse(r.text().contains("3."), "no number is drawn for the break");
        assertEquals(2, r.word("After").page());
        assertEquals(r.word("Before").y(), r.word("After").y(), 0.1, "the next paragraph starts the page");
    }

    @Test
    void aNumberedEmptyParagraphThatEndsASectionTakesNoLine() throws IOException {
        String page = "<w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\""
                + " w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>";
        String body = DocxDoc.p("First") + para(NUMBERED + "<w:sectPr>" + page + "</w:sectPr>", "")
                + DocxDoc.p("Second");
        DocxDoc.Rendered r = DocxDoc.render(dir, "sectmark", new DocxDoc().styles(DEFAULTS).numbering(NUMBERING)
                .section("<w:sectPr><w:type w:val=\"continuous\"/>" + page + "</w:sectPr>").body(body).bytes());
        assertFalse(r.text().contains("3."));
        assertEquals(11.5, r.word("Second").y() - r.word("First").y(), 0.2);
    }

    @Test
    void aLineBreakAfterAPictureTooWideForTheLineEndsThatLine() throws IOException {
        String wide = para("<w:ind w:left=\"1440\"/>", run("Top") + "<w:r><w:br/></w:r>" + inline(420, 50)
                + "<w:r><w:br/></w:r>" + run("Tail"));
        String narrow = para("<w:ind w:left=\"1440\"/>", run("Top") + "<w:r><w:br/></w:r>" + inline(300, 50)
                + "<w:r><w:br/></w:r>" + run("Tail"));
        DocxDoc.Rendered a = render("widepic", wide);
        DocxDoc.Rendered b = render("narrowpic", narrow);
        assertEquals(b.word("Tail").y(), a.word("Tail").y(), 0.1, "no empty line after the wide picture");
    }

    @Test
    void aWordWiderThanTheLineStartsRightAfterALeadingTab() throws IOException {
        String dots = "…".repeat(80);
        DocxDoc.Rendered r = render("longword", DocxDoc.p("Ref") + para("", "<w:r><w:tab/></w:r>" + run(dots)));
        DocxDoc.Word first = r.words().stream().filter(w -> w.text().startsWith("…")).findFirst().orElseThrow();
        assertEquals(11.5, first.y() - r.word("Ref").y(), 0.2, "the dots begin on the tab's line");
        assertTrue(first.x() > 100, "after the tab");
    }

    @Test
    void aWordWiderThanTheLineAfterTextStartsOnTheNextLine() throws IOException {
        String dots = "…".repeat(80);
        DocxDoc.Rendered r = render("longword2", para("", run("Label " + dots)));
        DocxDoc.Word first = r.words().stream().filter(w -> w.text().startsWith("…")).findFirst().orElseThrow();
        assertEquals(11.5, first.y() - r.word("Label").y(), 0.2);
        assertEquals(72, first.x(), 0.5);
    }
}

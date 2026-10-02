package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocxFlowRulesTest {

    private static final String FONT = "Liberation Sans";

    private static final String DEFAULTS = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"" + FONT
            + "\" w:hAnsi=\"" + FONT + "\"/><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";

    private static final String LETTER = "<w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\""
            + " w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>";

    @TempDir
    Path dir;

    private static String para(String pPr, String runs) {
        return "<w:p><w:pPr>" + pPr + "</w:pPr>" + runs + "</w:p>";
    }

    private static String run(String text) {
        return "<w:r><w:t xml:space=\"preserve\">" + text + "</w:t></w:r>";
    }

    private DocxDoc.Rendered render(String name, String styles, String body) throws IOException {
        return DocxDoc.render(dir, name, new DocxDoc().styles(DEFAULTS + styles).body(body).bytes());
    }

    private static String fillers(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(DocxDoc.p("F" + i));
        }
        return b.toString();
    }

    @Test
    void aPageBreakJustBeforeANextPageSectionBreakLeavesNoEmptyPage() throws IOException {
        String body = DocxDoc.p("One") + "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>"
                + para("<w:sectPr>" + LETTER + "</w:sectPr>", "") + DocxDoc.p("Two");
        DocxDoc.Rendered r = render("pbsect", "", body);
        assertEquals(2, r.word("Two").page());
        assertEquals(2, r.pages());
    }

    @Test
    void theSpaceBeforeAnEmptySectionBreakParagraphDoesNotPushItOntoAPageOfItsOwn() throws IOException {
        String body = fillers(56) + para("<w:spacing w:before=\"480\"/><w:sectPr>" + LETTER + "</w:sectPr>", "")
                + DocxDoc.p("Next");
        DocxDoc.Rendered r = render("sectbefore", "", body);
        assertEquals(2, r.word("Next").page());
        assertEquals(2, r.pages());
        String kept = fillers(56) + para("<w:keepNext/><w:sectPr>" + LETTER + "</w:sectPr>", "")
                + DocxDoc.p("Next");
        DocxDoc.Rendered keep = render("sectkeep", "", kept);
        assertEquals(2, keep.word("Next").page(), "keep with next on the break paragraph adds no page");
        assertEquals(2, keep.pages());
    }

    @Test
    void aColumnBreakOpeningAParagraphTakesNoRoomInTheColumnItLeaves() throws IOException {
        String twoCols = "<w:sectPr><w:type w:val=\"continuous\"/>" + LETTER + "<w:cols w:num=\"2\" w:space=\"720\"/>"
                + "</w:sectPr>";
        String body = DocxDoc.p("Left") + "<w:p><w:r><w:br w:type=\"column\"/></w:r>" + run("Right") + "</w:p>"
                + para(twoCols, "") + DocxDoc.p("Below");
        String last = "<w:sectPr><w:type w:val=\"continuous\"/>" + LETTER + "</w:sectPr>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "colbreak", new DocxDoc().styles(DEFAULTS).body(body).section(last)
                .bytes());
        assertTrue(r.word("Right").x() > 300, "the text after the break is in the second column");
        assertEquals(r.word("Left").y(), r.word("Right").y(), 0.5f);
        assertEquals(1, r.word("Below").page());
        float gap = r.word("Below").y() - r.word("Left").y();
        assertTrue(gap > 10 && gap < 13, "the next section starts one line below the first column: " + gap);
    }

    @Test
    void bookmarksAfterAPageBreakDoNotLeaveAnEmptyLineOnTheNextPage() throws IOException {
        String body = DocxDoc.p("One") + "<w:p><w:r><w:br w:type=\"page\"/></w:r><w:bookmarkStart w:id=\"1\""
                + " w:name=\"mark\"/><w:bookmarkEnd w:id=\"1\"/></w:p>" + DocxDoc.p("Two");
        DocxDoc.Rendered r = render("brmark", "", body);
        assertEquals(2, r.word("Two").page());
        assertEquals(r.word("One").y(), r.word("Two").y(), 0.5f);
    }

    @Test
    void bodyTextWrapsAroundAFloatingObjectInTheHeader() throws IOException {
        String shape = "<w:p><w:r><w:drawing><wp:anchor simplePos=\"0\" relativeHeight=\"1\" behindDoc=\"0\""
                + " locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\" distL=\"0\" distR=\"0\" distT=\"0\""
                + " distB=\"0\"><wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"page\"><wp:posOffset>"
                + "3810000</wp:posOffset></wp:positionH><wp:positionV relativeFrom=\"page\"><wp:posOffset>762000"
                + "</wp:posOffset></wp:positionV><wp:extent cx=\"2540000\" cy=\"762000\"/><wp:wrapSquare"
                + " wrapText=\"bothSides\"/><wp:docPr id=\"2\" name=\"logo\"/><a:graphic><a:graphicData"
                + " uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\"><wps:wsp><wps:spPr>"
                + "<a:prstGeom prst=\"rect\"/><a:solidFill><a:srgbClr val=\"DDEEFF\"/></a:solidFill></wps:spPr>"
                + "<wps:bodyPr/></wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r></w:p>";
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            words.append("w").append(i).append(' ');
        }
        String sect = "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdh1xml\"/>" + LETTER + "</w:sectPr>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "headwrap", new DocxDoc().styles(DEFAULTS).header("h1.xml", shape)
                .body(DocxDoc.p(words.toString())).section(sect).bytes());
        float firstLine = r.word("w0").y();
        int beside = 0;
        int covered = 0;
        int below = 0;
        for (DocxDoc.Word w : r.words()) {
            boolean under = w.x() > 300 && w.x() < 495;
            if (Math.abs(w.y() - firstLine) < 1) {
                beside++;
                covered += under ? 1 : 0;
            } else if (w.y() > 140 && under) {
                below++;
            }
        }
        assertTrue(beside > 5 && covered == 0, "the first line leaves the header's shape free: " + covered);
        assertTrue(below > 0, "below the shape the lines use the full width");
    }

    @Test
    void aFooterObjectLowOnThePageEndsATableThereInsteadOfMovingIt() throws IOException {
        String shape = "<w:p><w:r><w:drawing><wp:anchor simplePos=\"0\" relativeHeight=\"1\" behindDoc=\"0\""
                + " locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\"><wp:simplePos x=\"0\" y=\"0\"/>"
                + "<wp:positionH relativeFrom=\"page\"><wp:posOffset>5080000</wp:posOffset></wp:positionH>"
                + "<wp:positionV relativeFrom=\"page\"><wp:posOffset>8636000</wp:posOffset></wp:positionV>"
                + "<wp:extent cx=\"1905000\" cy=\"1270000\"/><wp:wrapSquare wrapText=\"largest\"/><wp:docPr id=\"3\""
                + " name=\"logo\"/><a:graphic><a:graphicData"
                + " uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\"><wps:wsp><wps:spPr>"
                + "<a:prstGeom prst=\"rect\"/></wps:spPr><wps:bodyPr/></wps:wsp></a:graphicData></a:graphic>"
                + "</wp:anchor></w:drawing></w:r></w:p>";
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            rows.append("<w:tr><w:tc>").append(DocxDoc.p("Row" + i)).append("</w:tc></w:tr>");
        }
        String table = "<w:tbl><w:tblGrid><w:gridCol w:w=\"9000\"/></w:tblGrid>" + rows + "</w:tbl>";
        String sect = "<w:sectPr><w:footerReference w:type=\"default\" r:id=\"rIdf1xml\"/>" + LETTER + "</w:sectPr>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "footfloat", new DocxDoc().styles(DEFAULTS).footer("f1.xml", shape)
                .body(fillers(30) + table).section(sect).bytes());
        assertEquals(1, r.word("Row0").page(), "the table starts where it is");
        assertEquals(2, r.word("Row39").page());
    }

    @Test
    void keepWithNextCountsOnlyTheLinesBeforeAPageBreak() throws IOException {
        String body = fillers(54) + para("<w:keepNext/>", run("Heading"))
                + "<w:p>" + run("Last") + "<w:r><w:br w:type=\"page\"/></w:r></w:p>" + DocxDoc.p("Next");
        DocxDoc.Rendered r = render("keepbr", "", body);
        assertEquals(1, r.word("Heading").page(), "the heading and the line before the break fit on page one");
        assertEquals(1, r.word("Last").page());
        assertEquals(2, r.word("Next").page());
    }

    @Test
    void numberingFromTheStyleKeepsTheStyleIndentsButDirectNumberingDoesNot() throws IOException {
        String numbering = "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/>"
                + "<w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind"
                + " w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:lvl></w:abstractNum><w:num w:numId=\"1\">"
                + "<w:abstractNumId w:val=\"0\"/></w:num>";
        String styles = DEFAULTS + "<w:style w:type=\"paragraph\" w:styleId=\"Item\"><w:name w:val=\"Item\"/><w:pPr>"
                + "<w:numPr><w:numId w:val=\"1\"/></w:numPr><w:ind w:left=\"567\" w:hanging=\"567\"/></w:pPr>"
                + "</w:style>";
        String body = para("<w:pStyle w:val=\"Item\"/>", run("Styled"))
                + para("<w:pStyle w:val=\"Item\"/><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>",
                        run("Direct"));
        DocxDoc.Rendered r = DocxDoc.render(dir, "numind", new DocxDoc().styles(styles).numbering(numbering)
                .body(body).bytes());
        assertEquals(72 + 28.35f, r.word("Styled").x(), 0.5f);
        assertEquals(72 + 36, r.word("Direct").x(), 0.5f);
    }

    @Test
    void aRotatedCellMakesItsRowOnlyAsTallAsItsLongestWord() throws IOException {
        String table = "<w:tbl><w:tblGrid><w:gridCol w:w=\"4000\"/><w:gridCol w:w=\"700\"/></w:tblGrid><w:tr><w:tc>"
                + DocxDoc.p("Left") + "</w:tc><w:tc><w:tcPr><w:textDirection w:val=\"btLr\"/></w:tcPr>"
                + para("<w:jc w:val=\"center\"/>", run("Clinical supervisor")) + "</w:tc></w:tr></w:tbl>"
                + DocxDoc.p("After");
        DocxDoc.Rendered r = render("rotated", "", table);
        float row = r.word("After").y() - r.word("Left").y();
        assertTrue(row > 45 && row < 80, "the row fits \"supervisor\", and the text wraps to it: " + row);
    }

    @Test
    void wordBreaksAfterAHyphenAttachedToWhatPrecedesIt() {
        assertTrue(breakAfter("https://example.com/-/media", "/-"));
        assertTrue(breakAfter("CIFAR-10 results", "R-"));
        assertTrue(breakAfter("networks--the", "--"));
        assertTrue(breakAfter("well-known", "l-"));
        assertFalse(breakAfter("value -5 here", " -"));
        assertFalse(breakAfter("a----b", "a-"));
        assertFalse(breakAfter("(-5)", "(-"));
    }

    private static boolean breakAfter(String text, String before) {
        int at = text.indexOf(before) + before.length();
        return Breaks.compute(text)[at];
    }

    @Test
    void missingSymbolFontsKeepTheirWindowsLineHeight() {
        assertArrayEquals(new float[] {2059f / 2048, 450f / 2048}, SymbolChars.vertical("Symbol"));
        assertArrayEquals(new float[] {1841f / 2048, 432f / 2048}, SymbolChars.vertical("Wingdings"));
        assertArrayEquals(new float[] {1727f / 2048, 432f / 2048}, SymbolChars.vertical("wingdings 2"));
        assertNull(SymbolChars.vertical("Arial"));
        assertNull(SymbolChars.vertical(null));
    }

    @Test
    void aLineOfOnlySpacesAndTabsTakesTheHeightOfTheParagraphMark() throws IOException {
        String small = "<w:rPr><w:sz w:val=\"12\"/></w:rPr>";
        String blank = "<w:r><w:tab/></w:r><w:r><w:t xml:space=\"preserve\"> </w:t></w:r>";
        String body = DocxDoc.p("Above") + para(small, blank) + DocxDoc.p("Below") + para(small, run("x"))
                + DocxDoc.p("Last");
        DocxDoc.Rendered r = render("blankline", "", body);
        float blankGap = r.word("Below").y() - r.word("Above").y();
        float textGap = r.word("Last").y() - r.word("Below").y();
        assertTrue(blankGap < textGap - 3, "the blank line is as tall as its 6 pt mark: " + blankGap + " vs " + textGap);
    }

    @Test
    void aLargerSpaceAtTheEndOfALineDoesNotMakeItTaller() throws IOException {
        String big = "<w:r><w:rPr><w:sz w:val=\"72\"/></w:rPr><w:t xml:space=\"preserve\"> </w:t></w:r>";
        String body = DocxDoc.p("Alpha") + "<w:p>" + run("Beta") + big + "</w:p>" + DocxDoc.p("Gamma");
        DocxDoc.Rendered r = render("trailing", "", body);
        assertEquals(r.word("Beta").y() - r.word("Alpha").y(), r.word("Gamma").y() - r.word("Beta").y(), 0.05f);
    }

    @Test
    void aTabInALargerFontDoesNotMakeTheLineTaller() throws IOException {
        String bigTab = "<w:r><w:rPr><w:sz w:val=\"72\"/></w:rPr><w:tab/></w:r>";
        String leader = "<w:tabs><w:tab w:val=\"right\" w:leader=\"dot\" w:pos=\"8000\"/></w:tabs>";
        String body = DocxDoc.p("Alpha") + "<w:p>" + run("Beta") + bigTab + run("Tail") + "</w:p>" + DocxDoc.p("Gamma")
                + para(leader, run("Delta") + bigTab + run("9"));
        DocxDoc.Rendered r = render("bigtab", "", body);
        assertEquals(r.word("Beta").y() - r.word("Alpha").y(), r.word("Gamma").y() - r.word("Beta").y(), 0.05f);
        assertTrue(r.word("Delta").y() - r.word("Gamma").y() > 30, "a leader tab is drawn text and sizes its line");
    }

    @Test
    void beforeWord2013ATableStyleFontSizeWinsOverTheNormalStyleInACell() throws IOException {
        String styles = "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/>"
                + "<w:rPr><w:sz w:val=\"36\"/></w:rPr></w:style><w:style w:type=\"table\" w:styleId=\"Grid\">"
                + "<w:name w:val=\"Table Grid\"/></w:style>";
        String cell = "<w:p>" + run("%1") + "<w:r><w:br/></w:r>" + run("%2") + "</w:p>";
        String styled = "<w:tbl><w:tblPr><w:tblStyle w:val=\"Grid\"/></w:tblPr><w:tblGrid><w:gridCol w:w=\"4000\"/>"
                + "</w:tblGrid><w:tr><w:tc>" + cell.replace("%1", "Top").replace("%2", "Bottom")
                + "</w:tc></w:tr></w:tbl>";
        String plain = "<w:tbl><w:tblGrid><w:gridCol w:w=\"4000\"/></w:tblGrid><w:tr><w:tc>"
                + cell.replace("%1", "Up").replace("%2", "Down") + "</w:tc></w:tr></w:tbl>";
        DocxDoc.Rendered r = render("tblsize", styles, styled + DocxDoc.p("Gap") + plain);
        float withStyle = r.word("Bottom").y() - r.word("Top").y();
        float withoutStyle = r.word("Down").y() - r.word("Up").y();
        assertTrue(withStyle < 13, "the table style brings the 10 pt default: " + withStyle);
        assertTrue(withoutStyle > 18, "without a table style the Normal style's 18 pt stays: " + withoutStyle);
    }

    private static String rowOf(String cellParagraph) {
        return "<w:tbl><w:tblGrid><w:gridCol w:w=\"4000\"/></w:tblGrid><w:tr><w:tc>" + cellParagraph
                + "</w:tc></w:tr></w:tbl>" + DocxDoc.p("After");
    }

    @Test
    void aTableRowNeedsRoomForTheSpaceAfterItsLastParagraph() throws IOException {
        String one = para("<w:spacing w:after=\"240\"/>", run("Single"));
        DocxDoc.Rendered moved = render("cellone", "", fillers(55) + rowOf(one));
        assertEquals(2, moved.word("Single").page(), "the line fits but its space after does not");
        StringBuilder lines = new StringBuilder(run("R1"));
        for (int i = 2; i <= 6; i++) {
            lines.append("<w:r><w:br/></w:r>").append(run("R" + i));
        }
        String six = para("<w:spacing w:after=\"480\"/>", lines.toString());
        DocxDoc.Rendered split = render("cellsix", "", fillers(50) + rowOf(six));
        assertEquals(1, split.word("R4").page());
        assertEquals(2, split.word("R5").page(), "the row splits before its last lines, keeping two together");
        assertEquals(2, split.word("R6").page());
    }

    @Test
    void aNestedTableRowSplitsAcrossPagesWithItsOuterRow() throws IOException {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            lines.append(DocxDoc.p("L" + i));
        }
        String inner = "<w:tbl><w:tblGrid><w:gridCol w:w=\"3000\"/></w:tblGrid><w:tr><w:tc>" + lines
                + "</w:tc></w:tr></w:tbl>";
        String outer = "<w:tbl><w:tblGrid><w:gridCol w:w=\"5000\"/></w:tblGrid><w:tr><w:tc>" + inner
                + "<w:p/></w:tc></w:tr></w:tbl>";
        DocxDoc.Rendered r = render("nestsplit", "", fillers(20) + outer);
        assertEquals(1, r.word("L0").page(), "the inner row starts right after the text on page one");
        assertEquals(2, r.word("L69").page());
    }

    @Test
    void aHiddenRowInTheDefaultTableStyleStillShowsItsRows() throws IOException {
        String styles = "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal"
                + " Table\"/><w:trPr><w:hidden/></w:trPr></w:style>";
        String table = "<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr><w:tblGrid><w:gridCol"
                + " w:w=\"4000\"/></w:tblGrid><w:tr><w:tc>" + DocxDoc.p("Cell") + "</w:tc></w:tr></w:tbl>";
        DocxDoc.Rendered r = render("stylehidden", styles, DocxDoc.p("Before") + table + DocxDoc.p("After"));
        assertTrue(r.word("Cell").y() > r.word("Before").y());
    }
}

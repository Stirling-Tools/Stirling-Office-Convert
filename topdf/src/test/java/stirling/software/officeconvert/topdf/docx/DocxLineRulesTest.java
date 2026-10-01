package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Fixtures;

class DocxLineRulesTest {

    private static final String FONT = "Liberation Sans";

    private static final String STYLES = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"" + FONT
            + "\" w:hAnsi=\"" + FONT + "\"/><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";

    @TempDir
    Path dir;

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

    private static String para(String pPr, String runs) {
        return "<w:p><w:pPr>" + pPr + "</w:pPr>" + runs + "</w:p>";
    }

    private static String run(String text) {
        return "<w:r><w:t xml:space=\"preserve\">" + text + "</w:t></w:r>";
    }

    private DocxDoc.Rendered render(String name, DocxDoc doc) throws IOException {
        return DocxDoc.render(dir, name, doc.bytes());
    }

    private float pictureGap(String line) throws IOException {
        String body = DocxDoc.p("Before") + para("<w:spacing w:line=\"" + line + "\" w:lineRule=\"auto\"/>",
                inline(100, 200)) + DocxDoc.p("After");
        DocxDoc doc = new DocxDoc().styles(STYLES).media("blue.png", Fixtures.png(4, 4, Color.BLUE), "rIdImg")
                .body(body);
        DocxDoc.Rendered r = render("pic" + line, doc);
        return r.word("After").y() - r.word("Before").y();
    }

    @Test
    void lineSpacingMultiplesWidenPictureLinesByTheTextHeightOnly() throws IOException {
        float single = pictureGap("240");
        float twice = pictureGap("480");
        float extra = twice - single;
        assertTrue(extra > 5 && extra < 20, "a double-spaced picture line grows by one text line, not by its own"
                + " height: " + extra);
    }

    @Test
    void symbolBulletsBecomeUnicodeWhenTheSymbolFontIsMissing() {
        assertEquals("•", SymbolChars.remap("", "Symbol", true, c -> false, c -> true));
        assertEquals("α≤", SymbolChars.remap("a", "Symbol", false, c -> c < 0x100, c -> true));
        assertEquals("▪", SymbolChars.remap("", "Wingdings", true, c -> false, c -> true));
        assertEquals("➢", SymbolChars.remap("", "Wingdings", true, c -> false, c -> true));
        assertEquals("•", SymbolChars.remap("", "Wingdings", true, c -> false, c -> true));
        assertEquals("•", SymbolChars.remap("", "Wingdings", true, c -> false, c -> c == 0x2022));
        assertEquals("", SymbolChars.remap("", "Wingdings", false, c -> false, c -> true));
        assertEquals("", SymbolChars.remap("", "Symbol", true, c -> true, c -> true));
        assertEquals("o", SymbolChars.remap("o", "Courier New", true, c -> false, c -> true));
        assertEquals("a b", SymbolChars.remap("a b", "Arial", false, c -> false, c -> true));
    }

    @Test
    void autoSpacedListItemsSitTogetherLikeHtmlListItems() throws IOException {
        String numbering = "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/>"
                + "<w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"-\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind"
                + " w:left=\"720\" w:hanging=\"360\"/></w:pPr></w:lvl></w:abstractNum><w:num w:numId=\"1\">"
                + "<w:abstractNumId w:val=\"0\"/></w:num>";
        String auto = "<w:spacing w:before=\"100\" w:beforeAutospacing=\"1\" w:after=\"100\""
                + " w:afterAutospacing=\"1\"/>";
        String item = "<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>" + auto;
        String body = para(auto, run("Intro")) + para(item, run("One")) + para(item, run("Two"))
                + para(item, run("Three")) + para(auto, run("Outro"));
        DocxDoc.Rendered r = render("autolist", new DocxDoc().styles(STYLES).numbering(numbering).body(body));
        float pitch = r.word("Three").y() - r.word("Two").y();
        float lead = r.word("One").y() - r.word("Intro").y();
        float tail = r.word("Outro").y() - r.word("Three").y();
        assertEquals(pitch, r.word("Two").y() - r.word("One").y(), 0.05f);
        assertTrue(pitch < 13, "no space between items: " + pitch);
        assertEquals(pitch + ParaFlow.AUTO_SPACING, lead, 0.1f);
        assertEquals(pitch + ParaFlow.AUTO_SPACING, tail, 0.1f);
    }

    private static final String LETTER = "<w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\""
            + " w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>";

    private DocxDoc.Rendered plain(String name, String body) throws IOException {
        return render(name, new DocxDoc().styles(STYLES).body(body));
    }

    @Test
    void defaultsWithoutParagraphPropertiesTakeWordsOwnSpacing() throws IOException {
        String body = DocxDoc.p("First") + DocxDoc.p("Second");
        String bare = STYLES.substring(0, STYLES.indexOf("<w:pPrDefault>")) + "</w:docDefaults>";
        DocxDoc.Rendered set = render("pprset", new DocxDoc().styles(STYLES).body(body));
        DocxDoc.Rendered missing = render("pprmissing", new DocxDoc().styles(bare).body(body));
        float single = set.word("Second").y() - set.word("First").y();
        float word = missing.word("Second").y() - missing.word("First").y();
        assertEquals(single * 1.15f + 10, word, 0.3f);
    }

    @Test
    void anEmptyNumberingPartStillNumbersItsListItems() throws IOException {
        String item = "<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>";
        String body = para(item, run("Apples")) + para(item, run("Pears"));
        DocxDoc.Rendered r = render("emptylist", new DocxDoc().styles(STYLES).numbering("").body(body));
        assertEquals("1.", r.words().get(0).text());
        assertEquals("2.", r.words().get(2).text());
        assertEquals(72 + 36, r.word("Pears").x(), 0.5f);
    }

    @Test
    void aDeletedParagraphMarkTakesItsSectionBreakWithIt() throws IOException {
        String del = "<w:del w:id=\"1\" w:author=\"a\" w:date=\"2020-01-01T00:00:00Z\"/>";
        String body = para("<w:rPr>" + del + "</w:rPr><w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/></w:sectPr>",
                "<w:del w:id=\"2\" w:author=\"a\" w:date=\"2020-01-01T00:00:00Z\"><w:r><w:delText>Gone</w:delText>"
                + "</w:r></w:del>") + DocxDoc.p("Kept");
        DocxDoc.Rendered r = render("delsect", new DocxDoc().styles(STYLES).body(body));
        try (var d = r.open()) {
            assertEquals(1, d.getNumberOfPages());
        }
        assertEquals(1, r.word("Kept").page());
    }

    @Test
    void aCharacterGridNarrowsEveryCharacter() throws IOException {
        String body = DocxDoc.p("AAAAAAAAAA End");
        String grid = DocxDoc.LETTER.replace("</w:sectPr>", "<w:docGrid w:type=\"linesAndChars\" w:linePitch=\"240\""
                + " w:charSpace=\"-8192\"/></w:sectPr>");
        float plain = render("nogrid", new DocxDoc().styles(STYLES).body(body)).word("End").x();
        float narrow = render("chargrid", new DocxDoc().styles(STYLES).body(body).section(grid)).word("End").x();
        assertEquals(plain - 11, narrow, 0.5f);
        String lines = grid.replace("linesAndChars", "lines");
        assertEquals(plain, render("linegrid", new DocxDoc().styles(STYLES).body(body).section(lines)).word("End").x(),
                0.05f);
    }

    @Test
    void lineBreaksOutsideARunStillBreakTheLine() throws IOException {
        DocxDoc.Rendered r = plain("barebr", "<w:p>" + run("First") + "<w:br/>" + run("Second") + "</w:p>");
        assertTrue(r.word("Second").y() > r.word("First").y() + 5, "the bare w:br should start a new line");
    }

    @Test
    void theParagraphMarkSizesOnlyALineWithoutText() throws IOException {
        String big = "<w:rPr><w:sz w:val=\"72\"/></w:rPr>";
        DocxDoc.Rendered r = plain("mark", DocxDoc.p("Above") + para(big, run("Text")) + DocxDoc.p("Below")
                + para(big, "") + DocxDoc.p("Last"));
        float withText = r.word("Below").y() - r.word("Text").y();
        float empty = r.word("Last").y() - r.word("Below").y();
        assertEquals(r.word("Text").y() - r.word("Above").y(), withText, 0.05f);
        assertTrue(empty > 2 * withText, "an empty paragraph takes the height of its mark: " + empty);
    }

    @Test
    void theEmptyParagraphAfterANestedTableTakesNoLine() throws IOException {
        String inner = "<w:tbl><w:tblGrid><w:gridCol w:w=\"2000\"/></w:tblGrid><w:tr><w:tc>" + DocxDoc.p("Inner")
                + "</w:tc></w:tr></w:tbl>";
        String outer = "<w:tbl><w:tblGrid><w:gridCol w:w=\"4000\"/></w:tblGrid><w:tr><w:tc>" + inner
                + "<w:p/></w:tc></w:tr></w:tbl>" + DocxDoc.p("After");
        DocxDoc.Rendered r = plain("nested", outer);
        float gap = r.word("After").y() - r.word("Inner").y();
        assertTrue(gap < 20, "no empty line below the nested table: " + gap);
    }

    @Test
    void aFramedPageNumberInAFooterSitsOnTheLineOfAnEmptyParagraphAfterIt() throws IOException {
        String frame = "<w:p><w:pPr><w:framePr w:wrap=\"around\" w:vAnchor=\"text\" w:hAnchor=\"margin\""
                + " w:xAlign=\"right\" w:y=\"1\"/></w:pPr>" + run("NUM") + "</w:p>";
        String sect = "<w:sectPr><w:footerReference w:type=\"default\" r:id=\"rIdf1xml\"/>" + LETTER + "</w:sectPr>";
        DocxDoc.Rendered floating = render("framefoot", new DocxDoc().styles(STYLES).footer("f1.xml", frame + "<w:p/>")
                .body(DocxDoc.p("Body")).section(sect));
        DocxDoc.Word num = floating.word("NUM");
        assertTrue(num.x() > 400, "right aligned in the margins: " + num);
        assertTrue(num.y() > 750, "on the footer's only line, just above the footer distance: " + num);
        DocxDoc.Rendered text = render("frametext", new DocxDoc().styles(STYLES).footer("f1.xml",
                frame + DocxDoc.p("Company")).body(DocxDoc.p("Body")).section(sect));
        float gap = text.word("Company").y() - text.word("NUM").y();
        assertTrue(gap > 10 && gap < 13, "text after the frame goes below it: " + gap);
        assertTrue(text.word("NUM").x() > 400);
    }

    @Test
    void frameAnchorsFromTheStyleStayWhenTheParagraphMovesTheFrame() throws IOException {
        String styles = STYLES + "<w:style w:type=\"paragraph\" w:styleId=\"Logo\"><w:name w:val=\"Logo\"/><w:pPr>"
                + "<w:framePr w:hAnchor=\"page\" w:vAnchor=\"page\" w:x=\"100\" w:y=\"100\"/></w:pPr></w:style>";
        String header = "<w:p><w:pPr><w:pStyle w:val=\"Logo\"/><w:framePr w:x=\"8000\" w:y=\"400\"/></w:pPr>"
                + run("LOGO") + "</w:p>" + DocxDoc.p("Title");
        String sect = "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdh1xml\"/>" + LETTER + "</w:sectPr>";
        DocxDoc.Rendered r = render("framehead", new DocxDoc().styles(styles).header("h1.xml", header)
                .body(DocxDoc.p("Body")).section(sect));
        assertEquals(400, r.word("LOGO").x(), 1f);
        assertTrue(r.word("LOGO").y() > 20 && r.word("LOGO").y() < 40, r.word("LOGO").toString());
        assertEquals(36 + 9, r.word("Title").y(), 3f);
    }

    @Test
    void theParagraphAfterAPageBreakLosesItsSpaceBeforeAtTheTop() throws IOException {
        String body = DocxDoc.p("One") + "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>"
                + para("<w:spacing w:before=\"480\"/>", run("Two"));
        DocxDoc.Rendered r = plain("brtop", body);
        DocxDoc.Word two = r.word("Two");
        assertEquals(2, two.page());
        assertEquals(r.word("One").y(), two.y(), 0.5f);
    }

    @Test
    void theExtraOfMultipleSpacingMayHangBelowTheLastLineOfAPage() throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 55; i++) {
            body.append(para("<w:spacing w:line=\"288\" w:lineRule=\"auto\"/>", run("L" + i)));
        }
        DocxDoc.Rendered r = plain("slack", body.toString());
        float pitch = r.word("L1").y() - r.word("L0").y();
        float natural = pitch / 1.2f;
        int perPage = (int) Math.floor((648 + 0.2f * natural) / pitch);
        assertTrue(Math.floor(648 / pitch) < perPage, "the case must tell the two rules apart");
        assertEquals(1, r.word("L" + (perPage - 1)).page(), "the last line whose text fits stays on page one");
        assertEquals(2, r.word("L" + perPage).page());
    }

    @Test
    void anEmptySectionBreakParagraphTakesNoLineAndTheSpaceBeforeItMakesNoPage() throws IOException {
        String next = "<w:sectPr>" + LETTER + "</w:sectPr>";
        String tight = para("<w:spacing w:before=\"12600\"/>", run("Low")) + para(next, "") + DocxDoc.p("Next");
        DocxDoc.Rendered fits = render("secttight", new DocxDoc().styles(STYLES).body(tight).section(next));
        assertEquals(2, fits.word("Next").page(), "the break paragraph needs no room of its own");
        String spaced = para("<w:spacing w:before=\"12400\" w:after=\"600\"/>", run("Low")) + para(next, "")
                + DocxDoc.p("Next");
        DocxDoc.Rendered pushed = render("sectspace", new DocxDoc().styles(STYLES).body(spaced).section(next));
        assertEquals(2, pushed.word("Next").page(), "space after that runs off the page ends with the page");
    }

    @Test
    void autoSpacingAfterTheLastParagraphOfACellIsDropped() throws IOException {
        String auto = "<w:spacing w:before=\"100\" w:beforeAutospacing=\"1\" w:after=\"100\" w:afterAutospacing=\"1\"/>";
        String table = "<w:tbl><w:tblGrid><w:gridCol w:w=\"4000\"/></w:tblGrid><w:tr><w:tc>" + para(auto, run("Cell"))
                + "</w:tc></w:tr></w:tbl>" + DocxDoc.p("After");
        DocxDoc.Rendered r = plain("autocell", table);
        float gap = r.word("After").y() - r.word("Cell").y();
        assertTrue(gap < 14, "no auto spacing below the cell text: " + gap);
    }

    @Test
    void pageBreaksInsideATableCellAreIgnored() throws IOException {
        String table = "<w:tbl><w:tblGrid><w:gridCol w:w=\"4000\"/></w:tblGrid><w:tr><w:tc><w:p><w:r><w:br"
                + " w:type=\"page\"/><w:t>Cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>";
        DocxDoc.Rendered r = plain("cellbreak", DocxDoc.p("Before") + table);
        assertEquals(1, r.pages());
        assertTrue(r.word("Cell").y() - r.word("Before").y() < 14, "no empty line for the break");
    }

    @Test
    void aPageAnchoredFloatingTableContinuesOnTheNextPage() throws IOException {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            rows.append("<w:tr><w:tc>").append(DocxDoc.p("Row" + i)).append("</w:tc></w:tr>");
        }
        String table = "<w:tbl><w:tblPr><w:tblpPr w:vertAnchor=\"page\" w:horzAnchor=\"margin\" w:tblpY=\"2000\"/>"
                + "</w:tblPr><w:tblGrid><w:gridCol w:w=\"4000\"/></w:tblGrid>" + rows + "</w:tbl>" + DocxDoc.p("End");
        DocxDoc.Rendered r = plain("floatpage", table);
        assertEquals(2, r.word("Row79").page());
        assertTrue(r.word("Row79").y() < 720, "the last rows sit inside page two: " + r.word("Row79"));
    }

    @Test
    void aNumberingRestartThatWouldRepeatAnOddPageGetsABlankPage() throws IOException {
        String settings = "<w:settings " + DocxDoc.NS + "><w:evenAndOddHeaders/></w:settings>";
        String first = "<w:sectPr>" + LETTER + "<w:pgNumType w:start=\"1\"/></w:sectPr>";
        String second = "<w:sectPr>" + LETTER + "<w:pgNumType w:start=\"1\"/></w:sectPr>";
        String body = DocxDoc.p("Cover") + para(first, "") + DocxDoc.p("Contents");
        DocxDoc.Rendered r = render("oddeven", new DocxDoc().styles(STYLES).part("settings.xml", "settings",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml", settings).body(body)
                .section(second));
        assertEquals(3, r.word("Contents").page());
    }

    @Test
    void aJustifiedLineSqueezesTheSpaceBetweenIdeographsAndDigitsToFitOneMoreCharacter() throws IOException {
        String text = "\u4E00" + "1\u4E00".repeat(60);
        int left = firstLineLength(text, "left");
        int justified = firstLineLength(text, "both");
        assertTrue(justified > left, left + " then " + justified);
    }

    @Test
    void anIdeographicCommaHangsPastTheMarginRatherThanWrapWithTheCharacterBeforeIt() throws IOException {
        String text = "\u4E00".repeat(45) + "\u89C1\uFF0C" + "\u4E00".repeat(10);
        int hanging = firstLineLength(text, "both");
        int kept = firstLineLength(text, "both\"/><w:overflowPunct w:val=\"0");
        assertEquals(47, hanging, "the comma ends the first line");
        assertEquals(45, kept);
        assertEquals(47, firstLineLength(text.substring(0, 47), "both"), "on the paragraph's last line too");
        assertEquals(45, firstLineLength(text, "left"), "only in a justified paragraph");
        String cell = "<w:tbl><w:tblPr><w:tblW w:w=\"9600\" w:type=\"dxa\"/></w:tblPr><w:tblGrid><w:gridCol"
                + " w:w=\"9600\"/></w:tblGrid><w:tr><w:tc><w:tcPr><w:tcW w:w=\"9600\" w:type=\"dxa\"/></w:tcPr>"
                + "<w:p><w:pPr><w:jc w:val=\"both\"/></w:pPr><w:r><w:rPr><w:rFonts w:eastAsia=\"SimSun\"/></w:rPr><w:t>"
                + text + "</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:p/>";
        String out = DocxDoc.render(dir, "hangcell", new DocxDoc().styles(STYLES).body(cell).bytes()).text();
        assertEquals(45, out.strip().split("\\R")[0].strip().length(), "a table cell lets nothing hang");
    }

    @Test
    void textAfterARightAlignedLabelEndingAtTheIndentStartsThere() throws IOException {
        String numbering = "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"4\"/>"
                + "<w:numFmt w:val=\"upperRoman\"/><w:lvlText w:val=\"%1.\"/><w:lvlJc w:val=\"right\"/><w:pPr>"
                + "<w:ind w:left=\"173\" w:hanging=\"173\"/></w:pPr></w:lvl></w:abstractNum><w:num w:numId=\"1\">"
                + "<w:abstractNumId w:val=\"0\"/></w:num>";
        String body = "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr><w:ind w:left=\"288\""
                + " w:firstLine=\"0\"/></w:pPr><w:r><w:t xml:space=\"preserve\"> Heading</w:t></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "rightlabel", new DocxDoc().styles(STYLES).numbering(numbering)
                .body(body).bytes());
        float x = r.word("Heading").x();
        assertTrue(x > 86.4 && x < 92, "a space after the label, no default tab gap: " + x);
    }

    @Test
    void aLeaderTabWithNoRoomToDrawDotsDoesNotSizeTheLine() throws IOException {
        String words = "Alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma"
                + " tau upsilon phi chi psi omega alpha beta gamma delta epsilon zeta eta theta";
        String body = "<w:p><w:pPr><w:tabs><w:tab w:val=\"right\" w:leader=\"dot\" w:pos=\"300\"/></w:tabs></w:pPr>"
                + "<w:r><w:t>9.</w:t></w:r><w:r><w:rPr><w:sz w:val=\"40\"/></w:rPr><w:tab/></w:r><w:r><w:t>" + words
                + "</w:t></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "leaderroom", new DocxDoc().styles(STYLES).body(body).bytes());
        float first = r.words().stream().filter(w -> w.text().startsWith("9.")).findFirst().orElseThrow().y();
        float second = r.words().stream().filter(w -> w.y() > first + 1).findFirst().orElseThrow().y();
        assertEquals(11.5, second - first, 0.3, "the line keeps the height of its 10 pt text");
    }

    private int firstLineLength(String text, String jc) throws IOException {
        String body = "<w:p><w:pPr><w:jc w:val=\"" + jc + "\"/></w:pPr><w:r><w:rPr><w:rFonts w:eastAsia=\"SimSun\"/>"
                + "<w:lang w:eastAsia=\"zh-CN\"/></w:rPr><w:t>" + text + "</w:t></w:r></w:p>";
        String out = DocxDoc.render(dir, "line-" + Integer.toHexString(jc.hashCode()), new DocxDoc().styles(STYLES).body(body).bytes()).text();
        return out.strip().split("\\R")[0].strip().length();
    }
}

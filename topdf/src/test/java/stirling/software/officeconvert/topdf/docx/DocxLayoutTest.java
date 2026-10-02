package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;
import stirling.software.officeconvert.topdf.testing.Fixtures;

class DocxLayoutTest {

    private static final String FONT = "Liberation Sans";

    private static final String STYLES = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"" + FONT
            + "\" w:hAnsi=\"" + FONT + "\"/><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";

    @TempDir
    Path dir;

    record Word(String text, int page, float x, float y) {}

    private static List<Word> words(DocxDoc.Rendered r) throws IOException {
        List<Word> out = new ArrayList<>();
        try (PDDocument d = r.open()) {
            PDFTextStripper s = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    StringBuilder sb = new StringBuilder();
                    TextPosition first = null;
                    for (TextPosition p : positions) {
                        String u = p.getUnicode();
                        if (u.isBlank()) {
                            if (first != null) {
                                out.add(new Word(sb.toString(), getCurrentPageNo(), first.getXDirAdj(),
                                        first.getYDirAdj()));
                            }
                            sb.setLength(0);
                            first = null;
                            continue;
                        }
                        if (first == null) {
                            first = p;
                        }
                        sb.append(u);
                    }
                    if (first != null) {
                        out.add(new Word(sb.toString(), getCurrentPageNo(), first.getXDirAdj(), first.getYDirAdj()));
                    }
                }
            };
            s.setSortByPosition(true);
            s.getText(d);
        }
        return out;
    }

    private static Word find(List<Word> words, String text) {
        for (Word w : words) {
            if (w.text().equals(text)) {
                return w;
            }
        }
        throw new AssertionError("no word " + text + " in " + words);
    }

    private static String pic(String id, float w, float h) {
        return "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic>"
                + "<pic:nvPicPr><pic:cNvPr id=\"1\" name=\"p\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill>"
                + "<a:blip r:embed=\"" + id + "\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr>"
                + "<a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"" + emu(w) + "\" cy=\"" + emu(h) + "\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"/></pic:spPr></pic:pic></a:graphicData></a:graphic>";
    }

    private static long emu(float pt) {
        return Math.round(pt * 12700);
    }

    private static String inline(float w, float h) {
        return "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"" + emu(w)
                + "\" cy=\"" + emu(h) + "\"/><wp:docPr id=\"1\" name=\"p\"/>" + pic("rIdImg", w, h)
                + "</wp:inline></w:drawing></w:r>";
    }

    private static String anchor(float x, float y, float w, float h, String vRel) {
        return "<w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\""
                + " relativeHeight=\"2\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">"
                + "<wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"page\"><wp:posOffset>" + emu(x)
                + "</wp:posOffset></wp:positionH><wp:positionV relativeFrom=\"" + vRel + "\"><wp:posOffset>" + emu(y)
                + "</wp:posOffset></wp:positionV><wp:extent cx=\"" + emu(w) + "\" cy=\"" + emu(h) + "\"/>"
                + "<wp:wrapSquare wrapText=\"bothSides\"/><wp:docPr id=\"2\" name=\"a\"/>" + pic("rIdImg", w, h)
                + "</wp:anchor></w:drawing></w:r>";
    }

    private static String para(String pPr, String runs) {
        return "<w:p><w:pPr>" + pPr + "</w:pPr>" + runs + "</w:p>";
    }

    private static String run(String text) {
        return "<w:r><w:t xml:space=\"preserve\">" + text + "</w:t></w:r>";
    }

    private DocxDoc.Rendered render(String name, String body) throws IOException {
        byte[] png = Fixtures.png(4, 4, Color.BLUE);
        return DocxDoc.render(dir, name, new DocxDoc().styles(STYLES).media("blue.png", png, "rIdImg").body(body)
                .bytes());
    }

    @Test
    void aPictureAsWideAsTheLineEndsItsParagraphOnThatLine() throws IOException {
        String mark = "<w:rPr><w:sz w:val=\"2\"/></w:rPr>";
        float full = 468;
        List<Word> wide = words(render("wide", para(mark, inline(full, 40)) + DocxDoc.p("After")));
        List<Word> narrow = words(render("narrow", para(mark, inline(full / 2, 40)) + DocxDoc.p("After")));
        assertEquals(find(narrow, "After").y(), find(wide, "After").y(), 0.05f);
    }

    @Test
    void aPictureLineIsAtLeastAsTallAsTheFontOfItsRun() throws IOException {
        String mark = "<w:rPr><w:sz w:val=\"2\"/></w:rPr>";
        String big = "<w:r><w:rPr><w:sz w:val=\"48\"/></w:rPr><w:drawing><wp:inline distT=\"0\" distB=\"0\""
                + " distL=\"0\" distR=\"0\"><wp:extent cx=\"" + emu(100) + "\" cy=\"" + emu(3) + "\"/>"
                + "<wp:docPr id=\"1\" name=\"p\"/>" + pic("rIdImg", 100, 3) + "</wp:inline></w:drawing></w:r>";
        List<Word> w = words(render("strip", DocxDoc.p("Before") + para(mark, big) + DocxDoc.p("After")));
        float gap = find(w, "After").y() - find(w, "Before").y();
        FontFace face = FontLibrary.system().find(FONT, false, false);
        float lineOf24 = face.metrics().gdiLineHeight(24);
        assertTrue(gap > lineOf24, gap + " should exceed " + lineOf24);
    }

    @Test
    void aLineThatFitsNoSegmentBesideAFloatMovesBelowItWithItsSpaceBefore() throws IOException {
        String body = para("", anchor(150, 100, 360, 60, "page") + run("Anchor"))
                + para("<w:spacing w:before=\"400\"/>", inline(300, 12) + run(" tail"));
        List<Word> w = words(render("below", body));
        assertTrue(find(w, "tail").y() > 160 + 18, "line should sit below the float after its space: " + w);
    }

    @Test
    void pagePositionedFloatsAlsoWrapTheTextAboveTheirAnchor() throws IOException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            text.append("word").append(i).append(' ');
        }
        String body = DocxDoc.p(text.toString().trim()) + para("", anchor(72, 72, 200, 100, "page") + run("Late"));
        List<Word> w = words(render("late", body));
        Word first = find(w, "word0");
        assertTrue(first.x() >= 272 - 0.5f, "text above the anchor should wrap around the float: " + first);
    }

    @Test
    void aTableThatDoesNotFitBesideAFloatMovesBelowIt() throws IOException {
        String table = "<w:tbl><w:tblPr><w:tblW w:w=\"6000\" w:type=\"dxa\"/><w:tblInd w:w=\"2000\" w:type=\"dxa\"/>"
                + "</w:tblPr><w:tblGrid><w:gridCol w:w=\"6000\"/></w:tblGrid><w:tr><w:tc><w:tcPr><w:tcW w:w=\"6000\""
                + " w:type=\"dxa\"/></w:tcPr>" + DocxDoc.p("Cell") + "</w:tc></w:tr></w:tbl>";
        String overlapping = para("", anchor(72, 60, 120, 80, "page") + run("Head")) + table;
        String beside = para("", anchor(72, 60, 20, 80, "page") + run("Head")) + table;
        assertTrue(find(words(render("tblbelow", overlapping)), "Cell").y() > 140);
        assertTrue(find(words(render("tblbeside", beside)), "Cell").y() < 140);
    }

    @Test
    void justifiedLinesSqueezeOnlyWordsThatMostlyFit() throws IOException {
        FontFace face = FontLibrary.system().find(FONT, false, false);
        String lead = "aa aa aa aa aa aa aa aa aa aa aa aa aa aa aa aa aa aa aa aa";
        String tail = "mm";
        float lineWidth = face.width(lead + " " + tail, 10);
        float tailWidth = face.width(tail, 10);
        for (float share : new float[] {0.2f, 0.6f}) {
            float avail = lineWidth - share * tailWidth;
            float right = 468 - avail;
            String pPr = "<w:jc w:val=\"both\"/><w:ind w:right=\"" + Math.round(right * 20) + "\"/>";
            String settings = "<w:settings " + DocxDoc.NS + "><w:compat><w:compatSetting w:name=\"compatibilityMode\""
                    + " w:uri=\"http://schemas.microsoft.com/office/word\" w:val=\"15\"/></w:compat></w:settings>";
            byte[] docx = new DocxDoc().styles(STYLES).part("settings.xml", "settings",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml", settings)
                    .body(para(pPr, run(lead + " " + tail + " and more words"))).bytes();
            List<Word> w = words(DocxDoc.render(dir, "squeeze" + share, docx));
            boolean sameLine = Math.abs(find(w, tail).y() - find(w, "aa").y()) < 0.5f;
            assertEquals(share < 0.3f, sameLine, "share " + share + ": " + w);
        }
    }

    @Test
    void theParagraphMarkAfterAColumnBreakStartsTheNextColumn() throws IOException {
        String cols = "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\""
                + " w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>"
                + "<w:cols w:num=\"2\" w:space=\"720\"/></w:sectPr>";
        String body = para("<w:rPr><w:sz w:val=\"60\"/></w:rPr>", run("Left") + "<w:r><w:br w:type=\"column\"/></w:r>")
                + DocxDoc.p("Right");
        List<Word> w = words(DocxDoc.render(dir, "colbreak", new DocxDoc().styles(STYLES).body(body).section(cols)
                .bytes()));
        assertTrue(find(w, "Right").y() > find(w, "Left").y() + 20, w.toString());
    }

    @Test
    void aSectionStartingLowOnThePageMovesALineThatDoesNotFit() throws IOException {
        String cont = "<w:sectPr><w:type w:val=\"continuous\"/><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar"
                + " w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\""
                + " w:gutter=\"0\"/><w:cols w:num=\"2\" w:space=\"720\"/></w:sectPr>";
        String body = para("<w:spacing w:before=\"12400\"/>", run("Top"))
                + para("<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\""
                        + " w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>"
                        + "</w:sectPr>", "")
                + para("<w:spacing w:line=\"800\" w:lineRule=\"exact\"/>", run("Moved"));
        DocxDoc.Rendered r = DocxDoc.render(dir, "lowsection", new DocxDoc().styles(STYLES).body(body).section(cont)
                .bytes());
        assertEquals(2, find(words(r), "Moved").page());
    }

    @Test
    void borderColoursComeFromTheColorAttribute() throws IOException {
        String t = "<w:tbl><w:tblPr><w:tblBorders><w:top w:val=\"single\" w:sz=\"8\" w:color=\"FF0000\"/>"
                + "</w:tblBorders></w:tblPr><w:tblGrid><w:gridCol w:w=\"3000\"/></w:tblGrid><w:tr><w:tc>"
                + DocxDoc.p("Red top") + "</w:tc></w:tr></w:tbl>";
        DocxDoc.Rendered r = render("bordercolor", t);
        try (PDDocument d = r.open()) {
            String content = new String(d.getPage(0).getContents().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(content.contains("1 0 0 SC"), content);
        }
    }

    @Test
    void listLabelsAddTheirAscentButNotTheirDescent() throws IOException {
        String numbering = "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/>"
                + "<w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"o\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind"
                + " w:left=\"720\" w:hanging=\"360\"/></w:pPr><w:rPr><w:rFonts w:ascii=\"Liberation Mono\""
                + " w:hAnsi=\"Liberation Mono\"/></w:rPr></w:lvl></w:abstractNum><w:num w:numId=\"1\">"
                + "<w:abstractNumId w:val=\"0\"/></w:num>";
        String item = "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr>";
        String body = DocxDoc.p("One") + DocxDoc.p("Two") + item + run("Three") + "</w:p>" + DocxDoc.p("Four");
        List<Word> w = words(DocxDoc.render(dir, "labels", new DocxDoc().styles(STYLES).numbering(numbering)
                .body(body).bytes()));
        float plain = find(w, "Two").y() - find(w, "One").y();
        float listed = find(w, "Four").y() - find(w, "Three").y();
        assertEquals(plain, listed, 0.05f);
    }

    @Test
    void theTabAfterAListLabelActsAsALeftTabAndIsNotContent() throws IOException {
        String numbering = "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/>"
                + "<w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"-\"/><w:lvlJc w:val=\"left\"/></w:lvl>"
                + "</w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>";
        String pPr = "<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr><w:tabs><w:tab w:val=\"right\""
                + " w:pos=\"9000\"/></w:tabs><w:ind w:left=\"0\" w:firstLine=\"0\"/>";
        List<Word> w = words(DocxDoc.render(dir, "labeltab", new DocxDoc().styles(STYLES).numbering(numbering)
                .body(para(pPr, run("Series closed at twenty two million"))).bytes()));
        Word first = w.stream().filter(x -> x.text().startsWith("S")).findFirst().orElse(null);
        assertNotNull(first, w.toString());
        assertEquals(72 + 450, first.x(), 1f);
    }

    @Test
    void textRightAlignedAtTheIndentRunsOnIntoTheIndent() throws IOException {
        FontFace face = FontLibrary.system().find(FONT, false, false);
        String tail = "tail words here";
        float target = 396 + 30 - face.width(tail, 10) - face.width("Lead ", 10);
        String filler = "n".repeat(Math.max(1, (int) (target / face.width("n", 10))));
        String pPr = "<w:tabs><w:tab w:val=\"right\" w:pos=\"7920\"/></w:tabs><w:ind w:right=\"1440\"/>";
        String body = para(pPr, run("Lead " + filler) + "<w:r><w:tab/></w:r>" + run(tail));
        List<Word> w = words(render("righttab", body));
        assertEquals(find(w, "Lead").y(), find(w, "words").y(), 0.5f, w.toString());
        assertEquals(find(w, "Lead").y(), find(w, "here").y(), 0.5f, w.toString());
        assertTrue(find(w, "here").x() > 72 + 396 - 30, "the text reaches into the indent: " + w);
    }

    @Test
    void rowsReserveTheHeavierBorderBetweenThem() throws IOException {
        String cell = "<w:tc><w:tcPr><w:tcW w:w=\"3000\" w:type=\"dxa\"/><w:tcBorders>%s</w:tcBorders></w:tcPr>%s</w:tc>";
        String row = "<w:tr><w:trPr><w:trHeight w:val=\"400\" w:hRule=\"atLeast\"/></w:trPr>%s</w:tr>";
        String t = "<w:tbl><w:tblPr><w:tblW w:w=\"3000\" w:type=\"dxa\"/></w:tblPr><w:tblGrid><w:gridCol"
                + " w:w=\"3000\"/></w:tblGrid>"
                + String.format(row, String.format(cell, "<w:top w:val=\"single\" w:sz=\"4\"/><w:bottom"
                        + " w:val=\"single\" w:sz=\"16\"/>", DocxDoc.p("First")))
                + String.format(row, String.format(cell, "<w:top w:val=\"single\" w:sz=\"4\"/>", DocxDoc.p("Second")))
                + "</w:tbl>";
        List<Word> w = words(render("rowborders", t));
        assertEquals(22, find(w, "Second").y() - find(w, "First").y(), 0.1f);
    }

    @Test
    void chartsAreDrawnFromTheirCachedValues() throws IOException {
        String chart = DocxDoc.chartXml("Sales by quarter", "Revenue", "North", "South", "East");
        DocxDoc doc = new DocxDoc().styles(STYLES).chart("chart1.xml", "rIdChart", chart)
                .body("<w:p>" + DocxDoc.chartRun("rIdChart") + "</w:p>");
        String text = DocxDoc.render(dir, "chart", doc.bytes()).text();
        for (String s : new String[] {"Sales by quarter", "Revenue", "North", "South", "East"}) {
            assertTrue(text.contains(s), text);
        }
    }

    @Test
    void customLeftTabsPastTheRightIndentAreHonoured() throws IOException {
        String pPr = "<w:tabs><w:tab w:val=\"left\" w:pos=\"8640\"/></w:tabs><w:ind w:right=\"1440\"/>";
        List<Word> w = words(render("pasttab", para(pPr, run("Numbered line") + "<w:r><w:tab/></w:r>" + run("30"))));
        Word n = find(w, "30");
        assertEquals(find(w, "Numbered").y(), n.y(), 0.5f);
        assertEquals(72 + 432, n.x(), 1f);
    }
}

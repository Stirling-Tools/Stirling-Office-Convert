package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocxPaginationTest {

    static final String BORDERS = "<w:tblBorders><w:top w:val=\"single\" w:sz=\"4\"/><w:left w:val=\"single\""
            + " w:sz=\"4\"/><w:bottom w:val=\"single\" w:sz=\"4\"/><w:right w:val=\"single\" w:sz=\"4\"/><w:insideH"
            + " w:val=\"single\" w:sz=\"4\"/><w:insideV w:val=\"single\" w:sz=\"4\"/></w:tblBorders>";

    @TempDir
    Path dir;

    static void allOnTheirPages(DocxDoc.Rendered r, String prefix, int count) throws IOException {
        List<DocxDoc.Word> words = r.words();
        for (int i = 0; i < count; i++) {
            String want = prefix + i;
            DocxDoc.Word w = words.stream().filter(x -> x.text().equals(want)).findFirst()
                    .orElseThrow(() -> new AssertionError("missing " + want));
            assertTrue(w.y() > 0 && w.y() <= r.height(w.page()) - 36, want + " at " + w.y() + " on " + w.page());
        }
    }

    @Test
    void aTableRowTallerThanAPageContinuesOnTheNextPages() throws IOException {
        StringBuilder cell = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            cell.append(DocxDoc.p("P_" + i));
        }
        for (String before : new String[] {"", DocxDoc.p("INTRO")}) {
            String table = "<w:tbl><w:tblPr><w:tblW w:w=\"5000\" w:type=\"dxa\"/>" + BORDERS + "</w:tblPr><w:tblGrid>"
                    + "<w:gridCol w:w=\"5000\"/></w:tblGrid><w:tr><w:tc><w:tcPr><w:tcW w:w=\"5000\" w:type=\"dxa\"/>"
                    + "</w:tcPr>" + cell + "</w:tc></w:tr></w:tbl>";
            DocxDoc.Rendered r = DocxDoc.render(dir, "tall" + before.length(),
                    new DocxDoc().body(before + table + DocxDoc.p("THEEND")).bytes());
            allOnTheirPages(r, "P_", 120);
            assertTrue(r.pages() >= 2, "pages " + r.pages());
            DocxDoc.Word end = r.word("THEEND");
            assertEquals(r.pages(), end.page());
        }
    }

    @Test
    void aFloatingTableContinuesOnTheNextPage() throws IOException {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            rows.append("<w:tr><w:tc><w:tcPr><w:tcW w:w=\"4000\" w:type=\"dxa\"/></w:tcPr>")
                    .append(DocxDoc.p("F_" + i)).append("</w:tc></w:tr>");
        }
        String table = "<w:tbl><w:tblPr><w:tblpPr w:leftFromText=\"180\" w:rightFromText=\"180\" w:vertAnchor=\"text\""
                + " w:tblpY=\"1\"/><w:tblW w:w=\"4000\" w:type=\"dxa\"/>" + BORDERS + "</w:tblPr><w:tblGrid><w:gridCol"
                + " w:w=\"4000\"/></w:tblGrid>" + rows + "</w:tbl>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "float",
                new DocxDoc().body(DocxDoc.p("before") + table + DocxDoc.p("after")).bytes());
        allOnTheirPages(r, "F_", 80);
        assertEquals(2, r.pages());
    }

    private static final String DEL = "<w:del w:id=\"1\" w:author=\"A\" w:date=\"2026-01-01T00:00:00Z\"/>";

    private static String row(String trPr, String cells) {
        return "<w:tr>" + trPr + cells + "</w:tr>";
    }

    private static String cell(String tcPr, String content) {
        return "<w:tc><w:tcPr><w:tcW w:w=\"3000\" w:type=\"dxa\"/>" + tcPr + "</w:tcPr>" + content + "</w:tc>";
    }

    @Test
    void trackedDeletionsShowTheFinalView() throws IOException {
        String deletedRow = row("<w:trPr>" + DEL + "</w:trPr>", cell("", "<w:p><w:pPr><w:rPr>" + DEL
                + "</w:rPr></w:pPr><w:del w:id=\"2\" w:author=\"A\"><w:r><w:delText>deleted row</w:delText></w:r>"
                + "</w:del></w:p>"));
        String grid = "<w:tblGrid><w:gridCol w:w=\"3000\"/><w:gridCol w:w=\"3000\"/></w:tblGrid>";
        String table = "<w:tbl><w:tblPr>" + BORDERS + "</w:tblPr>" + grid
                + row("", cell("", DocxDoc.p("kept1")) + cell("", DocxDoc.p("x")))
                + deletedRow.replace("</w:tc></w:tr>", "</w:tc>" + cell("", DocxDoc.p("y")) + "</w:tr>")
                + row("", cell("", DocxDoc.p("kept3")) + cell("<w:cellDel w:id=\"3\" w:author=\"A\"/>",
                        DocxDoc.p("GONE")))
                + "</w:tbl>";
        String control = "<w:tbl><w:tblPr>" + BORDERS + "</w:tblPr>" + grid
                + row("", cell("", DocxDoc.p("kept1")) + cell("", DocxDoc.p("x")))
                + row("", cell("", DocxDoc.p("kept3")))
                + "</w:tbl>";
        String paras = "<w:p><w:pPr><w:rPr>" + DEL + "</w:rPr></w:pPr><w:r><w:t xml:space=\"preserve\">First half"
                + " </w:t></w:r></w:p>" + DocxDoc.p("second") + "<w:p><w:pPr><w:rPr>" + DEL + "</w:rPr></w:pPr><w:del"
                + " w:id=\"4\" w:author=\"A\"><w:r><w:delText>gone</w:delText></w:r></w:del></w:p>" + DocxDoc.p("last")
                + "<w:p><w:pPr><w:rPr><w:vanish/><w:specVanish/></w:rPr></w:pPr><w:r><w:t xml:space=\"preserve\">"
                + "Heading. </w:t></w:r></w:p>" + DocxDoc.p("Body");
        DocxDoc.Rendered r = DocxDoc.render(dir, "redline", new DocxDoc().body(table + paras).bytes());
        DocxDoc.Rendered c = DocxDoc.render(dir, "redline-control", new DocxDoc().body(control).bytes());
        String text = r.text();
        assertTrue(!text.contains("deleted") && !text.contains("GONE") && !text.contains("gone"), text);
        assertEquals(c.word("kept3").y(), r.word("kept3").y(), 0.5f);
        assertEquals(r.word("First").y(), r.word("second").y(), 0.5f);
        assertEquals(r.word("Heading.").y(), r.word("Body").y(), 0.5f);
        float line = r.word("last").y() - r.word("second").y();
        assertTrue(line > 5 && line < 20, "line " + line);
    }

    static java.awt.image.BufferedImage page(DocxDoc.Rendered r, int index) throws IOException {
        try (org.apache.pdfbox.pdmodel.PDDocument d = r.open()) {
            return new org.apache.pdfbox.rendering.PDFRenderer(d).renderImageWithDPI(index, 72);
        }
    }

    static boolean green(java.awt.image.BufferedImage img, int x, int y) {
        java.awt.Color c = new java.awt.Color(img.getRGB(x, y));
        return c.getGreen() > 200 && c.getRed() < 80 && c.getBlue() < 80;
    }

    @Test
    void anchorsInsideATableCellArePlacedAgainstThatCell() throws IOException {
        long emu = 50 * 12700;
        String anchor = "<w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\""
                + " relativeHeight=\"2\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">"
                + "<wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"column\"><wp:posOffset>0</wp:posOffset>"
                + "</wp:positionH><wp:positionV relativeFrom=\"paragraph\"><wp:posOffset>0</wp:posOffset></wp:positionV>"
                + "<wp:extent cx=\"" + emu + "\" cy=\"" + emu + "\"/><wp:wrapNone/><wp:docPr id=\"2\" name=\"a\"/>"
                + "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic>"
                + "<pic:nvPicPr><pic:cNvPr id=\"1\" name=\"p\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip"
                + " r:embed=\"rIdImg\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off"
                + " x=\"0\" y=\"0\"/><a:ext cx=\"" + emu + "\" cy=\"" + emu + "\"/></a:xfrm><a:prstGeom prst=\"rect\"/>"
                + "</pic:spPr></pic:pic></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>";
        String table = "<w:tbl><w:tblPr>" + BORDERS + "<w:tblLayout w:type=\"fixed\"/></w:tblPr><w:tblGrid><w:gridCol"
                + " w:w=\"4000\"/><w:gridCol w:w=\"4000\"/></w:tblGrid><w:tr><w:trPr><w:trHeight w:val=\"2000\"/>"
                + "</w:trPr><w:tc><w:tcPr><w:tcW w:w=\"4000\" w:type=\"dxa\"/></w:tcPr>" + DocxDoc.p("first") + "</w:tc>"
                + "<w:tc><w:tcPr><w:tcW w:w=\"4000\" w:type=\"dxa\"/></w:tcPr><w:p>" + anchor + "<w:r><w:t>cell</w:t>"
                + "</w:r></w:p></w:tc></w:tr></w:tbl>";
        byte[] png = stirling.software.officeconvert.topdf.testing.Fixtures.png(4, 4, java.awt.Color.GREEN);
        DocxDoc.Rendered r = DocxDoc.render(dir, "incell", new DocxDoc().media("g.png", png, "rIdImg").body(table)
                .bytes());
        java.awt.image.BufferedImage img = page(r, 0);
        float cellText = r.word("cell").x();
        assertTrue(cellText > 250, "cell text at " + cellText);
        int y = Math.round(r.word("cell").y()) + 10;
        assertTrue(green(img, Math.round(cellText) + 25, y), "no picture in the second cell");
        assertTrue(!green(img, 97, y), "picture drawn in the first cell");
    }

    private DocxDoc.Rendered footnoted(String name, int noteLines) throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            body.append(DocxDoc.p("B_" + i));
        }
        body.append("<w:p><w:r><w:t>REF</w:t></w:r><w:r><w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr>"
                + "<w:footnoteReference w:id=\"1\"/></w:r></w:p>");
        for (int i = 0; i < 40; i++) {
            body.append(DocxDoc.p("A_" + i));
        }
        StringBuilder note = new StringBuilder("<w:footnote w:id=\"1\">");
        for (int i = 0; i < noteLines; i++) {
            note.append(DocxDoc.p("N_" + i));
        }
        note.append("</w:footnote>");
        String seps = "<w:footnote w:type=\"separator\" w:id=\"-1\"><w:p><w:r><w:separator/></w:r></w:p></w:footnote>"
                + "<w:footnote w:type=\"continuationSeparator\" w:id=\"0\"><w:p><w:r><w:continuationSeparator/></w:r>"
                + "</w:p></w:footnote>";
        return DocxDoc.render(dir, name, new DocxDoc().footnotes(seps + note).body(body.toString()).bytes());
    }

    @Test
    void longFootnotesContinueOnTheNextPage() throws IOException {
        DocxDoc.Rendered r = footnoted("longnote", 31);
        assertEquals(1, r.word("REF1").page());
        assertEquals(1, r.word("N_0").page());
        assertEquals(2, r.word("N_30").page());
        assertEquals(2, r.pages());
        allOnTheirPages(r, "N_", 31);
        allOnTheirPages(r, "A_", 40);
        DocxDoc.Rendered huge = footnoted("hugenote", 120);
        allOnTheirPages(huge, "N_", 120);
        allOnTheirPages(huge, "A_", 40);
    }

    @Test
    void equationsKeepTheirStructureInLinearForm() throws IOException {
        String m = "xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"";
        String body = "<w:p><w:r><w:t xml:space=\"preserve\">Eq: </w:t></w:r><m:oMath " + m + "><m:f><m:num><m:r><m:t>"
                + "x+1</m:t></m:r></m:num><m:den><m:r><m:t>2</m:t></m:r></m:den></m:f><m:r><m:t>=</m:t></m:r><m:sSup>"
                + "<m:e><m:r><m:t>y</m:t></m:r></m:e><m:sup><m:r><m:t>2</m:t></m:r></m:sup></m:sSup></m:oMath></w:p>"
                + "<w:p><w:r><w:t xml:space=\"preserve\">Root: </w:t></w:r><m:oMath " + m + "><m:rad><m:radPr><m:degHide"
                + " m:val=\"1\"/></m:radPr><m:deg/><m:e><m:r><m:t>z+1</m:t></m:r></m:e></m:rad></m:oMath></w:p>"
                + "<w:p><w:r><w:t xml:space=\"preserve\">Paren: </w:t></w:r><m:oMath " + m + "><m:d><m:e><m:r><m:t>a+b"
                + "</m:t></m:r></m:e></m:d><m:r><m:t>c</m:t></m:r></m:oMath></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "math", new DocxDoc().body(body).bytes());
        String text = r.text().replaceAll("\\s", "");
        assertTrue(text.contains("x+12=y2"), text);
        assertTrue(text.contains("z+1"), text);
        assertTrue(text.contains("(a+b)c"), text);
        java.util.List<Float> sizes = new java.util.ArrayList<>();
        java.util.List<Float> ys = new java.util.ArrayList<>();
        try (org.apache.pdfbox.pdmodel.PDDocument d = r.open()) {
            new org.apache.pdfbox.text.PDFTextStripper() {
                @Override
                protected void writeString(String t, java.util.List<org.apache.pdfbox.text.TextPosition> ps) {
                    for (org.apache.pdfbox.text.TextPosition p : ps) {
                        if (p.getUnicode().equals("x") || p.getUnicode().equals("y") || p.getUnicode().equals("2")) {
                            sizes.add(p.getFontSizeInPt());
                            ys.add(p.getYDirAdj());
                        }
                    }
                }
            }.getText(d);
        }
        assertTrue(sizes.get(sizes.size() - 1) < sizes.get(sizes.size() - 2), sizes.toString());
        assertTrue(ys.get(0) < ys.get(1) - 5, "the numerator sits above the denominator " + ys);
    }

    @Test
    void rotatedCellTextStaysWholeAlongTheRow() throws IOException {
        for (String way : new String[] {"btLr", "tbRl"}) {
            StringBuilder head = new StringBuilder("<w:tr>");
            StringBuilder data = new StringBuilder("<w:tr>");
            for (int i = 1; i <= 4; i++) {
                head.append("<w:tc><w:tcPr><w:tcW w:w=\"500\" w:type=\"dxa\"/><w:textDirection w:val=\"").append(way)
                        .append("\"/></w:tcPr>").append(DocxDoc.p("Quarterly revenue " + i)).append("</w:tc>");
                data.append("<w:tc><w:tcPr><w:tcW w:w=\"500\" w:type=\"dxa\"/></w:tcPr>").append(DocxDoc.p("" + i))
                        .append("</w:tc>");
            }
            String table = "<w:tbl><w:tblPr>" + BORDERS + "<w:tblLayout w:type=\"fixed\"/></w:tblPr><w:tblGrid>"
                    + "<w:gridCol w:w=\"500\"/><w:gridCol w:w=\"500\"/><w:gridCol w:w=\"500\"/><w:gridCol w:w=\"500\"/>"
                    + "</w:tblGrid>" + head + "</w:tr>" + data + "</w:tr></w:tbl>";
            DocxDoc.Rendered r = DocxDoc.render(dir, "rot" + way, new DocxDoc().body(table).bytes());
            java.util.List<Integer> dirs = new java.util.ArrayList<>();
            StringBuilder q = new StringBuilder();
            try (org.apache.pdfbox.pdmodel.PDDocument d = r.open()) {
                new org.apache.pdfbox.text.PDFTextStripper() {
                    @Override
                    protected void processTextPosition(org.apache.pdfbox.text.TextPosition p) {
                        if (Character.isLetter(p.getUnicode().charAt(0))) {
                            dirs.add(Math.round(p.getDir()));
                            q.append(p.getUnicode());
                        }
                        super.processTextPosition(p);
                    }
                }.getText(d);
            }
            assertTrue(dirs.stream().allMatch(x -> x == 90 || x == 270), way + " " + dirs);
            assertTrue(q.toString().startsWith("Quarterlyrevenue"), q.toString());
        }
    }

    private static final String SETTINGS = "application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml";

    private static final String BREAK = "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>";

    @Test
    void mirrorMarginsSwapOnEvenPagesAndBordersFollowTheirDisplay() throws IOException {
        String sect = "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"720\""
                + " w:bottom=\"1440\" w:left=\"4320\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/><w:pgBorders"
                + " w:display=\"firstPage\" w:offsetFrom=\"page\"><w:top w:val=\"single\" w:sz=\"24\" w:space=\"24\""
                + " w:color=\"FF0000\"/></w:pgBorders></w:sectPr>";
        DocxDoc doc = new DocxDoc().body(DocxDoc.p("pageone") + BREAK + DocxDoc.p("pagetwo")).section(sect);
        doc.part("settings.xml", "settings", SETTINGS, "<w:settings " + DocxDoc.NS + "><w:mirrorMargins/></w:settings>");
        DocxDoc.Rendered r = DocxDoc.render(dir, "mirror", doc.bytes());
        assertEquals(216, r.word("pageone").x(), 0.5f);
        assertEquals(36, r.word("pagetwo").x(), 0.5f);
        java.awt.Color first = new java.awt.Color(page(r, 0).getRGB(300, 25));
        java.awt.Color second = new java.awt.Color(page(r, 1).getRGB(300, 25));
        assertTrue(first.getRed() > 200 && first.getGreen() < 80, first.toString());
        assertTrue(second.getGreen() > 200, second.toString());
    }

    @Test
    void theBlankPageOfAnOddPageBreakIsNotTheSectionsFirstPage() throws IOException {
        String first = "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\""
                + " w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/></w:sectPr>";
        String second = "<w:sectPr><w:headerReference w:type=\"first\" r:id=\"rIdh1xml\"/><w:headerReference"
                + " w:type=\"default\" r:id=\"rIdh2xml\"/><w:type w:val=\"oddPage\"/><w:pgSz w:w=\"12240\""
                + " w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\""
                + " w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/><w:titlePg/></w:sectPr>";
        String body = "<w:p><w:pPr>" + first + "</w:pPr><w:r><w:t>sectionone</w:t></w:r></w:p>" + DocxDoc.p("sectiontwo")
                + BREAK + DocxDoc.p("secondpage");
        DocxDoc doc = new DocxDoc().header("h1.xml", DocxDoc.p("FIRSTHDR")).header("h2.xml", DocxDoc.p("DEFAULTHDR"))
                .body(body).section(second);
        DocxDoc.Rendered r = DocxDoc.render(dir, "oddpage", doc.bytes());
        assertEquals(4, r.pages());
        assertTrue(!r.text(2).contains("FIRSTHDR"), r.text(2));
        assertTrue(r.text(3).contains("FIRSTHDR") && r.text(3).contains("sectiontwo"), r.text(3));
        assertTrue(r.text(4).contains("DEFAULTHDR"), r.text(4));
    }

    @Test
    void automaticHyphenationBreaksLongWordsAtPatternPoints() throws IOException {
        assertTrue(Hyphenator.forLanguage("en-US").points("laboratory").length > 0);
        assertEquals(null, Hyphenator.forLanguage("de-DE"));
        String text = "The international laboratory protocol exceeds the documentation requirements considerably"
                + " and the characteristically comprehensive administrative responsibilities overwhelm everybody";
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            body.append("<w:p><w:pPr><w:jc w:val=\"both\"/></w:pPr><w:r><w:t>").append(text).append("</w:t></w:r></w:p>");
        }
        String sect = "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\""
                + " w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/><w:cols"
                + " w:num=\"3\" w:space=\"360\"/></w:sectPr>";
        int[] hyphens = new int[2];
        for (int on = 0; on < 2; on++) {
            DocxDoc doc = new DocxDoc().body(body.toString()).section(sect);
            doc.part("settings.xml", "settings", SETTINGS, "<w:settings " + DocxDoc.NS + ">"
                    + (on == 1 ? "<w:autoHyphenation/><w:hyphenationZone w:val=\"357\"/>" : "") + "</w:settings>");
            DocxDoc.Rendered r = DocxDoc.render(dir, "hyph" + on, doc.bytes());
            for (String line : r.text().split("\\R")) {
                if (line.strip().endsWith("-")) {
                    hyphens[on]++;
                }
            }
        }
        assertEquals(0, hyphens[0]);
        assertTrue(hyphens[1] > 3, "hyphenated lines " + hyphens[1]);
    }

    @Test
    void rightJustifiedListLabelsEndAtTheNumberPosition() throws IOException {
        String numbering = "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt"
                + " w:val=\"lowerRoman\"/><w:lvlText w:val=\"%1.\"/><w:lvlJc w:val=\"right\"/><w:pPr><w:ind"
                + " w:left=\"1440\" w:hanging=\"360\"/></w:pPr></w:lvl></w:abstractNum><w:num w:numId=\"1\">"
                + "<w:abstractNumId w:val=\"0\"/></w:num>";
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= 8; i++) {
            body.append("<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr><w:r><w:t>")
                    .append("item").append(i).append("</w:t></w:r></w:p>");
        }
        DocxDoc.Rendered r = DocxDoc.render(dir, "lvljc", new DocxDoc().numbering(numbering).body(body.toString())
                .bytes());
        float one = r.word("i.").x();
        float eight = r.word("viii.").x();
        assertTrue(eight < one - 5, "labels at " + one + " and " + eight);
        assertTrue(one < 126 && one > 112, "i. at " + one);
        assertEquals(144, r.word("item1").x(), 0.5f);
        assertEquals(144, r.word("item8").x(), 0.5f);
    }

    private static String field(String instr, String cached) {
        return "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> " + instr
                + " </w:instrText></w:r><w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>" + cached
                + "</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r>";
    }

    @Test
    void pageCountsInTheBodyAndNumberFormatSwitchesAreHonoured() throws IOException {
        String body = "<w:p><w:r><w:t xml:space=\"preserve\">Total </w:t></w:r>" + field("NUMPAGES", "9")
                + "<w:r><w:t xml:space=\"preserve\"> Sec </w:t></w:r>" + field("SECTIONPAGES", "9") + "</w:p>" + BREAK
                + DocxDoc.p("two") + BREAK + DocxDoc.p("three");
        String footer = "<w:p><w:r><w:t xml:space=\"preserve\">R </w:t></w:r>" + field("PAGE \\* ROMAN", "9")
                + "<w:r><w:t xml:space=\"preserve\"> A </w:t></w:r>" + field("PAGE \\* Arabic \\* MERGEFORMAT", "9")
                + "</w:p>";
        String sect = "<w:sectPr><w:footerReference w:type=\"default\" r:id=\"rIdf1xml\"/><w:pgSz w:w=\"12240\""
                + " w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\""
                + " w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/><w:pgNumType w:fmt=\"lowerRoman\"/></w:sectPr>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "fields", new DocxDoc().footer("f1.xml", footer).body(body)
                .section(sect).bytes());
        assertEquals(3, r.pages());
        assertTrue(r.text(1).contains("Total 3 Sec 3"), r.text(1));
        assertTrue(r.text(2).contains("R II A 2"), r.text(2));
        assertTrue(r.text(3).contains("R III A 3"), r.text(3));
    }

    private static String letter(String extra) {
        return "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\""
                + " w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>" + extra
                + "</w:sectPr>";
    }

    @Test
    void columnRulesAndLineNumbersAreDrawn() throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            body.append(DocxDoc.p("Line" + i));
        }
        DocxDoc.Rendered r = DocxDoc.render(dir, "colrule", new DocxDoc().body(body.toString())
                .section(letter("<w:cols w:num=\"2\" w:space=\"720\" w:sep=\"1\"/>")).bytes());
        java.awt.image.BufferedImage img = page(r, 0);
        java.awt.Color mid = new java.awt.Color(img.getRGB(306, 300));
        assertTrue(mid.getRed() < 128, "no column rule: " + mid);
        DocxDoc.Rendered n = DocxDoc.render(dir, "linenum", new DocxDoc().body(DocxDoc.p("Line0") + DocxDoc.p("Line1"))
                .section(letter("<w:lnNumType w:countBy=\"1\"/>")).bytes());
        DocxDoc.Word one = n.word("1");
        assertTrue(one.x() < 72 && Math.abs(one.y() - n.word("Line0").y()) < 1, one.toString());
    }

    @Test
    void footnoteNumbersRestartOnEachPage() throws IOException {
        String ref = "<w:r><w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr><w:footnoteReference w:id=\"%d\"/></w:r>";
        String body = "<w:p><w:r><w:t>OneA</w:t></w:r>" + ref.formatted(1) + "</w:p><w:p><w:r><w:t>OneB</w:t></w:r>"
                + ref.formatted(2) + "</w:p>" + BREAK + "<w:p><w:r><w:t>TwoA</w:t></w:r>" + ref.formatted(3) + "</w:p>";
        StringBuilder notes = new StringBuilder();
        for (int i = 1; i <= 3; i++) {
            notes.append("<w:footnote w:id=\"").append(i).append("\">").append(DocxDoc.p("note" + i)).append("</w:footnote>");
        }
        DocxDoc doc = new DocxDoc().footnotes(notes.toString()).body(body);
        doc.part("settings.xml", "settings", SETTINGS, "<w:settings " + DocxDoc.NS + "><w:footnotePr><w:numRestart"
                + " w:val=\"eachPage\"/></w:footnotePr></w:settings>");
        DocxDoc.Rendered r = DocxDoc.render(dir, "restart", doc.bytes());
        assertTrue(r.text(1).contains("OneA1") && r.text(1).contains("OneB2"), r.text(1));
        assertTrue(r.text(2).contains("TwoA1"), r.text(2));
    }

    @Test
    void cellSpacingSeparatesCellsAndRightToLeftTablesAreMirrored() throws IOException {
        String cells = "<w:tr><w:tc><w:tcPr><w:tcW w:w=\"2000\" w:type=\"dxa\"/></w:tcPr>" + DocxDoc.p("COL1")
                + "</w:tc><w:tc><w:tcPr><w:tcW w:w=\"2000\" w:type=\"dxa\"/></w:tcPr>" + DocxDoc.p("COL2") + "</w:tc>"
                + "<w:tc><w:tcPr><w:tcW w:w=\"2000\" w:type=\"dxa\"/></w:tcPr>" + DocxDoc.p("COL3") + "</w:tc></w:tr>";
        String grid = "<w:tblGrid><w:gridCol w:w=\"2000\"/><w:gridCol w:w=\"2000\"/><w:gridCol w:w=\"2000\"/></w:tblGrid>";
        float[] col2 = new float[2];
        String[] extra = {"", "<w:tblCellSpacing w:w=\"144\" w:type=\"dxa\"/>"};
        for (int i = 0; i < 2; i++) {
            DocxDoc.Rendered r = DocxDoc.render(dir, "spacing" + i, new DocxDoc().body("<w:tbl><w:tblPr>" + extra[i]
                    + BORDERS + "</w:tblPr>" + grid + cells + "</w:tbl>").bytes());
            col2[i] = r.word("COL2").x();
        }
        assertEquals(col2[0] + 7.2f, col2[1], 0.6f);
        DocxDoc.Rendered rtl = DocxDoc.render(dir, "bidi", new DocxDoc().body("<w:tbl><w:tblPr><w:bidiVisual/>" + BORDERS
                + "</w:tblPr>" + grid + cells + "</w:tbl>").bytes());
        assertTrue(rtl.word("COL1").x() > rtl.word("COL3").x(), rtl.words().toString());
    }
}

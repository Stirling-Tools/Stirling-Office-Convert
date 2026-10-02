package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Fixtures;

class DocxFlowDetailTest {

    private static final String FONT = "Liberation Sans";

    private static final String STYLES = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"" + FONT
            + "\" w:hAnsi=\"" + FONT + "\"/><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";

    @TempDir
    Path dir;

    private DocxDoc.Rendered plain(String name, String body) throws IOException {
        return DocxDoc.render(dir, name, new DocxDoc().styles(STYLES).body(body).bytes());
    }

    private static String numbering(String format, String text, int labelHalfPoints) {
        return "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\""
                + format + "\"/><w:lvlText w:val=\"" + text + "\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind"
                + " w:left=\"720\" w:hanging=\"360\"/></w:pPr><w:rPr><w:sz w:val=\"" + labelHalfPoints + "\"/></w:rPr>"
                + "</w:lvl></w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>";
    }

    private static String numbered(String extra, String text) {
        return "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>" + extra + "</w:pPr>"
                + (text.isEmpty() ? "" : "<w:r><w:t>" + text + "</w:t></w:r>") + "</w:p>";
    }

    @Test
    void anEmptyNumberedParagraphIsAsTallAsItsMark() throws IOException {
        String mark = "<w:rPr><w:sz w:val=\"40\"/></w:rPr>";
        String body = numbered(mark, "") + numbered(mark, "") + DocxDoc.p("After");
        DocxDoc.Rendered r = DocxDoc.render(dir, "emptylist", new DocxDoc().styles(STYLES)
                .numbering(numbering("bullet", "o", 8)).body(body).bytes());
        List<DocxDoc.Word> labels = r.words().stream().filter(w -> w.text().equals("o")).toList();
        assertEquals(2, labels.size());
        float pitch = labels.get(1).y() - labels.get(0).y();
        assertTrue(pitch > 20, "a 20 pt paragraph mark sizes the line, not the 4 pt label: " + pitch);
    }

    @Test
    void aTallListLabelIsNotMultipliedWithItsText() throws IOException {
        String spacing = "<w:spacing w:line=\"480\" w:lineRule=\"auto\"/>";
        String body = numbered(spacing, "A1") + numbered(spacing, "A2") + numbered(spacing, "A3");
        DocxDoc.Rendered r = DocxDoc.render(dir, "biglabel", new DocxDoc().styles(STYLES)
                .numbering(numbering("decimal", "%1.", 40)).body(body).bytes());
        float pitch = ending(r, "A3").y() - ending(r, "A2").y();
        assertTrue(pitch > 28 && pitch < 36, "double spacing adds the 10 pt text height once more: " + pitch);
    }

    private static DocxDoc.Word ending(DocxDoc.Rendered r, String text) throws IOException {
        return r.words().stream().filter(w -> w.text().endsWith(text)).findFirst().orElseThrow();
    }

    @Test
    void contextualSpacingLooksAcrossTableCells() throws IOException {
        String cell = "<w:p><w:pPr><w:spacing w:before=\"240\" w:after=\"240\"/><w:contextualSpacing/></w:pPr><w:r>"
                + "<w:t>Cell</w:t></w:r></w:p>";
        String table = "<w:tbl><w:tblGrid><w:gridCol w:w=\"3000\"/><w:gridCol w:w=\"3000\"/></w:tblGrid><w:tr><w:tc>"
                + cell + "</w:tc><w:tc>" + DocxDoc.p("Side") + "</w:tc></w:tr></w:tbl>";
        DocxDoc.Rendered r = plain("contextcell", DocxDoc.p("Before") + table + DocxDoc.p("Below"));
        float above = r.word("Cell").y() - r.word("Before").y();
        float below = r.word("Below").y() - r.word("Cell").y();
        assertTrue(above < 16, "the paragraph before the table has the same style: " + above);
        assertTrue(below < 16, "the next cell's paragraph has the same style: " + below);
    }

    private static String mergedTable(int fillers, String firstRowText) {
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 160; i++) {
            words.append(i == 0 ? "" : " ").append("word").append(i);
        }
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            String merge = i == 0 ? "<w:vMerge w:val=\"restart\"/>" : "<w:vMerge/>";
            String first = i == 0 ? "<w:r><w:t>" + words + "</w:t></w:r>" : "";
            rows.append("<w:tr><w:tc><w:tcPr><w:tcW w:w=\"2000\" w:type=\"dxa\"/>").append(merge)
                    .append("</w:tcPr><w:p>").append(first).append("</w:p></w:tc><w:tc><w:tcPr><w:tcW w:w=\"4000\"")
                    .append(" w:type=\"dxa\"/></w:tcPr>").append(DocxDoc.p(i == 0 ? firstRowText : "Row" + i))
                    .append("</w:tc></w:tr>");
        }
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < fillers; i++) {
            body.append(DocxDoc.p("Filler" + i));
        }
        return body + "<w:tbl><w:tblGrid><w:gridCol w:w=\"2000\"/><w:gridCol w:w=\"4000\"/></w:tblGrid>" + rows
                + "</w:tbl>" + DocxDoc.p("End");
    }

    private void assertMergedTextOnPages(DocxDoc.Rendered r) throws IOException {
        List<DocxDoc.Word> merged = r.words().stream().filter(w -> w.text().startsWith("word")).toList();
        assertEquals(160, merged.size());
        float height = r.height(1);
        for (DocxDoc.Word w : merged) {
            assertTrue(w.y() < height - 30, "merged text runs off the page: " + w);
        }
        assertTrue(r.word("word159").page() > r.word("word0").page(), "the merged cell continues on the next page");
    }

    @Test
    void aMergedCellWhoseRowsFitRunsOnToTheNextPage() throws IOException {
        assertMergedTextOnPages(plain("mergedfit", mergedTable(50, "Row0")));
    }

    @Test
    void aMergedCellSplitInItsFirstRowRunsOnOverItsRows() throws IOException {
        DocxDoc.Rendered r = plain("mergedsplit", mergedTable(54, "Row0 Row0 Row0 Row0 Row0 Row0 Row0 Row0 Row0"
                + " Row0 Row0 Row0 Row0 Row0 Row0 Row0 Row0 Row0 Row0 Row0 Row0 Row0 Row0 Row0 Row0 Row0 Row0 Row0"));
        assertMergedTextOnPages(r);
        DocxDoc.Word row1 = r.word("Row1");
        assertTrue(row1.page() == r.word("word100").page() && row1.y() < r.word("word100").y(),
                "the rows after the split row sit beside the merged text: " + row1);
    }

    private static String floatingTable(String anchor) {
        return "<w:tbl><w:tblPr><w:tblpPr w:leftFromText=\"180\" w:rightFromText=\"180\" " + anchor
                + " w:horzAnchor=\"margin\"/><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr><w:tblGrid><w:gridCol"
                + " w:w=\"9360\"/></w:tblGrid><w:tr><w:trPr><w:trHeight w:hRule=\"exact\" w:val=\"1700\"/></w:trPr><w:tc>"
                + DocxDoc.p("Table") + "</w:tc></w:tr></w:tbl>";
    }

    private float textAfterFloatingTable(String name, String anchor) throws IOException {
        String small = "<w:p><w:pPr><w:spacing w:before=\"280\" w:after=\"280\"/><w:rPr><w:sz w:val=\"8\"/></w:rPr>"
                + "</w:pPr></w:p>";
        return plain(name, "<w:p/>" + floatingTable(anchor) + small + small + DocxDoc.p("Text")).word("Text").y();
    }

    @Test
    void anEmptyParagraphRightAfterAPageAnchoredFloatingTableStaysWhereItFalls() throws IOException {
        float page = textAfterFloatingTable("floatpage", "w:vertAnchor=\"page\" w:tblpY=\"1700\"");
        // The table ends at 170 pt; only the second empty paragraph and its space after lie between it and the text
        assertTrue(page > 170 && page < 205, "text after the table at " + page);
        float text = textAfterFloatingTable("floattext", "w:vertAnchor=\"text\" w:tblpY=\"0\"");
        assertTrue(text > page + 10, "a table anchored to the text still pushes its paragraph: " + text);
    }

    private static String anchoredPicture(String polygon) {
        long w = 612 * 12700L;
        long h = 72 * 12700L;
        return "<w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\""
                + " relativeHeight=\"2\" behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">"
                + "<wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"page\"><wp:posOffset>0</wp:posOffset>"
                + "</wp:positionH><wp:positionV relativeFrom=\"page\"><wp:posOffset>" + 36 * 12700 + "</wp:posOffset>"
                + "</wp:positionV><wp:extent cx=\"" + w + "\" cy=\"" + h + "\"/><wp:wrapTight wrapText=\"bothSides\">"
                + "<wp:wrapPolygon edited=\"0\">" + polygon + "</wp:wrapPolygon></wp:wrapTight><wp:docPr id=\"2\""
                + " name=\"a\"/><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
                + "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"1\" name=\"p\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip"
                + " r:embed=\"rIdImg\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\""
                + " y=\"0\"/><a:ext cx=\"" + w + "\" cy=\"" + h + "\"/></a:xfrm><a:prstGeom prst=\"rect\"/></pic:spPr>"
                + "</pic:pic></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>";
    }

    private DocxDoc.Rendered wrapped(String name, String polygon) throws IOException {
        String body = "<w:p>" + anchoredPicture(polygon) + "<w:r><w:t>Alpha</w:t></w:r></w:p>" + DocxDoc.p("Beta")
                + DocxDoc.p("Gamma") + DocxDoc.p("Delta");
        byte[] png = Fixtures.png(4, 4, Color.GREEN);
        return DocxDoc.render(dir, name, new DocxDoc().styles(STYLES).media("g.png", png, "rIdImg").body(body)
                .bytes());
    }

    private static String points(int... xy) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < xy.length; i += 2) {
            b.append(i == 0 ? "<wp:start" : "<wp:lineTo").append(" x=\"").append(xy[i]).append("\" y=\"")
                    .append(xy[i + 1]).append("\"/>");
        }
        return b.toString();
    }

    @Test
    void textFollowsTheOutlineOfATightWrapPolygon() throws IOException {
        // The upper part covers only the right half of the page; a band across the bottom covers all of it
        DocxDoc.Rendered r = wrapped("polygon", points(10800, 0, 21600, 0, 21600, 21600, 0, 21600, 0, 16200, 10800,
                16200, 10800, 0));
        assertTrue(r.word("Alpha").y() < 100, "the first line fits beside the upper part: " + r.word("Alpha"));
        assertTrue(r.word("Alpha").x() < 100, "and starts at the margin: " + r.word("Alpha"));
        assertTrue(r.word("Delta").y() > 108, "a line that meets the bottom band moves below it: " + r.word("Delta"));
    }

    @Test
    void aWrapPolygonWithNoAreaLeavesTheTextAlone() throws IOException {
        DocxDoc.Rendered r = wrapped("nopolygon", points(0, 0, 0, 0, 0, 0));
        assertTrue(r.word("Alpha").y() < 90, "not pushed below the picture: " + r.word("Alpha"));
        assertTrue(r.word("Gamma").y() < 108, "text flows over the picture's box: " + r.word("Gamma"));
    }

    @Test
    void theBordersOfJoinedParagraphsRunThroughTheSpaceBetweenThem() throws IOException {
        String ppr = "<w:pPr><w:pBdr><w:left w:val=\"single\" w:sz=\"24\" w:space=\"4\" w:color=\"000000\"/><w:right"
                + " w:val=\"single\" w:sz=\"24\" w:space=\"4\" w:color=\"000000\"/></w:pBdr><w:spacing w:after=\"480\"/>"
                + "</w:pPr>";
        String body = "<w:p>" + ppr + "<w:r><w:t>Upper</w:t></w:r></w:p><w:p>" + ppr + "<w:r><w:t>Lower</w:t></w:r>"
                + "</w:p>";
        DocxDoc.Rendered r = plain("joined", body);
        BufferedImage img = DocxPaginationTest.page(r, 0);
        int gap = Math.round((r.word("Upper").y() + r.word("Lower").y()) / 2);
        int x = 72 - 6;
        boolean dark = false;
        for (int dx = -3; dx <= 3; dx++) {
            Color c = new Color(img.getRGB(x + dx, gap));
            dark |= c.getRed() < 100 && c.getGreen() < 100 && c.getBlue() < 100;
        }
        assertTrue(dark, "the left border is drawn between the two paragraphs");
    }

    @Test
    void aNumberedParagraphThatOnlyCarriesTheBreakIsLeftOutWhenColumnsBalance() throws IOException {
        String two = "<w:sectPr><w:type w:val=\"continuous\"/><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar"
                + " w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\""
                + " w:footer=\"720\" w:gutter=\"0\"/><w:cols w:num=\"2\" w:space=\"2\"/></w:sectPr>";
        String one = "<w:sectPr><w:type w:val=\"continuous\"/><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar"
                + " w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"720\""
                + " w:footer=\"720\" w:gutter=\"0\"/></w:sectPr>";
        String spacing = "<w:spacing w:line=\"276\" w:lineRule=\"auto\"/>";
        StringBuilder body = new StringBuilder(DocxDoc.p("Intro")).append("<w:p><w:pPr>").append(one)
                .append("</w:pPr></w:p>");
        for (int i = 0; i < 12; i++) {
            body.append(numbered(spacing, "Item" + i));
        }
        body.append(numbered("<w:spacing w:before=\"120\" w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/>"
                + two, "")).append(DocxDoc.p("After"));
        DocxDoc.Rendered r = DocxDoc.render(dir, "balance", new DocxDoc().styles(STYLES)
                .numbering(numbering("bullet", "o", 20)).body(body.toString()).section(one).bytes());
        assertTrue(r.word("Item5").x() < 300, "six items stay in the first column: " + r.word("Item5"));
        assertTrue(r.word("Item6").x() > 300, "six go to the second: " + r.word("Item6"));
    }
}

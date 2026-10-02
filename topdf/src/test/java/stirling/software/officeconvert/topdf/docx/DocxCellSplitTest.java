package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocxCellSplitTest {

    private static final String FONT = "Liberation Sans";

    private static final String DEFAULTS = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"" + FONT
            + "\" w:hAnsi=\"" + FONT + "\"/><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";

    private static final String BORDERS = "<w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\""
            + " w:color=\"000000\"/><w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>"
            + "<w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/></w:tblBorders>";

    @TempDir
    Path dir;

    private DocxDoc.Rendered render(String name, String body) throws IOException {
        return DocxDoc.render(dir, name, new DocxDoc().styles(DEFAULTS).body(body).bytes());
    }

    // 53 lines of 11.5 pt and an exact spacer bring the next block to 72 + 609.5 + spacer on a 720 pt page
    private static String lead(int spacerTwips) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 53; i++) {
            b.append(DocxDoc.p("F" + i));
        }
        b.append("<w:p><w:pPr><w:spacing w:line=\"").append(spacerTwips).append("\" w:lineRule=\"exact\"/></w:pPr>")
                .append("</w:p>");
        return b.toString();
    }

    private static String twoLines(String a, String b, String pPr) {
        return "<w:p><w:pPr>" + pPr + "</w:pPr><w:r><w:t>" + a + "</w:t></w:r><w:r><w:br/></w:r><w:r><w:t>" + b
                + "</w:t></w:r></w:p>";
    }

    private static String row(String trPr, String... cells) {
        StringBuilder b = new StringBuilder("<w:tr>" + trPr);
        for (String c : cells) {
            b.append("<w:tc><w:tcPr><w:tcW w:w=\"4500\" w:type=\"dxa\"/></w:tcPr>").append(c).append("</w:tc>");
        }
        return b.append("</w:tr>").toString();
    }

    private static String table(String tblPr, String... rows) {
        return "<w:tbl><w:tblPr>" + tblPr + "</w:tblPr><w:tblGrid><w:gridCol w:w=\"4500\"/><w:gridCol w:w=\"4500\"/>"
                + "</w:tblGrid>" + String.join("", rows) + "</w:tbl>";
    }

    @Test
    void aLastLineWhoseSpaceAfterDoesNotFitMovesEvenAgainstWidowControl() throws IOException {
        String cell = twoLines("One", "Two", "<w:spacing w:after=\"240\"/>");
        DocxDoc.Rendered r = render("spaceafter", lead(170) + table(BORDERS, row("", cell, DocxDoc.p("Box"))));
        assertEquals(1, r.word("One").page(), "the first line stays although it is left alone");
        assertEquals(2, r.word("Two").page(), "the second line and its space after do not fit");
        assertEquals(1, r.word("Box").page());
    }

    @Test
    void widowControlThatWouldEmptyACellMovesTheRow() throws IOException {
        String cell = twoLines("One", "Two", "");
        DocxDoc.Rendered r = render("widowrow", lead(400) + table(BORDERS, row("", cell, DocxDoc.p("Box"))));
        assertEquals(2, r.word("One").page());
        assertEquals(2, r.word("Two").page());
        assertEquals(2, r.word("Box").page(), "the whole row moves");
    }

    @Test
    void widowControlKeepsTheLastTwoLinesOfACellParagraphTogether() throws IOException {
        String cell = DocxDoc.p("First") + twoLines("One", "Two", "");
        DocxDoc.Rendered r = render("widowcell", lead(170) + table(BORDERS, row("", cell, DocxDoc.p("Box"))));
        assertEquals(1, r.word("First").page());
        assertEquals(2, r.word("One").page(), "one line alone may not stay behind");
        assertEquals(2, r.word("Two").page());
    }

    @Test
    void aRowWithAMinimumHeightTallerThanTheSpaceLeftMovesWhole() throws IOException {
        StringBuilder cell = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            cell.append(DocxDoc.p("L" + i));
        }
        String trPr = "<w:trPr><w:trHeight w:val=\"2000\"/></w:trPr>";
        DocxDoc.Rendered r = render("minmove", lead(20) + table(BORDERS, row(trPr, cell.toString(), DocxDoc.p("Box"))));
        assertEquals(2, r.word("L0").page(), "a 100 pt row does not start in the 38 pt left");
        assertEquals(2, r.word("L11").page());
    }

    @Test
    void rowsMadeOnlyOfVerticalMergesTakeTheHeightOfAnEmptyNormalParagraph() throws IOException {
        String defaults = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"" + FONT + "\" w:hAnsi=\"" + FONT
                + "\"/><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"200\""
                + " w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";
        String tight = "<w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr>";
        String start = "<w:tc><w:tcPr><w:tcW w:w=\"4500\" w:type=\"dxa\"/><w:vMerge w:val=\"restart\"/></w:tcPr><w:p>"
                + tight + "<w:r><w:rPr><w:sz w:val=\"16\"/></w:rPr><w:t>Top</w:t></w:r></w:p></w:tc>";
        String cont = "<w:tc><w:tcPr><w:tcW w:w=\"4500\" w:type=\"dxa\"/><w:vMerge/></w:tcPr><w:p>" + tight + "</w:p>"
                + "</w:tc>";
        String trPr = "<w:trPr><w:trHeight w:val=\"300\"/></w:trPr>";
        String body = "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w=\"4500\"/><w:gridCol w:w=\"4500\"/></w:tblGrid>"
                + "<w:tr>" + trPr + start + start + "</w:tr><w:tr>" + trPr + cont + cont + "</w:tr></w:tbl>"
                + "<w:p><w:r><w:t>After</w:t></w:r></w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "rowmark", new DocxDoc().styles(defaults).body(body).bytes());
        float rows = r.word("After").y() - 72 - 9.96f;
        assertEquals(2 * (12.65f * 1.15f + 10), rows, 1, "each row is an empty 11 pt paragraph with its 10 pt after");
    }

    @Test
    void aHiddenEndOfCellMarkAfterOtherContentDoesNotSizeTheRow() throws IOException {
        String hidden = "<w:tc><w:tcPr><w:tcW w:w=\"9000\" w:type=\"dxa\"/><w:hideMark/></w:tcPr>" + DocxDoc.p("Cell")
                + "<w:p/></w:tc>";
        String shown = "<w:tc><w:tcPr><w:tcW w:w=\"9000\" w:type=\"dxa\"/></w:tcPr>" + DocxDoc.p("Cell")
                + "<w:p/></w:tc>";
        String grid = "<w:tblGrid><w:gridCol w:w=\"9000\"/></w:tblGrid>";
        DocxDoc.Rendered a = render("hidemark", "<w:tbl><w:tblPr/>" + grid + "<w:tr>" + hidden + "</w:tr></w:tbl>"
                + DocxDoc.p("After"));
        DocxDoc.Rendered b = render("showmark", "<w:tbl><w:tblPr/>" + grid + "<w:tr>" + shown + "</w:tr></w:tbl>"
                + DocxDoc.p("After"));
        assertEquals(11.5, a.word("After").y() - a.word("Cell").y(), 0.1, "the empty closing paragraph is ignored");
        assertEquals(23, b.word("After").y() - b.word("Cell").y(), 0.1);
    }

    @Test
    void aFloatingTableWithoutAVerticalAnchorIsPlacedFromTheTopMargin() throws IOException {
        String table = "<w:tbl><w:tblPr><w:tblpPr w:horzAnchor=\"margin\" w:tblpY=\"585\"/></w:tblPr><w:tblGrid>"
                + "<w:gridCol w:w=\"4500\"/></w:tblGrid><w:tr><w:tc>" + DocxDoc.p("Inside") + "</w:tc></w:tr></w:tbl>";
        DocxDoc.Rendered r = render("tblpy", DocxDoc.p("Caption") + DocxDoc.p("Second") + table + "<w:p/>");
        assertEquals(29.25, r.word("Inside").y() - r.word("Caption").y(), 0.3, "29.25 pt below the top margin");
    }

    @Test
    void keepWithNextHoldsTheFirstLinesOfATallTableRow() throws IOException {
        StringBuilder cell = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            cell.append(DocxDoc.p("L" + i));
        }
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 55; i++) {
            body.append(DocxDoc.p("F" + i));
        }
        body.append("<w:p><w:pPr><w:keepNext/></w:pPr><w:r><w:t>Caption</w:t></w:r></w:p>").append(
                "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w=\"9000\"/></w:tblGrid><w:tr><w:tc>").append(cell)
                .append("</w:tc></w:tr></w:tbl>");
        DocxDoc.Rendered r = render("keeptable", body.toString());
        assertEquals(2, r.word("Caption").page(), "the caption goes with the table's first line");
        assertEquals(2, r.word("L0").page());
    }

    @Test
    void aBottomBorderTakesNoRoomWhenTheCellMarginsHoldIt() throws IOException {
        String mar = "<w:tblCellMar><w:top w:w=\"55\" w:type=\"dxa\"/><w:bottom w:w=\"55\" w:type=\"dxa\"/>"
                + "</w:tblCellMar>";
        String under = "<w:tcBorders><w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>"
                + "</w:tcBorders>";
        String cellA = "<w:tc><w:tcPr><w:tcW w:w=\"9000\" w:type=\"dxa\"/>" + under + "</w:tcPr>" + DocxDoc.p("RowA")
                + "</w:tc>";
        String cellB = "<w:tc><w:tcPr><w:tcW w:w=\"9000\" w:type=\"dxa\"/>" + under + "</w:tcPr>" + DocxDoc.p("RowB")
                + "</w:tc>";
        String body = "<w:tbl><w:tblPr>" + mar + "</w:tblPr><w:tblGrid><w:gridCol w:w=\"9000\"/></w:tblGrid><w:tr>"
                + cellA + "</w:tr><w:tr>" + cellB + "</w:tr></w:tbl>";
        DocxDoc.Rendered r = render("bordermargin", body);
        assertEquals(11.5 + 5.5, r.word("RowB").y() - r.word("RowA").y(), 0.1);
    }
}

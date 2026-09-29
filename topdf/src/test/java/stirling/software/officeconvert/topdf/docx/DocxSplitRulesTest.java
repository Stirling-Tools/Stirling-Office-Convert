package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocxSplitRulesTest {

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

    private static String fillers(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(DocxDoc.p("F" + i));
        }
        return b.toString();
    }

    // An empty paragraph exactly this tall, to bring what follows to a known height on the page
    private static String spacer(int twips) {
        return "<w:p><w:pPr><w:spacing w:line=\"" + twips + "\" w:lineRule=\"exact\"/></w:pPr></w:p>";
    }

    private static String lines(String prefix, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(DocxDoc.p(prefix + i));
        }
        return b.toString();
    }

    private static String table(String trPr, String cell) {
        return "<w:tbl><w:tblPr>" + BORDERS + "</w:tblPr><w:tblGrid><w:gridCol w:w=\"9000\"/></w:tblGrid><w:tr>"
                + trPr + "<w:tc>" + cell + "</w:tc></w:tr></w:tbl>";
    }

    @Test
    void textAfterABreakThatOpensAParagraphKeepsTheParagraphSpaceBefore() throws IOException {
        String body = fillers(3) + "<w:p><w:pPr><w:spacing w:before=\"240\"/></w:pPr><w:r><w:br w:type=\"page\"/>"
                + "</w:r><w:r><w:t>Next</w:t></w:r></w:p>";
        DocxDoc.Rendered r = render("breakbefore", body);
        assertEquals(2, r.word("Next").page());
        assertEquals(12, r.word("Next").y() - r.word("F0").y(), 0.2, "the 12 pt space before opens the new page");
    }

    @Test
    void keepWithNextLetsTheLastKeptLineReachIntoItsLineSpacing() throws IOException {
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            words.append("w").append(i).append(' ');
        }
        String body = fillers(50) + spacer(720) + "<w:p><w:pPr><w:keepNext/></w:pPr><w:r><w:t>Heading</w:t></w:r>"
                + "</w:p><w:p><w:pPr><w:spacing w:line=\"276\" w:lineRule=\"auto\"/></w:pPr><w:r><w:t>"
                + words.toString().strip() + "</w:t></w:r></w:p>";
        DocxDoc.Rendered r = render("keepslack", body);
        assertEquals(1, r.word("Heading").page(), "the heading and two lines fit when the extra spacing may overflow");
        assertEquals(1, r.word("w0").page());
    }

    @Test
    void aRowWhoseMinimumHeightHoldsItsContentMovesWhole() throws IOException {
        String body = fillers(50) + table("<w:trPr><w:trHeight w:val=\"3000\"/></w:trPr>", lines("L", 10));
        DocxDoc.Rendered r = render("minrow", body);
        assertEquals(2, r.word("L0").page(), "the row does not split");
        assertEquals(2, r.word("L9").page());
    }

    @Test
    void aSplitRowLeavesRoomForItsBordersOnThePageItLeaves() throws IOException {
        String body = fillers(50) + spacer(74) + table("", lines("L", 6));
        DocxDoc.Rendered r = render("splitborders", body);
        assertEquals(1, r.word("L0").page());
        assertEquals(1, r.word("L4").page());
        assertEquals(2, r.word("L5").page(), "the sixth line would cover the borders, so it moves");
    }

    @Test
    void everyCellOfARowTakesItsLargestTopMargin() throws IOException {
        String bare = "<w:tc><w:tcPr><w:tcW w:w=\"4500\" w:type=\"dxa\"/></w:tcPr>" + DocxDoc.p("Left") + "</w:tc>";
        String padded = "<w:tc><w:tcPr><w:tcW w:w=\"4500\" w:type=\"dxa\"/><w:tcMar><w:top w:w=\"200\""
                + " w:type=\"dxa\"/></w:tcMar></w:tcPr>" + DocxDoc.p("Right") + "</w:tc>";
        String body = "<w:tbl><w:tblGrid><w:gridCol w:w=\"4500\"/><w:gridCol w:w=\"4500\"/></w:tblGrid><w:tr>" + bare
                + padded + "</w:tr></w:tbl>" + DocxDoc.p("After");
        DocxDoc.Rendered r = render("rowmargin", body);
        assertEquals(r.word("Right").y(), r.word("Left").y(), 0.1, "both cells start below the 10 pt margin");
        assertEquals(11.5, r.word("After").y() - r.word("Left").y(), 0.2, "the row ends one line below them");
    }

    @Test
    void aTableRowLeavesRoomForTheFootnotesItReferences() throws IOException {
        String ref = "<w:p><w:r><w:t>REF</w:t></w:r><w:r><w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr>"
                + "<w:footnoteReference w:id=\"1\"/></w:r></w:p>";
        StringBuilder note = new StringBuilder("<w:footnote w:id=\"1\">");
        for (int i = 0; i < 6; i++) {
            note.append(DocxDoc.p("N" + i));
        }
        note.append("</w:footnote>");
        String seps = "<w:footnote w:type=\"separator\" w:id=\"-1\"><w:p><w:r><w:separator/></w:r></w:p></w:footnote>"
                + "<w:footnote w:type=\"continuationSeparator\" w:id=\"0\"><w:p><w:r><w:continuationSeparator/></w:r>"
                + "</w:p></w:footnote>";
        String body = fillers(50) + table("", DocxDoc.p("Top") + ref);
        DocxDoc.Rendered r = DocxDoc.render(dir, "rownote", new DocxDoc().styles(DEFAULTS).footnotes(seps + note)
                .body(body).bytes());
        assertEquals(1, r.word("Top").page(), "the line without a reference stays");
        int refPage = r.words().stream().filter(w -> w.text().startsWith("REF")).findFirst().orElseThrow().page();
        assertEquals(refPage, r.word("N0").page(), "the note is on the page of its reference");
    }
}

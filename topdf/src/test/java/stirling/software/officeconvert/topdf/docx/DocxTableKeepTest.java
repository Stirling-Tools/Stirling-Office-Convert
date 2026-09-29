package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocxTableKeepTest {

    private static final String FONT = "Liberation Sans";

    private static final String DEFAULTS = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"" + FONT
            + "\" w:hAnsi=\"" + FONT + "\"/><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";

    @TempDir
    Path dir;

    private static String fillers(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append(DocxDoc.p("F" + i));
        }
        return b.toString();
    }

    private static String para(String text, String pPr) {
        return "<w:p><w:pPr>" + pPr + "</w:pPr><w:r><w:t>" + text + "</w:t></w:r></w:p>";
    }

    private static String row(String rowPr, String content) {
        return "<w:tr><w:trPr>" + rowPr + "</w:trPr><w:tc><w:tcPr><w:tcW w:w=\"4000\" w:type=\"dxa\"/></w:tcPr>"
                + content + "</w:tc></w:tr>";
    }

    private static String table(String rows) {
        return "<w:tbl><w:tblPr><w:tblW w:w=\"4000\" w:type=\"dxa\"/></w:tblPr><w:tblGrid><w:gridCol w:w=\"4000\"/>"
                + "</w:tblGrid>" + rows + "</w:tbl>";
    }

    private DocxDoc.Rendered render(String name, String body) throws IOException {
        return DocxDoc.render(dir, name, new DocxDoc().styles(DEFAULTS).body(body).bytes());
    }

    @Test
    void keepWithNextInsideACellMovesTheRowRatherThanSplitIt() throws IOException {
        StringBuilder chained = new StringBuilder();
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            chained.append(para("K" + i, "<w:keepNext/>"));
            plain.append(para("P" + i, ""));
        }
        // 50 lines leave 73 pt, room for six of the eight 11.5 pt paragraphs
        DocxDoc.Rendered kept = render("cellkeep", fillers(50) + table(row("", chained.toString())));
        assertEquals(2, kept.word("K0").page());
        DocxDoc.Rendered split = render("cellsplit", fillers(50) + table(row("", plain.toString())));
        assertEquals(1, split.word("P5").page());
        assertEquals(2, split.word("P6").page());
    }

    @Test
    void keepLinesInsideACellKeepsTheParagraphWhole() throws IOException {
        String lines = "<w:p><w:pPr><w:keepLines/></w:pPr><w:r><w:t>L0</w:t></w:r><w:r><w:br/><w:t>L1</w:t></w:r><w:r>"
                + "<w:br/><w:t>L2</w:t></w:r><w:r><w:br/><w:t>L3</w:t></w:r></w:p>";
        DocxDoc.Rendered r = render("celllines", fillers(53) + table(row("", para("Head", "") + lines)));
        assertEquals(1, r.word("Head").page());
        assertEquals(2, r.word("L0").page(), "the four-line paragraph does not fit the 26.5 pt left and moves whole");
    }

    @Test
    void aRowWhoseParagraphsKeepWithNextMovesWithTheNextRow() throws IOException {
        String next = "<w:p><w:r><w:t>B0</w:t></w:r><w:r><w:br/><w:t>B1</w:t></w:r><w:r><w:br/><w:t>B2</w:t></w:r><w:r>"
                + "<w:br/><w:t>B3</w:t></w:r></w:p>";
        String rows = row("", para("Alone", "<w:keepNext/>")) + row("<w:cantSplit/>", next);
        // 52 lines leave 50 pt: the first row fits, but not with the 46 pt row it keeps with
        DocxDoc.Rendered r = render("rowkeep", fillers(52) + table(rows));
        assertEquals(1, r.word("F51").page());
        assertEquals(2, r.word("Alone").page());
        assertEquals(2, r.word("B0").page());
    }

    @Test
    void aCaptionKeptWithATableNeedsTheLinesTheFirstRowMayBreakAfter() throws IOException {
        String cell = "<w:p><w:r><w:t>A</w:t></w:r><w:r><w:br/><w:t>B</w:t></w:r></w:p>";
        // After 54 lines and the caption 15.5 pt are left: one line of the row fits, but widow control keeps two
        DocxDoc.Rendered r = render("caption", fillers(54) + para("Cap", "<w:keepNext/>") + table(row("", cell)));
        assertEquals(2, r.word("Cap").page());
        assertEquals(2, r.word("A").page());
    }

    @Test
    void aCaptionKeptWithATableNeedsItsHeaderRowsAndFirstRow() throws IOException {
        String rows = row("<w:tblHeader/>", para("Head", "")) + row("", para("Body", ""));
        DocxDoc.Rendered r = render("captionhead", fillers(54) + para("Cap", "<w:keepNext/>") + table(rows));
        assertEquals(2, r.word("Cap").page());
        assertEquals(2, r.word("Head").page());
    }
}


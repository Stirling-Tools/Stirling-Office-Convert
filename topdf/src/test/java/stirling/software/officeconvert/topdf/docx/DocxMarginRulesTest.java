package stirling.software.officeconvert.topdf.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocxMarginRulesTest {

    private static final String FONT = "Liberation Sans";

    private static final String DEFAULTS = "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"" + FONT
            + "\" w:hAnsi=\"" + FONT + "\"/><w:sz w:val=\"20\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>"
            + "<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>";

    @TempDir
    Path dir;

    private DocxDoc.Rendered row(String name, String cellMargins) throws IOException {
        return row(name, cellMargins, "");
    }

    private DocxDoc.Rendered row(String name, String cellMargins, String rule) throws IOException {
        String cell = "<w:tc><w:tcPr><w:tcW w:w=\"3000\" w:type=\"dxa\"/>" + cellMargins + "</w:tcPr>"
                + "<w:p><w:r><w:t>Cell</w:t></w:r></w:p></w:tc>";
        String body = "<w:tbl><w:tblPr><w:tblW w:w=\"3000\" w:type=\"dxa\"/></w:tblPr><w:tblGrid><w:gridCol"
                + " w:w=\"3000\"/></w:tblGrid><w:tr><w:trPr><w:trHeight w:val=\"600\"" + rule + "/></w:trPr>" + cell
                + "</w:tr></w:tbl>" + DocxDoc.p("After");
        return DocxDoc.render(dir, name, new DocxDoc().styles(DEFAULTS).body(body).bytes());
    }

    @Test
    void cellMarginsAddToAnAtLeastRowHeight() throws IOException {
        DocxDoc.Rendered plain = row("rowplain", "");
        DocxDoc.Rendered margins = row("rowmargins", "<w:tcMar><w:top w:w=\"100\" w:type=\"dxa\"/><w:bottom"
                + " w:w=\"60\" w:type=\"dxa\"/></w:tcMar>");
        float plainGap = plain.word("After").y() - plain.word("Cell").y();
        float marginGap = margins.word("After").y() - margins.word("Cell").y();
        // The 30 pt minimum holds the text; the 5 pt top and 3 pt bottom cell margins come on top of it
        assertEquals(3, marginGap - plainGap, 0.1);
        assertEquals(5, margins.word("Cell").y() - plain.word("Cell").y(), 0.1);
    }

    @Test
    void cellMarginsAddToAnExactRowHeight() throws IOException {
        String exact = " w:hRule=\"exact\"";
        DocxDoc.Rendered plain = row("exactplain", "", exact);
        DocxDoc.Rendered margins = row("exactmargins", "<w:tcMar><w:bottom w:w=\"400\" w:type=\"dxa\"/></w:tcMar>",
                exact);
        assertEquals(20, margins.word("After").y() - plain.word("After").y(), 0.1);
        assertEquals(plain.word("Cell").y(), margins.word("Cell").y(), 0.1);
    }

    @Test
    void tableMarginsAddToAnAtLeastRowHeight() throws IOException {
        String body = "<w:tbl><w:tblPr><w:tblW w:w=\"3000\" w:type=\"dxa\"/><w:tblCellMar><w:top w:w=\"100\""
                + " w:type=\"dxa\"/><w:bottom w:w=\"60\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>"
                + "<w:gridCol w:w=\"3000\"/></w:tblGrid><w:tr><w:trPr><w:trHeight w:val=\"600\"/></w:trPr><w:tc>"
                + "<w:tcPr><w:tcW w:w=\"3000\" w:type=\"dxa\"/></w:tcPr><w:p><w:r><w:t>Cell</w:t></w:r></w:p></w:tc>"
                + "</w:tr></w:tbl>" + DocxDoc.p("After");
        DocxDoc.Rendered r = DocxDoc.render(dir, "rowtable", new DocxDoc().styles(DEFAULTS).body(body).bytes());
        DocxDoc.Rendered plain = row("rowplain2", "");
        // As in Word at compatibility mode 14: the 5 pt top and 3 pt bottom table margins come on top of the 30 pt
        assertEquals(8, r.word("After").y() - plain.word("After").y(), 0.1);
        String exact = body.replace("<w:trHeight w:val=\"600\"/>", "<w:trHeight w:val=\"600\" w:hRule=\"exact\"/>");
        DocxDoc.Rendered fixed = DocxDoc.render(dir, "rowexact", new DocxDoc().styles(DEFAULTS).body(exact).bytes());
        assertEquals(plain.word("After").y(), fixed.word("After").y(), 0.1);
    }

    @Test
    void repeatingHeaderRowsMoveWithTheirFirstRow() throws IOException {
        StringBuilder fill = new StringBuilder();
        for (int i = 0; i < 53; i++) {
            fill.append(DocxDoc.p("F" + i));
        }
        String cell = "<w:tc><w:tcPr><w:tcW w:w=\"3000\" w:type=\"dxa\"/></w:tcPr><w:p><w:r><w:t>%s</w:t></w:r></w:p>"
                + "</w:tc>";
        String table = "<w:tbl><w:tblPr><w:tblW w:w=\"3000\" w:type=\"dxa\"/></w:tblPr><w:tblGrid><w:gridCol"
                + " w:w=\"3000\"/></w:tblGrid><w:tr><w:trPr><w:tblHeader/></w:trPr>" + cell.formatted("Head")
                + "</w:tr><w:tr><w:trPr><w:cantSplit/></w:trPr>" + cell.formatted("Row1") + "</w:tr></w:tbl>";
        // 53 lines leave 38.5 pt: room for the 11.5 pt header row, not for it and the 34.5 pt row together
        String row1 = "<w:tc><w:tcPr><w:tcW w:w=\"3000\" w:type=\"dxa\"/></w:tcPr><w:p><w:r><w:t>Row1</w:t></w:r>"
                + "<w:r><w:br/><w:t>more</w:t></w:r><w:r><w:br/><w:t>lines</w:t></w:r></w:p></w:tc>";
        table = table.replace(cell.formatted("Row1"), row1);
        DocxDoc.Rendered r = DocxDoc.render(dir, "header", new DocxDoc().styles(DEFAULTS).body(fill + table).bytes());
        assertEquals(1, r.word("F52").page());
        assertEquals(2, r.word("Head").page());
        assertEquals(2, r.word("Row1").page());
    }

    private static String table(String cells, String rowPr) {
        return "<w:tbl><w:tblPr><w:tblW w:w=\"3000\" w:type=\"dxa\"/></w:tblPr><w:tblGrid><w:gridCol w:w=\"3000\"/>"
                + "</w:tblGrid><w:tr><w:trPr>" + rowPr + "</w:trPr>" + cells + "</w:tr></w:tbl>";
    }

    @Test
    void anEmptyCellWithHideMarkLeavesTheRowItsSetHeight() throws IOException {
        String empty = "<w:tc><w:tcPr><w:tcW w:w=\"3000\" w:type=\"dxa\"/>%s</w:tcPr><w:p><w:pPr><w:rPr><w:sz"
                + " w:val=\"36\"/></w:rPr></w:pPr></w:p></w:tc>";
        String height = "<w:trHeight w:val=\"80\"/>";
        String body = DocxDoc.p("Top") + table(empty.formatted("<w:hideMark/>"), height) + DocxDoc.p("After");
        DocxDoc.Rendered hidden = DocxDoc.render(dir, "hidemark", new DocxDoc().styles(DEFAULTS).body(body).bytes());
        body = DocxDoc.p("Top") + table(empty.formatted(""), height) + DocxDoc.p("After");
        DocxDoc.Rendered shown = DocxDoc.render(dir, "showmark", new DocxDoc().styles(DEFAULTS).body(body).bytes());
        assertEquals(11.5 + 4, hidden.word("After").y() - hidden.word("Top").y(), 0.1);
        assertEquals(11.5 + 20.7, shown.word("After").y() - shown.word("Top").y(), 0.3);
    }

    @Test
    void aVerticalMergeThatNoRowContinuesIsAPlainCell() throws IOException {
        String cell = "<w:tc><w:tcPr><w:tcW w:w=\"3000\" w:type=\"dxa\"/><w:vMerge w:val=\"restart\"/></w:tcPr>"
                + "<w:p><w:pPr><w:spacing w:after=\"0\"/></w:pPr><w:r><w:t>Cell</w:t></w:r></w:p></w:tc>";
        String styles = DEFAULTS + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name"
                + " w:val=\"Normal\"/><w:pPr><w:spacing w:after=\"400\"/></w:pPr></w:style>";
        String body = table(cell, "") + "<w:p><w:pPr><w:spacing w:after=\"0\"/></w:pPr><w:r><w:t>After</w:t></w:r>"
                + "</w:p>";
        DocxDoc.Rendered r = DocxDoc.render(dir, "lonemerge", new DocxDoc().styles(styles).body(body).bytes());
        // The cell's own paragraph sizes the row, not the end-of-row mark with its 20 pt space after
        assertEquals(11.5, r.word("After").y() - r.word("Cell").y(), 0.1);
    }
}


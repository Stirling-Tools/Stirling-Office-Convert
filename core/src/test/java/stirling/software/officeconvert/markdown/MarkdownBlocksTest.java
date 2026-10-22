package stirling.software.officeconvert.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Table;

class MarkdownBlocksTest {

    private static final RunStyle MONO = new RunStyle("Courier New", 10, false, false, false, false, 0, -1, 0, false);

    @Test
    void monospaceRunsBecomeOneIndentedCodeBlock() throws IOException {
        MarkdownBlocks blocks = new MarkdownBlocks(null);
        StringBuilder sb = new StringBuilder();
        String[][] lines = {{"server:", "0"}, {"port: 8080", "12"}, {"name: `x`", "12"}};
        for (String[] l : lines) {
            Paragraph p = new Paragraph();
            p.indentLeft = Float.parseFloat(l[1]);
            p.inlines.add(new Inline.Text(l[0], MONO, null, -1));
            blocks.paragraph(sb, p);
        }
        Paragraph after = new Paragraph();
        after.inlines.add(new Inline.Text("Plain *text*", new RunStyle("Arial", 10, false, false, false, false, 0, -1, 0, false), null, -1));
        blocks.paragraph(sb, after);
        assertEquals("```\nserver:\n  port: 8080\n  name: `x`\n```\n\nPlain \\*text\\*", sb.toString());
    }

    @Test
    void monospaceDocumentsStayParagraphs() throws IOException {
        MarkdownBlocks blocks = new MarkdownBlocks(null);
        blocks.bodyFont("Courier New");
        StringBuilder sb = new StringBuilder();
        Paragraph p = new Paragraph();
        p.inlines.add(new Inline.Text("INT. OFFICE - NIGHT", MONO, null, -1));
        blocks.paragraph(sb, p);
        assertEquals("`INT. OFFICE - NIGHT`", sb.toString());
    }

    @Test
    void textStaysLiteral() {
        assertEquals("&amp;copy; \\*x\\* a\\|b &lt;i>", MarkdownBlocks.escape("&copy; *x* a|b <i>", true));
    }

    @Test
    void codeSpansOutlastTheirBackticksAndPipes() throws IOException {
        Table t = new Table();
        Table.Row row = new Table.Row();
        for (String text : new String[] {"c", "p|q ``x`` y"}) {
            Table.Cell cell = new Table.Cell();
            Paragraph p = new Paragraph();
            p.inlines.add(new Inline.Text(text, MONO, null, -1));
            cell.paragraphs.add(p);
            row.cells.add(cell);
        }
        t.rows.add(row);
        StringBuilder sb = new StringBuilder();
        new MarkdownBlocks(null).table(sb, t);
        String header = sb.toString().lines().findFirst().orElseThrow();
        assertEquals("| `c` | ```p\\|q ``x`` y``` |", header);
        assertTrue(sb.toString().contains("| --- | --- |"));
    }
}

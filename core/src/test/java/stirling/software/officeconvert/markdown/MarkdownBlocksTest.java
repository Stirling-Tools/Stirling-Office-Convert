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

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

    private static final RunStyle PLAIN = new RunStyle("Arial", 10, false, false, false, false, 0, -1, 0, false);

    private static Paragraph paragraph(String text, RunStyle style) {
        Paragraph p = new Paragraph();
        p.inlines.add(new Inline.Text(text, style, null, -1));
        return p;
    }

    private static String write(Paragraph... paragraphs) throws IOException {
        MarkdownBlocks blocks = new MarkdownBlocks(null);
        StringBuilder sb = new StringBuilder();
        for (Paragraph p : paragraphs) {
            blocks.paragraph(sb, p);
        }
        blocks.flush(sb);
        return sb.toString();
    }

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
        assertEquals("&amp;copy; \\*x\\* a\\|b &lt;i>", MarkdownBlocks.escape("&copy; *x* a|b <i>"));
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

    @Test
    void boldRunsThatDifferOnlyInColourStayOneStrongSpan() throws IOException {
        RunStyle bold = new RunStyle("Arial", 10, true, false, false, false, 0, -1, 0, false);
        RunStyle red = new RunStyle("Arial", 10, true, false, false, false, 0xFF0000, -1, 0, false);
        Paragraph p = paragraph("Total", bold);
        p.inlines.add(new Inline.Text("due", red, null, -1));
        assertEquals("**Totaldue**", write(p));
    }

    @Test
    void codeInATextBoxStaysBeforeTheParagraphItFloatsAbove() throws IOException {
        Paragraph box = paragraph("x = 1", MONO);
        Paragraph p = paragraph("After the box", PLAIN);
        p.sourceBottom = 100;
        p.inlines.add(0, new Inline.TextBox(0, 10, 100, 20, -1, 0, 0, 0, java.util.List.of(box), -1, 0, false, false));
        assertEquals("```\nx = 1\n```\n\nAfter the box", write(p));
    }

    @Test
    void pipesOutsideTablesCannotStartATable() throws IOException {
        Paragraph p = paragraph("a | b", PLAIN);
        p.inlines.add(new Inline.Break());
        p.inlines.add(new Inline.Text(":-- | --:", PLAIN, null, -1));
        assertEquals("a \\| b  \n:-- \\| --:", write(p));
    }

    @Test
    void trailingHashStaysInTheHeading() throws IOException {
        Paragraph p = paragraph("Fix for issue #", PLAIN);
        p.style = "Heading2";
        assertEquals("## Fix for issue \\#", write(p));
    }

    @Test
    void listItemTextIsNotReadAsBlockSyntax() throws IOException {
        Paragraph p = paragraph("# of items", PLAIN);
        p.list = new Paragraph.ListRef(1, 0);
        assertEquals(MarkdownBlocks.MARK + "L1:0" + MarkdownBlocks.MARK + " \\# of items", write(p));
    }

    @Test
    void longCodeListingsAreWrittenAsTheyGo() throws IOException {
        MarkdownBlocks blocks = new MarkdownBlocks(null);
        StringBuilder sb = new StringBuilder();
        String line = "x".repeat(99);
        for (int i = 0; i < 20_000 && sb.isEmpty(); i++) {
            blocks.paragraph(sb, paragraph(line, MONO));
        }
        assertTrue(sb.toString().startsWith("```\n" + line + "\n"), sb.length() + " chars written");
    }
}

package stirling.software.officeconvert.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.Table;

class TableContinuationTest {

    @Test
    void wholeRowsFromTheNextPageStayAnotherTable() {
        Table prev = table(new String[] {"1", "one"}, new String[] {"2", "two"});
        prev.rows.getLast().height = 14f;
        Table next = table(new String[] {"", ""}, new String[] {"3", "three"});
        next.rows.getFirst().height = 24f;
        assertTrue(TableContinuation.continues(prev, next));
        assertFalse(TableContinuation.join(prev, next, null), "LibreOffice ignores page breaks inside a table");
        assertEquals(2, prev.rows.size());
        assertEquals(14f, prev.rows.getLast().height, 0.001f, "the cut row keeps its height");
        assertEquals(24f, TableContinuation.dropLeftover(next), 0.001f, "the blank tail of the cut row becomes space");
        assertEquals(1, next.rows.size());
        assertEquals("3", ((Inline.Text) next.rows.getFirst().cells.getFirst().paragraphs.getFirst().inlines.getFirst()).text());
    }

    @Test
    void aRepeatedHeaderOverWholeRowsIsNotCopiedIntoTheBody() {
        Table prev = table(new String[] {"Name", "Description"}, new String[] {"Item 1", "one"});
        Table next = table(new String[] {"Name", "Description"}, new String[] {"Item 2", "two"}, new String[] {"Item 3", "three"});
        assertTrue(TableContinuation.join(prev, next, null), "a repeated header says it is one table");
        assertTrue(prev.rows.getFirst().header);
        assertEquals(4, prev.rows.size());
        assertEquals("Item 2", ((Inline.Text) prev.rows.get(2).cells.getFirst().paragraphs.getFirst().inlines.getFirst()).text());
    }

    @Test
    void aRowCutAcrossThePageStaysOneRow() {
        Table prev = table(new String[] {"1", "one and"});
        Table next = table(new String[] {"", "more"}, new String[] {"2", "two"});
        assertTrue(TableContinuation.join(prev, next, null));
        assertEquals(2, prev.rows.size());
        assertEquals(2, prev.rows.getFirst().cells.get(1).paragraphs.size(), "the cut cell holds both halves");
        assertFalse(prev.rows.get(1).cells.getFirst().paragraphs.getFirst().pageBreakBefore, "row 2 follows on the same page");
    }

    @Test
    void aRepeatedHeaderMarksTheJoinedTable() {
        Table prev = table(new String[] {"No", "Title"}, new String[] {"1", "one and"});
        Table next = table(new String[] {"No", "Title"}, new String[] {"", "more"}, new String[] {"2", "two"});
        assertTrue(TableContinuation.join(prev, next, null));
        assertTrue(prev.rows.getFirst().header);
        assertEquals(3, prev.rows.size());
    }

    private static Table table(String[]... rows) {
        Table t = new Table();
        t.columnWidths.add(50f);
        t.columnWidths.add(200f);
        for (String[] r : rows) {
            Table.Row row = new Table.Row();
            for (String text : r) {
                Table.Cell c = new Table.Cell();
                Paragraph p = new Paragraph();
                if (!text.isEmpty()) {
                    p.inlines.add(new Inline.Text(text, new RunStyle("Arial", 10, false, false, false, false, 0, -1, 0, false),
                            null, -1));
                }
                c.paragraphs.add(p);
                row.cells.add(c);
            }
            t.rows.add(row);
        }
        return t;
    }
}

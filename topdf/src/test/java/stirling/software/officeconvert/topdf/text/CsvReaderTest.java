package stirling.software.officeconvert.topdf.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class CsvReaderTest {

    static List<List<String>> rows(String csv, char sep) throws IOException {
        CsvReader r = new CsvReader(new StringReader(csv), sep);
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        while (r.next(row)) {
            out.add(List.copyOf(row));
        }
        return out;
    }

    static List<List<String>> rows(String csv) throws IOException {
        return rows(csv, ',');
    }

    @Test
    void quotedFieldsKeepSeparatorsQuotesAndLineBreaks() throws IOException {
        assertEquals(List.of(List.of("a,b", "say \"hi\"", "x\ny"), List.of("z")),
                rows("\"a,b\",\"say \"\"hi\"\"\",\"x\r\ny\"\r\nz\r\n"));
        assertEquals(List.of(List.of("1", "two\nlines")), rows("1,\"two\rlines\""));
    }

    @Test
    void everyLineEndingEndsARowAndBlankLinesStay() throws IOException {
        assertEquals(List.of(List.of("a"), List.of("b"), List.of(""), List.of("c")), rows("a\rb\r\n\nc"));
        assertEquals(List.of(List.of("a", "b", "")), rows("a,b,\n"));
        assertEquals(List.of(), rows(""));
    }

    @Test
    void anUnclosedQuoteIsReadAsAPlainCharacter() throws IOException {
        assertEquals(List.of(List.of("a", "\"b", "c"), List.of("d", "e")), rows("a,\"b,c\nd,e\n"));
    }

    @Test
    void textAfterAClosingQuoteKeepsTheFieldAsWritten() throws IOException {
        assertEquals(List.of(List.of("a", "b\"c", "d"), List.of("\"x\"y", "z")), rows("a,b\"c,d\n\"x\"y,z\n"));
    }

    @Test
    void spacesBeforeAnOpeningQuoteAreDropped() throws IOException {
        assertEquals(List.of(List.of("a", "b", " c ")), rows("a, \"b\", c \n"));
    }

    @Test
    void tabSeparatedFilesSplitOnTabsOnly() throws IOException {
        assertEquals(List.of(List.of("a,b", "c d", "e\tf")), rows("a,b\tc d\t\"e\tf\"\n", '\t'));
    }

    @Test
    void cellsAndRowsAreBounded() throws IOException {
        CsvReader r = new CsvReader(new StringReader("x".repeat(CsvReader.MAX_CELL_CHARS + 10) + ",y\n"), ',');
        List<String> row = new ArrayList<>();
        assertTrue(r.next(row));
        assertEquals(CsvReader.MAX_CELL_CHARS, row.get(0).length());
        assertEquals("y", row.get(1));
        assertTrue(r.clipped());
        CsvReader wide = new CsvReader(new StringReader(",".repeat(CsvReader.MAX_COLUMNS + 5)), ',');
        assertTrue(wide.next(row));
        assertEquals(CsvReader.MAX_COLUMNS, row.size());
        assertTrue(wide.clipped());
        CsvReader fine = new CsvReader(new StringReader("a,b\n"), ',');
        assertTrue(fine.next(row));
        assertFalse(fine.clipped());
    }
}

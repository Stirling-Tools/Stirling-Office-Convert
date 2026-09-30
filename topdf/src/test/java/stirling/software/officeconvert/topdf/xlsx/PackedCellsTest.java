package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

class PackedCellsTest {

    private static final FontSpec PLAIN = new FontSpec("Calibri", 11, false, false, null, false, null, null);

    private static final FontSpec RED = new FontSpec("Arial", 9.5, true, true, FontSpec.Underline.DOUBLE, true,
            Color.RED, FontSpec.Offset.SUPER);

    private static CellFormat format(Color fill) {
        return new CellFormat(PLAIN, fill, null, null, null, null, null, false, false, CellFormat.HAlign.RIGHT, null,
                true, false, 2, 90, 164, "#,##0.00", 1);
    }

    @Test
    void cellsComeBackExactlyAsTheyWent() {
        PackedCells store = new PackedCells();
        CellFormat a = format(null);
        CellFormat b = format(Color.YELLOW);
        List<CellEntry> in = List.of(
                new CellEntry(7, 0, a, null),
                new CellEntry(7, 1, b, new CellText(CellText.Kind.NUMBER, List.of(new TextRun("1,234.50", PLAIN)),
                        Color.BLUE, true, 1234.5)),
                new CellEntry(7, 2, a, new CellText(CellText.Kind.TEXT, List.of(new TextRun("مرحبا بالعالم", RED),
                        new TextRun(" 你好 😀 ", PLAIN), new TextRun("\uD800 lone", RED)), null, false, 0)),
                new CellEntry(7, 300, b, new CellText(CellText.Kind.NUMBER, List.of(new TextRun("NaN", PLAIN)), null,
                        false, Double.NaN)),
                new CellEntry(7, 16383, a, new CellText(CellText.Kind.ERROR, List.of(new TextRun("#N/A", PLAIN)), null,
                        false, -0.0)),
                new CellEntry(7, 5000, a, new CellText(CellText.Kind.BOOLEAN, List.of(new TextRun("", PLAIN)), null,
                        false, 0)));
        Grid.RowInfo row = new Grid.RowInfo(store, 7, 15, false, -1);
        TreeMap<Integer, CellEntry> cells = new TreeMap<>();
        for (CellEntry e : in) {
            cells.put(e.col(), e);
        }
        store.store(row, cells);
        for (int i = 0; i < PackedCells.MAX_CACHED_ROWS + 10; i++) {
            Grid.RowInfo filler = new Grid.RowInfo(store, 100 + i, 15, false, -1);
            TreeMap<Integer, CellEntry> one = new TreeMap<>();
            one.put(0, new CellEntry(100 + i, 0, a, null));
            store.store(filler, one);
        }
        for (CellEntry e : in) {
            assertEquals(e, store.cell(row, e.col()), "a single cell is read without unpacking the row");
            assertSame(e.format(), store.format(row, e.col()));
        }
        assertEquals(List.of(in.get(2), in.get(3)), store.cells(row, 2, 300), "a page reads only its own columns");
        assertEquals(List.of(), store.cells(row, 301, 4999));
        assertEquals(List.of(), store.cells(row, 9, 3));
        assertNull(store.cell(row, 3));
        assertNull(store.format(row, 16384));
        TreeMap<Integer, CellEntry> back = new TreeMap<>(store.cells(row));
        assertEquals(new ArrayList<>(cells.values()), new ArrayList<>(back.values()));
        assertSame(b, back.get(1).format(), "formats are shared, not copied");
        assertTrue(Double.isNaN(back.get(300).text().number()));
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(back.get(16383).text().number()));
        assertNull(back.get(0).text());
        assertEquals(6, row.count());
        assertEquals(List.of(1, 2, 300, 16383), java.util.Arrays.stream(row.textColumns()).boxed().toList());
    }

    @Test
    void anEmptyRowKeepsNoBytes() {
        PackedCells store = new PackedCells();
        Grid.RowInfo row = new Grid.RowInfo(store, 1, 15, false, -1);
        store.store(row, new TreeMap<>());
        assertNull(row.packed);
        assertTrue(row.isEmpty());
        assertTrue(row.cells().isEmpty());
    }

    @Test
    void aChangedRowIsPackedAgain() {
        PackedCells store = new PackedCells();
        Grid.RowInfo row = new Grid.RowInfo(store, 3, 15, false, -1);
        store.store(row, new TreeMap<>());
        CellEntry e = new CellEntry(3, 4, format(Color.GREEN), null);
        row.put(e);
        assertEquals(1, row.count());
        assertEquals(e, store.cells(row).get(4));
    }
}

package stirling.software.officeconvert.topdf.grid;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/** One sheet of cells read from a simple table format (SYLK, DIF, dBASE), with what little formatting they carry. */
public final class Grid {

    public static final int MAX_ROWS = 1_048_576;

    public static final int MAX_COLS = 16_384;

    public static final int MAX_CELLS = 5_000_000;

    /** A cell's value: a Double, String or Boolean, or an Error. */
    public record Cell(Object value, String format, boolean bold, boolean italic, String align, int rotation) {

        public Cell(Object value, String format, boolean bold, boolean italic, String align) {
            this(value, format, bold, italic, align, 0);
        }
    }

    public record Error(String code) {}

    final TreeMap<Integer, TreeMap<Integer, Cell>> rows = new TreeMap<>();

    final TreeMap<Integer, Double> widths = new TreeMap<>();

    double defaultWidth = -1;

    private int count;

    boolean truncated;

    private final Set<String> warnings = new LinkedHashSet<>();

    public void put(int row, int col, Cell cell) {
        if (row < 0 || row >= MAX_ROWS || col < 0 || col >= MAX_COLS || cell == null) {
            return;
        }
        if (count >= MAX_CELLS) {
            truncated = true;
            return;
        }
        TreeMap<Integer, Cell> r = rows.computeIfAbsent(row, k -> new TreeMap<>());
        Cell old = r.get(col);
        if (old == null) {
            count++;
            r.put(col, cell);
        } else {
            r.put(col, new Cell(cell.value() != null ? cell.value() : old.value(),
                    cell.format() != null ? cell.format() : old.format(), cell.bold() || old.bold(),
                    cell.italic() || old.italic(), cell.align() != null ? cell.align() : old.align(),
                    cell.rotation() != 0 ? cell.rotation() : old.rotation()));
        }
    }

    public Cell get(int row, int col) {
        TreeMap<Integer, Cell> r = rows.get(row);
        return r == null ? null : r.get(col);
    }

    public long restyle(int firstRow, int lastRow, int firstCol, int lastCol, UnaryOperator<Cell> change) {
        if (firstRow > lastRow || firstCol > lastCol) {
            return 1;
        }
        long seen = 1;
        for (TreeMap<Integer, Cell> r : rows.subMap(firstRow, true, lastRow, true).values()) {
            seen++;
            for (Map.Entry<Integer, Cell> e : r.subMap(firstCol, true, lastCol, true).entrySet()) {
                e.setValue(change.apply(e.getValue()));
                seen++;
            }
        }
        return seen;
    }

    public void value(int row, int col, Object value) {
        put(row, col, new Cell(value, null, false, false, null));
    }

    public void width(int col, double chars) {
        if (col >= 0 && col < MAX_COLS && chars >= 0 && chars <= 255) {
            widths.put(col, chars);
        }
    }

    public double width(int col) {
        return widths.getOrDefault(col, defaultWidth > 0 ? defaultWidth : 8.43);
    }

    public void defaultWidth(double chars) {
        if (chars > 0 && chars <= 255) {
            defaultWidth = chars;
        }
    }

    public boolean truncated() {
        return truncated;
    }

    public void warn(String warning) {
        warnings.add(warning);
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }
}

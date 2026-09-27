package stirling.software.officeconvert.sheet;

import java.util.List;

public record Row(int index, float height, List<Cell> cells) {

    public record Cell(int col, CellValue value, CellStyle style, int rowSpan, int colSpan) {

        public Cell(int col, CellValue value, CellStyle style) {
            this(col, value, style, 1, 1);
        }

        public Cell {
            rowSpan = Math.max(1, rowSpan);
            colSpan = Math.max(1, colSpan);
        }

        public boolean merged() {
            return rowSpan > 1 || colSpan > 1;
        }
    }

    public Row {
        cells = List.copyOf(cells);
    }

    public boolean fixedHeight() {
        return !Float.isNaN(height) && height > 0;
    }
}

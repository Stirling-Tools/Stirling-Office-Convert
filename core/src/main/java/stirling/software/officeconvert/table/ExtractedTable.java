package stirling.software.officeconvert.table;

import java.util.Arrays;
import java.util.List;

public record ExtractedTable(
        int firstPage,
        int lastPage,
        int rowCount,
        int columnCount,
        List<Float> columnWidths,
        List<ExtractedCell> cells,
        int headerRows,
        boolean ruled) {

    public String[][] toGrid() {
        String[][] grid = new String[rowCount][columnCount];
        for (String[] row : grid) {
            Arrays.fill(row, "");
        }
        for (ExtractedCell cell : cells) {
            grid[cell.row()][cell.col()] = cell.text();
        }
        return grid;
    }
}

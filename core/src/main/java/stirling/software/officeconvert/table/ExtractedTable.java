package stirling.software.officeconvert.table;

import java.util.List;

public record ExtractedTable(
        int firstPage,
        int lastPage,
        int rowCount,
        int columnCount,
        List<Float> columnWidths,
        List<ExtractedCell> cells,
        int headerRows,
        boolean ruled) {}

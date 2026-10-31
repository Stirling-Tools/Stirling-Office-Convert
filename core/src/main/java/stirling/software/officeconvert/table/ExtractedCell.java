package stirling.software.officeconvert.table;

public record ExtractedCell(
        int row, int col, int rowSpan, int colSpan, String text, CellStyle style) {}

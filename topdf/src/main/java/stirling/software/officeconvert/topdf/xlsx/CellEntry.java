package stirling.software.officeconvert.topdf.xlsx;

record CellEntry(int row, int col, CellFormat format, CellText text) {

    boolean hasText() {
        return text != null && !text.isEmpty();
    }
}

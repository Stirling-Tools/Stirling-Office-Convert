package stirling.software.officeconvert.xlsx;

final class CellRefs {

    private CellRefs() {}

    static String column(int col) {
        StringBuilder sb = new StringBuilder(3);
        int n = col + 1;
        while (n > 0) {
            int r = (n - 1) % 26;
            sb.insert(0, (char) ('A' + r));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    static String cell(int row, int col) {
        return column(col) + (row + 1);
    }

    static String range(int firstRow, int firstCol, int lastRow, int lastCol) {
        String a = cell(firstRow, firstCol);
        return firstRow == lastRow && firstCol == lastCol ? a : a + ":" + cell(lastRow, lastCol);
    }

    static String absolute(String sheet, int firstRow, int firstCol, int lastRow, int lastCol) {
        return "'" + sheet.replace("'", "''") + "'!$" + column(firstCol) + "$" + (firstRow + 1) + ":$" + column(lastCol)
                + "$" + (lastRow + 1);
    }
}

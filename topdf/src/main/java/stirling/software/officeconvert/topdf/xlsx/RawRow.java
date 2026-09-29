package stirling.software.officeconvert.topdf.xlsx;

import java.util.List;

record RawRow(int index, double height, boolean hidden, int style, List<Cell> cells, int thickEdges) {

    record Cell(int col, int style, String type, String value, RichText inline) {}

    boolean hasHeight() {
        return !Double.isNaN(height);
    }
}

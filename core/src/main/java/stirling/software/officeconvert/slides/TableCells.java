package stirling.software.officeconvert.slides;

import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.layout.Box;
import stirling.software.officeconvert.model.Table;

final class TableCells {

    private TableCells() {}

    static List<Box> shaded(TableShape shape) {
        Table t = shape.table();
        List<Box> out = new ArrayList<>();
        float[] x = new float[t.columnWidths.size() + 1];
        x[0] = shape.x();
        for (int c = 0; c < t.columnWidths.size(); c++) {
            x[c + 1] = x[c] + t.columnWidths.get(c);
        }
        float top = shape.y();
        for (Table.Row row : t.rows) {
            int col = 0;
            for (Table.Cell cell : row.cells) {
                int span = Math.max(1, Math.min(cell.gridSpan, x.length - 1 - col));
                if (col + span > x.length - 1) {
                    break;
                }
                if (cell.shading >= 0) {
                    out.add(new Box(x[col], top, x[col + span], top + row.height));
                }
                col += span;
            }
            top += row.height;
        }
        return out;
    }

    static boolean covered(Box fill, List<Box> shaded) {
        float inside = 0;
        for (Box b : shaded) {
            inside += b.overlapArea(fill);
        }
        return inside >= 0.7f * fill.area();
    }
}

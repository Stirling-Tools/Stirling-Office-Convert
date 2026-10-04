package stirling.software.officeconvert.model;

import java.util.ArrayList;
import java.util.List;

public final class Table implements Block {

    public record Border(float width, int rgb) {

        public static final Border NONE = new Border(0, 0);

        public boolean visible() {
            return width > 0;
        }
    }

    public enum VAlign {
        TOP,
        CENTER,
        BOTTOM
    }

    public static final class Cell {
        public final List<Paragraph> paragraphs = new ArrayList<>();
        public int gridSpan = 1;
        public int vMerge;
        public Border top = Border.NONE;
        public Border bottom = Border.NONE;
        public Border left = Border.NONE;
        public Border right = Border.NONE;
        public int shading = -1;
        public VAlign vAlign = VAlign.TOP;
    }

    public static final class Row {
        public final List<Cell> cells = new ArrayList<>();
        public float height;
        public boolean header;
        public boolean exactHeight;
        public boolean splits;
    }

    public static final float BORDER_REACH = 4.5f;

    public final List<Float> columnWidths = new ArrayList<>();
    public final List<Row> rows = new ArrayList<>();
    public float indent;
    public float cellMarginLeft = 2f;
    public float cellMarginRight = 2f;
    public boolean pageBreakBefore;
    public float floatX = Float.NaN;
    public float floatY = Float.NaN;
    public float floatRoom;
    public boolean rightToLeft;
    public float indentEnd;

    public boolean floating() {
        return !Float.isNaN(floatY);
    }

    public void mirror() {
        java.util.Collections.reverse(columnWidths);
        for (Row row : rows) {
            java.util.Collections.reverse(row.cells);
            for (Cell c : row.cells) {
                Border left = c.left;
                c.left = c.right;
                c.right = left;
            }
        }
        float start = indent;
        indent = indentEnd;
        indentEnd = start;
        rightToLeft = !rightToLeft;
    }
}

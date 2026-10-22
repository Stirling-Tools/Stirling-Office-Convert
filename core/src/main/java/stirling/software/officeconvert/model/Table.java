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

    public record Nested(int before, Table table) {}

    public static final class Cell {
        public final List<Paragraph> paragraphs = new ArrayList<>();
        public final List<Nested> tables = new ArrayList<>();
        public int gridSpan = 1;
        public int vMerge;
        public Border top = Border.NONE;
        public Border bottom = Border.NONE;
        public Border left = Border.NONE;
        public Border right = Border.NONE;
        public int shading = -1;
        public VAlign vAlign = VAlign.TOP;

        public void add(Block b) {
            switch (b) {
                case Paragraph p -> paragraphs.add(p);
                case Table t -> tables.add(new Nested(paragraphs.size(), t));
            }
        }

        public boolean empty() {
            return paragraphs.isEmpty() && tables.isEmpty();
        }

        public List<Block> blocks() {
            if (tables.isEmpty()) {
                return new ArrayList<>(paragraphs);
            }
            List<Block> out = new ArrayList<>(paragraphs.size() + tables.size());
            int t = 0;
            for (int i = 0; i <= paragraphs.size(); i++) {
                while (t < tables.size() && tables.get(t).before() <= i) {
                    out.add(tables.get(t++).table());
                }
                if (i < paragraphs.size()) {
                    out.add(paragraphs.get(i));
                }
            }
            while (t < tables.size()) {
                out.add(tables.get(t++).table());
            }
            return out;
        }

        public List<Paragraph> flatParagraphs() {
            if (tables.isEmpty()) {
                return paragraphs;
            }
            List<Paragraph> out = new ArrayList<>();
            for (Block b : blocks()) {
                switch (b) {
                    case Paragraph p -> out.add(p);
                    case Table t -> out.addAll(t.flatParagraphs());
                }
            }
            return out;
        }
    }

    public static final class Row {
        public final List<Cell> cells = new ArrayList<>();
        public float height;
        public boolean header;
        public boolean exactHeight;
        public boolean splits;
    }

    public static final float BORDER_REACH = 4.5f;

    public static final int MAX_NESTING = 8;

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

    public int nesting() {
        int deepest = 0;
        for (Row row : rows) {
            for (Cell c : row.cells) {
                for (Nested n : c.tables) {
                    deepest = Math.max(deepest, n.table().nesting());
                }
            }
        }
        return deepest + 1;
    }

    public List<Paragraph> flatParagraphs() {
        List<Paragraph> out = new ArrayList<>();
        for (Row row : rows) {
            for (Cell c : row.cells) {
                if (c.vMerge != 2) {
                    out.addAll(c.flatParagraphs());
                }
            }
        }
        return out;
    }

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

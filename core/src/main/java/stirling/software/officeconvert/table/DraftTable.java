package stirling.software.officeconvert.table;

import java.util.List;

import stirling.software.officeconvert.table.PageContent.PdfWord;

record DraftTable(
        int page,
        int rows,
        int cols,
        float[] colEdges,
        boolean ruled,
        List<Cell> cells,
        int headerHint) {

    float left() {
        return colEdges[0];
    }

    float right() {
        return colEdges[cols];
    }

    float top() {
        float top = Float.MAX_VALUE;
        for (Cell c : cells) {
            top = Math.min(top, c.box().top());
        }
        return top;
    }

    float bottom() {
        float bottom = -Float.MAX_VALUE;
        for (Cell c : cells) {
            bottom = Math.max(bottom, c.box().bottom());
        }
        return bottom;
    }

    record Cell(
            int row,
            int col,
            int rowSpan,
            int colSpan,
            Box box,
            float wrapWidth,
            Borders borders,
            List<PdfWord> words) {}

    record Borders(boolean top, boolean bottom, boolean left, boolean right) {

        static final Borders NONE = new Borders(false, false, false, false);
    }
}

package stirling.software.officeconvert.table;

import java.util.List;

import stirling.software.officeconvert.table.PageContent.PdfWord;

record CellLine(TextLine line, List<List<PdfWord>> cells) {

    List<PdfWord> cell(int col) {
        return cells.get(col);
    }

    boolean has(int col) {
        return !cells.get(col).isEmpty();
    }

    int columns() {
        return cells.size();
    }

    int filledCount() {
        int n = 0;
        for (List<PdfWord> cell : cells) {
            if (!cell.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    float baseline() {
        return line.baseline();
    }
}

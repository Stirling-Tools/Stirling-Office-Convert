package stirling.software.officeconvert.table;

import java.util.List;

import stirling.software.officeconvert.table.PageContent.PdfWord;

record ChunkedLine(TextLine line, List<List<PdfWord>> chunks) {

    private static final float CELL_GAP_EM = 0.65f;

    private static final float CELL_GAP_SPACES = 2.2f;

    static ChunkedLine of(TextLine line) {
        return new ChunkedLine(line, line.chunks(cellGap(line)));
    }

    static float cellGap(TextLine line) {
        float space = 0;
        for (PdfWord w : line.words()) {
            space = Math.max(space, w.spaceWidth());
        }
        return Math.max(CELL_GAP_SPACES * space, CELL_GAP_EM * line.fontSize());
    }

    boolean tabular() {
        return chunks.size() >= 2;
    }
}

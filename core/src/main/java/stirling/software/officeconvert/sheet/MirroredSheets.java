package stirling.software.officeconvert.sheet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.layout.LogicalOrder;

public final class MirroredSheets implements WorkbookSink {

    private static final int MAX_CELLS = 200_000;

    private static final float DEFAULT_WIDTH = 48f;

    private final WorkbookSink sink;
    private final boolean rightToLeft;
    private final List<Row> rows = new ArrayList<>();
    private boolean buffering;
    private int cells;
    private int lastCol;
    private long rtl;
    private long ltr;

    public MirroredSheets(WorkbookSink sink, boolean rightToLeft) {
        this.sink = sink;
        this.rightToLeft = rightToLeft;
    }

    @Override
    public void startSheet(SheetSetup setup) throws IOException {
        sink.startSheet(setup);
        rows.clear();
        buffering = rightToLeft;
        cells = 0;
        lastCol = -1;
        rtl = 0;
        ltr = 0;
    }

    @Override
    public void row(Row row) throws IOException {
        if (!buffering) {
            sink.row(row);
            return;
        }
        rows.add(row);
        for (Row.Cell c : row.cells()) {
            lastCol = Math.max(lastCol, c.col() + c.colSpan() - 1);
            if (c.value().isText()) {
                count(c.value().text());
            }
        }
        cells += row.cells().size();
        if (cells > MAX_CELLS) {
            flush(0);
            buffering = false;
        }
    }

    private void count(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            String s = Character.toString(cp);
            if (LogicalOrder.isRtl(s)) {
                rtl++;
            } else if (Character.isLetter(cp)) {
                ltr++;
            }
        }
    }

    private void flush(int mirrorCols) throws IOException {
        for (Row row : rows) {
            if (mirrorCols == 0 || row.cells().size() == 1 && row.cells().getFirst().col() == 0
                    && row.cells().getFirst().colSpan() == 1) {
                sink.row(row);
                continue;
            }
            List<Row.Cell> out = new ArrayList<>(row.cells().size());
            for (Row.Cell c : row.cells().reversed()) {
                out.add(new Row.Cell(mirrorCols - c.col() - c.colSpan(), c.value(), c.style(), c.rowSpan(), c.colSpan()));
            }
            sink.row(new Row(row.index(), row.height(), out));
        }
        rows.clear();
    }

    @Override
    public void endSheet(SheetEnd end) throws IOException {
        if (!buffering || rtl <= ltr || lastCol < 0) {
            flush(0);
            sink.endSheet(end);
            return;
        }
        int cols = Math.max(lastCol + 1, end.columnWidths().size());
        flush(cols);
        List<Float> widths = new ArrayList<>(end.columnWidths());
        while (!widths.isEmpty() && widths.size() < cols) {
            widths.add(DEFAULT_WIDTH);
        }
        List<NamedRange> names = new ArrayList<>();
        for (NamedRange n : end.names()) {
            names.add(new NamedRange(n.name(), n.firstRow(), cols - 1 - n.lastCol(), n.lastRow(), cols - 1 - n.firstCol()));
        }
        sink.endSheet(new SheetEnd(widths.reversed(), end.frozenRows(), names, true));
    }

    @Override
    public void finish(String title, String author) throws IOException {
        sink.finish(title, author);
    }

    @Override
    public void close() throws IOException {
        sink.close();
    }
}

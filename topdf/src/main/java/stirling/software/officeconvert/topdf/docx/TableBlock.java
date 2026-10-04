package stirling.software.officeconvert.topdf.docx;

import java.util.ArrayList;
import java.util.List;

final class TableBlock implements Block {

    static final class Row {
        final RowProps rp;
        final List<Cell> cells = new ArrayList<>();
        TableProps exceptions;

        Row(RowProps rp) {
            this.rp = rp;
        }
    }

    static final class Cell {
        final CellProps cp;
        final List<Block> blocks;
        int col;
        int span;

        Cell(CellProps cp, List<Block> blocks) {
            this.cp = cp;
            this.blocks = blocks;
        }
    }

    final TableProps tp;

    final float[] grid;

    final List<Row> rows = new ArrayList<>();

    TableBlock(TableProps tp, float[] grid) {
        this.tp = tp;
        this.grid = grid;
    }
}

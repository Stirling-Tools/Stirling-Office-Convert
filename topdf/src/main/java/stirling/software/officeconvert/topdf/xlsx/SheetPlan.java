package stirling.software.officeconvert.topdf.xlsx;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.util.CellRangeAddress;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTBreak;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTPageBreak;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorksheet;

import stirling.software.officeconvert.topdf.RenderJob;

final class SheetPlan {

    final String name;

    final Grid grid;

    final PageSetup setup;

    final Drawings drawings;

    final List<Paginator.Page> pages = new ArrayList<>();

    final HeaderPictures pictures;

    final double scale;

    SheetPlan(Book book, WorkbookModel.SheetRef ref, RenderJob job, int budget) throws IOException {
        this.name = ref.name();
        WorksheetReader reader = new WorksheetReader(() -> job.zip().open(ref.part()));
        this.grid = new Grid(book, reader, ref.name(), ref.part(), job);
        CTWorksheet ws = reader.skeleton();
        this.setup = PageSetup.safe(ws);
        this.drawings = Drawings.read(book, ref.part(), ref.name(), grid);
        String hfDrawing = null;
        try {
            hfDrawing = ws.isSetLegacyDrawingHF() ? ws.getLegacyDrawingHF().getId() : null;
        } catch (RuntimeException e) {
            hfDrawing = null;
        }
        this.pictures = HeaderPictures.read(job, ref.part(), hfDrawing);
        List<CellRangeAddress> ranges = new ArrayList<>();
        String area = book.workbook().printArea(ref.index());
        if (area != null) {
            ranges.addAll(PrintRanges.parse(area, Math.max(0, grid.lastRow()), Math.max(0, grid.lastCol())));
        }
        if (ranges.isEmpty() && grid.lastRow() >= 0 && grid.lastCol() >= 0) {
            ranges.add(new CellRangeAddress(0, grid.lastRow(), 0, grid.lastCol()));
        }
        CellRangeAddress titleRows = book.workbook().titleRows(ref.index());
        CellRangeAddress titleCols = book.workbook().titleCols(ref.index());
        if (titleRows != null && (titleRows.getFirstRow() < 0 || titleRows.getLastRow() >= Grid.MAX_ROWS)) {
            titleRows = null;
        }
        if (titleCols != null && (titleCols.getFirstColumn() < 0 || titleCols.getLastColumn() >= Columns.MAX)) {
            titleCols = null;
        }
        Paginator p = new Paginator(grid, setup, titleRows, titleCols, rowBreaks(ws), colBreaks(ws));
        if (setup.headings()) {
            p.headings(new Headings(grid, grid.lastRow()));
        }
        Paginator.Result result = p.paginate(ranges, this::content, budget);
        this.scale = result.scale();
        pages.addAll(result.pages());
        if (result.cut()) {
            job.truncate();
        }
    }

    private static int[] rowBreaks(CTWorksheet ws) {
        try {
            return breaks(ws.isSetRowBreaks() ? ws.getRowBreaks() : null);
        } catch (RuntimeException e) {
            return new int[0];
        }
    }

    private static int[] colBreaks(CTWorksheet ws) {
        try {
            return breaks(ws.isSetColBreaks() ? ws.getColBreaks() : null);
        } catch (RuntimeException e) {
            return new int[0];
        }
    }

    private static int[] breaks(CTPageBreak pb) {
        if (pb == null) {
            return new int[0];
        }
        CTBreak[] b = pb.getBrkArray();
        int[] out = new int[b.length];
        for (int i = 0; i < b.length; i++) {
            out[i] = (int) Math.min(Integer.MAX_VALUE, b[i].getId());
        }
        return out;
    }

    private void content(CellRangeAddress range, Paginator.Sink sink) throws InterruptedIOException {
        for (int n = 0; n < drawings.items.size(); n++) {
            Drawings.Item i = drawings.items.get(n);
            if (i.from() == null) {
                sink.add(range.getFirstRow(), range.getLastRow(), range.getFirstColumn(), range.getLastColumn());
                continue;
            }
            int[] end = drawings.ends.get(n);
            sink.add(i.from().row(), end[0], i.from().col(), end[1]);
        }
        for (Grid.RowInfo r : grid.rows(range.getFirstRow(), range.getLastRow()).values()) {
            if (r.hidden) {
                continue;
            }
            if (r.spillTo >= range.getFirstColumn() && r.spillFrom <= range.getLastColumn()) {
                sink.add(r.index, r.index, r.spillFrom, r.spillTo);
            }
            for (CellEntry e : r.cells(range.getFirstColumn(), range.getLastColumn())) {
                sink.add(r.index, r.index, e.col(), e.col());
            }
        }
        List<CellRangeAddress> merges = new ArrayList<>();
        grid.mergesIn(range.getFirstRow(), range.getLastRow(), range.getFirstColumn(), range.getLastColumn(),
                merges::add);
        for (CellRangeAddress m : merges) {
            if (grid.cell(m.getFirstRow(), m.getFirstColumn()) != null) {
                sink.add(m.getFirstRow(), m.getLastRow(), m.getFirstColumn(), m.getLastColumn());
            }
        }
    }
}

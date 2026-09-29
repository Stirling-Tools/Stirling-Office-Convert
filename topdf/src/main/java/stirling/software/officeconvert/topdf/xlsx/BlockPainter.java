package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.util.CellRangeAddress;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.pdf.Fill;
import stirling.software.officeconvert.topdf.pdf.Gradient;
import stirling.software.officeconvert.topdf.pdf.PdfCanvas;
import stirling.software.officeconvert.topdf.pdf.Stroke;

final class BlockPainter {

    static final Color GRID = Color.BLACK;

    private final Grid grid;

    private final PdfCanvas canvas;

    private final RenderJob job;

    private final boolean gridlines;

    private final double device;

    private int failures;

    BlockPainter(Grid grid, PdfCanvas canvas, RenderJob job, boolean gridlines, double device) {
        this.grid = grid;
        this.canvas = canvas;
        this.job = job;
        this.gridlines = gridlines;
        this.device = device;
    }

    void paint(Band rows, Band cols) throws IOException {
        double w = cols.length();
        double h = rows.length();
        if (w <= 0 || h <= 0) {
            return;
        }
        canvas.save();
        try {
            canvas.clipRect(-3, -3, (float) w + 6, (float) h + 6);
            fills(rows, cols);
            bars(rows, cols);
            if (gridlines) {
                gridlines(rows, cols);
            }
            texts(rows, cols, new CellPainter.Box(0, 0, w, h));
            new BorderPainter(grid, canvas, device).paint(rows, cols);
        } finally {
            canvas.restore();
        }
    }

    private void fills(Band rows, Band cols) throws IOException {
        Set<CellRangeAddress> doneMerges = new HashSet<>();
        for (int r = rows.first; r <= rows.last; r++) {
            if ((r & 63) == 0) {
                job.checkpoint();
            }
            double y0 = rows.start(r);
            double y1 = rows.end(r);
            if (y1 <= y0) {
                continue;
            }
            Color run = null;
            double runX0 = 0;
            double runX1 = 0;
            for (int c = cols.first; c <= cols.last; c++) {
                double x0 = cols.start(c);
                double x1 = cols.end(c);
                if (x1 <= x0) {
                    continue;
                }
                Color fill = null;
                CellRangeAddress m = grid.merges().isEmpty() ? null : grid.mergeCovering(r, c);
                if (m != null) {
                    if (doneMerges.add(m)) {
                        CellFormat mf = grid.formatAt(m.getFirstRow(), m.getFirstColumn());
                        if (mf != null && mf.fill() != null) {
                            rect(cols.start(m.getFirstColumn()), rows.start(m.getFirstRow()),
                                    cols.end(m.getLastColumn()), rows.end(m.getLastRow()), mf.fill());
                        }
                    }
                } else {
                    CellFormat f = grid.formatAt(r, c);
                    fill = f == null ? null : f.fill();
                }
                if (run != null && (fill == null || !fill.equals(run) || Math.abs(x0 - runX1) > 0.01)) {
                    rect(runX0, y0, runX1, y1, run);
                    run = null;
                }
                if (fill != null) {
                    if (run == null) {
                        run = fill;
                        runX0 = x0;
                    }
                    runX1 = x1;
                }
            }
            if (run != null) {
                rect(runX0, y0, runX1, y1, run);
            }
        }
    }

    // Data bars grow from the cell's left edge, a gradient fading to white as Excel draws them
    private void bars(Band rows, Band cols) throws IOException {
        for (Grid.RowInfo info : grid.rows(rows.first, rows.last).values()) {
            if (info.hidden) {
                continue;
            }
            for (CellEntry e : info.cells.subMap(cols.first, true, cols.last, true).values()) {
                Overlays.Bar bar = grid.dataBar(info.index, e.col());
                if (bar == null) {
                    continue;
                }
                double x0 = cols.start(e.col()) + 1.5;
                double w = Math.max(0, (cols.end(e.col()) - cols.start(e.col()) - 3) * bar.fraction());
                double y0 = rows.start(info.index) + 1.5;
                double h = rows.end(info.index) - rows.start(info.index) - 3;
                if (w <= 0 || h <= 0) {
                    continue;
                }
                Color c = bar.color();
                Fill fill = Fill.of(Gradient.linear((float) x0, 0, (float) (x0 + w), 0, List.of(
                        new Gradient.Stop(0, c), new Gradient.Stop(1, new Color(255, 255, 255)))));
                canvas.rect((float) x0, (float) y0, (float) w, (float) h, fill, Stroke.solid(0.5f, c));
            }
        }
    }

    private void rect(double x0, double y0, double x1, double y1, Color c) throws IOException {
        canvas.rect((float) x0, (float) y0, (float) (x1 - x0), (float) (y1 - y0), Fill.solid(c), null);
    }

    private void gridlines(Band rows, Band cols) throws IOException {
        Stroke s = Stroke.solid(0.14f, GRID);
        double w = cols.length();
        double h = rows.length();
        for (int r = rows.first; r <= rows.last + 1; r++) {
            double y = rows.start(r);
            if (r > rows.first && r <= rows.last && rows.size(r) <= 0) {
                continue;
            }
            segments(true, y, 0, w, r, cols, s);
        }
        for (int c = cols.first; c <= cols.last + 1; c++) {
            double x = cols.start(c);
            if (c > cols.first && c <= cols.last && cols.size(c) <= 0) {
                continue;
            }
            segments(false, x, 0, h, c, rows, s);
        }
    }

    private void segments(boolean horizontal, double at, double from, double to, int index, Band other, Stroke s)
            throws IOException {
        if (grid.merges().isEmpty()) {
            line(horizontal, at, from, to, s);
            return;
        }
        double start = from;
        boolean open = true;
        for (int k = other.first; k <= other.last; k++) {
            boolean inside = horizontal ? interiorH(index, k) : interiorV(k, index);
            double a = other.start(k);
            double b = other.end(k);
            if (inside) {
                if (open && a > start) {
                    line(horizontal, at, start, a, s);
                }
                open = false;
                start = b;
            } else if (!open) {
                open = true;
                start = a;
            }
        }
        if (open && to > start) {
            line(horizontal, at, start, to, s);
        }
    }

    private boolean interiorH(int row, int col) {
        CellRangeAddress m = grid.mergeCovering(row, col);
        return m != null && row > m.getFirstRow() && row <= m.getLastRow();
    }

    private boolean interiorV(int row, int col) {
        CellRangeAddress m = grid.mergeCovering(row, col);
        return m != null && col > m.getFirstColumn() && col <= m.getLastColumn();
    }

    private void line(boolean horizontal, double at, double from, double to, Stroke s) throws IOException {
        Hairline.draw(canvas, horizontal, at, from, to, s.color(), device);
    }

    private void texts(Band rows, Band cols, CellPainter.Box clip) throws IOException {
        CellPainter painter = new CellPainter(grid, canvas);
        Set<CellRangeAddress> done = new HashSet<>();
        List<CellRangeAddress> inBlock = new ArrayList<>();
        grid.mergesIn(rows.first, rows.last, cols.first, cols.last, inBlock::add);
        inBlock.sort(java.util.Comparator.comparingInt(CellRangeAddress::getFirstRow)
                .thenComparingInt(CellRangeAddress::getFirstColumn));
        for (CellRangeAddress m : inBlock) {
            CellEntry e = grid.cell(m.getFirstRow(), m.getFirstColumn());
            done.add(m);
            if (e == null || !e.hasText()) {
                continue;
            }
            CellPainter.Box box = new CellPainter.Box(cols.start(m.getFirstColumn()), rows.start(m.getFirstRow()),
                    cols.end(m.getLastColumn()), rows.end(m.getLastRow()));
            double lift = lift(m.getLastRow(), m.getFirstColumn(), e.format());
            safely(painter, e, box, 0, 0, grid.rowDescent(m.getLastRow()) + lift, clip);
        }
        for (Map.Entry<Integer, Grid.RowInfo> re : grid.rows(rows.first, rows.last).entrySet()) {
            int r = re.getKey();
            if ((r & 63) == 0) {
                job.checkpoint();
            }
            Grid.RowInfo info = re.getValue();
            if (info.hidden || grid.rowHeight(r) <= 0) {
                continue;
            }
            double y0 = rows.start(r);
            double y1 = rows.end(r);
            double descent = grid.rowDescent(r);
            spillIn(painter, info, cols, y0, y1, descent, clip);
            for (CellEntry e : info.cells.subMap(cols.first, true, cols.last, true).values()) {
                if (!e.hasText() || cols.size(e.col()) <= 0) {
                    continue;
                }
                if (!grid.merges().isEmpty() && grid.mergeCovering(r, e.col()) != null) {
                    continue;
                }
                double x0 = cols.start(e.col());
                double x1 = cols.end(e.col());
                CellFormat f = e.format();
                if (f.hAlign() == CellFormat.HAlign.CENTER_CONTINUOUS) {
                    x1 = cols.end(continuousEnd(r, e.col()));
                }
                double left = 0;
                double right = 0;
                if (spills(e)) {
                    CellFormat.HAlign h = CellLayout.horizontal(f, e.text());
                    double extra = need(e) - (x1 - x0);
                    if (extra > 0) {
                        if (h == CellFormat.HAlign.RIGHT) {
                            left = spill(r, e.col(), -1, cols, extra);
                        } else if (h == CellFormat.HAlign.CENTER || h == CellFormat.HAlign.CENTER_CONTINUOUS) {
                            left = spill(r, e.col(), -1, cols, extra / 2);
                            right = spill(r, e.col(), 1, cols, extra / 2);
                        } else {
                            right = spill(r, e.col(), 1, cols, extra);
                        }
                    }
                }
                safely(painter, e, new CellPainter.Box(x0, y0, x1, y1), left, right,
                        descent + lift(r, e.col(), f), clip);
            }
        }
    }

    // Excel raises bottom-aligned text above a medium or thick bottom edge, whichever cell it belongs to
    private double lift(int row, int col, CellFormat f) {
        if (f.vAlign() != CellFormat.VAlign.BOTTOM) {
            return 0;
        }
        CellFormat below = row + 1 < Grid.MAX_ROWS ? grid.formatAt(row + 1, col) : null;
        BorderLine edge = BorderLine.stronger(f.bottom(), below == null ? null : below.top());
        return edge.width() > BorderLine.THIN_WIDTH ? BORDER_LIFT : 0;
    }

    static final double BORDER_LIFT = 0.96;

    private void spillIn(CellPainter painter, Grid.RowInfo info, Band cols, double y0, double y1, double descent,
            CellPainter.Box clip) throws IOException {
        CellEntry before = nearestText(info, cols.first, -1);
        if (before != null && spills(before)) {
            CellFormat.HAlign h = CellLayout.horizontal(before.format(), before.text());
            double x1 = cols.end(before.col());
            double extra = need(before) - (x1 - cols.start(before.col()));
            if (h == CellFormat.HAlign.CENTER) {
                extra /= 2;
            }
            if (h == CellFormat.HAlign.CENTER_CONTINUOUS) {
                int last = continuousEnd(info.index, before.col());
                if (last >= cols.first) {
                    safely(painter, before, new CellPainter.Box(cols.start(before.col()), y0, cols.end(last), y1), 0, 0,
                            descent + lift(info.index, before.col(), before.format()), clip);
                }
            } else if (h != CellFormat.HAlign.RIGHT && extra > 0) {
                double right = spill(info.index, before.col(), 1, cols, extra);
                if (x1 + right > 0) {
                    safely(painter, before, new CellPainter.Box(cols.start(before.col()), y0, x1, y1), 0, right,
                            descent + lift(info.index, before.col(), before.format()), clip);
                }
            }
        }
        CellEntry after = nearestText(info, cols.last, 1);
        if (after != null && spills(after)) {
            CellFormat.HAlign h = CellLayout.horizontal(after.format(), after.text());
            double x0 = cols.start(after.col());
            double extra = need(after) - (cols.end(after.col()) - x0);
            if (h == CellFormat.HAlign.CENTER) {
                extra /= 2;
            }
            if ((h == CellFormat.HAlign.RIGHT || h == CellFormat.HAlign.CENTER) && extra > 0) {
                double left = spill(info.index, after.col(), -1, cols, extra);
                if (x0 - left < cols.length()) {
                    safely(painter, after, new CellPainter.Box(x0, y0, cols.end(after.col()), y1), left, 0,
                            descent + lift(info.index, after.col(), after.format()), clip);
                }
            }
        }
    }

    // Excel centres across a selection's whole run, even where it goes on past the page's last column
    private int continuousEnd(int row, int col) {
        int c = col + 1;
        while (c < Columns.MAX && c - col <= MAX_SPILL_COLUMNS && !grid.hasValue(row, c)) {
            CellFormat nf = grid.formatAt(row, c);
            if (nf == null || nf.hAlign() != CellFormat.HAlign.CENTER_CONTINUOUS) {
                break;
            }
            c++;
        }
        return c - 1;
    }

    private CellEntry nearestText(Grid.RowInfo info, int from, int dir) {
        int[] texts = info.textColumns();
        int i = Arrays.binarySearch(texts, from);
        int at = dir < 0 ? (i >= 0 ? i - 1 : -i - 2) : (i >= 0 ? i + 1 : -i - 1);
        if (at < 0 || at >= texts.length || Math.abs(texts[at] - from) > MAX_SPILL_COLUMNS) {
            return null;
        }
        CellEntry c = info.cells.get(texts[at]);
        boolean merged = !grid.merges().isEmpty() && grid.mergeCovering(info.index, c.col()) != null;
        return merged || grid.columnWidth(c.col()) <= 0 ? null : c;
    }

    static final int MAX_SPILL_COLUMNS = 256;

    private double need(CellEntry e) {
        return grid.textWidth(e) + 2 * CellLayout.pad(grid.book().typesetter(), e.text(), e.format());
    }

    private void safely(CellPainter painter, CellEntry e, CellPainter.Box box, double left, double right,
            double descent, CellPainter.Box clip) throws IOException {
        canvas.save();
        try {
            painter.paint(e, box, left, right, descent, clip);
        } catch (RuntimeException ex) {
            failures++;
        } finally {
            canvas.restore();
        }
    }

    int failures() {
        return failures;
    }

    private static boolean spills(CellEntry e) {
        CellFormat f = e.format();
        return e.text().kind() == CellText.Kind.TEXT && !f.wraps() && !f.shrink() && f.rotation() == 0
                && f.hAlign() != CellFormat.HAlign.FILL;
    }

    private double spill(int row, int col, int dir, Band cols, double need) {
        double got = 0;
        int limit = dir > 0 ? cols.last : cols.first;
        for (int c = col + dir; (dir > 0 ? c <= limit : c >= limit) && got < need; c += dir) {
            if (grid.hasValue(row, c) || (!grid.merges().isEmpty() && grid.mergeCovering(row, c) != null)) {
                break;
            }
            got += cols.size(c);
        }
        return got;
    }
}

package stirling.software.officeconvert.topdf.xlsx;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.function.IntToDoubleFunction;

import org.apache.poi.ss.util.CellRangeAddress;

final class Paginator {

    record Span(int first, int last) {}

    record Page(List<Band> rows, List<Band> cols, Band bodyRows, Band bodyCols) {}

    record Result(double scale, List<Page> pages, boolean cut) {}

    @FunctionalInterface
    interface Content {
        void each(CellRangeAddress range, Sink sink) throws InterruptedIOException;
    }

    @FunctionalInterface
    interface Sink {
        void add(int r0, int r1, int c0, int c1) throws InterruptedIOException;
    }

    private final Grid grid;

    private final PageSetup setup;

    private final CellRangeAddress titleRows;

    private final CellRangeAddress titleCols;

    private final NavigableSet<Integer> rowBreaks;

    private final NavigableSet<Integer> colBreaks;

    private Headings headings;

    Paginator(Grid grid, PageSetup setup, CellRangeAddress titleRows, CellRangeAddress titleCols, int[] rowBreaks,
            int[] colBreaks) {
        this.grid = grid;
        this.setup = setup;
        this.titleRows = titleRows;
        this.titleCols = titleCols;
        this.rowBreaks = set(rowBreaks);
        this.colBreaks = set(colBreaks);
    }

    private static NavigableSet<Integer> set(int[] ids) {
        TreeSet<Integer> s = new TreeSet<>();
        if (ids != null) {
            for (int id : ids) {
                if (id >= 0) {
                    s.add(id);
                }
            }
        }
        return s;
    }

    Paginator headings(Headings h) {
        this.headings = h;
        return this;
    }

    static double quantize(double size, double device) {
        if (!(size > 0) || device == 1) {
            return size;
        }
        return Math.round(size / PrintMetrics.PX * device) * PrintMetrics.PX / device;
    }

    private double device(double scale) {
        return scale * (setup.resized() ? PageSetup.LETTER_ON_A4 : 1);
    }

    private IntToDoubleFunction colSize(double scale) {
        double e = device(scale);
        return c -> quantize(grid.columnWidth(c), e);
    }

    private IntToDoubleFunction rowSize(double scale) {
        double e = device(scale);
        return r -> quantize(grid.rowHeight(r), e);
    }

    private double headWidth() {
        return headings == null ? 0 : headings.width(1);
    }

    private double headHeight() {
        return headings == null ? 0 : headings.height(1);
    }

    Result paginate(List<CellRangeAddress> ranges, Content content, int limit) throws InterruptedIOException {
        double scale = setup.fitToPage() ? fitScale(ranges) : setup.scale() / 100.0;
        List<Page> pages = new ArrayList<>();
        boolean cut = false;
        for (CellRangeAddress r : ranges) {
            if (pages.size() >= limit) {
                cut = true;
                break;
            }
            cut |= pages(r, scale, content, limit - pages.size(), pages);
        }
        return new Result(scale, pages, cut);
    }

    private double fitScale(List<CellRangeAddress> ranges) throws InterruptedIOException {
        int w = setup.fitWidth();
        int h = setup.fitHeight();
        if (w <= 0 && h <= 0) {
            return 1;
        }
        double width = fitWidth(setup.printableWidth());
        for (int pct = 100; pct >= 10; pct--) {
            double s = pct / 100.0;
            boolean ok = true;
            for (CellRangeAddress r : ranges) {
                if (w > 0 && spans(r.getFirstColumn(), r.getLastColumn(), grid::columnWidth, first(titleCols, false),
                        last(titleCols, false), (width / s - headWidth()), null, w + 1).size() > w) {
                    ok = false;
                    break;
                }
                if (h > 0 && spans(r.getFirstRow(), r.getLastRow(), grid::rowHeight, first(titleRows, true),
                        last(titleRows, true), (rowRoom(setup.printableHeight(), s) - headHeight()), null, h + 1).size() > h) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return s;
            }
        }
        return 0.1;
    }

    // Excel fits the columns to whole device pixels, keeping the grid's 4 px inset and closing line clear of the edge
    static double rowRoom(double printable, double scale) {
        return fitWidth(printable) / scale;
    }

    static double fitWidth(double printable) {
        return (Math.floor(printable / PrintMetrics.PX + 1e-6) - FIT_EDGE_PX) * PrintMetrics.PX;
    }

    static final int FIT_EDGE_PX = 5;

    // Only pages with something on them are printed, so pick them from the content instead of walking every page
    private boolean pages(CellRangeAddress range, double scale, Content content, int limit, List<Page> out)
            throws InterruptedIOException {
        List<Span> colSpans = spans(range.getFirstColumn(), range.getLastColumn(), grid::columnWidth,
                first(titleCols, false), last(titleCols, false), (setup.printableWidth() / scale - headWidth()),
                manualBreaks(setup.fitWidth()) ? colBreaks : null, Integer.MAX_VALUE);
        List<Span> rowSpans = spans(range.getFirstRow(), range.getLastRow(), grid::rowHeight, first(titleRows, true),
                last(titleRows, true), (rowRoom(setup.printableHeight(), scale) - headHeight()),
                manualBreaks(setup.fitHeight()) ? rowBreaks : null, Integer.MAX_VALUE);
        boolean rowMajor = setup.overThenDown();
        int[] rowStarts = starts(rowSpans);
        int[] colStarts = starts(colSpans);
        long minors = rowMajor ? colSpans.size() : rowSpans.size();
        TreeSet<Long> picked = new TreeSet<>();
        boolean[] cut = new boolean[1];
        long[] previous = {-1};
        content.each(range, (r0, r1, c0, c1) -> {
            int a0 = Math.max(r0, range.getFirstRow());
            int a1 = Math.min(r1, range.getLastRow());
            int b0 = Math.max(c0, range.getFirstColumn());
            int b1 = Math.min(c1, range.getLastColumn());
            if (a0 > a1 || b0 > b1) {
                return;
            }
            int ri0 = index(rowStarts, a0);
            int ri1 = index(rowStarts, a1);
            int ci0 = index(colStarts, b0);
            int ci1 = index(colStarts, b1);
            long maj0 = rowMajor ? ri0 : ci0;
            long maj1 = rowMajor ? ri1 : ci1;
            long min0 = rowMajor ? ci0 : ri0;
            long min1 = rowMajor ? ci1 : ri1;
            if (maj0 == maj1 && min0 == min1 && maj0 * minors + min0 == previous[0]) {
                return;
            }
            previous[0] = maj0 == maj1 && min0 == min1 ? maj0 * minors + min0 : -1;
            for (long maj = maj0; maj <= maj1; maj++) {
                checkpoint(maj);
                for (long min = min0; min <= min1; min++) {
                    long key = maj * minors + min;
                    if (picked.size() >= limit && key > picked.last()) {
                        cut[0] = true;
                        if (min == min0) {
                            return;
                        }
                        break;
                    }
                    if (picked.add(key) && picked.size() > limit) {
                        picked.pollLast();
                        cut[0] = true;
                    }
                }
            }
        });
        Map<Integer, Band> rowBands = new HashMap<>();
        Map<Integer, Band> colBands = new HashMap<>();
        Band[] titles = new Band[2];
        for (long key : picked) {
            int maj = (int) (key / minors);
            int min = (int) (key % minors);
            int ri = rowMajor ? maj : min;
            int ci = rowMajor ? min : maj;
            out.add(page(rowSpans.get(ri), colSpans.get(ci), scale, rowBands, colBands, titles));
            checkpoint(out.size());
        }
        return cut[0];
    }

    private boolean manualBreaks(int fitPages) {
        return !setup.fitToPage() || fitPages == 0;
    }

    private static int[] starts(List<Span> spans) {
        int[] s = new int[spans.size()];
        for (int i = 0; i < s.length; i++) {
            s[i] = spans.get(i).first;
        }
        return s;
    }

    private static int index(int[] starts, int at) {
        int i = Arrays.binarySearch(starts, at);
        return i >= 0 ? i : Math.max(0, -i - 2);
    }

    private static void checkpoint(long i) throws InterruptedIOException {
        if ((i & 0xFFF) == 0 && Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Conversion interrupted");
        }
    }

    private static int first(CellRangeAddress r, boolean rows) {
        return r == null ? -1 : rows ? r.getFirstRow() : r.getFirstColumn();
    }

    private static int last(CellRangeAddress r, boolean rows) {
        return r == null ? -1 : rows ? r.getLastRow() : r.getLastColumn();
    }

    private Page page(Span rs, Span cs, double scale, Map<Integer, Band> rowBands, Map<Integer, Band> colBands,
            Band[] titles) {
        List<Band> rows = new ArrayList<>();
        List<Band> cols = new ArrayList<>();
        IntToDoubleFunction rowSize = rowSize(scale);
        IntToDoubleFunction colSize = colSize(scale);
        if (titleRows != null && rs.first > titleRows.getLastRow()) {
            if (titles[0] == null) {
                titles[0] = new Band(titleRows.getFirstRow(), titleRows.getLastRow(), rowSize);
            }
            rows.add(titles[0]);
        }
        Band bodyRows = rowBands.computeIfAbsent(rs.first, k -> new Band(rs.first, rs.last, rowSize));
        rows.add(bodyRows);
        if (titleCols != null && cs.first > titleCols.getLastColumn()) {
            if (titles[1] == null) {
                titles[1] = new Band(titleCols.getFirstColumn(), titleCols.getLastColumn(), colSize);
            }
            cols.add(titles[1]);
        }
        Band bodyCols = colBands.computeIfAbsent(cs.first, k -> new Band(cs.first, cs.last, colSize));
        cols.add(bodyCols);
        return new Page(rows, cols, bodyRows, bodyCols);
    }

    static List<Span> spans(int first, int last, IntToDoubleFunction size, int titleFirst, int titleLast,
            double avail, NavigableSet<Integer> breaks, int limit) throws InterruptedIOException {
        List<Span> out = new ArrayList<>();
        double titleSize = 0;
        for (int i = titleFirst; i <= titleLast && i >= 0; i++) {
            titleSize += size.applyAsDouble(i);
        }
        int start = first;
        double used = 0;
        double room = room(start, titleLast, titleSize, avail);
        for (int i = first; i <= last; i++) {
            checkpoint(i);
            double s = size.applyAsDouble(i);
            boolean manual = breaks != null && breaks.contains(i) && i > start;
            if (manual || (used + s > room + 1e-6 && i > start && used > 0)) {
                out.add(new Span(start, i - 1));
                if (out.size() >= limit) {
                    return out;
                }
                start = i;
                used = 0;
                room = room(start, titleLast, titleSize, avail);
            }
            used += s;
        }
        out.add(new Span(start, last));
        return out;
    }

    private static double room(int start, int titleLast, double titleSize, double avail) {
        return titleLast >= 0 && start > titleLast ? Math.max(avail * 0.1, avail - titleSize) : avail;
    }
}

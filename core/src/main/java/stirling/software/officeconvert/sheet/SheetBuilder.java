package stirling.software.officeconvert.sheet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.extract.PageData;
import stirling.software.officeconvert.layout.DocStats;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.PageFrame;
import stirling.software.officeconvert.layout.PageLayout;
import stirling.software.officeconvert.layout.RunningLine;
import stirling.software.officeconvert.sheet.PageItems.Para;
import stirling.software.officeconvert.sheet.WorkbookSink.NamedRange;
import stirling.software.officeconvert.sheet.WorkbookSink.SheetEnd;
import stirling.software.officeconvert.sheet.WorkbookSink.SheetSetup;

public final class SheetBuilder {

    public enum Split {
        PAGE,
        TABLE,
        SINGLE
    }

    public record Settings(Split split, boolean splitLargeTables, boolean typed, boolean dropHyphens) {}

    private static final int LARGE_ROWS = 5;
    private static final int LARGE_CELLS = 30;

    private static final int FREEZE_WITHIN = 6;
    private static final int FREEZE_MIN_DATA = 10;

    private static final float OVERHANG = 1.15f;

    private static final float FILLER_MIN = 36f;

    static final int MAX_ROWS = 1_048_576;

    private final WorkbookSink sink;
    private final DocStats stats;
    private final Conventions document;
    private final Settings settings;
    private final int pageCount;
    private final SheetNames names = new SheetNames();
    private Sheet open;
    private int sheets;
    private int tables;
    private List<RunningLine> omitted = List.of();
    private int pageTables;

    private record Placed(TableGrid grid, String name, int firstRow, float pageHeight, int page) {}

    private static final class Sheet {
        final List<Float> widths = new ArrayList<>();
        final List<NamedRange> ranges = new ArrayList<>();
        final float textWidth;
        int next;
        int frozen;
        Placed last;
        float lastBottom = Float.NaN;
        float lastSize;
        boolean lastWasTable;
        boolean lastFigure;

        Sheet(float textWidth) {
            this.textWidth = textWidth;
        }

        float total() {
            float t = 0;
            for (float w : widths) {
                t += w;
            }
            return t;
        }
    }

    public SheetBuilder(WorkbookSink sink, DocStats stats, Conventions document, Settings settings, int pageCount) {
        this.sink = sink;
        this.stats = stats;
        this.document = document;
        this.settings = settings;
        this.pageCount = pageCount;
    }

    public int tables() {
        return tables;
    }

    public void page(PageLayout layout) throws IOException {
        PageData page = layout.page();
        Conventions[] prior = settings.split() != Split.PAGE && open != null && open.last != null
                ? open.last.grid().conventions : null;
        PageItems.Prepared prepared =
                PageItems.of(layout, stats, document, prior, settings.typed(), settings.dropHyphens());
        omitted = prepared.taken();
        pageTables = 0;
        List<Object> items = prepared.items();
        float textWidth = textWidth(page, items);
        switch (settings.split()) {
            case PAGE -> pageSheets(page, items, textWidth);
            case TABLE -> tableSheets(page, items);
            case SINGLE -> singleSheet(page, items, textWidth);
        }
    }

    public void plainPage(PageData page, List<Line> lines) throws IOException {
        if (settings.split() == Split.TABLE) {
            return;
        }
        omitted = List.of();
        PageFrame frame = stats.frame(page.width(), page.height());
        float textWidth = Math.max(200f, frame.textRight() - frame.textLeft());
        if (settings.split() == Split.PAGE || open == null) {
            closeOpen();
            open = start(page, settings.split() == Split.PAGE ? "Page " + (page.index() + 1) : "Document", textWidth);
            open.widths.add(textWidth);
        } else if (open.next > 0) {
            open.next++;
        }
        for (Line l : lines) {
            String text = LogicalText.of(l, 0).replace('\t', ' ').strip();
            if (!text.isEmpty()) {
                sink.row(new Row(open.next++, Float.NaN, List.of(new Row.Cell(0, CellValue.text(text), CellStyle.DEFAULT))));
            }
        }
        open.last = null;
        if (settings.split() == Split.PAGE) {
            closeOpen();
        }
    }

    public void finish(String title, String author) throws IOException {
        closeOpen();
        if (sheets == 0) {
            sink.startSheet(new SheetSetup(names.unique("Sheet1"), false, true, "", ""));
            sink.endSheet(new SheetEnd(List.of(), 0, List.of()));
            sheets++;
        }
        sink.finish(title, author);
    }

    private void pageSheets(PageData page, List<Object> items, float textWidth) throws IOException {
        List<List<Object>> groups = settings.splitLargeTables() ? groups(items) : List.of(items);
        int number = page.index() + 1;
        for (int g = 0; g < groups.size(); g++) {
            List<Object> group = groups.get(g);
            String name = groups.size() == 1 ? "Page " + number : "Page " + number + " Table " + (g + 1);
            Sheet s = start(page, name, textWidth);
            for (TableGrid grid : grids(group)) {
                widen(s, grid);
            }
            if (s.widths.isEmpty()) {
                s.widths.add(s.textWidth);
            }
            for (Object item : group) {
                write(s, item, page);
            }
            List<TableGrid> grids = grids(group);
            if (grids.size() == 1 && s.ranges.size() == 1) {
                TableGrid grid = grids.getFirst();
                int first = s.ranges.getFirst().firstRow();
                if (grid.headerRows > 0 && first < FREEZE_WITHIN && grid.rows - grid.headerRows >= FREEZE_MIN_DATA) {
                    s.frozen = first + grid.headerRows;
                }
            }
            end(s);
        }
    }

    private static List<List<Object>> groups(List<Object> items) {
        List<Integer> large = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof TableGrid g && g.rows >= LARGE_ROWS && g.rows * g.cols >= LARGE_CELLS) {
                large.add(i);
            }
        }
        if (large.size() < 2) {
            return List.of(items);
        }
        List<List<Object>> out = new ArrayList<>();
        int from = 0;
        for (int k = 1; k < large.size(); k++) {
            int cut = large.get(k);
            while (cut > from && !(items.get(cut - 1) instanceof TableGrid)) {
                cut--;
            }
            out.add(items.subList(from, cut));
            from = cut;
        }
        out.add(items.subList(from, items.size()));
        return out;
    }

    private void tableSheets(PageData page, List<Object> items) throws IOException {
        List<TableGrid> grids = grids(items);
        for (int i = 0; i < grids.size(); i++) {
            TableGrid g = grids.get(i);
            if (i == 0 && open != null && carriesOn(open.last, g, page) && open.next + g.rows < MAX_ROWS) {
                append(open, g, page);
            } else {
                closeOpen();
                int number = page.index() + 1;
                open = start(page, grids.size() == 1 ? "Page " + number : "Page " + number + " Table " + (i + 1), 0);
                writeTable(open, g, page, 0);
                if (g.headerRows > 0 && g.rows > g.headerRows) {
                    open.frozen = g.headerRows;
                }
            }
            if (i < grids.size() - 1) {
                closeOpen();
            }
        }
        if (open != null && (grids.isEmpty() || open.last == null || open.last.page() != page.index())) {
            closeOpen();
        }
    }

    private void singleSheet(PageData page, List<Object> items, float textWidth) throws IOException {
        if (open == null) {
            open = start(page, "Document", textWidth);
            for (TableGrid g : grids(items)) {
                widen(open, g);
            }
            if (open.widths.isEmpty()) {
                open.widths.add(textWidth);
            }
        }
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            int size = item instanceof TableGrid g ? g.rows + g.above.size() + 2 : 2;
            if (open.next + size >= MAX_ROWS) {
                closeOpen();
                open = start(page, "Document", textWidth);
                open.widths.add(textWidth);
            }
            if (i == 0 && item instanceof TableGrid g && carriesOn(open.last, g, page)) {
                append(open, g, page);
                continue;
            }
            if (i == 0 && open.next > 0) {
                open.next++;
                open.lastBottom = Float.NaN;
            }
            boolean top = open.next == 0;
            write(open, item, page);
            if (top && item instanceof TableGrid g && g.headerRows > 0) {
                open.frozen = g.headerRows;
            }
        }
    }

    private Sheet start(PageData page, String name, float textWidth) throws IOException {
        int index = page.index();
        String header = PrintHeader.of(stats.headerFooterInfo().headers(), index, pageCount, omitted);
        String footer = PrintHeader.of(stats.headerFooterInfo().footers(), index, pageCount, omitted);
        float shortSide = Math.min(page.width(), page.height());
        sink.startSheet(new SheetSetup(names.unique(name), page.width() > page.height(),
                Math.abs(shortSide - 595f) < 12f, header, footer));
        sheets++;
        return new Sheet(textWidth);
    }

    private void end(Sheet s) throws IOException {
        sink.endSheet(new SheetEnd(s.widths, s.frozen, s.ranges));
    }

    private void closeOpen() throws IOException {
        if (open != null) {
            end(open);
            open = null;
        }
    }

    private void write(Sheet s, Object item, PageData page) throws IOException {
        if (item instanceof TableGrid g) {
            for (CellText caption : g.above) {
                writeText(s, caption.text(), caption);
            }
            if (s.next > 0) {
                s.next++;
            }
            writeTable(s, g, page, 0);
            return;
        }
        Para para = (Para) item;
        PageBlocks.Text t = para.block();
        CellText ct = para.text();
        String text = (t.prefix().isEmpty() ? "" : t.prefix() + " ") + ct.text().replace("\t", "    ");
        if (text.isBlank()) {
            return;
        }
        if (s.lastWasTable || gapBefore(s, t) && !(para.figure() && s.lastFigure)) {
            s.next += s.next > 0 ? 1 : 0;
        }
        s.lastFigure = para.figure();
        writeText(s, text, ct);
        s.lastWasTable = false;
        s.lastBottom = t.bottom();
        s.lastSize = ct.size();
        s.last = null;
    }

    private static boolean gapBefore(Sheet s, PageBlocks.Text t) {
        if (Float.isNaN(s.lastBottom)) {
            return false;
        }
        float gap = t.top() - s.lastBottom;
        return gap > 1.2f * Math.max(s.lastSize, t.para().size());
    }

    private void writeText(Sheet s, String text, CellText ct) throws IOException {
        CellStyle style = new CellStyle(ct.size(), ct.bold(), ct.italic(), ct.underline(), ct.strike(), ct.rgb(), -1,
                null, null, null, null, CellStyle.HAlign.GENERAL, CellStyle.VAlign.TOP, false, NumberFormat.GENERAL);
        float need = TextWidth.of(text, style.size(), style.bold()) + TableGrid.PADDING;
        if (!mostlyAscii(text)) {
            need = Math.max(need, ct.width() + TableGrid.PADDING);
        }
        boolean breaks = text.indexOf('\n') >= 0;
        int row = s.next++;
        CellValue value = CellValue.text(text);
        if (s.widths.size() <= 1) {
            if (s.widths.isEmpty()) {
                s.widths.add(s.textWidth);
            }
            boolean wrap = breaks || need > s.widths.getFirst();
            sink.row(new Row(row, Float.NaN, List.of(new Row.Cell(0, value, style.withWrap(wrap)))));
            return;
        }
        if (!breaks && need <= s.total() * OVERHANG) {
            sink.row(new Row(row, Float.NaN, List.of(new Row.Cell(0, value, style))));
            return;
        }
        if (s.total() < s.textWidth - FILLER_MIN) {
            s.widths.add(s.textWidth - s.total());
        }
        int lines = TextWidth.lines(text, style.size(), style.bold(), s.total() - TableGrid.PADDING);
        float height = lines * TableGrid.lineHeight(style.size()) + 2f;
        sink.row(new Row(row, height, List.of(new Row.Cell(0, value, style.withWrap(true), 1, s.widths.size()))));
    }

    private void writeTable(Sheet s, TableGrid g, PageData page, int skip) throws IOException {
        int start = s.next;
        List<List<Row.Cell>> rows = new ArrayList<>();
        for (int r = skip; r < g.rows; r++) {
            rows.add(new ArrayList<>());
        }
        for (TableGrid.Anchor a : g.anchors) {
            if (a.row() < skip) {
                continue;
            }
            int r = a.row() - skip;
            int lastRow = Math.min(g.rows - skip, r + a.rowSpan()) - 1;
            rows.get(r).add(new Row.Cell(a.col(), a.value(), a.style(), lastRow - r + 1, a.colSpan()));
            if (a.rowSpan() > 1 || a.colSpan() > 1) {
                CellStyle frame = a.style().frameOnly();
                boolean framed = frame.fill() >= 0 || frame.top().visible() || frame.bottom().visible()
                        || frame.left().visible() || frame.right().visible();
                for (int rr = r; framed && rr <= lastRow; rr++) {
                    for (int cc = a.col(); cc < a.col() + a.colSpan(); cc++) {
                        if (rr != r || cc != a.col()) {
                            rows.get(rr).add(new Row.Cell(cc, CellValue.text(""), frame));
                        }
                    }
                }
            }
        }
        for (int r = 0; r < rows.size(); r++) {
            List<Row.Cell> cells = rows.get(r);
            cells.sort((x, y) -> Integer.compare(x.col(), y.col()));
            sink.row(new Row(start + r, g.heights[r + skip], cells));
        }
        s.next = start + rows.size();
        widen(s, g);
        tables++;
        String name = "Page" + (page.index() + 1) + "_Table" + (++pageTables);
        s.ranges.add(new NamedRange(name, start, 0, Math.max(start, s.next - 1), g.cols - 1));
        s.last = new Placed(g, name, start, page.height(), page.index());
        s.lastWasTable = true;
        s.lastBottom = Float.NaN;
    }

    private void append(Sheet s, TableGrid g, PageData page) throws IOException {
        Placed prev = s.last;
        int skip = Continuation.repeatedHeader(prev.grid(), g);
        writeTable(s, g, page, skip);
        s.ranges.removeLast();
        tables--;
        pageTables--;
        s.ranges.removeIf(n -> n.name().equals(prev.name()));
        s.ranges.add(new NamedRange(prev.name(), prev.firstRow(), 0, s.next - 1, g.cols - 1));
        s.last = new Placed(g, prev.name(), prev.firstRow(), page.height(), page.index());
    }

    private static boolean carriesOn(Placed prev, TableGrid g, PageData page) {
        return prev != null && prev.page() == page.index() - 1
                && Continuation.carriesOn(prev.grid(), prev.pageHeight(), g, page.height());
    }

    private static void widen(Sheet s, TableGrid g) {
        for (int c = 0; c < g.cols; c++) {
            if (c < s.widths.size()) {
                s.widths.set(c, Math.max(s.widths.get(c), g.widths[c]));
            } else {
                s.widths.add(g.widths[c]);
            }
        }
    }

    private static List<TableGrid> grids(List<Object> items) {
        List<TableGrid> out = new ArrayList<>();
        for (Object o : items) {
            if (o instanceof TableGrid g) {
                out.add(g);
            }
        }
        return out;
    }

    private float textWidth(PageData page, List<Object> items) {
        PageFrame frame = stats.frame(page.width(), page.height());
        float w = frame.textRight() - frame.textLeft();
        for (Object o : items) {
            if (o instanceof Para p) {
                w = Math.max(w, p.block().para().rightEdge() - p.block().para().x());
            }
        }
        return Math.max(150f, Math.min(w, page.width()));
    }

    private static boolean mostlyAscii(String s) {
        int ascii = 0;
        for (int i = 0; i < s.length(); i++) {
            ascii += s.charAt(i) < 128 ? 1 : 0;
        }
        return ascii * 10 >= s.length() * 7;
    }
}

package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Consumer;

import org.apache.poi.ss.util.CellRangeAddress;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTMergeCell;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTSheetFormatPr;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorksheet;

import stirling.software.officeconvert.topdf.RenderJob;

final class Grid {

    static final int MAX_ROWS = 1_048_576;

    static final class RowInfo {

        final int index;

        final double source;

        final boolean hidden;

        final int style;

        private final PackedCells store;

        char[] cols;

        int[] formats;

        int[] texts;

        byte[] packed;

        double descent;

        int spillFrom = -1;

        int spillTo = -1;

        private int[] textCols;

        RowInfo(PackedCells store, int index, double source, boolean hidden, int style) {
            this.store = store;
            this.index = index;
            this.source = source;
            this.hidden = hidden;
            this.style = style;
        }

        NavigableMap<Integer, CellEntry> cells() {
            return store.cells(this);
        }

        List<CellEntry> cells(int first, int last) {
            return store.cells(this, first, last);
        }

        CellEntry cell(int col) {
            return store.cell(this, col);
        }

        CellFormat format(int col) {
            return store.format(this, col);
        }

        boolean hasText(int col) {
            int i = cols == null || col < 0 || col > Character.MAX_VALUE ? -1 : Arrays.binarySearch(cols, (char) col);
            return i >= 0 && store.hasText(this, i);
        }

        boolean isEmpty() {
            return cols == null;
        }

        int count() {
            return cols == null ? 0 : cols.length;
        }

        int[] textColumns() {
            if (textCols == null) {
                int n = 0;
                int[] out = new int[count()];
                for (int i = 0; i < out.length; i++) {
                    if (store.hasText(this, i)) {
                        out[n++] = cols[i];
                    }
                }
                textCols = Arrays.copyOf(out, n);
            }
            return textCols;
        }

        void put(CellEntry e) {
            TreeMap<Integer, CellEntry> cells = store.cells(this);
            cells.put(e.col(), e);
            textCols = null;
            store.store(this, cells);
        }
    }

    private final PackedCells packed = new PackedCells();

    private final Book book;

    private final Columns columns;

    private final TreeMap<Integer, RowInfo> rows = new TreeMap<>();

    private RowInfo recent;

    private RowInfo earlier;

    private final Map<Long, CellRangeAddress> mergeTopLeft = new HashMap<>();

    private final List<CellRangeAddress> merges;

    private final MergeIndex mergeIndex;

    private Overlays overlays;

    private final Map<Long, Double> textWidths = new HashMap<>();

    private final double defaultSource;

    private final double defaultDescent;

    // Excel's row height for a sheet that has no sheetFormatPr, whatever its default font
    private static final double SHEET_DEFAULT_ROW = 15;

    private final double rowFactor;

    private final boolean defaultHidden;

    private final boolean columnTops;

    private final boolean rightToLeft;

    private int lastRow = -1;

    private int damagedRows;

    private int lastCol = -1;

    Grid(Book book, WorksheetReader reader, String sheetName, RenderJob job) throws IOException {
        this(book, reader, sheetName, null, job);
    }

    Grid(Book book, WorksheetReader reader, String sheetName, String sheetPart, RenderJob job) throws IOException {
        this.book = book;
        CTWorksheet ws = reader.skeleton();
        PrintMetrics metrics = book.metrics();
        Columns cols;
        try {
            cols = new Columns(ws, metrics);
        } catch (RuntimeException e) {
            cols = new Columns(CTWorksheet.Factory.newInstance(), metrics);
        }
        this.columns = cols;
        CTSheetFormatPr f = null;
        double fileDefault = 0;
        boolean customDefault = false;
        boolean zero = false;
        try {
            f = ws.isSetSheetFormatPr() ? ws.getSheetFormatPr() : null;
            fileDefault = f == null ? SHEET_DEFAULT_ROW
                    : f.getDefaultRowHeight() > 0 ? Math.min(409.5, f.getDefaultRowHeight()) : 0;
            customDefault = f != null && f.isSetCustomHeight() && f.getCustomHeight();
            zero = f != null && f.isSetZeroHeight() && f.getZeroHeight();
        } catch (RuntimeException e) {
            fileDefault = 0;
        }
        double screenDefault = metrics.estimatedScreenRowPt();
        boolean near = Math.abs(fileDefault - screenDefault) <= DEFAULT_ROW_TOLERANCE;
        this.defaultSource = fileDefault > 0 && (customDefault || !near) ? fileDefault : screenDefault;
        this.rowFactor = metrics.rowFactor(screenDefault);
        this.defaultHidden = zero;
        this.columnTops = columns.anyStyle(st -> book.styles().at(st).top().visible());
        this.rightToLeft = rightToLeft(ws);
        this.defaultDescent = descentPoints(book.styles().defaultFont());
        this.merges = merges(ws);
        this.mergeIndex = new MergeIndex(merges);
        for (CellRangeAddress m : merges) {
            if (m.getFirstRow() < 0 || m.getFirstColumn() < 0) {
                continue;
            }
            mergeTopLeft.putIfAbsent(key(m.getFirstRow(), m.getFirstColumn()), m);
            lastRow = Math.max(lastRow, m.getLastRow());
            lastCol = Math.max(lastCol, m.getLastColumn());
        }
        reader.rows(row -> {
            try {
                readRow(row);
            } catch (RuntimeException e) {
                damagedRows++;
            }
        }, job);
        lastCol = Math.max(lastCol, -1);
        if (sheetPart != null) {
            overlay(Overlays.read(book, sheetPart, ws, this, job));
        }
        extendForOverflow();
        if (damagedRows > 0 || reader.damaged()) {
            job.warn("Sheet " + sheetName + " is damaged; some rows could not be read");
            job.losePart();
        }
    }

    private static boolean rightToLeft(CTWorksheet ws) {
        try {
            return ws.isSetSheetViews() && ws.getSheetViews().sizeOfSheetViewArray() > 0
                    && ws.getSheetViews().getSheetViewArray(0).getRightToLeft();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // A right-to-left sheet is laid out as a left-to-right one and mirrored as a whole when it is painted
    boolean rightToLeft() {
        return rightToLeft;
    }

    private static List<CellRangeAddress> merges(CTWorksheet ws) {
        List<CellRangeAddress> out = new ArrayList<>();
        try {
            if (!ws.isSetMergeCells()) {
                return out;
            }
            for (CTMergeCell m : ws.getMergeCells().getMergeCellArray()) {
                try {
                    CellRangeAddress r = CellRangeAddress.valueOf(m.getRef());
                    if (r.getFirstRow() >= 0 && r.getFirstColumn() >= 0 && r.getLastRow() < MAX_ROWS
                            && r.getLastColumn() < Columns.MAX && r.getNumberOfCells() > 1) {
                        out.add(r);
                    }
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (out.size() >= 100_000) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            return out;
        }
        return out;
    }

    private void readRow(RawRow row) {
        int index = row.index();
        boolean hidden = row.hidden();
        int style = row.style();
        List<CellEntry> entries = new ArrayList<>();
        List<FontSpec> blanks = new ArrayList<>();
        boolean auto = !(row.hasHeight() && row.height() >= 0);
        boolean fit = auto || !row.custom();
        int lastBlank = -1;
        double descent = 0;
        for (RawRow.Cell cell : row.cells()) {
            int col = cell.col();
            CellFormat format = book.styles().at(cell.style());
            CellText text = book.formatter().display(cell, format);
            if (text != null && text.isEmpty()) {
                text = null;
            }
            if (text == null && !format.visible() && format.hAlign() != CellFormat.HAlign.CENTER_CONTINUOUS) {
                if (fit && cell.style() != lastBlank && blanks.size() < MAX_BLANK_FONTS
                        && !format.font().equals(book.styles().defaultFont())) {
                    blanks.add(format.font());
                }
                lastBlank = cell.style();
                continue;
            }
            entries.add(new CellEntry(index, col, format, text));
            if (text != null) {
                for (TextRun run : text.runs()) {
                    descent = Math.max(descent, descentPoints(run.font()));
                }
            }
        }
        boolean gone = hidden || !auto && row.height() <= 0;
        double height;
        if (gone) {
            height = auto ? defaultSource : Math.min(409.5, row.height());
        } else if (auto) {
            height = Math.min(409.5, autofit(index, entries, blanks) + Math.max(0, row.thickEdges()) * SCREEN_PX);
        } else if (fit) {
            height = refit(row, autofit(index, entries, blanks) + Math.max(0, row.thickEdges()) * SCREEN_PX,
                    entries.isEmpty() && blanks.isEmpty());
        } else {
            height = Math.min(409.5, row.height());
        }
        for (CellEntry e : entries) {
            if (e.text() != null || e.format().visible()) {
                lastRow = Math.max(lastRow, index);
                lastCol = Math.max(lastCol, e.col());
            }
        }
        if (gone) {
            entries.removeIf(e -> !keptWhenHidden(style, e));
            if (entries.isEmpty() && defaultHidden && plainHiddenRow(style)) {
                return;
            }
        }
        RowInfo info = new RowInfo(packed, index, height, gone || height <= 0, style);
        info.descent = descent > 0 ? descent : defaultDescent;
        TreeMap<Integer, CellEntry> cells = new TreeMap<>();
        for (CellEntry e : entries) {
            cells.put(e.col(), e);
        }
        packed.store(info, cells);
        rows.put(index, info);
        recent = null;
        earlier = null;
    }

    // A hidden row shows nothing, so a blank cell there matters only as the edge above the next visible row
    private boolean keptWhenHidden(int rowStyle, CellEntry e) {
        if (e.text() != null || mergeTopLeft.containsKey(key(e.row(), e.col())) || e.format().diagonal().visible()) {
            return true;
        }
        CellFormat fallback = rowStyle >= 0 ? book.styles().at(rowStyle) : columnFormat(e.col());
        return !sameEdge(e.format().top(), fallback == null ? BorderLine.NONE : fallback.top());
    }

    private boolean plainHiddenRow(int rowStyle) {
        return rowStyle < 0 || !book.styles().at(rowStyle).top().visible() && !columnTops;
    }

    private CellFormat columnFormat(int col) {
        int cs = columns.style(col);
        return cs >= 0 ? book.styles().at(cs) : null;
    }

    static boolean sameEdge(BorderLine a, BorderLine b) {
        return !a.visible() && !b.visible() || a.equals(b);
    }

    private void overlay(Overlays o) {
        overlays = o;
        if (o.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, Overlays.Delta> e : o.under().entrySet()) {
            int row = (int) (e.getKey() >> 16);
            int col = (int) (e.getKey() & 0xFFFF);
            Overlays.Delta d = e.getValue();
            CellEntry old = cell(row, col);
            CellFormat base = old != null ? old.format() : formatAt(row, col);
            if (base == null) {
                base = book.styles().at(-1);
            }
            boolean ownFont = !base.font().equals(book.styles().defaultFont());
            CellFormat f = restyle(base, d, false, ownFont);
            if (old == null && !f.visible()) {
                continue;
            }
            put(row, col, old == null ? new CellEntry(row, col, f, null)
                    : new CellEntry(row, col, f, recolor(old.text(), d, ownFont)));
        }
        for (Map.Entry<Long, Overlays.Delta> e : o.over().entrySet()) {
            int row = (int) (e.getKey() >> 16);
            int col = (int) (e.getKey() & 0xFFFF);
            CellEntry old = cell(row, col);
            if (old != null) {
                Overlays.Delta d = e.getValue();
                put(row, col, new CellEntry(row, col, restyle(old.format(), d, true, false),
                        recolor(old.text(), d, false)));
            }
        }
    }

    private void put(int row, int col, CellEntry e) {
        RowInfo info = rows.computeIfAbsent(row, r -> {
            RowInfo n = new RowInfo(packed, r, defaultSource, defaultHidden, -1);
            n.descent = defaultDescent;
            return n;
        });
        info.put(e);
        if (e.hasText() || e.format().visible()) {
            lastRow = Math.max(lastRow, row);
            lastCol = Math.max(lastCol, col);
        }
    }

    private static CellFormat restyle(CellFormat f, Overlays.Delta d, boolean over, boolean ownFont) {
        FontSpec font = f.font();
        if (over || !ownFont) {
            font = new FontSpec(font.family(), font.size(), font.bold() || d.bold(), font.italic() || d.italic(),
                    font.underline(), font.strike(), d.font() != null ? d.font() : font.color(), font.offset());
        }
        Color fill = over ? (d.fill() != null ? d.fill() : f.fill()) : (f.fill() != null ? f.fill() : d.fill());
        return new CellFormat(font, fill, pick(f.left(), d.left(), over), pick(f.right(), d.right(), over),
                pick(f.top(), d.top(), over), pick(f.bottom(), d.bottom(), over), f.diagonal(), f.diagonalUp(),
                f.diagonalDown(), f.hAlign(), f.vAlign(), f.wrap(), f.shrink(), f.indent(), f.rotation(),
                f.formatIndex(), f.formatString(), f.readingOrder());
    }

    private static BorderLine pick(BorderLine own, BorderLine delta, boolean over) {
        if (delta == null) {
            return own;
        }
        return over || !own.visible() ? delta : own;
    }

    private static CellText recolor(CellText t, Overlays.Delta d, boolean ownFont) {
        if (t == null || ownFont || d.font() == null && !d.bold() && !d.italic()) {
            return t;
        }
        List<TextRun> runs = new ArrayList<>();
        for (TextRun r : t.runs()) {
            FontSpec f = r.font();
            runs.add(new TextRun(r.text(), new FontSpec(f.family(), f.size(), f.bold() || d.bold(),
                    f.italic() || d.italic(), f.underline(), f.strike(), d.font() != null ? d.font() : f.color(),
                    f.offset())));
        }
        return new CellText(t.kind(), runs, d.font() != null ? null : t.color(), t.general(), t.number());
    }

    Overlays.Bar dataBar(int row, int col) {
        return overlays == null ? null : overlays.bar(row, col);
    }

    static final double SCREEN_PAD = 4;

    static final double DEFAULT_ROW_TOLERANCE = 1.5;

    static final int MAX_BLANK_FONTS = 64;

    static final double SCREEN_PX = 0.75;

    private double fontLine(FontSpec f, double defaultPx) {
        FontSpec def = book.styles().defaultFont();
        boolean isDefault = f.family().equalsIgnoreCase(def.family()) && Math.abs(f.size() - def.size()) < 0.01;
        return isDefault ? defaultPx : screenLine(f) / 0.75;
    }

    // Excel re-fits a row without customHeight: an empty one drops to the default, and wrapped text grows
    // a height another program stored (Excel's own stored height is already its fit)
    private double refit(RawRow row, double fitted, boolean empty) {
        double stored = Math.min(409.5, row.height());
        if (empty && row.style() < 0 || !book.workbook().savedByExcel && fitted > stored + defaultSource / 4) {
            return Math.min(409.5, fitted);
        }
        return stored;
    }

    private double autofit(int row, List<CellEntry> entries, List<FontSpec> blanks) {
        double defaultPx = defaultSource / 0.75;
        double best = defaultPx;
        PrintMetrics m = book.metrics();
        for (FontSpec f : blanks) {
            best = Math.max(best, Math.min(546, fontLine(f, defaultPx)));
        }
        for (CellEntry e : entries) {
            CellRangeAddress merge = mergeCovering(row, e.col());
            if (merge != null && merge.getFirstRow() != merge.getLastRow()) {
                continue;
            }
            if (e.text() == null || merge != null) {
                if (merge == null || merge.getFirstColumn() == e.col()) {
                    best = Math.max(best, Math.min(546, e.text() == null ? fontLine(e.format().font(), defaultPx)
                            : runsLine(e.text().runs(), defaultPx)));
                }
                continue;
            }
            double line = runsLine(e.text().runs(), defaultPx);
            int lines = 1;
            CellFormat fmt = e.format();
            if (fmt.wraps() && fmt.rotation() == 0) {
                double colPx = m.screenColumnPixels(columns.chars(e.col()));
                double indent = fmt.indent() > 0 ? fmt.indent() * book.screenIndentPixels() : 0;
                double avail = colPx - SCREEN_PAD - indent - overhang(e.text().runs());
                Typesetter t = book.typesetter();
                lines = Math.max(1, CellLayout.wrap(e.text().runs(), avail,
                        (str, font) -> t.screenWidth(str, font, FontMeasure.ppem(font.size(), 96))).size());
            }
            best = Math.max(best, Math.min(546, line * lines));
        }
        return best * 0.75;
    }

    // Wrapping keeps room on each line for the slant of italic text above the baseline
    private double overhang(List<TextRun> runs) {
        long px = 0;
        for (TextRun r : runs) {
            FontSpec f = r.font();
            if (f.italic() && !r.text().isBlank()) {
                px = Math.max(px, italicOverhang(book.typesetter().measure(f), f.size()));
            }
        }
        return px;
    }

    static long italicOverhang(FontMeasure fm, double size) {
        int ppem = FontMeasure.ppem(size, 96);
        return Math.round((double) fm.winAscent() * ppem / fm.unitsPerEm() * ITALIC_SLANT);
    }

    static final double ITALIC_SLANT = 0.2;

    private double runsLine(List<TextRun> runs, double defaultPx) {
        double line = 0;
        for (TextRun r : runs) {
            line = Math.max(line, fontLine(r.font(), defaultPx));
        }
        return line;
    }

    private void extendForOverflow() {
        int limit = lastCol;
        for (RowInfo row : rows.values()) {
            if (row.hidden || row.isEmpty()) {
                continue;
            }
            NavigableMap<Integer, CellEntry> cells = row.cells();
            CellEntry e = cells.lastEntry().getValue();
            while (e != null && !e.hasText()) {
                var lower = cells.lowerEntry(e.col());
                e = lower == null ? null : lower.getValue();
            }
            if (e == null || e.text().kind() != CellText.Kind.TEXT || e.format().wraps() || e.format().shrink()
                    || e.format().rotation() != 0 || mergeTopLeft.containsKey(key(e.row(), e.col()))) {
                continue;
            }
            CellFormat.HAlign h = CellLayout.horizontal(e.format(), e.text(), rightToLeft);
            if (h == CellFormat.HAlign.RIGHT || h == CellFormat.HAlign.FILL) {
                continue;
            }
            int first = e.col();
            int last = e.col();
            if (h == CellFormat.HAlign.CENTER_CONTINUOUS) {
                while (last + 1 < Columns.MAX && cells.containsKey(last + 1)
                        && cells.get(last + 1).format().hAlign() == CellFormat.HAlign.CENTER_CONTINUOUS
                        && !cells.get(last + 1).hasText()) {
                    last++;
                }
            }
            double span = 0;
            for (int c = first; c <= last; c++) {
                span += columns.width(c);
            }
            Typesetter t = book.typesetter();
            double need = t.width(e.text().runs(), 1) + 2 * CellLayout.pad(t, e.text(), e.format());
            double extra = need - span;
            if (h == CellFormat.HAlign.CENTER || h == CellFormat.HAlign.CENTER_CONTINUOUS) {
                extra /= 2;
            }
            int c = last;
            while (extra > 0 && c + 1 < Columns.MAX && c - last < 256) {
                c++;
                extra -= columns.width(c);
            }
            if (c > last) {
                row.spillFrom = first;
                row.spillTo = c;
            }
            limit = Math.max(limit, c);
        }
        lastCol = limit;
    }

    double textWidth(CellEntry e) {
        Long k = key(e.row(), e.col());
        Double known = textWidths.get(k);
        if (known != null) {
            return known;
        }
        double w = book.typesetter().width(e.text().runs(), 1);
        if (textWidths.size() < MAX_CACHED_WIDTHS) {
            textWidths.put(k, w);
        }
        return w;
    }

    static final int MAX_CACHED_WIDTHS = 16_384;

    double screenLine(FontSpec f) {
        return book.typesetter().measure(f).screenLinePx(f.size()) * 0.75;
    }

    double linePitch(FontSpec f) {
        FontSpec def = book.styles().defaultFont();
        if (f.family().equalsIgnoreCase(def.family()) && Math.abs(f.size() - def.size()) < 0.01) {
            return defaultHeight();
        }
        return book.typesetter().measure(f).printerLinePx(f.size()) * PrintMetrics.PX;
    }

    double descentPoints(FontSpec f) {
        FontMeasure m = book.typesetter().measure(f);
        int ppem = FontMeasure.ppem(f.size(), 96);
        long px = Math.round((double) m.winDescent() * ppem / m.unitsPerEm()) + 1;
        return px * 0.75;
    }

    private double printed(double heightPt) {
        long px = (long) Math.floor(heightPt * 600 / 72 * rowFactor + 1e-6);
        return px * PrintMetrics.PX;
    }

    double defaultHeight() {
        return printed(defaultSource);
    }

    double rowFactor() {
        return rowFactor;
    }

    double columnWidth(int col) {
        return columns.width(col);
    }

    // Painters alternate between a row and the one above it, so the last two rows found are kept
    private RowInfo row(int row) {
        RowInfo r = recent;
        if (r != null && r.index == row) {
            return r;
        }
        RowInfo e = earlier;
        if (e != null && e.index == row) {
            earlier = r;
            recent = e;
            return e;
        }
        r = rows.get(row);
        if (r != null) {
            earlier = recent;
            recent = r;
        }
        return r;
    }

    double rowHeight(int row) {
        RowInfo r = row(row);
        if (r == null) {
            return defaultHidden ? 0 : printed(defaultSource);
        }
        return r.hidden ? 0 : printed(r.source);
    }

    double rowDescent(int row) {
        RowInfo r = row(row);
        return (r == null ? defaultDescent : r.descent) * rowFactor;
    }

    NavigableMap<Integer, RowInfo> rows(int first, int last) {
        return rows.subMap(first, true, last, true);
    }

    CellEntry cell(int row, int col) {
        RowInfo r = row(row);
        return r == null ? null : r.cell(col);
    }

    CellFormat formatAt(int row, int col) {
        RowInfo r = row(row);
        if (r != null) {
            CellFormat f = r.format(col);
            if (f != null) {
                return f;
            }
            if (r.style >= 0) {
                return book.styles().at(r.style);
            }
        }
        int cs = columns.style(col);
        return cs >= 0 ? book.styles().at(cs) : null;
    }

    boolean hasValue(int row, int col) {
        RowInfo r = row(row);
        return r != null && r.hasText(col);
    }

    CellRangeAddress mergeAt(int row, int col) {
        return mergeTopLeft.get(key(row, col));
    }

    List<CellRangeAddress> merges() {
        return merges;
    }

    CellRangeAddress mergeCovering(int row, int col) {
        return mergeIndex.covering(row, col);
    }

    void mergesIn(int r0, int r1, int c0, int c1, Consumer<CellRangeAddress> out) {
        mergeIndex.intersecting(r0, r1, c0, c1, out);
    }

    int lastRow() {
        return lastRow;
    }

    int lastCol() {
        return lastCol;
    }

    void extend(int row, int col) {
        lastRow = Math.max(lastRow, Math.min(MAX_ROWS - 1, row));
        lastCol = Math.max(lastCol, Math.min(Columns.MAX - 1, col));
    }

    Book book() {
        return book;
    }

    private static long key(int row, int col) {
        return (long) row << 16 | col;
    }
}

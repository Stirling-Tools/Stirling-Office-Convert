package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.IntPredicate;

import org.apache.poi.ss.util.CellRangeAddress;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTMergeCell;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTSheetFormatPr;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorksheet;

import stirling.software.officeconvert.topdf.RenderJob;

final class Grid {

    static final int MAX_ROWS = 1_048_576;

    static final class RowInfo {

        final int index;

        double source;

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

        int markFrom = -1;

        int markTo = -1;

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

        void formats(int first, int last, PackedCells.FormatVisitor visit) throws IOException {
            store.formats(this, first, last, visit);
        }

        boolean hasText(int col) {
            int i = cols == null || col < 0 || col > Character.MAX_VALUE ? -1 : Arrays.binarySearch(cols, (char) col);
            return i >= 0 && store.hasText(this, i);
        }

        boolean isEmpty() {
            return cols == null;
        }

        int lastText(IntPredicate shown) {
            for (int i = count() - 1; i >= 0; i--) {
                if (store.hasText(this, i) && shown.test(cols[i])) {
                    return cols[i];
                }
            }
            return -1;
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

    private double columnCap = Double.POSITIVE_INFINITY;

    private final boolean defaultHidden;

    private final boolean fixedDefault;

    private final boolean otherScreen;

    private boolean storedHeights;

    private final List<RowInfo> fitted = new ArrayList<>();

    private final boolean columnTops;

    private final boolean rightToLeft;

    private int lastRow = -1;

    private int damagedRows;

    private int lastCol = -1;

    private int styledCol = -1;

    private int filledRow = -1;

    private int filledCol = -1;

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
        this.defaultSource = fileDefault > 0 && (customDefault || !near || book.workbook().openDocument) ? fileDefault
                : screenDefault;
        this.rowFactor = book.workbook().openDocument ? 1 : metrics.rowFactor(screenDefault);
        this.defaultHidden = zero;
        this.fixedDefault = customDefault;
        this.otherScreen = !customDefault && near && Math.abs(fileDefault - screenDefault) > OTHER_SCREEN_ROW;
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
        }
        reader.rows(row -> {
            try {
                readRow(row);
            } catch (RuntimeException e) {
                damagedRows++;
            }
        }, job);
        lastCol = Math.max(lastCol, -1);
        keepDefaultHeights();
        if (sheetPart != null) {
            overlay(Overlays.read(book, sheetPart, ws, this, job));
        }
        extendForMerges();
        extendForOverflow();
        if (damagedRows > 0 || reader.damaged()) {
            job.warn("Sheet " + sheetName + " is damaged; some rows could not be read");
            job.losePart();
        }
    }

    private void keepDefaultHeights() {
        if (!storedHeights) {
            for (RowInfo r : fitted) {
                r.source = defaultSource;
            }
        }
        fitted.clear();
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
        int markFrom = -1;
        int markTo = -1;
        int styledTo = -1;
        FontSpec base = style >= 0 ? book.styles().at(style).font() : book.styles().defaultFont();
        double descent = 0;
        for (RawRow.Cell cell : row.cells()) {
            int col = cell.col();
            CellFormat format = book.styles().at(cell.style());
            CellText text = book.formatter().display(cell, format);
            if (text != null && text.isEmpty()) {
                text = null;
            }
            if (text == null && !format.visible() && format.hAlign() != CellFormat.HAlign.CENTER_CONTINUOUS) {
                if (cell.style() > 0 && col > styledTo && columns.width(col) > 0 && !book.styles()
                        .sameRecord(cell.style(), columns.style(col) >= 0 ? columns.style(col) : Math.max(0, style))) {
                    styledTo = col;
                }
                if (book.styles().ruled(cell.style())) {
                    markFrom = markFrom < 0 ? col : markFrom;
                    markTo = col;
                }
                if (fit && cell.style() != lastBlank && blanks.size() < MAX_BLANK_FONTS
                        && !format.font().equals(base)) {
                    if (inTallMerge(index, col)) {
                        continue;
                    }
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
            height = Math.min(409.5, autofit(index, entries, blanks, base) + Math.max(0, row.thickEdges()) * SCREEN_PX);
        } else if (fit && fixedDefault && book.workbook().savedOnMac) {
            double screenPx = screenLine(book.styles().defaultFont()) / 0.75;
            height = Math.min(409.5, autofit(index, entries, blanks, base, screenPx)
                    + Math.max(0, row.thickEdges()) * SCREEN_PX);
        } else if (fit) {
            height = refit(row, autofit(index, entries, blanks, base) + Math.max(0, row.thickEdges()) * SCREEN_PX,
                    entries.isEmpty() && blanks.isEmpty(), wrapsText(entries));
        } else {
            height = Math.min(409.5, row.height());
        }
        boolean printed = false;
        for (CellEntry e : entries) {
            if (e.text() == null && e.format().fill() != null && !gone && columns.width(e.col()) > 0) {
                filledRow = Math.max(filledRow, index);
                filledCol = Math.max(filledCol, e.col());
            }
            if (e.text() != null || inked(e.format())) {
                printed = true;
                if (columns.width(e.col()) > 0 || !book.workbook().openDocument) {
                    lastRow = Math.max(lastRow, index);
                }
                if (columns.width(e.col()) > 0) {
                    lastCol = Math.max(lastCol, e.col());
                }
            }
        }
        if (printed && !gone) {
            styledCol = Math.max(styledCol, styledTo);
        }
        if (markTo >= 0 && !gone) {
            lastRow = Math.max(lastRow, index);
            lastCol = Math.max(lastCol, markTo);
        }
        if (gone) {
            entries.removeIf(e -> !keptWhenHidden(style, e));
            if (entries.isEmpty() && defaultHidden && plainHiddenRow(style)) {
                return;
            }
        }
        RowInfo info = new RowInfo(packed, index, height, gone || height <= 0, style);
        storedHeights |= !auto;
        if (auto && !gone && fixedDefault && !storedHeights && height != defaultSource) {
            fitted.add(info);
        }
        info.descent = descent > 0 ? descent : defaultDescent;
        if (!gone) {
            info.markFrom = markFrom;
            info.markTo = markTo;
        }
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

    static boolean inked(CellFormat f) {
        return f.fill() != null && !white(f.fill()) || inked(f.left()) || inked(f.right()) || inked(f.top())
                || inked(f.bottom()) || inked(f.diagonal()) && (f.diagonalUp() || f.diagonalDown());
    }

    private static boolean inked(BorderLine b) {
        return b.visible();
    }

    private static boolean white(Color c) {
        return (c.getRGB() & 0xFFFFFF) == 0xFFFFFF;
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
            boolean ownColor = !Objects.equals(base.font().color(), book.styles().defaultFont().color());
            CellFormat f = restyle(base, d, false, ownColor);
            if (old == null && !f.visible()) {
                continue;
            }
            put(row, col, old == null ? new CellEntry(row, col, f, null)
                    : new CellEntry(row, col, f, recolor(old.text(), d, ownColor)));
        }
        for (Map.Entry<Long, Overlays.Delta> e : o.over().entrySet()) {
            int row = (int) (e.getKey() >> 16);
            int col = (int) (e.getKey() & 0xFFFF);
            CellEntry old = cell(row, col);
            if (old != null) {
                Overlays.Delta d = e.getValue();
                CellFormat f = restyle(old.format(), d, true, false);
                CellText text = old.text();
                if (d.format() != null && text != null && text.numeric()) {
                    f = f.withFormat(d.format());
                    text = book.formatter().number(text.number(), f);
                }
                put(row, col, new CellEntry(row, col, f, recolor(text, d, false)));
            }
        }
        for (Map.Entry<Long, Overlays.Bar> e : o.bars().entrySet()) {
            int row = (int) (e.getKey() >> 16);
            int col = (int) (e.getKey() & 0xFFFF);
            CellEntry old = cell(row, col);
            if (e.getValue().valueHidden() && old != null && old.text() != null) {
                put(row, col, new CellEntry(row, col, old.format(), null));
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
        if (e.hasText() || inked(e.format())) {
            lastRow = Math.max(lastRow, row);
            lastCol = Math.max(lastCol, col);
        }
    }

    private static CellFormat restyle(CellFormat f, Overlays.Delta d, boolean over, boolean ownColor) {
        FontSpec font = f.font();
        Color color = d.font() != null && (over || !ownColor) ? d.font() : font.color();
        font = new FontSpec(font.family(), font.size(), font.bold() || d.bold(), font.italic() || d.italic(),
                font.underline(), font.strike(), color, font.offset());
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

    private static CellText recolor(CellText t, Overlays.Delta d, boolean ownColor) {
        Color color = ownColor ? null : d.font();
        if (t == null || color == null && !d.bold() && !d.italic()) {
            return t;
        }
        List<TextRun> runs = new ArrayList<>();
        for (TextRun r : t.runs()) {
            FontSpec f = r.font();
            runs.add(new TextRun(r.text(), new FontSpec(f.family(), f.size(), f.bold() || d.bold(),
                    f.italic() || d.italic(), f.underline(), f.strike(), color != null ? color : f.color(),
                    f.offset())));
        }
        return new CellText(t.kind(), runs, color != null ? null : t.color(), t.general(), t.number());
    }

    boolean hasDataBars() {
        return overlays != null && overlays.hasBars();
    }

    Overlays.Bar dataBar(int row, int col) {
        return overlays == null ? null : overlays.bar(row, col);
    }

    static final double SCREEN_PAD = 4;

    static final double DEFAULT_ROW_TOLERANCE = 1.5;

    static final double OTHER_SCREEN_ROW = 0.05;

    static final int MAX_BLANK_FONTS = 64;

    static final double SCREEN_PX = 0.75;

    private double fontLine(FontSpec f, double defaultPx) {
        FontSpec def = book.styles().defaultFont();
        boolean isDefault = f.family().equalsIgnoreCase(def.family()) && Math.abs(f.size() - def.size()) < 0.01;
        return isDefault ? defaultPx : screenLine(f) / 0.75;
    }

    // Excel re-fits a row without customHeight: an empty one drops to the default, and wrapped text grows
    // a height another program stored (Excel's own stored height is already its fit, unless the Mac or a screen
    // at another DPI wrapped it)
    private double refit(RawRow row, double fitted, boolean empty, boolean wrapped) {
        double stored = Math.min(409.5, row.height());
        if (book.workbook().openDocument) {
            return row.index() > CALC_REFIT_ROWS || calcPixels(stored) == calcPixels(fitted) ? stored
                    : Math.min(409.5, fitted);
        }
        if ((book.workbook().savedOnMac || otherScreen) && wrapped) {
            return Math.min(409.5, fitted);
        }
        if (empty && row.style() < 0 || !book.workbook().savedByExcel
                && fitted > stored + (book.workbook().savedByCalc ? SCREEN_PX : defaultSource / 4)) {
            return Math.min(409.5, fitted);
        }
        return stored;
    }

    private boolean inTallMerge(int row, int col) {
        CellRangeAddress m = mergeCovering(row, col);
        return m != null && m.getFirstRow() != m.getLastRow();
    }

    private static boolean wrapsText(List<CellEntry> entries) {
        for (CellEntry e : entries) {
            if (e.text() != null && e.format().wraps()) {
                return true;
            }
        }
        return false;
    }

    private double autofit(int row, List<CellEntry> entries, List<FontSpec> blanks, FontSpec base) {
        return autofit(row, entries, blanks, base, defaultSource / 0.75);
    }

    private double autofit(int row, List<CellEntry> entries, List<FontSpec> blanks, FontSpec base, double defaultPx) {
        if (book.workbook().openDocument) {
            return calcFit(row, entries, blanks);
        }
        double best = Math.min(546, fontLine(base, defaultPx));
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
                        (str, font) -> Math.rint(t.screenWidth(str, font, FontMeasure.ppem(font.size(), 96)))).size());
            }
            best = Math.max(best, Math.min(546, line * lines));
        }
        return best * 0.75;
    }

    private double calcFit(int row, List<CellEntry> entries, List<FontSpec> blanks) {
        double best = Math.max(CALC_MIN_ROW_TWIPS / 20.0, defaultSource);
        for (FontSpec f : blanks) {
            best = Math.max(best, calcPlain(f.size()));
        }
        Typesetter t = book.typesetter();
        for (CellEntry e : entries) {
            if (columns.width(e.col()) <= 0) {
                continue;
            }
            CellRangeAddress merge = mergeCovering(row, e.col());
            if (merge != null && (merge.getFirstRow() != merge.getLastRow() || merge.getFirstColumn() != e.col())) {
                continue;
            }
            if (e.text() == null) {
                best = Math.max(best, calcPlain(e.format().font().size()));
                continue;
            }
            CellFormat fmt = e.format();
            boolean wraps = merge == null && fmt.wraps() && fmt.rotation() == 0
                    && e.text().kind() == CellText.Kind.TEXT;
            List<FontSpec> fonts = new ArrayList<>();
            double size = 0;
            for (TextRun r : e.text().runs()) {
                fonts.add(r.font());
                size = Math.max(size, r.font().size());
            }
            if (!wraps && e.text().plain().indexOf('\n') < 0) {
                best = Math.max(best, calcPlain(size));
                continue;
            }
            double avail = Double.MAX_VALUE;
            if (wraps) {
                double indent = fmt.indent() > 0 ? fmt.indent() * book.indentPoints() : 0;
                avail = Math.max(1, columnWidth(e.col()) - 2 * CellLayout.pad(t, e.text(), fmt) - indent)
                        + CellLayout.WRAP_SLACK;
            }
            int lines = Math.max(1, CellLayout.wrap(t, e.text().runs(), avail, 1).size());
            best = Math.max(best, calcHeight(fonts, lines));
        }
        return Math.min(409.5, best);
    }

    private static long calcPixels(double points) {
        return (long) Math.floor(Math.round(points * 20) * CALC_PX_PER_TWIP + 1e-9);
    }

    private static double calcPlain(double size) {
        return Math.max(CALC_MIN_ROW_TWIPS, Math.floor(size * 20 * CALC_LINE + 1e-6) + CALC_PLAIN_TWIPS) / 20.0;
    }

    private double calcHeight(List<FontSpec> fonts, int lines) {
        int line = 0;
        for (FontSpec f : fonts) {
            line = Math.max(line, book.typesetter().measure(f).calcLinePx(f.size()));
        }
        long twips = (long) Math.floor((Math.min(lines, 10_000) * (double) line + CALC_MARGIN_PX) / CALC_PX_PER_TWIP + 1e-6);
        return twips / 20.0;
    }

    static final double CALC_LINE = 1.18;

    static final int CALC_PLAIN_TWIPS = 17;

    static final int CALC_MIN_ROW_TWIPS = 256;

    static final int CALC_REFIT_ROWS = 200;

    static final int CALC_MARGIN_PX = 2;

    static final double CALC_PX_PER_TWIP = 0.067;

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

    private void extendForMerges() {
        for (CellRangeAddress m : mergeTopLeft.values()) {
            CellEntry e = cell(m.getFirstRow(), m.getFirstColumn());
            if (e != null && (e.hasText() || inked(e.format()))) {
                lastRow = Math.max(lastRow, m.getLastRow());
                lastCol = Math.max(lastCol, m.getLastColumn());
            }
        }
    }

    private void extendForOverflow() {
        int limit = lastCol;
        for (RowInfo row : rows.values()) {
            if (row.hidden || row.isEmpty()) {
                continue;
            }
            int shown = row.lastText(c -> columns.width(c) > 0);
            CellEntry e = shown < 0 ? null : row.cell(shown);
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
                while (last + 1 < Columns.MAX && row.format(last + 1) != null
                        && row.format(last + 1).hAlign() == CellFormat.HAlign.CENTER_CONTINUOUS
                        && !row.hasText(last + 1)) {
                    last++;
                }
            }
            double span = 0;
            for (int c = first; c <= last; c++) {
                span += columnWidth(c);
            }
            Typesetter t = book.typesetter();
            double need = t.width(shown(e.text().runs()), 1) + 2 * CellLayout.pad(t, e.text(), e.format());
            double extra = need - span;
            if (h == CellFormat.HAlign.CENTER || h == CellFormat.HAlign.CENTER_CONTINUOUS) {
                extra /= 2;
            }
            int c = last;
            while (extra > 0 && c + 1 < Columns.MAX && c - last < 256) {
                c++;
                extra -= columnWidth(c);
            }
            if (c > last) {
                row.spillFrom = first;
                row.spillTo = c;
            }
            limit = Math.max(limit, c);
        }
        lastCol = limit;
    }

    static final int SHOWN_CHARS = 1024;

    static List<TextRun> shown(List<TextRun> runs) {
        List<TextRun> out = new ArrayList<>();
        int left = SHOWN_CHARS;
        for (TextRun r : runs) {
            if (left <= 0) {
                break;
            }
            if (r.text().length() <= left) {
                out.add(r);
                left -= r.text().length();
                continue;
            }
            int cut = Character.isHighSurrogate(r.text().charAt(left - 1)) ? left - 1 : left;
            out.add(new TextRun(r.text().substring(0, cut), r.font()));
            left = 0;
        }
        return out;
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
        if (book.workbook().openDocument) {
            FontMeasure m = book.typesetter().measure(f);
            return m.ascent(f.size()) + m.descent(f.size());
        }
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
        return Math.min(columnCap, columns.width(col));
    }

    double screenColumnWidth(int col) {
        return columns.width(col) > 0 ? book.metrics().screenColumnPixels(columns.chars(col)) * SCREEN_PX : 0;
    }

    void includeFormattedBlanks() {
        if (lastRow < 0) {
            return;
        }
        int col = Math.max(styledCol, filledCol);
        lastRow = Math.max(lastRow, filledRow);
        if (col > lastCol) {
            lastCol = col;
            extendForOverflow();
        }
    }

    void capColumns(double cap) {
        if (cap > 0 && cap < columnCap) {
            columnCap = cap;
            extendForOverflow();
        }
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

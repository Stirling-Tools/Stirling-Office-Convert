package stirling.software.officeconvert.topdf.xls;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.poi.hssf.record.ColumnInfoRecord;
import org.apache.poi.hssf.record.DefaultRowHeightRecord;
import org.apache.poi.hssf.record.HeaderFooterBase;
import org.apache.poi.hssf.record.RecordBase;
import org.apache.poi.hssf.record.RowRecord;
import org.apache.poi.hssf.record.WSBoolRecord;
import org.apache.poi.hssf.record.aggregates.ColumnInfoRecordsAggregate;
import org.apache.poi.hssf.record.aggregates.PageSettingsBlock;
import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFPrintSetup;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.PageMargin;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;

import stirling.software.officeconvert.memory.Admission;

final class SheetPart {

    private static final int MAX_DIGIT_PX = 7;

    private final HSSFSheet sheet;

    private final TreeMap<Integer, ColumnInfoRecord> columns = new TreeMap<>();

    private double defaultRowPt;

    private boolean defaultCustom;

    private boolean defaultHidden;

    SheetPart(HSSFSheet sheet) {
        this.sheet = sheet;
        defaultRowPt = sheet.getDefaultRowHeightInPoints();
        for (RecordBase r : sheet.getSheet().getRecords()) {
            if (r instanceof ColumnInfoRecordsAggregate agg) {
                agg.visitContainedRecords(c -> {
                    if (c instanceof ColumnInfoRecord ci && ci.getFirstColumn() <= ci.getLastColumn()) {
                        columns.put(ci.getFirstColumn(), ci);
                    }
                });
            } else if (r instanceof DefaultRowHeightRecord d) {
                defaultCustom = (d.getOptionFlags() & 1) != 0;
                defaultHidden = (d.getOptionFlags() & 2) != 0;
            }
        }
    }

    // POI reads WSBOOL's two bytes the wrong way round, so fFitToPage (bit 8) is taken from the record itself
    private boolean fitToPage() {
        for (RecordBase r : sheet.getSheet().getRecords()) {
            if (r instanceof WSBoolRecord w) {
                byte[] b = w.serialize();
                return b.length >= 6 && (b[5] & 1) != 0;
            }
        }
        return false;
    }

    // Column width in screen pixels as Excel draws it, for anchors given in fractions of a cell
    double columnPx(int col) {
        Map.Entry<Integer, ColumnInfoRecord> e = columns.floorEntry(col);
        double chars;
        if (e != null && e.getValue().getLastColumn() >= col) {
            if (e.getValue().getHidden()) {
                return 0;
            }
            chars = e.getValue().getColumnWidth() / 256.0;
            return Math.floor((chars * 256 + Math.floor(128.0 / MAX_DIGIT_PX)) / 256 * MAX_DIGIT_PX);
        }
        return sheet.getDefaultColumnWidth() * MAX_DIGIT_PX + 5;
    }

    double rowPt(int row) {
        HSSFRow r = sheet.getRow(row);
        if (r == null) {
            return defaultHidden ? 0 : defaultRowPt;
        }
        return r.getZeroHeight() ? 0 : r.getHeightInPoints();
    }

    // Returns false when the sheet was cut short at the part size limit
    boolean write(Parts parts, Parts.Part p, Strings strings, String drawing) throws IOException {
        p.append(Xml.HEAD).append("<worksheet xmlns=\"").append(Xml.MAIN).append("\" xmlns:r=\"").append(Xml.REL)
                .append("\">");
        p.write(guarded(this::head, "<sheetViews><sheetView workbookViewId=\"0\"/></sheetViews>"));
        p.write(guarded(this::columnsXml, ""));
        String tail = guarded(this::merges, "") + guarded(this::print, "<pageMargins left=\"0.75\" right=\"0.75\""
                + " top=\"1\" bottom=\"1\" header=\"0.5\" footer=\"0.5\"/>");
        boolean complete = writeRows(parts, p, strings);
        p.write(tail);
        if (drawing != null) {
            p.append("<drawing r:id=\"").append(drawing).append("\"/>");
        }
        p.write("</worksheet>");
        return complete;
    }

    int skippedRows;

    private static String guarded(java.util.function.Supplier<String> part, String fallback) {
        try {
            return part.get();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private String head() {
        StringBuilder p = new StringBuilder();
        if (fitToPage()) {
            p.append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>");
        }
        p.append("<sheetViews><sheetView workbookViewId=\"0\"")
                .append(flag(sheet::isRightToLeft) ? " rightToLeft=\"1\"" : "")
                .append(flag(sheet::isDisplayGridlines) ? "" : " showGridLines=\"0\"").append("/></sheetViews>");
        p.append("<sheetFormatPr baseColWidth=\"").append(Math.max(0, sheet.getDefaultColumnWidth()))
                .append("\" defaultRowHeight=\"").append(number(defaultRowPt)).append("\"")
                .append(defaultCustom ? " customHeight=\"1\"" : "").append(defaultHidden ? " zeroHeight=\"1\"" : "")
                .append("/>");
        return p.toString();
    }

    private String columnsXml() {
        if (columns.isEmpty()) {
            return "";
        }
        StringBuilder p = new StringBuilder("<cols>");
        int next = 0;
        for (ColumnInfoRecord c : columns.values()) {
            int first = Math.max(next, c.getFirstColumn());
            int last = Math.min(255, c.getLastColumn());
            if (first > last) {
                continue;
            }
            p.append("<col min=\"").append(first + 1).append("\" max=\"").append(last + 1).append("\" width=\"")
                    .append(number(c.getColumnWidth() / 256.0)).append("\" customWidth=\"1\" style=\"")
                    .append(c.getXFIndex()).append("\"").append(c.getHidden() ? " hidden=\"1\"" : "").append("/>");
            next = last + 1;
        }
        return p.append("</cols>").toString();
    }

    private boolean writeRows(Parts parts, Parts.Part p, Strings strings) throws IOException {
        p.write("<sheetData>");
        int count = 0;
        boolean complete = true;
        StringBuilder b = new StringBuilder(4096);
        for (Row row : sheet) {
            if ((++count & 255) == 0) {
                stopIfInterrupted();
                if (p.full() || parts.full(strings.size())) {
                    complete = false;
                    break;
                }
            }
            b.setLength(0);
            try {
                row(b, (HSSFRow) row, strings);
            } catch (RuntimeException e) {
                skippedRows++;
                continue;
            }
            p.write(b.toString());
        }
        p.write("</sheetData>");
        return complete;
    }

    private void row(StringBuilder b, HSSFRow row, Strings strings) {
        int index = row.getRowNum();
        RowRecord rec = sheet.getSheet().getRow(index);
        b.append("<row r=\"").append(index + 1).append("\" ht=\"").append(number(row.getHeightInPoints()))
                .append('"');
        if (rec != null && rec.getBadFontHeight()) {
            b.append(" customHeight=\"1\"");
        }
        if (row.getZeroHeight()) {
            b.append(" hidden=\"1\"");
        }
        if (rec != null && rec.getFormatted()) {
            b.append(" s=\"").append(rec.getXFIndex()).append("\" customFormat=\"1\"");
        }
        b.append('>');
        for (Cell c : row) {
            cell(b, (HSSFCell) c, strings);
        }
        b.append("</row>");
    }

    // Formulas are never evaluated: a formula cell is written as the value Excel cached for it
    private static void cell(StringBuilder b, HSSFCell cell, Strings strings) {
        b.append("<c r=\"").append(CellReference.convertNumToColString(cell.getColumnIndex()))
                .append(cell.getRowIndex() + 1).append("\" s=\"").append(cell.getCellStyle().getIndex() & 0xFFFF)
                .append('"');
        CellType type = cell.getCellType();
        boolean formula = type == CellType.FORMULA;
        if (formula) {
            type = cell.getCachedFormulaResultType();
        }
        switch (type) {
            case NUMERIC -> {
                double v = cell.getNumericCellValue();
                if (Double.isFinite(v)) {
                    b.append("><v>").append(number(v)).append("</v></c>");
                } else {
                    b.append(" t=\"e\"><v>#NUM!</v></c>");
                }
            }
            case STRING -> {
                if (formula) {
                    b.append(" t=\"str\"><v>").append(Xml.text(cell.getRichStringCellValue().getString()))
                            .append("</v></c>");
                } else {
                    b.append(" t=\"s\"><v>").append(strings.add(cell.getRichStringCellValue())).append("</v></c>");
                }
            }
            case BOOLEAN -> b.append(" t=\"b\"><v>").append(cell.getBooleanCellValue() ? 1 : 0).append("</v></c>");
            case ERROR -> b.append(" t=\"e\"><v>").append(Xml.attr(error(cell))).append("</v></c>");
            default -> b.append("/>");
        }
    }

    private static String error(HSSFCell cell) {
        try {
            return FormulaError.forInt(cell.getErrorCellValue()).getString();
        } catch (RuntimeException e) {
            return "#VALUE!";
        }
    }

    private String merges() {
        int n = sheet.getNumMergedRegions();
        if (n == 0) {
            return "";
        }
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            CellRangeAddress r = sheet.getMergedRegion(i);
            if (r != null && r.getFirstRow() >= 0 && r.getFirstColumn() >= 0 && r.getNumberOfCells() > 1) {
                refs.add(r.formatAsString());
            }
        }
        if (refs.isEmpty()) {
            return "";
        }
        StringBuilder p = new StringBuilder("<mergeCells count=\"").append(refs.size()).append("\">");
        for (String ref : refs) {
            p.append("<mergeCell ref=\"").append(ref).append("\"/>");
        }
        return p.append("</mergeCells>").toString();
    }

    private String print() {
        StringBuilder b = new StringBuilder("<printOptions");
        if (flag(sheet::getHorizontallyCenter)) {
            b.append(" horizontalCentered=\"1\"");
        }
        if (flag(sheet::getVerticallyCenter)) {
            b.append(" verticalCentered=\"1\"");
        }
        if (flag(sheet::isPrintRowAndColumnHeadings)) {
            b.append(" headings=\"1\"");
        }
        if (flag(sheet::isPrintGridlines)) {
            b.append(" gridLines=\"1\"");
        }
        b.append("/><pageMargins left=\"").append(margin(PageMargin.LEFT, 0.75))
                .append("\" right=\"").append(margin(PageMargin.RIGHT, 0.75))
                .append("\" top=\"").append(margin(PageMargin.TOP, 1))
                .append("\" bottom=\"").append(margin(PageMargin.BOTTOM, 1))
                .append("\" header=\"").append(margin(PageMargin.HEADER, 0.5))
                .append("\" footer=\"").append(margin(PageMargin.FOOTER, 0.5)).append("\"/>");
        HSSFPrintSetup ps = sheet.getPrintSetup();
        b.append("<pageSetup");
        // fNoPls: the paper is the printer's own, which the renderer takes to be A4
        if (ps.getValidSettings()) {
            b.append(" paperSize=\"9\"");
        } else {
            b.append(" paperSize=\"").append(Math.max(1, ps.getPaperSize() & 0xFFFF)).append('"');
            if (ps.getScale() > 0) {
                b.append(" scale=\"").append(ps.getScale() & 0xFFFF).append('"');
            }
            if (!ps.getNoOrientation()) {
                b.append(" orientation=\"").append(ps.getLandscape() ? "landscape" : "portrait").append('"');
            }
        }
        b.append(" fitToWidth=\"").append(ps.getFitWidth() & 0xFFFF).append("\" fitToHeight=\"")
                .append(ps.getFitHeight() & 0xFFFF).append('"');
        if (ps.getLeftToRight()) {
            b.append(" pageOrder=\"overThenDown\"");
        }
        if (ps.getUsePage()) {
            b.append(" useFirstPageNumber=\"1\" firstPageNumber=\"").append(ps.getPageStart()).append('"');
        }
        if (ps.getNoColor()) {
            b.append(" blackAndWhite=\"1\"");
        }
        if (ps.getDraft()) {
            b.append(" draft=\"1\"");
        }
        b.append("/>");
        PageSettingsBlock settings = sheet.getSheet().getPageSettings();
        String header = text(settings.getHeader());
        String footer = text(settings.getFooter());
        if (header != null || footer != null) {
            b.append("<headerFooter>");
            if (header != null) {
                b.append("<oddHeader>").append(Xml.attr(header)).append("</oddHeader>");
            }
            if (footer != null) {
                b.append("<oddFooter>").append(Xml.attr(footer)).append("</oddFooter>");
            }
            b.append("</headerFooter>");
        }
        try {
            breaks(b, "rowBreaks", sheet.getRowBreaks(), 16383);
            breaks(b, "colBreaks", sheet.getColumnBreaks(), 1048575);
        } catch (RuntimeException ignored) {
            // a sheet without page break records has none
        }
        return b.toString();
    }

    // Records a workbook may lack; POI's getters fail on some of them instead of giving the default
    private static boolean flag(java.util.function.BooleanSupplier get) {
        try {
            return get.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String margin(PageMargin which, double fallback) {
        try {
            double v = sheet.getMargin(which);
            return number(Double.isFinite(v) && v >= 0 && v < 50 ? v : fallback);
        } catch (RuntimeException e) {
            return number(fallback);
        }
    }

    private static String text(HeaderFooterBase record) {
        String t = record == null ? null : record.getText();
        return t == null || t.isEmpty() ? null : t;
    }

    // POI gives the last row or column before a break; the renderer takes the first one after it, as BIFF stores it
    private static void breaks(StringBuilder b, String tag, int[] at, int max) {
        if (at == null || at.length == 0) {
            return;
        }
        b.append('<').append(tag).append(" count=\"").append(at.length).append("\" manualBreakCount=\"")
                .append(at.length).append("\">");
        for (int i : at) {
            b.append("<brk id=\"").append(i + 1).append("\" max=\"").append(max).append("\" man=\"1\"/>");
        }
        b.append("</").append(tag).append('>');
    }

    static String number(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    static void stopIfInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Conversion interrupted");
        }
        Admission.checkpoint();
    }
}

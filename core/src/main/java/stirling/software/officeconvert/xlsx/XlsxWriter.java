package stirling.software.officeconvert.xlsx;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import stirling.software.officeconvert.sheet.CellValue;
import stirling.software.officeconvert.sheet.Row;
import stirling.software.officeconvert.sheet.SheetXml;
import stirling.software.officeconvert.sheet.SpillBuffer;
import stirling.software.officeconvert.sheet.WorkbookSink;

public final class XlsxWriter implements WorkbookSink {

    static final int MAX_TEXT = 32_767;

    private static final float POINTS_PER_WIDTH_UNIT = 5.25f;

    private record SheetInfo(String name, List<WorkbookSink.NamedRange> names, int frozenRows) {}

    private final ZipOutputStream zip;
    private final SharedStrings strings = new SharedStrings();
    private final XlsxStyles styles = new XlsxStyles();
    private final List<SheetInfo> sheets = new ArrayList<>();
    private final StringBuilder sb = new StringBuilder(1 << 12);

    private final List<String> merges = new ArrayList<>();
    private SheetSetup setup;
    private SpillBuffer rows;
    private Writer rowOut;
    private int lastRow;
    private int lastCol;

    public XlsxWriter(OutputStream target) {
        this.zip = new ZipOutputStream(new BufferedOutputStream(new KeepOpen(target), 1 << 16), StandardCharsets.UTF_8);
        this.zip.setLevel(6);
    }

    @Override
    public void startSheet(SheetSetup sheet) throws IOException {
        if (setup != null) {
            throw new IllegalStateException("Sheet " + setup.name() + " is still open");
        }
        setup = sheet;
        rows = new SpillBuffer(4L << 20);
        rowOut = new BufferedWriter(new OutputStreamWriter(rows, StandardCharsets.UTF_8), 1 << 16);
        lastRow = -1;
        lastCol = -1;
        merges.clear();
    }

    @Override
    public void row(Row row) throws IOException {
        if (row.cells().isEmpty() && !row.fixedHeight()) {
            return;
        }
        sb.setLength(0);
        sb.append("<row r=\"").append(row.index() + 1).append('"');
        if (row.fixedHeight()) {
            sb.append(" ht=\"").append(height(row.height())).append("\" customHeight=\"1\"");
        }
        sb.append('>');
        for (Row.Cell c : row.cells()) {
            cell(c, row.index());
            lastCol = Math.max(lastCol, c.col() + c.colSpan() - 1);
            if (c.merged()) {
                merges.add(CellRefs.range(row.index(), c.col(), row.index() + c.rowSpan() - 1, c.col() + c.colSpan() - 1));
            }
        }
        sb.append("</row>");
        rowOut.write(sb.toString());
        lastRow = Math.max(lastRow, row.index());
    }

    private void cell(Row.Cell c, int r) throws IOException {
        int style = styles.index(c.style());
        CellValue v = c.value();
        sb.append("<c r=\"").append(CellRefs.cell(r, c.col())).append('"');
        if (style != 0) {
            sb.append(" s=\"").append(style).append('"');
        }
        if (v.isEmpty()) {
            sb.append("/>");
        } else if (v.isText()) {
            String text = v.text().length() > MAX_TEXT ? v.text().substring(0, MAX_TEXT) : v.text();
            sb.append(" t=\"s\"><v>").append(strings.add(text)).append("</v></c>");
        } else {
            sb.append("><v>").append(SheetXml.number(v.number())).append("</v></c>");
        }
    }

    @Override
    public void endSheet(SheetEnd end) throws IOException {
        rowOut.flush();
        int n = sheets.size() + 1;
        zip.putNextEntry(new ZipEntry("xl/worksheets/sheet" + n + ".xml"));
        int cols = Math.max(lastCol + 1, end.columnWidths().size());
        StringBuilder head = new StringBuilder(1024);
        head.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                        + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
                .append("<sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>")
                .append("<dimension ref=\"")
                .append(lastRow < 0 ? "A1" : CellRefs.range(0, 0, lastRow, Math.max(0, cols - 1))).append("\"/>");
        sheetView(head, end.frozenRows(), sheets.isEmpty());
        head.append("<sheetFormatPr defaultRowHeight=\"15\"/>");
        if (!end.columnWidths().isEmpty()) {
            head.append("<cols>");
            for (int c = 0; c < end.columnWidths().size(); c++) {
                head.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"")
                        .append(width(end.columnWidths().get(c))).append("\" customWidth=\"1\"/>");
            }
            head.append("</cols>");
        }
        head.append(lastRow < 0 ? "<sheetData/>" : "<sheetData>");
        zip.write(head.toString().getBytes(StandardCharsets.UTF_8));
        rows.writeTo(zip);
        StringBuilder tail = new StringBuilder(1024);
        if (lastRow >= 0) {
            tail.append("</sheetData>");
        }
        if (!merges.isEmpty()) {
            tail.append("<mergeCells count=\"").append(merges.size()).append("\">");
            for (String m : merges) {
                tail.append("<mergeCell ref=\"").append(m).append("\"/>");
            }
            tail.append("</mergeCells>");
        }
        tail.append("<pageMargins left=\"0.5\" right=\"0.5\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>")
                .append("<pageSetup paperSize=\"").append(setup.a4() ? 9 : 1).append("\" orientation=\"")
                .append(setup.landscape() ? "landscape" : "portrait").append("\" fitToWidth=\"1\" fitToHeight=\"0\"/>");
        headerFooter(tail, setup.header(), setup.footer());
        if (lastRow >= 0) {
            tail.append("<ignoredErrors><ignoredError sqref=\"")
                    .append(CellRefs.range(0, 0, lastRow, Math.max(0, cols - 1)))
                    .append("\" numberStoredAsText=\"1\"/></ignoredErrors>");
        }
        tail.append("</worksheet>");
        zip.write(tail.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        sheets.add(new SheetInfo(setup.name(), end.names(), end.frozenRows()));
        rows.close();
        rows = null;
        rowOut = null;
        setup = null;
    }

    private static void sheetView(StringBuilder sb, int frozen, boolean first) {
        sb.append("<sheetViews><sheetView");
        if (first) {
            sb.append(" tabSelected=\"1\"");
        }
        sb.append(" workbookViewId=\"0\"");
        if (frozen <= 0) {
            sb.append("/></sheetViews>");
            return;
        }
        String top = "A" + (frozen + 1);
        sb.append("><pane ySplit=\"").append(frozen).append("\" topLeftCell=\"").append(top)
                .append("\" activePane=\"bottomLeft\" state=\"frozen\"/><selection pane=\"bottomLeft\" activeCell=\"")
                .append(top).append("\" sqref=\"").append(top).append("\"/></sheetView></sheetViews>");
    }

    private static void headerFooter(StringBuilder sb, String header, String footer) {
        boolean h = header != null && !header.isBlank();
        boolean f = footer != null && !footer.isBlank();
        if (!h && !f) {
            return;
        }
        sb.append("<headerFooter>");
        if (h) {
            sb.append("<oddHeader>");
            SheetXml.escape(sb, "&C" + codeText(header));
            sb.append("</oddHeader>");
        }
        if (f) {
            sb.append("<oddFooter>");
            SheetXml.escape(sb, "&C" + codeText(footer));
            sb.append("</oddFooter>");
        }
        sb.append("</headerFooter>");
    }

    private static String codeText(String s) {
        String t = s.replace("&", "&&").replace('\n', ' ');
        return t.length() > 240 ? t.substring(0, 240) : t;
    }

    private static String width(float points) {
        float w = Math.max(0.5f, Math.min(255f, points / POINTS_PER_WIDTH_UNIT));
        return String.format(Locale.ROOT, "%.2f", w);
    }

    private static String height(float points) {
        float h = Math.max(1f, Math.min(409f, points));
        return String.format(Locale.ROOT, "%.2f", h);
    }

    @Override
    public void finish(String title, String author) throws IOException {
        if (setup != null) {
            throw new IllegalStateException("Sheet " + setup.name() + " is still open");
        }
        if (sheets.isEmpty()) {
            startSheet(new SheetSetup("Sheet1", false, true, "", ""));
            endSheet(new SheetEnd(List.of(), 0, List.of()));
        }
        entry("xl/sharedStrings.xml", null);
        strings.writeTo(zip);
        zip.closeEntry();
        entry("xl/styles.xml", styles.xml());
        entry("xl/workbook.xml", XlsxParts.workbook(names(), definedNames()));
        entry("xl/_rels/workbook.xml.rels", XlsxParts.workbookRels(sheets.size()));
        entry("[Content_Types].xml", XlsxParts.contentTypes(sheets.size()));
        entry("_rels/.rels", XlsxParts.rootRels());
        entry("docProps/core.xml", XlsxParts.core(title, author));
        entry("docProps/app.xml", XlsxParts.app());
        zip.finish();
    }

    private List<String> names() {
        return sheets.stream().map(SheetInfo::name).toList();
    }

    private List<String[]> definedNames() {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < sheets.size(); i++) {
            SheetInfo s = sheets.get(i);
            if (s.frozenRows() > 0) {
                String sheet = "'" + s.name().replace("'", "''") + "'!";
                out.add(new String[] {"_xlnm.Print_Titles", sheet + "$1:$" + s.frozenRows(), Integer.toString(i)});
            }
        }
        for (SheetInfo s : sheets) {
            for (WorkbookSink.NamedRange n : s.names()) {
                if (seen.add(n.name().toLowerCase(Locale.ROOT))) {
                    out.add(new String[] {n.name(),
                        CellRefs.absolute(s.name(), n.firstRow(), n.firstCol(), n.lastRow(), n.lastCol())});
                }
            }
        }
        out.sort(Comparator.comparing((String[] n) -> n[0].toUpperCase(Locale.ROOT))
                .thenComparingInt(n -> n.length > 2 ? Integer.parseInt(n[2]) : -1));
        return out;
    }

    private void entry(String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        if (content != null) {
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    @Override
    public void close() throws IOException {
        try {
            zip.close();
        } finally {
            try {
                strings.close();
            } finally {
                if (rows != null) {
                    rows.close();
                }
            }
        }
    }

    private static final class KeepOpen extends FilterOutputStream {

        KeepOpen(OutputStream target) {
            super(target);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            out.flush();
        }
    }
}

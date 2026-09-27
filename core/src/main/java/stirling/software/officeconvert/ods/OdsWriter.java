package stirling.software.officeconvert.ods;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import stirling.software.officeconvert.sheet.CellValue;
import stirling.software.officeconvert.sheet.NumberFormat;
import stirling.software.officeconvert.sheet.Row;
import stirling.software.officeconvert.sheet.SheetXml;
import stirling.software.officeconvert.sheet.SpillBuffer;
import stirling.software.officeconvert.sheet.WorkbookSink;

public final class OdsWriter implements WorkbookSink {

    static final String MIME = "application/vnd.oasis.opendocument.spreadsheet";

    private static final LocalDate EPOCH = LocalDate.of(1899, 12, 30);

    private record SheetInfo(String name, int frozen) {}

    private final ZipOutputStream zip;
    private final OdsStyles styles = new OdsStyles();
    private final SpillBuffer body = new SpillBuffer(8L << 20);
    private final Writer bodyOut = new BufferedWriter(new OutputStreamWriter(body, StandardCharsets.UTF_8), 1 << 16);
    private final List<SheetInfo> sheets = new ArrayList<>();
    private final List<String> namedRanges = new ArrayList<>();
    private final Set<String> rangeNames = new HashSet<>();
    private final StringBuilder sb = new StringBuilder(1 << 12);

    private final List<int[]> active = new ArrayList<>();

    private SheetSetup setup;
    private SpillBuffer rows;
    private Writer rowOut;
    private int nextRow;

    public OdsWriter(OutputStream target) throws IOException {
        this.zip = new ZipOutputStream(new BufferedOutputStream(new KeepOpen(target), 1 << 16), StandardCharsets.UTF_8);
        zip.setLevel(6);
        byte[] mime = MIME.getBytes(StandardCharsets.US_ASCII);
        ZipEntry e = new ZipEntry("mimetype");
        e.setMethod(ZipEntry.STORED);
        e.setSize(mime.length);
        e.setCompressedSize(mime.length);
        CRC32 crc = new CRC32();
        crc.update(mime);
        e.setCrc(crc.getValue());
        zip.putNextEntry(e);
        zip.write(mime);
        zip.closeEntry();
    }

    @Override
    public void startSheet(SheetSetup sheet) throws IOException {
        if (setup != null) {
            throw new IllegalStateException("Sheet " + setup.name() + " is still open");
        }
        setup = sheet;
        rows = new SpillBuffer(4L << 20);
        rowOut = new BufferedWriter(new OutputStreamWriter(rows, StandardCharsets.UTF_8), 1 << 16);
        nextRow = 0;
        active.clear();
    }

    @Override
    public void row(Row row) throws IOException {
        while (nextRow < row.index()) {
            writeRow(nextRow++, Float.NaN, List.of());
        }
        writeRow(row.index(), row.height(), row.cells());
        nextRow = row.index() + 1;
    }

    private void writeRow(int r, float height, List<Row.Cell> cells) throws IOException {
        sb.setLength(0);
        sb.append("<table:table-row table:style-name=\"").append(styles.row(height)).append("\">");
        int col = 0;
        int blanks = 0;
        for (Row.Cell c : cells) {
            if (c.col() < col) {
                continue;
            }
            for (; col < c.col(); col++) {
                blanks = gap(r, col, blanks);
            }
            blanks = flushBlanks(blanks);
            if (covered(r, col)) {
                sb.append("<table:covered-table-cell table:style-name=\"").append(styles.cell(c.style())).append("\"/>");
            } else {
                cell(c);
                if (c.merged()) {
                    active.add(new int[] {r, c.col(), r + c.rowSpan() - 1, c.col() + c.colSpan() - 1});
                }
            }
            col++;
        }
        int reach = -1;
        for (int[] m : active) {
            if (m[0] <= r && r <= m[2]) {
                reach = Math.max(reach, m[3]);
            }
        }
        for (; col <= reach; col++) {
            blanks = gap(r, col, blanks);
        }
        if (col == 0) {
            sb.append("<table:table-cell/>");
        }
        sb.append("</table:table-row>");
        rowOut.write(sb.toString());
        active.removeIf(m -> m[2] <= r);
    }

    private int gap(int r, int col, int blanks) {
        if (!covered(r, col)) {
            return blanks + 1;
        }
        flushBlanks(blanks);
        sb.append("<table:covered-table-cell/>");
        return 0;
    }

    private boolean covered(int r, int col) {
        for (int[] m : active) {
            if (m[0] <= r && r <= m[2] && m[1] <= col && col <= m[3] && !(m[0] == r && m[1] == col)) {
                return true;
            }
        }
        return false;
    }

    private int flushBlanks(int blanks) {
        if (blanks == 1) {
            sb.append("<table:table-cell/>");
        } else if (blanks > 1) {
            sb.append("<table:table-cell table:number-columns-repeated=\"").append(blanks).append("\"/>");
        }
        return 0;
    }

    private void cell(Row.Cell c) {
        CellValue v = c.value();
        sb.append("<table:table-cell table:style-name=\"").append(styles.cell(c.style())).append('"');
        if (c.merged()) {
            sb.append(" table:number-columns-spanned=\"").append(c.colSpan()).append("\" table:number-rows-spanned=\"")
                    .append(c.rowSpan()).append('"');
        }
        if (v.isEmpty()) {
            sb.append("/>");
            return;
        }
        switch (v.kind()) {
            case NUMBER -> sb.append(v.format().kind() == NumberFormat.Kind.PERCENT
                            ? " office:value-type=\"percentage\" office:value=\""
                            : " office:value-type=\"float\" office:value=\"")
                    .append(SheetXml.number(v.number())).append('"');
            case DATE -> dateValue(v);
            default -> sb.append(" office:value-type=\"string\"");
        }
        sb.append('>');
        paragraphs(v.text());
        sb.append("</table:table-cell>");
    }

    private void dateValue(CellValue v) {
        long days = (long) Math.floor(v.number());
        long seconds = Math.round((v.number() - days) * 86400);
        if (seconds >= 86400) {
            days++;
            seconds -= 86400;
        }
        String time = String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        if (!v.format().hasDay()) {
            sb.append(" office:value-type=\"time\" office:time-value=\"PT").append(seconds / 3600).append('H')
                    .append(seconds / 60 % 60).append('M').append(seconds % 60).append("S\"");
            return;
        }
        sb.append(" office:value-type=\"date\" office:date-value=\"").append(EPOCH.plusDays(days));
        if (v.format().hasTime()) {
            sb.append('T').append(time);
        }
        sb.append('"');
    }

    private void paragraphs(String text) {
        for (String line : text.split("\n", -1)) {
            sb.append("<text:p>");
            int spaces = 0;
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (ch == ' ') {
                    spaces++;
                    continue;
                }
                spaces = flushSpaces(spaces, i - spaces == 0);
                if (ch == '\t') {
                    sb.append("<text:tab/>");
                } else {
                    SheetXml.escape(sb, String.valueOf(ch));
                }
            }
            flushSpaces(spaces, true);
            sb.append("</text:p>");
        }
    }

    private int flushSpaces(int n, boolean edge) {
        if (n == 0) {
            return 0;
        }
        if (n == 1 && !edge) {
            sb.append(' ');
        } else if (edge) {
            sb.append("<text:s").append(n > 1 ? " text:c=\"" + n + "\"" : "").append("/>");
        } else {
            sb.append(' ').append("<text:s").append(n > 2 ? " text:c=\"" + (n - 1) + "\"" : "").append("/>");
        }
        return 0;
    }

    @Override
    public void endSheet(SheetEnd end) throws IOException {
        rowOut.flush();
        String table = styles.table(setup.landscape(), setup.a4(), setup.header(), setup.footer());
        StringBuilder head = new StringBuilder(512);
        head.append("<table:table table:name=\"");
        SheetXml.escape(head, setup.name());
        head.append("\" table:style-name=\"").append(table).append("\">");
        for (float w : end.columnWidths()) {
            head.append("<table:table-column table:style-name=\"").append(styles.column(w))
                    .append("\" table:default-cell-style-name=\"Default\"/>");
        }
        if (end.columnWidths().isEmpty()) {
            head.append("<table:table-column table:default-cell-style-name=\"Default\"/>");
        }
        bodyOut.write(head.toString());
        bodyOut.flush();
        rows.writeTo(body);
        if (nextRow == 0) {
            bodyOut.write("<table:table-row><table:table-cell/></table:table-row>");
        }
        bodyOut.write("</table:table>");
        for (NamedRange n : end.names()) {
            if (rangeNames.add(n.name().toLowerCase(Locale.ROOT))) {
                namedRanges.add(namedRange(setup.name(), n));
            }
        }
        sheets.add(new SheetInfo(setup.name(), end.frozenRows()));
        rows.close();
        rows = null;
        rowOut = null;
        setup = null;
    }

    private static String namedRange(String sheet, NamedRange n) {
        String quoted = "$'" + sheet.replace("'", "''") + "'.";
        StringBuilder r = new StringBuilder("<table:named-range table:name=\"");
        SheetXml.escape(r, n.name());
        r.append("\" table:base-cell-address=\"");
        SheetXml.escape(r, quoted + "$A$1");
        r.append("\" table:cell-range-address=\"");
        SheetXml.escape(r, quoted + ref(n.firstRow(), n.firstCol()) + ":." + ref(n.lastRow(), n.lastCol()));
        return r.append("\"/>").toString();
    }

    private static String ref(int row, int col) {
        StringBuilder letters = new StringBuilder();
        for (int n = col + 1; n > 0; n = (n - 1) / 26) {
            letters.insert(0, (char) ('A' + (n - 1) % 26));
        }
        return "$" + letters + "$" + (row + 1);
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
        bodyOut.flush();
        zip.putNextEntry(new ZipEntry("content.xml"));
        zip.write(OdsParts.contentHead(styles.contentStyles()).getBytes(StandardCharsets.UTF_8));
        body.writeTo(zip);
        zip.write(OdsParts.contentTail(namedRanges).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        entry("styles.xml", OdsParts.styles(styles.pageLayouts(), styles.masterPages()));
        entry("meta.xml", OdsParts.meta(title, author));
        entry("settings.xml", OdsParts.settings(sheets.stream().map(s -> new String[] {s.name(),
            Integer.toString(s.frozen())}).toList()));
        entry("META-INF/manifest.xml", OdsParts.manifest());
        zip.finish();
    }

    private void entry(String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Override
    public void close() throws IOException {
        try {
            zip.close();
        } finally {
            try {
                body.close();
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

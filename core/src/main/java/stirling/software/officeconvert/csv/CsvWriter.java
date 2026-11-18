package stirling.software.officeconvert.csv;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import stirling.software.officeconvert.sheet.CellValue;
import stirling.software.officeconvert.sheet.Row;
import stirling.software.officeconvert.sheet.ScriptMarks;
import stirling.software.officeconvert.sheet.SheetXml;
import stirling.software.officeconvert.sheet.WorkbookSink;
import stirling.software.officeconvert.sink.SpillBuffer;

// The workbook reduced to cell values: RFC 4180 rows, a blank line between tables, no styles, merges or pictures.
public final class CsvWriter implements WorkbookSink {

    private static final String EOL = "\r\n";

    private static final Pattern SIGNED_NUMBER = Pattern.compile("[+-]\\d[\\d,.\\s]*%?");

    private final Writer out;
    private SpillBuffer sheet;
    private boolean anyRecord;

    public CsvWriter(OutputStream stream) {
        this.out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
    }

    @Override
    public void startSheet(SheetSetup setup) throws IOException {
        closeSheet(List.of());
        sheet = new SpillBuffer();
    }

    @Override
    public void row(Row row) throws IOException {
        if (row.cells().isEmpty()) {
            return;
        }
        if (sheet == null) {
            sheet = new SpillBuffer();
        }
        TreeMap<Integer, String> byColumn = new TreeMap<>();
        for (Row.Cell c : row.cells()) {
            String value = text(c.value());
            byColumn.merge(c.col(), value, (held, next) -> held.isEmpty() ? next : held);
        }
        StringBuilder sb = new StringBuilder();
        int col = 0;
        for (Map.Entry<Integer, String> e : byColumn.entrySet()) {
            while (col < e.getKey()) {
                sb.append(col++ == 0 ? "" : ",");
            }
            if (col > 0) {
                sb.append(',');
            }
            field(sb, e.getValue().replace("\0", ""));
            col++;
        }
        sheet.append(sb);
        sheet.placeholder(row.index() + ":" + col);
        sheet.append(EOL);
    }

    static String text(CellValue v) {
        if (v.isEmpty()) {
            return "";
        }
        if (v.kind() == CellValue.Kind.NUMBER) {
            return v.text().isEmpty() ? SheetXml.number(v.number()) : ScriptMarks.strip(v.text());
        }
        if (!v.text().isEmpty()) {
            return inert(ScriptMarks.strip(v.text()));
        }
        return SheetXml.number(v.number());
    }

    private static String inert(String s) {
        if (s.isEmpty() || "=+-@\t\r".indexOf(s.charAt(0)) < 0 || s.equals("-") || s.equals("+") || SIGNED_NUMBER.matcher(s).matches()) {
            return s;
        }
        return "'" + s;
    }

    static void field(StringBuilder sb, String s) {
        boolean quote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0
                || !s.isEmpty() && (Character.isWhitespace(s.charAt(0)) || Character.isWhitespace(s.charAt(s.length() - 1)));
        if (!quote) {
            sb.append(s);
            return;
        }
        sb.append('"').append(s.replace("\"", "\"\"")).append('"');
    }

    @Override
    public void endSheet(SheetEnd end) throws IOException {
        closeSheet(end.names());
    }

    private void closeSheet(List<NamedRange> tables) throws IOException {
        if (sheet == null) {
            return;
        }
        try (SpillBuffer records = sheet) {
            sheet = null;
            if (records.isEmpty()) {
                return;
            }
            if (anyRecord) {
                out.write(EOL);
            }
            anyRecord = true;
            records.copyTo(out, key -> padding(key, tables));
        }
    }

    private static String padding(String key, List<NamedRange> tables) {
        int split = key.indexOf(':');
        int row = Integer.parseInt(key, 0, split, 10);
        int fields = Integer.parseInt(key, split + 1, key.length(), 10);
        for (NamedRange t : tables) {
            if (row >= t.firstRow() && row <= t.lastRow() && t.lastCol() + 1 > fields) {
                return ",".repeat(t.lastCol() + 1 - fields);
            }
        }
        return "";
    }

    @Override
    public void finish(String title, String author) throws IOException {
        closeSheet(List.of());
        out.flush();
    }

    @Override
    public void close() throws IOException {
        try {
            if (sheet != null) {
                sheet.close();
                sheet = null;
            }
        } finally {
            out.flush();
        }
    }
}

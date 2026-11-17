package stirling.software.officeconvert.csv;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import stirling.software.officeconvert.sheet.CellValue;
import stirling.software.officeconvert.sheet.Row;
import stirling.software.officeconvert.sheet.ScriptMarks;
import stirling.software.officeconvert.sheet.SheetXml;
import stirling.software.officeconvert.sheet.WorkbookSink;

// The workbook reduced to cell values: RFC 4180 rows, a blank line between tables, no styles, merges or pictures.
public final class CsvWriter implements WorkbookSink {

    private static final String EOL = "\r\n";

    private final Writer out;
    private boolean anySheet;
    private boolean sheetHasRows;

    public CsvWriter(OutputStream stream) {
        this.out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
    }

    @Override
    public void startSheet(SheetSetup setup) throws IOException {
        if (anySheet && sheetHasRows) {
            out.write(EOL);
        }
        anySheet = true;
        sheetHasRows = false;
    }

    @Override
    public void row(Row row) throws IOException {
        if (row.cells().isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        int col = 0;
        for (Row.Cell c : row.cells()) {
            while (col < c.col()) {
                sb.append(col++ == 0 ? "" : ",");
            }
            if (col > 0) {
                sb.append(',');
            }
            field(sb, text(c.value()));
            col++;
        }
        out.write(sb.append(EOL).toString());
        sheetHasRows = true;
    }

    static String text(CellValue v) {
        if (v.isEmpty()) {
            return "";
        }
        if (!v.text().isEmpty()) {
            return ScriptMarks.strip(v.text());
        }
        return SheetXml.number(v.number());
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
    public void endSheet(SheetEnd end) {
    }

    @Override
    public void finish(String title, String author) throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        out.flush();
    }
}

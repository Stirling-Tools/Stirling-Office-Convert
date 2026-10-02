package stirling.software.officeconvert.topdf.text;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.zip.Deflater;
import java.util.zip.ZipOutputStream;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.topdf.font.FontLibrary;

public final class CsvPackage {

    static final int MAX_ROWS = 1_048_576;

    static final int ROWS_PER_PAGE = 53;

    private CsvPackage() {}

    private record Scan(int rows, boolean more, boolean clipped, ColumnWidths widths) {}

    public static long estimate(long bytes) {
        long v = Admission.BASE_BYTES + Math.max(0, bytes) * 2;
        return v < 0 ? Long.MAX_VALUE : v;
    }

    public static Converted write(Path source, OutputStream out, char separator, String sheetName, int maxPages,
            FontLibrary fonts) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(fonts, "fonts");
        TextEncoding encoding = TextEncoding.detect(source);
        Scan scan = scan(source, encoding, separator, new ColumnWidths(fonts));
        int bands = SheetXml.bands(scan.widths());
        int rows = scan.rows();
        boolean cutAtPages = false;
        if (maxPages > 0) {
            long cap = (Math.ceilDiv((long) maxPages, bands) + 1) * ROWS_PER_PAGE;
            if (cap < rows) {
                rows = (int) cap;
                cutAtPages = true;
            }
        }
        ZipOutputStream zip = new ZipOutputStream(Parts.keepOpen(out));
        zip.setLevel(Deflater.BEST_SPEED);
        SheetXml.packageParts(zip, sheetName == null || sheetName.isBlank() ? "Sheet1" : sheetName);
        try (Reader in = encoding.open(source)) {
            Writer w = Parts.open(zip, "xl/worksheets/sheet1.xml");
            SheetXml sheet = new SheetXml(w, scan.widths());
            sheet.start();
            CsvReader csv = new CsvReader(in, separator);
            List<String> row = new ArrayList<>();
            for (int r = 0; r < rows && csv.next(row); r++) {
                if ((r & 0xFFF) == 0 && Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Interrupted while reading the table");
                }
                sheet.row(r, row);
            }
            sheet.end();
            w.flush();
        }
        zip.closeEntry();
        zip.finish();
        zip.flush();
        List<String> warnings = new ArrayList<>();
        if (cutAtPages) {
            warnings.add("Only the first " + rows + " rows were converted: the rest is past the page limit of "
                    + maxPages + " pages");
        } else if (scan.more()) {
            warnings.add("Only the first " + MAX_ROWS + " rows were converted, the most a sheet holds");
        }
        if (scan.clipped()) {
            warnings.add("Some cells or columns were cut short: a cell holds at most " + CsvReader.MAX_CELL_CHARS
                    + " characters and a row " + CsvReader.MAX_COLUMNS + " columns");
        }
        return new Converted(warnings, cutAtPages || scan.more() || scan.clipped());
    }

    private static Scan scan(Path source, TextEncoding encoding, char separator, ColumnWidths widths)
            throws IOException {
        int rows = 0;
        boolean more = false;
        boolean clipped;
        try (Reader in = encoding.open(source)) {
            CsvReader csv = new CsvReader(in, separator);
            List<String> row = new ArrayList<>();
            while (csv.next(row)) {
                if (rows == MAX_ROWS) {
                    more = true;
                    break;
                }
                if ((rows & 0xFFF) == 0 && Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Interrupted while reading the table");
                }
                for (int c = 0; c < row.size(); c++) {
                    widths.add(c, CellValue.of(row.get(c)).text());
                }
                rows++;
            }
            clipped = csv.clipped();
        }
        return new Scan(rows, more, clipped, widths);
    }
}

package stirling.software.officeconvert.topdf.text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.List;

final class CsvReader {

    static final int MAX_CELL_CHARS = 32_767;

    static final int MAX_COLUMNS = 16_384;

    static final int MAX_ROW_CHARS = 1 << 22;

    static final int MAX_QUOTED_CHARS = 1 << 20;

    private static final int EOF = -1;

    private static final int UNCLOSED = Integer.MIN_VALUE;

    private final BufferedReader in;

    private final char sep;

    private final StringBuilder field = new StringBuilder();

    private int rowChars;

    private boolean clipped;

    private boolean ended;

    CsvReader(Reader in, char sep) {
        this.in = new BufferedReader(in, 1 << 16);
        this.sep = sep;
    }

    boolean clipped() {
        return clipped;
    }

    boolean next(List<String> row) throws IOException {
        row.clear();
        rowChars = 0;
        if (ended) {
            return false;
        }
        int c = in.read();
        if (c == EOF) {
            ended = true;
            return false;
        }
        while (true) {
            int end = field(c);
            add(row);
            if (end != sep) {
                lineEnd(end);
                return true;
            }
            c = in.read();
            if (c == EOF || c == '\n' || c == '\r') {
                field.setLength(0);
                add(row);
                lineEnd(c);
                return true;
            }
        }
    }

    private void lineEnd(int c) throws IOException {
        if (c == EOF) {
            ended = true;
        } else if (c == '\r') {
            in.mark(1);
            if (in.read() != '\n') {
                in.reset();
            }
        }
    }

    private void add(List<String> row) {
        if (row.size() < MAX_COLUMNS) {
            row.add(field.toString());
        } else {
            clipped = true;
        }
    }

    private int field(int c) throws IOException {
        field.setLength(0);
        int spaces = 0;
        while (c == ' ') {
            spaces++;
            c = in.read();
        }
        if (c == '"') {
            in.mark(MAX_QUOTED_CHARS + 2);
            int before = rowChars;
            int end = quoted();
            if (end != UNCLOSED) {
                return end;
            }
            in.reset();
            rowChars = before;
            field.setLength(0);
            c = '"';
        }
        for (int i = 0; i < spaces; i++) {
            append(' ');
        }
        return plain(c);
    }

    private int plain(int c) throws IOException {
        while (c != EOF && c != sep && c != '\n' && c != '\r') {
            append(c);
            c = in.read();
        }
        return c;
    }

    private int quoted() throws IOException {
        int seen = 0;
        boolean afterCr = false;
        while (true) {
            int c = in.read();
            if (c == EOF || ++seen > MAX_QUOTED_CHARS) {
                return UNCLOSED;
            }
            if (c == '\n' && afterCr) {
                afterCr = false;
                continue;
            }
            afterCr = c == '\r';
            if (c != '"') {
                append(afterCr ? '\n' : c);
                continue;
            }
            int next = in.read();
            seen++;
            if (next == '"') {
                append('"');
                continue;
            }
            if (next == EOF || next == sep || next == '\n' || next == '\r') {
                return next;
            }
            String inner = field.toString().replace("\"", "\"\"");
            field.setLength(0);
            append('"');
            for (int i = 0; i < inner.length(); i++) {
                append(inner.charAt(i));
            }
            append('"');
            return plain(next);
        }
    }

    private void append(int c) {
        if (field.length() < MAX_CELL_CHARS && rowChars < MAX_ROW_CHARS) {
            field.append((char) c);
            rowChars++;
        } else {
            clipped = true;
        }
    }
}

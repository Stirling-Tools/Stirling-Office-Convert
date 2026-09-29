package stirling.software.officeconvert.topdf.xlsx;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.apache.poi.ooxml.POIXMLTypeLoader;
import org.apache.xmlbeans.XmlException;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.CTWorksheet;
import org.openxmlformats.schemas.spreadsheetml.x2006.main.WorksheetDocument;

import stirling.software.officeconvert.topdf.RenderJob;
import stirling.software.officeconvert.topdf.io.SecureXml;

// Reads a worksheet part twice as a stream, so a sheet of hundreds of megabytes is never held in memory whole
final class WorksheetReader {

    interface RowSink {
        void row(RawRow row) throws IOException;
    }

    interface Source {
        InputStream open() throws IOException;
    }

    private static final byte[] NAME = "sheetData".getBytes(StandardCharsets.US_ASCII);

    private static final int CHUNK = 1 << 16;

    private static final int KEEP = 128;

    private final Source source;

    private final CTWorksheet skeleton;

    private boolean damaged;

    WorksheetReader(Source source) throws IOException {
        this.source = source;
        this.skeleton = skeleton(source);
    }

    WorksheetReader(byte[] xml) throws IOException {
        this(() -> new ByteArrayInputStream(xml));
    }

    CTWorksheet skeleton() {
        return skeleton;
    }

    boolean damaged() {
        return damaged;
    }

    void rows(RowSink sink, RenderJob job) throws IOException {
        try (InputStream in = source.open()) {
            XMLStreamReader r;
            try {
                r = SecureXml.reader(in);
            } catch (IOException e) {
                damaged = true;
                return;
            }
            try {
                boolean inData = false;
                int nextRow = 0;
                int count = 0;
                while (r.hasNext()) {
                    int ev = r.next();
                    if (ev == XMLStreamConstants.START_ELEMENT) {
                        String name = r.getLocalName();
                        if (name.equals("sheetData")) {
                            inData = true;
                        } else if (inData && name.equals("row")) {
                            int index = index(RunProps.attr(r, "r"), nextRow + 1) - 1;
                            nextRow = index + 1;
                            RawRow row = row(r, index);
                            if (index >= 0 && index < Grid.MAX_ROWS) {
                                sink.row(row);
                            }
                            if ((++count & 255) == 0) {
                                job.checkpoint();
                            }
                        }
                    } else if (ev == XMLStreamConstants.END_ELEMENT && inData
                            && r.getLocalName().equals("sheetData")) {
                        break;
                    }
                }
            } catch (XMLStreamException | RuntimeException e) {
                job.checkpoint();
                damaged = true;
            } finally {
                close(r);
            }
        }
    }

    private static RawRow row(XMLStreamReader r, int index) throws XMLStreamException {
        double ht = number(RunProps.attr(r, "ht"));
        boolean hidden = flag(RunProps.attr(r, "hidden"));
        int edges = (flag(RunProps.attr(r, "thickTop")) ? 1 : 0) + (flag(RunProps.attr(r, "thickBot")) ? 1 : 0);
        int style = flag(RunProps.attr(r, "customFormat")) ? (int) Math.max(-1, number(RunProps.attr(r, "s"), -1)) : -1;
        List<RawRow.Cell> cells = new ArrayList<>();
        int nextCol = 0;
        int depth = 1;
        while (r.hasNext() && depth > 0) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                if (depth == 1 && r.getLocalName().equals("c")) {
                    int col = column(RunProps.attr(r, "r"), nextCol);
                    nextCol = col + 1;
                    RawRow.Cell c = cell(r, col);
                    if (col >= 0 && col < Columns.MAX) {
                        cells.add(c);
                    }
                } else {
                    depth++;
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        return new RawRow(index, ht, hidden, style, cells, edges);
    }

    private static RawRow.Cell cell(XMLStreamReader r, int col) throws XMLStreamException {
        int style = (int) Math.max(0, number(RunProps.attr(r, "s"), 0));
        String type = RunProps.attr(r, "t");
        String value = null;
        RichText inline = null;
        int depth = 1;
        while (r.hasNext() && depth > 0) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String name = r.getLocalName();
                if (depth == 1 && name.equals("v")) {
                    value = r.getElementText();
                } else if (depth == 1 && name.equals("is")) {
                    inline = RichText.read(r);
                } else {
                    RichText.skip(r);
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        return new RawRow.Cell(col, style, type, value, inline);
    }

    static int column(String ref, int fallback) {
        if (ref == null || ref.isEmpty()) {
            return fallback;
        }
        int col = 0;
        int i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i)) && i < 4) {
            col = col * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
        }
        return i == 0 ? fallback : col - 1;
    }

    private static int index(String v, int fallback) {
        double d = number(v, fallback);
        return d >= 1 && d <= Grid.MAX_ROWS ? (int) d : fallback;
    }

    private static double number(String v) {
        return number(v, Double.NaN);
    }

    private static double number(String v, double fallback) {
        if (v == null) {
            return fallback;
        }
        try {
            double d = Double.parseDouble(v.trim());
            return Double.isFinite(d) ? d : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean flag(String v) {
        return v != null && (v.equals("1") || v.equalsIgnoreCase("true"));
    }

    static boolean close(XMLStreamReader r) {
        try {
            r.close();
            return true;
        } catch (XMLStreamException e) {
            return false;
        }
    }

    static CTWorksheet skeleton(Source source) throws IOException {
        byte[] shell;
        try (InputStream in = source.open()) {
            shell = withoutData(in);
        }
        if (shell == null) {
            return CTWorksheet.Factory.newInstance();
        }
        try {
            return WorksheetDocument.Factory.parse(new ByteArrayInputStream(shell),
                    POIXMLTypeLoader.DEFAULT_XML_OPTIONS).getWorksheet();
        } catch (IOException | XmlException | RuntimeException e) {
            return CTWorksheet.Factory.newInstance();
        }
    }

    static byte[] withoutData(byte[] xml) {
        try {
            return withoutData(new ByteArrayInputStream(xml));
        } catch (IOException e) {
            return null;
        }
    }

    // The part without the rows inside sheetData; only the bytes around the rows are kept while it streams by
    static byte[] withoutData(InputStream in) throws IOException {
        Bytes shell = new Bytes();
        int open = -1;
        int scanned = 0;
        while (open < 0) {
            if (!shell.fill(in)) {
                return shell.size < (8 << 20) ? shell.array() : null;
            }
            for (; scanned + NAME.length < shell.size && open < 0; scanned++) {
                open = tagAt(shell.data, scanned, false);
            }
        }
        int tagEnd = shell.indexOf((byte) '>', open);
        while (tagEnd < 0) {
            int from = shell.size;
            if (!shell.fill(in)) {
                return null;
            }
            tagEnd = shell.indexOf((byte) '>', from);
        }
        if (shell.data[tagEnd - 1] == '/') {
            shell.rest(in);
            return shell.array();
        }
        Bytes window = new Bytes();
        window.write(shell.data, tagEnd + 1, shell.size - tagEnd - 1);
        shell.size = tagEnd;
        shell.write(new byte[] {'/', '>'}, 0, 2);
        int close = -1;
        scanned = 0;
        while (close < 0) {
            for (; scanned + NAME.length < window.size && close < 0; scanned++) {
                close = tagAt(window.data, scanned, true);
            }
            if (close >= 0) {
                break;
            }
            if (window.size > CHUNK * 4) {
                scanned -= window.keepLast(KEEP);
            }
            if (!window.fill(in)) {
                return null;
            }
        }
        window.keepLast(window.size - close);
        window.rest(in);
        int last = findLastClose(window.data, window.size, 0);
        int closeEnd = window.indexOf((byte) '>', last);
        if (closeEnd < 0) {
            return null;
        }
        shell.write(window.data, closeEnd + 1, window.size - closeEnd - 1);
        return shell.array();
    }

    private static int findLastClose(byte[] xml, int size, int from) {
        for (int i = size - NAME.length - 1; i >= from; i--) {
            int tag = tagAt(xml, i, true);
            if (tag >= from) {
                return tag;
            }
        }
        return -1;
    }

    private static int tagAt(byte[] xml, int i, boolean closing) {
        if (xml[i] != 's' || !matches(xml, i)) {
            return -1;
        }
        byte after = xml[i + NAME.length];
        if (!(after == '>' || after == '/' || after == ' ' || after == '\t' || after == '\r' || after == '\n')) {
            return -1;
        }
        int j = i - 1;
        if (j >= 0 && xml[j] == ':') {
            j--;
            while (j >= 0 && xml[j] != '<' && xml[j] != '/' && xml[j] > ' ' && i - j < 64) {
                j--;
            }
        }
        if (closing) {
            return j >= 1 && xml[j] == '/' && xml[j - 1] == '<' ? j - 1 : -1;
        }
        return j >= 0 && xml[j] == '<' ? j : -1;
    }

    private static boolean matches(byte[] xml, int at) {
        for (int k = 0; k < NAME.length; k++) {
            if (xml[at + k] != NAME[k]) {
                return false;
            }
        }
        return true;
    }

    private static final class Bytes {

        byte[] data = new byte[CHUNK];

        int size;

        boolean fill(InputStream in) throws IOException {
            ensure(size + CHUNK);
            int n = in.read(data, size, CHUNK);
            if (n < 0) {
                return false;
            }
            size += n;
            return true;
        }

        void rest(InputStream in) throws IOException {
            boolean more;
            do {
                more = fill(in);
            } while (more);
        }

        void write(byte[] b, int off, int len) {
            ensure(size + len);
            System.arraycopy(b, off, data, size, len);
            size += len;
        }

        int keepLast(int n) {
            int drop = size - n;
            if (drop <= 0) {
                return 0;
            }
            System.arraycopy(data, drop, data, 0, n);
            size = n;
            return drop;
        }

        int indexOf(byte b, int from) {
            for (int i = Math.max(0, from); i < size; i++) {
                if (data[i] == b) {
                    return i;
                }
            }
            return -1;
        }

        byte[] array() {
            return Arrays.copyOf(data, size);
        }

        private void ensure(int capacity) {
            if (capacity > data.length) {
                data = Arrays.copyOf(data, Math.max(capacity, data.length * 2));
            }
        }
    }
}

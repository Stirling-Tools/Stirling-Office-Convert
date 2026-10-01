package stirling.software.officeconvert.topdf.rtf;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

final class Story {

    static final int MAX_DEPTH = 32;

    final Rels rels;

    final ParaBuilder para = new ParaBuilder();

    final ParaBuilder listText = new ParaBuilder();

    private final Writer writer;

    private final StringBuilder buffer;

    private final List<TableBuilder> tables = new ArrayList<>();

    private final ColorTable colors;

    private final long limit;

    private long written;

    private RowProps lastRow;

    private String deferred;

    private String deferredPlain;

    Story(Rels rels, ColorTable colors, Writer writer, long limit) {
        this.rels = rels;
        this.colors = colors;
        this.writer = writer;
        this.buffer = writer == null ? new StringBuilder() : null;
        this.limit = limit;
    }

    void block(String xml, int depth) throws IOException {
        int d = Math.min(MAX_DEPTH, depth);
        closeTables(d);
        if (d == 0) {
            out(xml);
            return;
        }
        open(d).block(xml);
    }

    void endCell(int depth) throws IOException {
        int d = Math.max(1, Math.min(MAX_DEPTH, depth));
        closeTables(d);
        open(d).endCell();
    }

    void endRow(int depth, RowProps props) throws IOException {
        int d = Math.max(1, Math.min(MAX_DEPTH, depth));
        closeTables(d);
        open(d).endRow(props);
        lastRow = props;
    }

    int openTables() {
        return tables.size();
    }

    void finish() throws IOException {
        closeTables(0);
    }

    void defer(String with, String plain) throws IOException {
        closeTables(0);
        flushDeferred();
        deferred = with;
        deferredPlain = plain;
    }

    boolean endDeferred() throws IOException {
        if (deferred == null) {
            return false;
        }
        String plain = deferredPlain;
        deferred = null;
        deferredPlain = null;
        write(plain);
        return true;
    }

    private void flushDeferred() throws IOException {
        if (deferred != null) {
            String d = deferred;
            deferred = null;
            deferredPlain = null;
            write(d);
        }
    }

    String content() {
        return buffer == null ? "" : buffer.toString();
    }

    private TableBuilder open(int d) {
        while (tables.size() < d) {
            tables.add(new TableBuilder());
        }
        return tables.get(d - 1);
    }

    private void closeTables(int depth) throws IOException {
        while (tables.size() > depth) {
            TableBuilder t = tables.remove(tables.size() - 1);
            String xml = t.xml(colors, lastRow);
            if (tables.isEmpty()) {
                out(xml);
            } else {
                tables.get(tables.size() - 1).block(xml);
            }
        }
    }

    private void out(String xml) throws IOException {
        flushDeferred();
        write(xml);
    }

    private void write(String xml) throws IOException {
        written += xml.length();
        if (written > limit) {
            throw new RtfPackage.TooLarge();
        }
        if (writer != null) {
            writer.write(xml);
        } else {
            buffer.append(xml);
        }
    }
}

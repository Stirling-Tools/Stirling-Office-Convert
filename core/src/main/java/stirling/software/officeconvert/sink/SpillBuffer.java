package stirling.software.officeconvert.sink;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

public final class SpillBuffer implements Closeable {

    private static final int MEMORY_CHARS = 1 << 20;
    private static final char MARK = '\0';

    private final StringBuilder memory = new StringBuilder();
    private Path file;
    private Writer spill;

    public SpillBuffer append(CharSequence s) throws IOException {
        memory.append(s);
        if (memory.length() > MEMORY_CHARS) {
            if (spill == null) {
                file = Files.createTempFile("office-convert-", ".spill");
                spill = new BufferedWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8), 1 << 16);
            }
            spill.append(memory);
            memory.setLength(0);
        }
        return this;
    }

    public SpillBuffer placeholder(String key) throws IOException {
        return append(MARK + key + MARK);
    }

    public boolean isEmpty() {
        return spill == null && memory.isEmpty();
    }

    public void copyTo(Writer out, Function<String, String> resolve) throws IOException {
        if (spill != null) {
            spill.close();
            spill = null;
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                copy(in, out, resolve);
            }
            Files.deleteIfExists(file);
            file = null;
        }
        copy(new StringReader(memory.toString()), out, resolve);
        memory.setLength(0);
    }

    private static void copy(Reader in, Writer out, Function<String, String> resolve) throws IOException {
        Reader r = in instanceof BufferedReader || in instanceof StringReader ? in : new BufferedReader(in);
        char[] buf = new char[1 << 14];
        StringBuilder key = null;
        for (int n; (n = r.read(buf)) > 0; ) {
            int from = 0;
            for (int i = 0; i < n; i++) {
                if (buf[i] != MARK) {
                    continue;
                }
                if (key == null) {
                    out.write(buf, from, i - from);
                    key = new StringBuilder();
                } else {
                    key.append(buf, from, i - from);
                    String value = resolve.apply(key.toString());
                    out.write(value == null ? "" : value);
                    key = null;
                }
                from = i + 1;
            }
            if (key == null) {
                out.write(buf, from, n - from);
            } else {
                key.append(buf, from, n - from);
            }
        }
    }

    @Override
    public void close() throws IOException {
        try {
            if (spill != null) {
                spill.close();
                spill = null;
            }
        } finally {
            if (file != null) {
                Files.deleteIfExists(file);
                file = null;
            }
        }
    }
}

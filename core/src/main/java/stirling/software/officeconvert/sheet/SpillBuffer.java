package stirling.software.officeconvert.sheet;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SpillBuffer extends OutputStream {

    private final long limit;
    private ByteArrayOutputStream memory = new ByteArrayOutputStream(1 << 12);
    private Path file;
    private OutputStream fileOut;
    private long size;

    public SpillBuffer(long limit) {
        this.limit = limit;
    }

    @Override
    public void write(int b) throws IOException {
        target(1).write(b);
        size++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        target(len).write(b, off, len);
        size += len;
    }

    private OutputStream target(int more) throws IOException {
        if (fileOut != null) {
            return fileOut;
        }
        if (size + more <= limit) {
            return memory;
        }
        file = Files.createTempFile("office-convert-sheet", ".part");
        fileOut = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16);
        memory.writeTo(fileOut);
        memory = null;
        return fileOut;
    }

    public long size() {
        return size;
    }

    public void writeTo(OutputStream out) throws IOException {
        if (fileOut == null) {
            memory.writeTo(out);
            return;
        }
        fileOut.flush();
        Files.copy(file, out);
    }

    @Override
    public void close() throws IOException {
        try {
            if (fileOut != null) {
                fileOut.close();
            }
        } finally {
            fileOut = null;
            if (file != null) {
                Files.deleteIfExists(file);
                file = null;
            }
            memory = null;
        }
    }
}

package stirling.software.officeconvert.optimize;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class Bytes extends OutputStream {

    private byte[] data;

    private int size;

    Bytes() {
        this(256);
    }

    Bytes(int capacity) {
        data = new byte[Math.max(16, capacity)];
    }

    private void room(int more) {
        long need = (long) size + more;
        if (need > data.length) {
            if (need > Integer.MAX_VALUE - 16) {
                throw new OutOfMemoryError("A buffer would pass 2 GB");
            }
            data = Arrays.copyOf(data, (int) Math.min(Integer.MAX_VALUE - 16, Math.max(need, data.length * 2L)));
        }
    }

    @Override
    public void write(int b) {
        room(1);
        data[size++] = (byte) b;
    }

    @Override
    public void write(byte[] b, int off, int len) {
        room(len);
        System.arraycopy(b, off, data, size, len);
        size += len;
    }

    @Override
    public void write(byte[] b) {
        write(b, 0, b.length);
    }

    void ascii(String s) {
        write(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    int size() {
        return size;
    }

    byte at(int i) {
        return data[i];
    }

    byte[] array() {
        return data;
    }

    void reset() {
        size = 0;
    }

    byte[] toByteArray() {
        return Arrays.copyOf(data, size);
    }

    void writeTo(OutputStream out) throws IOException {
        out.write(data, 0, size);
    }
}

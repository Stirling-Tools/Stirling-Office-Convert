package stirling.software.officeconvert.optimize;

import java.util.Arrays;

final class BitWriter {

    private byte[] buf;

    private int size;

    private long acc;

    private int count;

    BitWriter(int capacity) {
        buf = new byte[capacity];
    }

    void bits(int value, int n) {
        acc |= (long) (value & ((1 << n) - 1)) << count;
        count += n;
        while (count >= 8) {
            put((int) acc);
            acc >>>= 8;
            count -= 8;
        }
    }

    void huffman(int code, int length) {
        int reversed = Integer.reverse(code) >>> (32 - length);
        bits(reversed, length);
    }

    void align() {
        if (count > 0) {
            put((int) acc);
            acc = 0;
            count = 0;
        }
    }

    void writeByte(int b) {
        put(b);
    }

    void writeBytes(byte[] b, int off, int len) {
        if (size + len > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(size + len, buf.length * 2));
        }
        System.arraycopy(b, off, buf, size, len);
        size += len;
    }

    private void put(int b) {
        if (size == buf.length) {
            buf = Arrays.copyOf(buf, buf.length * 2);
        }
        buf[size++] = (byte) b;
    }

    byte[] toByteArray() {
        return Arrays.copyOf(buf, size);
    }
}

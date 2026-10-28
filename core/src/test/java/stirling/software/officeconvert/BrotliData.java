package stirling.software.officeconvert;

import java.io.ByteArrayOutputStream;

final class BrotliData {

    static final byte[] HELLO = bytes(0x02, 0x06, 0x00, 0xbf, 0xc0, 0xd3, 0xce, 0x9f, 0xd6, 0x37, 0x32, 0x39, 0x32,
            0x98, 0x15, 0x69, 0xbf, 0x61, 0x44, 0xd1, 0x95, 0xfa, 0xfd, 0x44, 0x44, 0x94, 0x91, 0xea, 0x04, 0x1c, 0x9e,
            0xaa, 0x84, 0x1e, 0x89, 0x05, 0xdd, 0x64, 0x21, 0x89, 0xa0, 0x13, 0x37, 0x96, 0xd9, 0xa3, 0x7e, 0xef, 0xb7,
            0x43, 0x5e, 0x97, 0xa6);

    static final String HELLO_TEXT = "BT /F1 18 Tf 40 200 Td (Hello Brotli world) Tj ET";

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private long bits;

    private int count;

    private BrotliData() {}

    static byte[] stored(byte[] data) {
        BrotliData b = new BrotliData();
        b.put(0, 1);
        for (int at = 0; at < data.length; at += 1 << 16) {
            int n = Math.min(1 << 16, data.length - at);
            b.storedBlock(data, at, n);
        }
        return b.finish();
    }

    static byte[] zeros(long total) {
        BrotliData b = new BrotliData();
        b.put(0, 1);
        b.storedBlock(new byte[1], 0, 1);
        long left = total - 1;
        while (left > 0) {
            int m = (int) Math.min(left, 1 << 24);
            if (m < 2118) {
                b.storedBlock(new byte[m], 0, m);
            } else {
                b.repeatBlock(m);
            }
            left -= m;
        }
        return b.finish();
    }

    private void storedBlock(byte[] data, int at, int n) {
        put(0, 1);
        put(0, 2);
        put(n - 1, 16);
        put(1, 1);
        align();
        out.write(data, at, n);
    }

    private void repeatBlock(int m) {
        int nibbles = 4;
        while (((long) (m - 1) >> (4 * nibbles)) != 0) {
            nibbles++;
        }
        put(0, 1);
        put(nibbles - 4, 2);
        put(m - 1, 4 * nibbles);
        put(0, 1);
        put(0, 3);
        put(0, 2);
        put(0, 4);
        put(0, 2);
        put(0, 2);
        simple(0, 8);
        simple(391, 10);
        simple(16, 6);
        put(m - 2118, 24);
        put(0, 1);
    }

    private void simple(int symbol, int width) {
        put(1, 2);
        put(0, 2);
        put(symbol, width);
    }

    private byte[] finish() {
        put(1, 1);
        put(1, 1);
        align();
        return out.toByteArray();
    }

    private void put(long value, int width) {
        bits |= (value & ((1L << width) - 1)) << count;
        count += width;
        while (count >= 8) {
            out.write((int) (bits & 0xFF));
            bits >>>= 8;
            count -= 8;
        }
    }

    private void align() {
        if (count > 0) {
            put(0, 8 - count);
        }
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            b[i] = (byte) v[i];
        }
        return b;
    }
}

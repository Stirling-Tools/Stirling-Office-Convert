package stirling.software.officeconvert.jpx;

import java.util.Arrays;

final class HeaderWriter {

    private byte[] out = new byte[256];

    private int size;

    private int buf;

    private int free = 8;

    private boolean stuffed;

    void reset() {
        size = 0;
        buf = 0;
        free = 8;
        stuffed = false;
    }

    void bit(int b) {
        if (free == 0) {
            push();
        }
        free--;
        buf |= (b & 1) << free;
    }

    void bits(int value, int n) {
        for (int i = n - 1; i >= 0; i--) {
            bit(value >> i);
        }
    }

    private void push() {
        if (size == out.length) {
            out = Arrays.copyOf(out, size * 2);
        }
        out[size++] = (byte) buf;
        stuffed = buf == 0xFF;
        buf = 0;
        free = stuffed ? 7 : 8;
    }

    void flush() {
        int capacity = stuffed ? 7 : 8;
        if (free < capacity) {
            push();
        }
        if (stuffed) {
            push();
        }
    }

    int size() {
        return size;
    }

    byte[] bytes() {
        return out;
    }
}

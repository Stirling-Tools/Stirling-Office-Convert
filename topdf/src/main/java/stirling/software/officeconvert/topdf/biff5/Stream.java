package stirling.software.officeconvert.topdf.biff5;

import java.io.InterruptedIOException;

/** The records of a BIFF5 workbook stream: a 16-bit type and size before each one. */
final class Stream {

    private final byte[] b;

    private int next;

    private int type;

    private int start;

    private int size;

    private long count;

    Stream(byte[] b) {
        this.b = b;
    }

    void seek(int position) {
        next = Math.max(0, Math.min(position, b.length));
    }

    boolean next() throws InterruptedIOException {
        if ((++count & 0xFFF) == 0 && Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Conversion interrupted");
        }
        if (next + 4 > b.length) {
            return false;
        }
        type = abs16(next);
        size = abs16(next + 2);
        start = next + 4;
        if (start + size > b.length) {
            return false;
        }
        next = start + size;
        return true;
    }

    int type() {
        return type;
    }

    int size() {
        return size;
    }

    int u8(int at) {
        return at < size ? b[start + at] & 0xFF : 0;
    }

    int u16(int at) {
        return at + 1 < size ? (b[start + at] & 0xFF) | (b[start + at + 1] & 0xFF) << 8 : 0;
    }

    int i32(int at) {
        return at + 3 < size ? (b[start + at] & 0xFF) | (b[start + at + 1] & 0xFF) << 8
                | (b[start + at + 2] & 0xFF) << 16 | (b[start + at + 3] & 0xFF) << 24 : 0;
    }

    double f64(int at) {
        if (at + 7 >= size) {
            return Double.NaN;
        }
        long lo = i32(at) & 0xFFFFFFFFL;
        long hi = i32(at + 4) & 0xFFFFFFFFL;
        return Double.longBitsToDouble(hi << 32 | lo);
    }

    byte[] bytes(int at, int n) {
        int len = Math.max(0, Math.min(n, size - at));
        byte[] out = new byte[len];
        System.arraycopy(b, start + Math.max(0, at), out, 0, len);
        return out;
    }

    private int abs16(int abs) {
        return (b[abs] & 0xFF) | (b[abs + 1] & 0xFF) << 8;
    }
}

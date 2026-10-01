package stirling.software.officeconvert.topdf.io;

final class PictReader {

    private final byte[] data;

    private final int end;

    private int pos;

    PictReader(byte[] data, int start) {
        this.data = data;
        this.end = data.length;
        this.pos = start;
    }

    int position() {
        return pos;
    }

    boolean more() {
        return pos < end;
    }

    void seek(int to) {
        if (to < 0 || to > end) {
            throw new IllegalStateException("past the end of the picture");
        }
        pos = to;
    }

    void skip(long n) {
        if (n < 0 || n > end - pos) {
            throw new IllegalStateException("past the end of the picture");
        }
        pos += (int) n;
    }

    void align() {
        if ((pos & 1) != 0 && pos < end) {
            pos++;
        }
    }

    int u8() {
        need(1);
        return data[pos++] & 0xFF;
    }

    int u16() {
        need(2);
        int v = (data[pos] & 0xFF) << 8 | data[pos + 1] & 0xFF;
        pos += 2;
        return v;
    }

    int s16() {
        return (short) u16();
    }

    long u32() {
        return (long) u16() << 16 | u16();
    }

    void read(byte[] out, int off, int n) {
        need(n);
        System.arraycopy(data, pos, out, off, n);
        pos += n;
    }

    byte[] bytes(int n) {
        byte[] b = new byte[n];
        read(b, 0, n);
        return b;
    }

    private void need(int n) {
        if (n < 0 || n > end - pos) {
            throw new IllegalStateException("past the end of the picture");
        }
    }
}

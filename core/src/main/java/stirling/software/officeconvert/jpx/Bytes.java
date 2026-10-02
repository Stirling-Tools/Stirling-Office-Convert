package stirling.software.officeconvert.jpx;

final class Bytes {

    private final byte[] data;

    private final int end;

    private int pos;

    Bytes(byte[] data, int start, int end) {
        this.data = data;
        this.pos = start;
        this.end = end;
    }

    int pos() {
        return pos;
    }

    int end() {
        return end;
    }

    int remaining() {
        return end - pos;
    }

    void seek(int at) throws JpxException {
        if (at < 0 || at > end) {
            throw new JpxException("JPEG 2000 data ends early");
        }
        pos = at;
    }

    void skip(int n) throws JpxException {
        if (n < 0 || n > end - pos) {
            throw new JpxException("JPEG 2000 data ends early");
        }
        pos += n;
    }

    int u8() throws JpxException {
        need(1);
        return data[pos++] & 0xFF;
    }

    int u16() throws JpxException {
        need(2);
        int v = (data[pos] & 0xFF) << 8 | data[pos + 1] & 0xFF;
        pos += 2;
        return v;
    }

    long u32() throws JpxException {
        need(4);
        long v = u32(data, pos);
        pos += 4;
        return v;
    }

    long u64() throws JpxException {
        long hi = u32();
        return hi << 32 | u32();
    }

    int peekU16() {
        return pos + 2 <= end ? (data[pos] & 0xFF) << 8 | data[pos + 1] & 0xFF : -1;
    }

    private void need(int n) throws JpxException {
        if (end - pos < n) {
            throw new JpxException("JPEG 2000 data ends early");
        }
    }

    static long u32(byte[] b, int at) {
        return (long) (b[at] & 0xFF) << 24 | (b[at + 1] & 0xFF) << 16 | (b[at + 2] & 0xFF) << 8 | b[at + 3] & 0xFF;
    }
}

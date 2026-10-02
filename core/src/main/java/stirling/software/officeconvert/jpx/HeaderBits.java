package stirling.software.officeconvert.jpx;

final class HeaderBits {

    private byte[] data;

    private int pos;

    private int end;

    private int buf;

    private int ct;

    void reset(byte[] data, int pos, int end) {
        this.data = data;
        this.pos = pos;
        this.end = end;
        this.buf = 0;
        this.ct = 0;
    }

    int pos() {
        return pos;
    }

    int bit() throws JpxException {
        if (ct == 0) {
            fill();
        }
        ct--;
        return buf >> ct & 1;
    }

    int bits(int n) throws JpxException {
        int v = 0;
        for (int i = 0; i < n; i++) {
            v = v << 1 | bit();
        }
        return v;
    }

    void align() throws JpxException {
        if ((buf & 0xFF) == 0xFF) {
            fill();
        }
        ct = 0;
    }

    boolean marker(int code) {
        return pos + 1 < end && (data[pos] & 0xFF) == code >> 8 && (data[pos + 1] & 0xFF) == (code & 0xFF);
    }

    void skip(int n) {
        pos = Math.min(end, pos + n);
    }

    private void fill() throws JpxException {
        buf = buf << 8 & 0xFFFF;
        ct = buf == 0xFF00 ? 7 : 8;
        if (pos >= end) {
            throw new JpxException("JPEG 2000 packet header ends early");
        }
        buf |= data[pos++] & 0xFF;
    }
}

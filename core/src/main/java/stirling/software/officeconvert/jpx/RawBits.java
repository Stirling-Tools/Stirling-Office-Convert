package stirling.software.officeconvert.jpx;

final class RawBits {

    private byte[] data;

    private int pos;

    private int end;

    private int c;

    private int ct;

    void init(byte[] data, int length) {
        this.data = data;
        this.pos = 0;
        this.end = length;
        this.c = 0;
        this.ct = 0;
    }

    int bit() {
        if (ct == 0) {
            if (c == 0xFF) {
                int b = at(pos);
                if (b > 0x8F) {
                    c = 0xFF;
                    ct = 8;
                } else {
                    c = b;
                    pos++;
                    ct = 7;
                }
            } else {
                c = at(pos);
                pos++;
                ct = 8;
            }
        }
        ct--;
        return c >> ct & 1;
    }

    private int at(int i) {
        return i < end ? data[i] & 0xFF : 0xFF;
    }
}

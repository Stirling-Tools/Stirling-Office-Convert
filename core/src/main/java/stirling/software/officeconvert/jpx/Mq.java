package stirling.software.officeconvert.jpx;

import java.util.Arrays;

final class Mq {

    static final int CONTEXTS = 19;
    static final int RUN = 17;
    static final int UNIFORM = 18;

    private static final int[] QE = {0x5601, 0x3401, 0x1801, 0x0AC1, 0x0521, 0x0221, 0x5601, 0x5401, 0x4801, 0x3801,
            0x3001, 0x2401, 0x1C01, 0x1601, 0x5601, 0x5401, 0x5101, 0x4801, 0x3801, 0x3401, 0x3001, 0x2801, 0x2401,
            0x2201, 0x1C01, 0x1801, 0x1601, 0x1401, 0x1201, 0x1101, 0x0AC1, 0x09C1, 0x08A1, 0x0521, 0x0441, 0x02A1,
            0x0221, 0x0141, 0x0111, 0x0085, 0x0049, 0x0025, 0x0015, 0x0009, 0x0005, 0x0001, 0x5601};

    private static final int[] NMPS = {1, 2, 3, 4, 5, 38, 7, 8, 9, 10, 11, 12, 13, 29, 15, 16, 17, 18, 19, 20, 21, 22,
            23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 45, 46};

    private static final int[] NLPS = {1, 6, 9, 12, 29, 33, 6, 14, 14, 14, 17, 18, 20, 21, 14, 14, 15, 16, 17, 18, 19,
            19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 46};

    private static final int[] Q = new int[94];

    private static final int[] ON_MPS = new int[94];

    private static final int[] ON_LPS = new int[94];

    static {
        for (int i = 0; i < 47; i++) {
            boolean flip = i == 0 || i == 6 || i == 14;
            for (int mps = 0; mps < 2; mps++) {
                int st = i << 1 | mps;
                Q[st] = QE[i];
                ON_MPS[st] = NMPS[i] << 1 | mps;
                ON_LPS[st] = NLPS[i] << 1 | (flip ? 1 - mps : mps);
            }
        }
    }

    private final int[] state = new int[CONTEXTS];

    private byte[] data;

    private int pos;

    private int end;

    private int a;

    private int c;

    private int ct;

    void resetContexts() {
        Arrays.fill(state, 0);
        state[0] = 4 << 1;
        state[RUN] = 3 << 1;
        state[UNIFORM] = 46 << 1;
    }

    void init(byte[] data, int length) {
        this.data = data;
        this.pos = 0;
        this.end = length;
        c = at(0) << 16;
        byteIn();
        c <<= 7;
        ct -= 7;
        a = 0x8000;
    }

    int decode(int cx) {
        int st = state[cx];
        int qe = Q[st];
        int d;
        a -= qe;
        if ((c >>> 16) < qe) {
            if (a < qe) {
                d = st & 1;
                state[cx] = ON_MPS[st];
            } else {
                d = 1 - (st & 1);
                state[cx] = ON_LPS[st];
            }
            a = qe;
            renormalise();
            return d;
        }
        c -= qe << 16;
        if ((a & 0x8000) != 0) {
            return st & 1;
        }
        if (a < qe) {
            d = 1 - (st & 1);
            state[cx] = ON_LPS[st];
        } else {
            d = st & 1;
            state[cx] = ON_MPS[st];
        }
        renormalise();
        return d;
    }

    private void renormalise() {
        int n = Integer.numberOfLeadingZeros(a) - 16;
        while (n > 0) {
            if (ct == 0) {
                byteIn();
            }
            int k = Math.min(n, ct);
            a <<= k;
            c <<= k;
            ct -= k;
            n -= k;
        }
    }

    private void byteIn() {
        if (at(pos) == 0xFF) {
            int next = at(pos + 1);
            if (next > 0x8F) {
                c += 0xFF00;
                ct = 8;
            } else {
                pos++;
                c += next << 9;
                ct = 7;
            }
        } else {
            pos++;
            c += at(pos) << 8;
            ct = 8;
        }
    }

    private int at(int i) {
        return i < end ? data[i] & 0xFF : 0xFF;
    }
}

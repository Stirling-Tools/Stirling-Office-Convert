package stirling.software.officeconvert.lineart;

import java.io.ByteArrayOutputStream;

final class MqEncoder {

    static final class Contexts {
        final byte[] index;
        final byte[] mps;

        Contexts(int size) {
            index = new byte[size];
            mps = new byte[size];
        }
    }

    private static final int[] QE = {0x5601, 0x3401, 0x1801, 0x0AC1, 0x0521, 0x0221, 0x5601, 0x5401, 0x4801, 0x3801,
        0x3001, 0x2401, 0x1C01, 0x1601, 0x5601, 0x5401, 0x5101, 0x4801, 0x3801, 0x3401, 0x3001, 0x2801, 0x2401, 0x2201,
        0x1C01, 0x1801, 0x1601, 0x1401, 0x1201, 0x1101, 0x0AC1, 0x09C1, 0x08A1, 0x0521, 0x0441, 0x02A1, 0x0221, 0x0141,
        0x0111, 0x0085, 0x0049, 0x0025, 0x0015, 0x0009, 0x0005, 0x0001, 0x5601};

    private static final int[] NMPS = {1, 2, 3, 4, 5, 38, 7, 8, 9, 10, 11, 12, 13, 29, 15, 16, 17, 18, 19, 20, 21, 22,
        23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 45, 46};

    private static final int[] NLPS = {1, 6, 9, 12, 29, 33, 6, 14, 14, 14, 17, 18, 20, 21, 14, 14, 15, 16, 17, 18, 19,
        19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 46};

    private static final boolean[] SWITCH = new boolean[47];

    static {
        SWITCH[0] = true;
        SWITCH[6] = true;
        SWITCH[14] = true;
    }

    private final ByteArrayOutputStream out;
    private int a = 0x8000;
    private int c;
    private int ct = 12;
    private int b = -1;

    MqEncoder(int expected) {
        out = new ByteArrayOutputStream(Math.max(64, expected));
    }

    void encode(Contexts contexts, int context, int bit) {
        byte[] index = contexts.index;
        int i = index[context];
        int qe = QE[i];
        a -= qe;
        if (bit == contexts.mps[context]) {
            if ((a & 0x8000) == 0) {
                if (a < qe) {
                    a = qe;
                } else {
                    c += qe;
                }
                index[context] = (byte) NMPS[i];
                renormalise();
            } else {
                c += qe;
            }
        } else {
            if (a < qe) {
                c += qe;
            } else {
                a = qe;
            }
            if (SWITCH[i]) {
                contexts.mps[context] ^= 1;
            }
            index[context] = (byte) NLPS[i];
            renormalise();
        }
    }

    private void renormalise() {
        do {
            a <<= 1;
            c <<= 1;
            ct--;
            if (ct == 0) {
                byteOut();
            }
        } while ((a & 0x8000) == 0);
    }

    private void byteOut() {
        if (b == 0xFF) {
            emit(c >>> 20);
            c &= 0xFFFFF;
            ct = 7;
        } else if ((c & 0xFFFFFFFFL) < 0x8000000L) {
            emit(c >>> 19);
            c &= 0x7FFFF;
            ct = 8;
        } else {
            b++;
            if (b == 0xFF) {
                c &= 0x7FFFFFF;
                emit(c >>> 20);
                c &= 0xFFFFF;
                ct = 7;
            } else {
                emit(c >>> 19);
                c &= 0x7FFFF;
                ct = 8;
            }
        }
    }

    private void emit(int next) {
        if (b >= 0) {
            out.write(b);
        }
        b = next & 0xFF;
    }

    byte[] finish() {
        int temp = c + a;
        c |= 0xFFFF;
        if (Integer.compareUnsigned(c, temp) >= 0) {
            c -= 0x8000;
        }
        c <<= ct;
        byteOut();
        c <<= ct;
        byteOut();
        if (b != 0xFF) {
            emit(0xFF);
        }
        emit(0xAC);
        out.write(b);
        return out.toByteArray();
    }
}

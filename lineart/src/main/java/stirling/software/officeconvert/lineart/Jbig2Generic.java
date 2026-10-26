package stirling.software.officeconvert.lineart;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

final class Jbig2Generic {

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

    private static final int TYPICAL_CONTEXT = 0x9B25;

    private final byte[] index = new byte[1 << 16];
    private final byte[] mps = new byte[1 << 16];
    private final ByteArrayOutputStream out;
    private int a = 0x8000;
    private int c;
    private int ct = 12;
    private int b = -1;

    private Jbig2Generic(int expected) {
        out = new ByteArrayOutputStream(Math.max(64, expected));
    }

    static byte[] encode(Bitmap bitmap) {
        int w = bitmap.width();
        int h = bitmap.height();
        byte[] region = arithmetic(bitmap);
        ByteArrayOutputStream s = new ByteArrayOutputStream(region.length + 64);
        header(s, 0, 48, 19);
        int32(s, w);
        int32(s, h);
        int32(s, 0);
        int32(s, 0);
        s.write(0);
        s.write(0);
        s.write(0);
        header(s, 1, 38, 17 + 1 + 8 + region.length);
        int32(s, w);
        int32(s, h);
        int32(s, 0);
        int32(s, 0);
        s.write(0);
        s.write(0x08);
        s.writeBytes(new byte[] {3, -1, -3, -1, 2, -2, -2, -2});
        s.writeBytes(region);
        return s.toByteArray();
    }

    private static void header(ByteArrayOutputStream s, int number, int type, int length) {
        int32(s, number);
        s.write(type);
        s.write(0);
        s.write(1);
        int32(s, length);
    }

    private static void int32(ByteArrayOutputStream s, int v) {
        s.write(v >>> 24);
        s.write(v >>> 16);
        s.write(v >>> 8);
        s.write(v);
    }

    static byte[] arithmetic(Bitmap bitmap) {
        int w = bitmap.width();
        int h = bitmap.height();
        int stride = bitmap.stride();
        byte[] bits = bitmap.packed();
        Jbig2Generic coder = new Jbig2Generic(bits.length / 8);
        int[] above2 = new int[w + 8];
        int[] above1 = new int[w + 8];
        int[] current = new int[w + 8];
        boolean typical = false;
        for (int y = 0; y < h; y++) {
            int row = y * stride;
            boolean same = y == 0 ? emptyRow(bits, row, stride) : sameRow(bits, row, row - stride, stride);
            coder.encode(TYPICAL_CONTEXT, same != typical ? 1 : 0);
            typical = same;
            int[] t = above2;
            above2 = above1;
            above1 = current;
            current = t;
            if (same) {
                System.arraycopy(above1, 0, current, 0, current.length);
                continue;
            }
            for (int x = 0; x < w; x++) {
                current[x + 4] = (bits[row + (x >>> 3)] >>> (7 - (x & 7))) & 1;
            }
            for (int x = 0; x < w; x++) {
                int p = x + 4;
                int context = current[p - 1] | current[p - 2] << 1 | current[p - 3] << 2 | current[p - 4] << 3
                        | above1[p + 3] << 4 | above1[p + 2] << 5 | above1[p + 1] << 6 | above1[p] << 7
                        | above1[p - 1] << 8 | above1[p - 2] << 9 | above1[p - 3] << 10 | above2[p + 2] << 11
                        | above2[p + 1] << 12 | above2[p] << 13 | above2[p - 1] << 14 | above2[p - 2] << 15;
                coder.encode(context, current[p]);
            }
        }
        return coder.finish();
    }

    private static boolean emptyRow(byte[] bits, int row, int stride) {
        for (int i = 0; i < stride; i++) {
            if (bits[row + i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameRow(byte[] bits, int row, int previous, int stride) {
        return Arrays.equals(bits, row, row + stride, bits, previous, previous + stride);
    }

    private void encode(int context, int bit) {
        int i = index[context];
        int qe = QE[i];
        a -= qe;
        if (bit == mps[context]) {
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
                mps[context] ^= 1;
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

    private byte[] finish() {
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

package stirling.software.officeconvert.mq;

import java.util.Arrays;

public final class MqEncoder {

    public static final class Contexts {
        final byte[] state;

        public Contexts(int size) {
            state = new byte[size];
        }

        public void reset() {
            Arrays.fill(state, (byte) 0);
        }

        public void set(int context, int index) {
            state[context] = (byte) (index << 1);
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

    private static final int[] Q = new int[94];

    private static final byte[] ON_MPS = new byte[94];

    private static final byte[] ON_LPS = new byte[94];

    private static final int[] PACKED = new int[94];

    static {
        for (int i = 0; i < 47; i++) {
            boolean flip = i == 0 || i == 6 || i == 14;
            for (int mps = 0; mps < 2; mps++) {
                int st = i << 1 | mps;
                Q[st] = QE[i];
                ON_MPS[st] = (byte) (NMPS[i] << 1 | mps);
                ON_LPS[st] = (byte) (NLPS[i] << 1 | (flip ? 1 - mps : mps));
                PACKED[st] = QE[i] | (ON_MPS[st] & 0xFF) << 16 | (ON_LPS[st] & 0xFF) << 24;
            }
        }
    }

    private static final int WINDOW = 56;

    private byte[] out;
    private int size;
    private int[] marks = new int[320];
    private int marked;
    private int a = 0x8000;
    private int c;
    private int ct = 12;
    private int b = -1;

    public MqEncoder(int expected) {
        out = new byte[Math.max(64, expected)];
    }

    public void reset() {
        marked = 0;
        size = 0;
        a = 0x8000;
        c = 0;
        ct = 12;
        b = -1;
    }

    public void encode(Contexts contexts, int context, int bit) {
        byte[] state = contexts.state;
        int st = state[context];
        int e = PACKED[st];
        int qe = e & 0xFFFF;
        int na = a - qe;
        if (bit == (st & 1)) {
            if ((na & 0x8000) != 0) {
                a = na;
                c += qe;
                return;
            }
            if (na < qe) {
                na = qe;
            } else {
                c += qe;
            }
            state[context] = (byte) (e >>> 16);
        } else {
            if (na < qe) {
                c += qe;
            } else {
                na = qe;
            }
            state[context] = (byte) (e >>> 24);
        }
        a = na;
        renormalise();
    }

    private void renormalise() {
        int n = Integer.numberOfLeadingZeros(a) - 16;
        if (n < ct) {
            a <<= n;
            c <<= n;
            ct -= n;
            return;
        }
        while (n > 0) {
            int k = Math.min(n, ct);
            a <<= k;
            c <<= k;
            ct -= k;
            n -= k;
            if (ct == 0) {
                byteOut();
            }
        }
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
            write(b);
        }
        b = next & 0xFF;
    }

    private void write(int v) {
        if (size == out.length) {
            out = Arrays.copyOf(out, out.length * 2);
        }
        out[size++] = (byte) v;
    }

    private void setBits() {
        int temp = c + a;
        c |= 0xFFFF;
        if (Integer.compareUnsigned(c, temp) >= 0) {
            c -= 0x8000;
        }
    }

    public int length() {
        return size;
    }

    public byte[] buffer() {
        return out;
    }

    public void mark() {
        if (marked + 5 > marks.length) {
            marks = Arrays.copyOf(marks, marks.length * 2);
        }
        marks[marked++] = size;
        marks[marked++] = b;
        marks[marked++] = c;
        marks[marked++] = a;
        marks[marked++] = ct;
    }

    public int[] truncations(byte[] stream, int length) {
        int[] out = new int[marked / 5];
        int previous = 0;
        for (int m = 0; m < out.length; m++) {
            int k = shortest(stream, length, m * 5);
            k = Math.clamp(k, previous, length);
            while (k < length && k > 0 && (stream[k - 1] & 0xFF) == 0xFF) {
                k++;
            }
            out[m] = k;
            previous = k;
        }
        marked = 0;
        return out;
    }

    private int shortest(byte[] stream, int length, int at) {
        int s = marks[at];
        boolean prevFf = s > 0 && (stream[s - 1] & 0xFF) == 0xFF;
        long floor = bound(marks[at + 1], marks[at + 2], marks[at + 4], prevFf);
        long limit = bound(marks[at + 1], marks[at + 2] + marks[at + 3], marks[at + 4], prevFf);
        long prefix = 0;
        int shift = prevFf ? 7 : 8;
        int e = 0;
        long ulp = 1L << WINDOW;
        for (int k = s; k <= Math.min(length, s + 6); k++) {
            long padded = prefix + ulp;
            if (Long.compareUnsigned(padded, limit) <= 0 && Long.compareUnsigned(padded, floor) > 0) {
                return k;
            }
            if (k == length) {
                break;
            }
            int v = stream[k] & 0xFF;
            e += shift;
            prefix += (long) v << (WINDOW - e);
            ulp = 1L << (WINDOW - e);
            shift = v == 0xFF ? 7 : 8;
        }
        return Math.min(length, s + 6);
    }

    private long bound(int pending, int register, int count, boolean prevFf) {
        if (pending >= 0) {
            byte[] tail = drain(pending, register, count);
            return value(tail, tail.length, prevFf);
        }
        byte[] tail = drain(0, register, count);
        if (tail[0] != 0) {
            return -1L;
        }
        return value(Arrays.copyOfRange(tail, 1, tail.length), tail.length - 1, false);
    }

    private static long value(byte[] bytes, int n, boolean prevFf) {
        long v = 0;
        int e = 0;
        int shift = prevFf ? 7 : 8;
        for (int i = 0; i < n; i++) {
            int x = bytes[i] & 0xFF;
            e += shift;
            v += (long) x << (WINDOW - e);
            shift = x == 0xFF ? 7 : 8;
        }
        return v;
    }

    private byte[] drain(int pending, int register, int count) {
        int sb = b;
        int sc = c;
        int sct = ct;
        int ss = size;
        byte[] saved = out;
        out = new byte[8];
        size = 0;
        b = pending;
        c = register;
        ct = count;
        for (int i = 0; i < 5; i++) {
            c <<= ct;
            byteOut();
        }
        if (b >= 0) {
            write(b);
        }
        byte[] tail = Arrays.copyOf(out, size);
        out = saved;
        size = ss;
        b = sb;
        c = sc;
        ct = sct;
        return tail;
    }

    public byte[] flush() {
        setBits();
        c <<= ct;
        byteOut();
        c <<= ct;
        byteOut();
        if (b != 0xFF) {
            write(b);
        }
        b = -1;
        return Arrays.copyOf(out, size);
    }

    public byte[] finish() {
        setBits();
        c <<= ct;
        byteOut();
        c <<= ct;
        byteOut();
        if (b != 0xFF) {
            emit(0xFF);
        }
        emit(0xAC);
        write(b);
        return Arrays.copyOf(out, size);
    }
}

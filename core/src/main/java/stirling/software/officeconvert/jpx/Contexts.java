package stirling.software.officeconvert.jpx;

final class Contexts {

    static final int SIG = 1;
    static final int NEG = 1 << 1;
    static final int VISIT = 1 << 2;
    static final int REFINED = 1 << 3;
    static final int N = 1 << 4;
    static final int S = 1 << 5;
    static final int W = 1 << 6;
    static final int E = 1 << 7;
    static final int NW = 1 << 8;
    static final int NE = 1 << 9;
    static final int SW = 1 << 10;
    static final int SE = 1 << 11;
    static final int N_NEG = 1 << 12;
    static final int S_NEG = 1 << 13;
    static final int W_NEG = 1 << 14;
    static final int E_NEG = 1 << 15;
    static final int NEIGHBOURS = N | S | W | E | NW | NE | SW | SE;
    static final int BELOW = S | SW | SE | S_NEG;

    private static final byte[][] ZERO = new byte[4][256];

    private static final byte[] SIGN = new byte[256];

    static {
        for (int f = 0; f < 256; f++) {
            int n = f & 1;
            int s = f >> 1 & 1;
            int w = f >> 2 & 1;
            int e = f >> 3 & 1;
            int d = (f >> 4 & 1) + (f >> 5 & 1) + (f >> 6 & 1) + (f >> 7 & 1);
            int h = w + e;
            int v = n + s;
            ZERO[Band.LL][f] = (byte) zeroContext(h, v, d);
            ZERO[Band.LH][f] = (byte) zeroContext(h, v, d);
            ZERO[Band.HL][f] = (byte) zeroContext(v, h, d);
            ZERO[Band.HH][f] = (byte) diagonalContext(h + v, d);
            SIGN[f] = (byte) signContext(f);
        }
    }

    private Contexts() {}

    static int zero(int orient, int flags) {
        return ZERO[orient][flags >> 4 & 0xFF];
    }

    static int sign(int flags) {
        return SIGN[flags >> 4 & 0xF | flags >> 8 & 0xF0];
    }

    private static int zeroContext(int h, int v, int d) {
        if (h == 2) {
            return 8;
        }
        if (h == 1) {
            return v >= 1 ? 7 : d >= 1 ? 6 : 5;
        }
        if (v == 2) {
            return 4;
        }
        if (v == 1) {
            return 3;
        }
        return d >= 2 ? 2 : d;
    }

    private static int diagonalContext(int hv, int d) {
        if (d >= 3) {
            return 8;
        }
        if (d == 2) {
            return hv >= 1 ? 7 : 6;
        }
        if (d == 1) {
            return hv >= 2 ? 5 : hv == 1 ? 4 : 3;
        }
        return hv >= 2 ? 2 : hv;
    }

    private static int signContext(int f) {
        int h = contribution(f >> 2 & 1, f >> 6 & 1) + contribution(f >> 3 & 1, f >> 7 & 1);
        int v = contribution(f & 1, f >> 4 & 1) + contribution(f >> 1 & 1, f >> 5 & 1);
        h = Math.clamp(h, -1, 1);
        v = Math.clamp(v, -1, 1);
        int ctx;
        int xor = 0;
        if (h == 0) {
            ctx = v == 0 ? 9 : 10;
            xor = v < 0 ? 1 : 0;
        } else {
            ctx = v == 0 ? 12 : v == h ? 13 : 11;
            xor = h < 0 ? 1 : 0;
        }
        return ctx | xor << 5;
    }

    private static int contribution(int significant, int negative) {
        return significant == 0 ? 0 : negative == 0 ? 1 : -1;
    }
}

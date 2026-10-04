package stirling.software.officeconvert.topdf.doc6;

/** The Word 6.0/95 file information block: where each structure lies in the WordDocument stream. */
final class Fib6 {

    static final int PAIRS = 62;

    private static final int FIRST_PAIRS = 38;

    final byte[] main;

    final int nFib;

    final int lid;

    final int flags;

    final int envr;

    final int chse;

    final int fcMin;

    final int fcMac;

    final int[] ccp = new int[8];

    final int pnChpFirst;

    final int pnPapFirst;

    final int cpnBteChp;

    final int cpnBtePap;

    private final int[] fc = new int[PAIRS];

    private final int[] lcb = new int[PAIRS];

    Fib6(byte[] main) {
        this.main = main;
        nFib = u16(0x02);
        lid = u16(0x06);
        flags = u16(0x0A);
        envr = u8(0x12);
        chse = u16(0x14);
        fcMin = i32(0x18);
        fcMac = i32(0x1C);
        for (int i = 0; i < 8; i++) {
            ccp[i] = Math.max(0, i32(0x34 + 4 * i));
        }
        for (int i = 0; i < PAIRS; i++) {
            int at = i < FIRST_PAIRS ? 0x58 + 8 * i : 0x192 + 8 * (i - FIRST_PAIRS);
            fc[i] = i32(at);
            lcb[i] = Math.max(0, i32(at + 4));
        }
        pnChpFirst = u16(0x18A);
        pnPapFirst = u16(0x18C);
        cpnBteChp = u16(0x18E);
        cpnBtePap = u16(0x190);
    }

    boolean complex() {
        return (flags & 0x0004) != 0;
    }

    boolean encrypted() {
        return (flags & 0x0100) != 0;
    }

    /** Whether the structure at pair {@code i} lies inside the stream. */
    boolean present(int i) {
        return lcb[i] > 0 && fc[i] >= 0 && (long) fc[i] + lcb[i] <= main.length;
    }

    int fc(int i) {
        return fc[i];
    }

    int lcb(int i) {
        return lcb[i];
    }

    int u8(int at) {
        return at >= 0 && at < main.length ? main[at] & 0xFF : 0;
    }

    int u16(int at) {
        return at >= 0 && at + 1 < main.length ? (main[at] & 0xFF) | (main[at + 1] & 0xFF) << 8 : 0;
    }

    int i32(int at) {
        return at >= 0 && at + 3 < main.length ? (main[at] & 0xFF) | (main[at + 1] & 0xFF) << 8
                | (main[at + 2] & 0xFF) << 16 | (main[at + 3] & 0xFF) << 24 : 0;
    }
}

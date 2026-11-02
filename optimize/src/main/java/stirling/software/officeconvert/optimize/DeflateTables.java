package stirling.software.officeconvert.optimize;

final class DeflateTables {

    static final int MIN_MATCH = 3;

    static final int MAX_MATCH = 258;

    static final int[] NEXT = new int[MAX_MATCH + 2];

    static final int[] LENGTH_BASE = {3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59,
            67, 83, 99, 115, 131, 163, 195, 227, 258};

    static final int[] LENGTH_EXTRA = {0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4,
            5, 5, 5, 5, 0};

    static final int[] DIST_BASE = {1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513,
            769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577};

    static final int[] DIST_EXTRA = {0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10,
            11, 11, 12, 12, 13, 13};

    static final int[] LENGTH_SYMBOL = new int[MAX_MATCH + 1];

    private static final int[] DIST_SYMBOL_SMALL = new int[512];

    private static final int[] DIST_SYMBOL_LARGE = new int[256];

    static final int[] CL_ORDER = {16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15};

    static {
        for (int code = 0; code < 29; code++) {
            int end = code == 28 ? 258 : LENGTH_BASE[code] + (1 << LENGTH_EXTRA[code]) - 1;
            for (int l = LENGTH_BASE[code]; l <= Math.min(end, 258); l++) {
                LENGTH_SYMBOL[l] = code;
            }
        }
        LENGTH_SYMBOL[258] = 28;
        for (int l = 0; l <= MAX_MATCH; l++) {
            int n = l + 1;
            while (n <= MAX_MATCH && LENGTH_SYMBOL[n] == LENGTH_SYMBOL[l] && n < MAX_MATCH) {
                n++;
            }
            NEXT[l] = n;
        }
        for (int d = 1; d <= 512; d++) {
            DIST_SYMBOL_SMALL[d - 1] = distSymbolSlow(d);
        }
        for (int k = 4; k < 256; k++) {
            DIST_SYMBOL_LARGE[k] = distSymbolSlow((k << 7) + 1);
        }
    }

    static int distSymbol(int d) {
        return d <= 512 ? DIST_SYMBOL_SMALL[d - 1] : DIST_SYMBOL_LARGE[(d - 1) >> 7];
    }

    private static int distSymbolSlow(int d) {
        for (int c = 29; c >= 0; c--) {
            if (d >= DIST_BASE[c]) {
                return c;
            }
        }
        return 0;
    }

    private DeflateTables() {}
}

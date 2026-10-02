package stirling.software.officeconvert.jpx;

record ComponentStyle(int levels, int blockWidth, int blockHeight, int blockStyle, boolean reversible, int[] ppx,
        int[] ppy) {

    static final int BYPASS = 1;
    static final int RESET = 2;
    static final int TERMALL = 4;
    static final int CAUSAL = 8;
    static final int SEGMARK = 32;

    static ComponentStyle read(Bytes b, boolean precincts) throws JpxException {
        int levels = b.u8();
        int cbw = b.u8() + 2;
        int cbh = b.u8() + 2;
        int style = b.u8();
        int transform = b.u8();
        if (levels > 32 || cbw > 10 || cbh > 10 || cbw + cbh > 12 || transform > 1) {
            throw new JpxException("Invalid JPEG 2000 coding style");
        }
        if ((style & 0xC0) != 0) {
            throw new JpxException("JPEG 2000 high-throughput or mixed code-blocks are not supported");
        }
        int[] ppx = new int[levels + 1];
        int[] ppy = new int[levels + 1];
        for (int r = 0; r <= levels; r++) {
            if (precincts) {
                int v = b.u8();
                ppx[r] = v & 0xF;
                ppy[r] = v >> 4;
                if (r > 0 && (ppx[r] == 0 || ppy[r] == 0)) {
                    throw new JpxException("Invalid JPEG 2000 precinct size");
                }
            } else {
                ppx[r] = 15;
                ppy[r] = 15;
            }
        }
        return new ComponentStyle(levels, cbw, cbh, style, transform == 1, ppx, ppy);
    }

    boolean has(int flag) {
        return (blockStyle & flag) != 0;
    }
}

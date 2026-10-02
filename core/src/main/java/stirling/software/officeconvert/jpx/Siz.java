package stirling.software.officeconvert.jpx;

record Siz(long width, long height, long x0, long y0, long tileWidth, long tileHeight, long tileX0, long tileY0,
        int[] depth, boolean[] signed, int[] dx, int[] dy) {

    static final int MAX_DEPTH = 30;

    static final long MAX_TILE_COMPONENTS = 1 << 21;

    static Siz read(Bytes b) throws JpxException {
        int length = b.u16();
        int end = b.pos() - 2 + length;
        b.u16();
        long xsiz = b.u32();
        long ysiz = b.u32();
        long xo = b.u32();
        long yo = b.u32();
        long xt = b.u32();
        long yt = b.u32();
        long xto = b.u32();
        long yto = b.u32();
        int n = b.u16();
        if (n == 0 || n > 16384 || length != 38 + 3 * n) {
            throw new JpxException("Invalid JPEG 2000 image size header");
        }
        int[] depth = new int[n];
        boolean[] signed = new boolean[n];
        int[] dx = new int[n];
        int[] dy = new int[n];
        for (int c = 0; c < n; c++) {
            int s = b.u8();
            depth[c] = (s & 0x7F) + 1;
            signed[c] = (s & 0x80) != 0;
            dx[c] = b.u8();
            dy[c] = b.u8();
            if (depth[c] > MAX_DEPTH || dx[c] == 0 || dy[c] == 0) {
                throw new JpxException("Unsupported JPEG 2000 component " + c);
            }
        }
        b.seek(end);
        if (xsiz > Integer.MAX_VALUE || ysiz > Integer.MAX_VALUE) {
            throw new JpxException("JPEG 2000 image is too large");
        }
        if (xo >= xsiz || yo >= ysiz || xt == 0 || yt == 0 || xto > xo || yto > yo || xto + xt <= xo
                || yto + yt <= yo) {
            throw new JpxException("Invalid JPEG 2000 image geometry");
        }
        Siz siz = new Siz(xsiz, ysiz, xo, yo, xt, yt, xto, yto, depth, signed, dx, dy);
        if (siz.tilesX() * siz.tilesY() > 65535 || siz.tilesX() * siz.tilesY() * n > MAX_TILE_COMPONENTS) {
            throw new JpxException("JPEG 2000 image has too many tiles");
        }
        return siz;
    }

    int components() {
        return depth.length;
    }

    boolean uniform() {
        for (int c = 1; c < dx.length; c++) {
            if (dx[c] != dx[0] || dy[c] != dy[0]) {
                return false;
            }
        }
        return true;
    }

    long tilesX() {
        return ceilDiv(width - tileX0, tileWidth);
    }

    long tilesY() {
        return ceilDiv(height - tileY0, tileHeight);
    }

    int tiles() {
        return (int) (tilesX() * tilesY());
    }

    long[] tileRect(int index) {
        long p = index % tilesX();
        long q = index / tilesX();
        return new long[] {Math.max(tileX0 + p * tileWidth, x0), Math.max(tileY0 + q * tileHeight, y0),
                Math.min(tileX0 + (p + 1) * tileWidth, width), Math.min(tileY0 + (q + 1) * tileHeight, height)};
    }

    static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    static long ceilShift(long a, int shift) {
        return shift >= 63 ? (a > 0 ? 1 : 0) : (a + (1L << shift) - 1) >> shift;
    }
}

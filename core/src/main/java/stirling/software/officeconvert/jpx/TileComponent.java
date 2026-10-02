package stirling.software.officeconvert.jpx;

final class TileComponent {

    static final int MAX_PRECINCTS = 1 << 22;

    final int index;

    final int x0;

    final int y0;

    final int x1;

    final int y1;

    final ComponentStyle style;

    final int roi;

    final int top;

    final Resolution[] resolutions;

    TileComponent(Siz siz, int c, long[] tile, ComponentStyle style, Quant quant, int roi, int reduce,
            BlockBudget budget) throws JpxException {
        this.index = c;
        this.style = style;
        this.roi = roi;
        x0 = (int) Siz.ceilDiv(tile[0], siz.dx()[c]);
        y0 = (int) Siz.ceilDiv(tile[1], siz.dy()[c]);
        x1 = (int) Siz.ceilDiv(tile[2], siz.dx()[c]);
        y1 = (int) Siz.ceilDiv(tile[3], siz.dy()[c]);
        int levels = style.levels();
        top = levels - Math.min(reduce, levels);
        resolutions = new Resolution[levels + 1];
        long precincts = 0;
        for (int r = 0; r <= levels; r++) {
            int shift = levels - r;
            int rx0 = (int) Siz.ceilShift(x0, shift);
            int ry0 = (int) Siz.ceilShift(y0, shift);
            int rx1 = (int) Siz.ceilShift(x1, shift);
            int ry1 = (int) Siz.ceilShift(y1, shift);
            int ppx = style.ppx()[r];
            int ppy = style.ppy()[r];
            long wide = rx1 > rx0 ? Siz.ceilShift(rx1, ppx) - (rx0 >> ppx) : 0;
            long high = ry1 > ry0 ? Siz.ceilShift(ry1, ppy) - (ry0 >> ppy) : 0;
            precincts += wide * high;
            if (precincts > MAX_PRECINCTS) {
                throw new JpxException("JPEG 2000 tile has too many precincts");
            }
            Band[] bands = r == 0 ? new Band[] {band(siz, c, quant, Band.LL, levels, 0, rx0, ry0, rx1, ry1)}
                    : new Band[] {band(siz, c, quant, Band.HL, levels, r, 0, 0, 0, 0),
                            band(siz, c, quant, Band.LH, levels, r, 0, 0, 0, 0),
                            band(siz, c, quant, Band.HH, levels, r, 0, 0, 0, 0)};
            resolutions[r] = new Resolution(r, rx0, ry0, rx1, ry1, ppx, ppy, bands, budget);
        }
    }

    private Band band(Siz siz, int c, Quant quant, int orient, int levels, int r, int lx0, int ly0, int lx1, int ly1) {
        int bx0 = lx0;
        int by0 = ly0;
        int bx1 = lx1;
        int by1 = ly1;
        int cbw = Math.min(style.blockWidth(), style.ppx()[r] - (r == 0 ? 0 : 1));
        int cbh = Math.min(style.blockHeight(), style.ppy()[r] - (r == 0 ? 0 : 1));
        if (r > 0) {
            int nb = levels - r + 1;
            long xo = (orient & 1) != 0 ? 1L << (nb - 1) : 0;
            long yo = (orient & 2) != 0 ? 1L << (nb - 1) : 0;
            long d = 1L << nb;
            bx0 = (int) Math.ceilDiv(x0 - xo, d);
            by0 = (int) Math.ceilDiv(y0 - yo, d);
            bx1 = (int) Math.ceilDiv(x1 - xo, d);
            by1 = (int) Math.ceilDiv(y1 - yo, d);
        }
        int exponent = quant.exponent(levels, r, orient);
        int mantissa = quant.mantissa(r, orient);
        int depth = siz.depth()[c];
        float step = (float) (Math.scalb(1.0, depth - exponent) * (1.0 + mantissa / 2048.0));
        return new Band(orient, bx0, by0, bx1, by1, Math.max(0, cbw), Math.max(0, cbh),
                quant.guard() + exponent - 1, step);
    }

    int width() {
        return resolutions[top].width();
    }

    int height() {
        return resolutions[top].height();
    }
}

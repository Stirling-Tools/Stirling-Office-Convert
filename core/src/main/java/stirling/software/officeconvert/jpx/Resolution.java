package stirling.software.officeconvert.jpx;

final class Resolution {

    final int level;

    final int x0;

    final int y0;

    final int x1;

    final int y1;

    final int ppx;

    final int ppy;

    final int precinctsWide;

    final int precinctsHigh;

    final Band[] bands;

    private final Precinct[] precincts;

    final int[] nextLayer;

    private final BlockBudget budget;

    Resolution(int level, int x0, int y0, int x1, int y1, int ppx, int ppy, Band[] bands, BlockBudget budget) {
        this.budget = budget;
        this.level = level;
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.ppx = ppx;
        this.ppy = ppy;
        this.bands = bands;
        precinctsWide = x1 > x0 ? (int) (Siz.ceilShift(x1, ppx) - (x0 >> ppx)) : 0;
        precinctsHigh = y1 > y0 ? (int) (Siz.ceilShift(y1, ppy) - (y0 >> ppy)) : 0;
        precincts = new Precinct[precinctsWide * precinctsHigh];
        nextLayer = new int[precincts.length];
    }

    int precincts() {
        return precincts.length;
    }

    int width() {
        return x1 - x0;
    }

    int height() {
        return y1 - y0;
    }

    Precinct precinct(int k) throws JpxException {
        Precinct p = precincts[k];
        if (p == null) {
            int i = k % precinctsWide;
            int j = k / precinctsWide;
            long px0 = ((long) (x0 >> ppx) + i) << ppx;
            long py0 = ((long) (y0 >> ppy) + j) << ppy;
            int shift = level == 0 ? 0 : 1;
            PrecinctBand[] pb = new PrecinctBand[bands.length];
            for (int b = 0; b < bands.length; b++) {
                pb[b] = new PrecinctBand(bands[b], clamp(px0 >> shift), clamp(py0 >> shift),
                        clamp((px0 + (1L << ppx)) >> shift), clamp((py0 + (1L << ppy)) >> shift), budget);
            }
            p = new Precinct(pb);
            precincts[k] = p;
        }
        return p;
    }

    Precinct existing(int k) {
        return precincts[k];
    }

    private static int clamp(long v) {
        return (int) Math.min(Integer.MAX_VALUE, v);
    }
}

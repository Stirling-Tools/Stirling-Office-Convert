package stirling.software.officeconvert.jpx;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

final class TileCoder {

    static final int PRECINCT = 15;

    static final class Blocks {
        final int band;
        final int gw;
        final int gh;
        final int[] x0;
        final int[] y0;
        final int[] x1;
        final int[] y1;
        final EncodedBlock[] coded;
        TagTreeEncoder inclusion;
        TagTreeEncoder zeros;
        int[] lblock;
        int[] sent;

        Blocks(int band, int gw, int gh) {
            this.band = band;
            this.gw = gw;
            this.gh = gh;
            int n = gw * gh;
            x0 = new int[n];
            y0 = new int[n];
            x1 = new int[n];
            y1 = new int[n];
            coded = new EncodedBlock[n];
        }
    }

    static final class Resolution {
        final int x0;
        final int y0;
        final int x1;
        final int y1;
        final int wide;
        final int high;
        final int[][] bands;
        final int[] orients;
        final Blocks[][] precincts;

        Resolution(int x0, int y0, int x1, int y1, int[][] bands, int[] orients) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.bands = bands;
            this.orients = orients;
            wide = x1 > x0 ? ceilShift(x1, PRECINCT) - (x0 >> PRECINCT) : 0;
            high = y1 > y0 ? ceilShift(y1, PRECINCT) - (y0 >> PRECINCT) : 0;
            precincts = new Blocks[wide * high][];
        }
    }

    static final class Component {
        final int x0;
        final int y0;
        final int x1;
        final int y1;
        final Resolution[] resolutions;

        Component(int x0, int y0, int x1, int y1, Resolution[] resolutions) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.resolutions = resolutions;
        }
    }

    final int index;

    final int x0;

    final int y0;

    final int x1;

    final int y1;

    final Component[] components;

    TileCoder(int index, int x0, int y0, int x1, int y1, int levels, int blockLog, int[] dx, int[] dy) {
        this.index = index;
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        components = new Component[dx.length];
        for (int c = 0; c < dx.length; c++) {
            int cx0 = Math.ceilDiv(x0, dx[c]);
            int cy0 = Math.ceilDiv(y0, dy[c]);
            int cx1 = Math.ceilDiv(x1, dx[c]);
            int cy1 = Math.ceilDiv(y1, dy[c]);
            Resolution[] res = new Resolution[levels + 1];
            for (int r = 0; r <= levels; r++) {
                int shift = levels - r;
                int rx0 = ceilShift(cx0, shift);
                int ry0 = ceilShift(cy0, shift);
                int rx1 = ceilShift(cx1, shift);
                int ry1 = ceilShift(cy1, shift);
                int[][] bands;
                int[] orients;
                if (r == 0) {
                    bands = new int[][] {{rx0, ry0, rx1, ry1}};
                    orients = new int[] {Band.LL};
                } else {
                    orients = new int[] {Band.HL, Band.LH, Band.HH};
                    bands = new int[3][];
                    int nb = levels - r + 1;
                    for (int b = 0; b < 3; b++) {
                        int o = orients[b];
                        long xo = (o & 1) != 0 ? 1L << (nb - 1) : 0;
                        long yo = (o & 2) != 0 ? 1L << (nb - 1) : 0;
                        long d = 1L << nb;
                        bands[b] = new int[] {(int) Math.ceilDiv(cx0 - xo, d), (int) Math.ceilDiv(cy0 - yo, d),
                            (int) Math.ceilDiv(cx1 - xo, d), (int) Math.ceilDiv(cy1 - yo, d)};
                    }
                }
                Resolution resolution = new Resolution(rx0, ry0, rx1, ry1, bands, orients);
                int cb = Math.min(blockLog, PRECINCT - (r == 0 ? 0 : 1));
                for (int k = 0; k < resolution.precincts.length; k++) {
                    int i = k % resolution.wide;
                    int j = k / resolution.wide;
                    long px0 = ((long) (rx0 >> PRECINCT) + i) << PRECINCT;
                    long py0 = ((long) (ry0 >> PRECINCT) + j) << PRECINCT;
                    int s = r == 0 ? 0 : 1;
                    Blocks[] pb = new Blocks[bands.length];
                    for (int b = 0; b < bands.length; b++) {
                        pb[b] = blocks(b, bands[b], (int) (px0 >> s), (int) (py0 >> s),
                                (int) Math.min(Integer.MAX_VALUE, (px0 + (1L << PRECINCT)) >> s),
                                (int) Math.min(Integer.MAX_VALUE, (py0 + (1L << PRECINCT)) >> s), cb);
                    }
                    resolution.precincts[k] = pb;
                }
                res[r] = resolution;
            }
            components[c] = new Component(cx0, cy0, cx1, cy1, res);
        }
    }

    private static Blocks blocks(int band, int[] b, int px0, int py0, int px1, int py1, int cb) {
        int bx0 = Math.max(px0, b[0]);
        int by0 = Math.max(py0, b[1]);
        int bx1 = Math.min(px1, b[2]);
        int by1 = Math.min(py1, b[3]);
        if (bx1 <= bx0 || by1 <= by0) {
            return new Blocks(band, 0, 0);
        }
        int gx0 = Math.floorDiv(bx0, 1 << cb);
        int gy0 = Math.floorDiv(by0, 1 << cb);
        int gx1 = Math.ceilDiv(bx1, 1 << cb);
        int gy1 = Math.ceilDiv(by1, 1 << cb);
        Blocks blocks = new Blocks(band, gx1 - gx0, gy1 - gy0);
        for (int j = 0; j < blocks.gh; j++) {
            for (int i = 0; i < blocks.gw; i++) {
                int k = j * blocks.gw + i;
                blocks.x0[k] = Math.max(bx0, (gx0 + i) << cb);
                blocks.y0[k] = Math.max(by0, (gy0 + j) << cb);
                blocks.x1[k] = Math.min(bx1, (gx0 + i + 1) << cb);
                blocks.y1[k] = Math.min(by1, (gy0 + j + 1) << cb);
            }
        }
        return blocks;
    }

    static int ceilShift(long v, int shift) {
        return (int) -((-v) >> shift);
    }

    int[][] resolutionBoxes(int c) {
        Resolution[] res = components[c].resolutions;
        int[][] boxes = new int[res.length][];
        for (int r = 0; r < res.length; r++) {
            boxes[r] = new int[] {res[r].x0, res[r].y0, res[r].x1, res[r].y1};
        }
        return boxes;
    }

    void codeReversible(int c, int[] coefficients, Quantiser q, BlockPool pool, boolean measure)
            throws InterruptedIOException {
        Component comp = components[c];
        int stride = comp.x1 - comp.x0;
        Resolution[] res = comp.resolutions;
        List<BlockPool.Job> jobs = new ArrayList<>();
        for (int r = 0; r < res.length; r++) {
            Resolution resolution = res[r];
            for (Blocks[] precinct : resolution.precincts) {
                for (Blocks blocks : precinct) {
                    int orient = resolution.orients[blocks.band];
                    int[] band = resolution.bands[blocks.band];
                    int ox = r > 0 && (orient & 1) != 0 ? res[r - 1].x1 - res[r - 1].x0 : 0;
                    int oy = r > 0 && (orient & 2) != 0 ? res[r - 1].y1 - res[r - 1].y0 : 0;
                    double weight = q.weight(c, r, orient);
                    for (int k = 0; k < blocks.coded.length; k++) {
                        int b = k;
                        jobs.add((coder, scratch) -> {
                            int w = blocks.x1[b] - blocks.x0[b];
                            int h = blocks.y1[b] - blocks.y0[b];
                            int at = (oy + blocks.y0[b] - band[1]) * stride + ox + blocks.x0[b] - band[0];
                            for (int y = 0; y < h; y++) {
                                System.arraycopy(coefficients, at + y * stride, scratch, y * w, w);
                            }
                            EncodedBlock e = coder.encode(scratch, w, h, orient, 0, measure);
                            e.weigh(weight);
                            blocks.coded[b] = e;
                        });
                    }
                }
            }
        }
        pool.run(jobs);
        settle(c, q);
    }

    void codeIrreversible(int c, float[] coefficients, Quantiser q, BlockPool pool) throws InterruptedIOException {
        Component comp = components[c];
        int stride = comp.x1 - comp.x0;
        Resolution[] res = comp.resolutions;
        List<BlockPool.Job> jobs = new ArrayList<>();
        for (int r = 0; r < res.length; r++) {
            Resolution resolution = res[r];
            for (Blocks[] precinct : resolution.precincts) {
                for (Blocks blocks : precinct) {
                    int orient = resolution.orients[blocks.band];
                    int[] band = resolution.bands[blocks.band];
                    int ox = r > 0 && (orient & 1) != 0 ? res[r - 1].x1 - res[r - 1].x0 : 0;
                    int oy = r > 0 && (orient & 2) != 0 ? res[r - 1].y1 - res[r - 1].y0 : 0;
                    double weight = q.weight(c, r, orient);
                    double step = q.step(c, r, orient);
                    for (int k = 0; k < blocks.coded.length; k++) {
                        int b = k;
                        jobs.add((coder, scratch) -> {
                            int w = blocks.x1[b] - blocks.x0[b];
                            int h = blocks.y1[b] - blocks.y0[b];
                            int at = (oy + blocks.y0[b] - band[1]) * stride + ox + blocks.x0[b] - band[0];
                            float max = 0;
                            for (int y = 0; y < h; y++) {
                                int s = at + y * stride;
                                for (int x = 0; x < w; x++) {
                                    max = Math.max(max, Math.abs(coefficients[s + x]));
                                }
                            }
                            double top = max / step;
                            int bits = top < 1 ? 0 : Math.getExponent(top) + 1;
                            int fraction = Math.clamp(30 - bits, 1, 8);
                            double scale = Math.scalb(1.0, fraction) / step;
                            for (int y = 0; y < h; y++) {
                                int s = at + y * stride;
                                int d = y * w;
                                for (int x = 0; x < w; x++) {
                                    float v = coefficients[s + x];
                                    int m = (int) Math.min(Integer.MAX_VALUE >> 1, Math.abs(v) * scale);
                                    scratch[d + x] = v < 0 ? -m : m;
                                }
                            }
                            EncodedBlock e = coder.encode(scratch, w, h, orient, fraction, true);
                            e.weigh(weight * Math.scalb(1.0, -2 * fraction));
                            blocks.coded[b] = e;
                        });
                    }
                }
            }
        }
        pool.run(jobs);
        settle(c, q);
    }

    private void settle(int c, Quantiser q) {
        Resolution[] res = components[c].resolutions;
        for (int r = 0; r < res.length; r++) {
            for (Blocks[] precinct : res[r].precincts) {
                for (Blocks blocks : precinct) {
                    int orient = res[r].orients[blocks.band];
                    for (EncodedBlock e : blocks.coded) {
                        q.planes(c, r, orient, e.planes);
                    }
                }
            }
        }
    }
}

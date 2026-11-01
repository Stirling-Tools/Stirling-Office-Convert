package stirling.software.officeconvert.lineart;

import java.util.Arrays;

final class Paper {

    static final double SOLID = 0.6;

    static final double FLAT = 12;

    static final long LOOKAHEAD_PIXELS = 2_000_000;

    private static final int BINS = 64;

    private static final int SHIFT = 2;

    private static final double PERCENTILE = 0.9;

    private static final float BIN_SCALE = BINS / 65536f;

    private final int width;

    private final int height;

    private final int tile;

    private final int across;

    private final int down;

    private final int reach;

    private final int soften;

    private final int[] widened;

    private final int[] raised;

    private final boolean[] raisedReady;

    private final float[] level;

    private final boolean[] ready;

    private final int[] histogram;

    private final int[] bin;

    private final int[] left;

    private final float[] blend;

    private int fed;

    private static final int MIN_TILE = 15;

    Paper(int width, int height, int window) {
        this.width = width;
        this.height = height;
        this.tile = Math.max(window, MIN_TILE);
        this.across = Math.ceilDiv(width, tile);
        this.down = Math.ceilDiv(height, tile);
        this.reach = reach(width, height, tile);
        this.soften = soften(reach);
        this.widened = new int[across * down];
        this.raised = new int[across * down];
        this.raisedReady = new boolean[down];
        this.level = new float[across * down];
        this.ready = new boolean[down];
        this.histogram = new int[across * BINS];
        this.bin = new int[width];
        this.left = new int[width];
        this.blend = new float[width];
        for (int x = 0; x < width; x++) {
            bin[x] = (x / tile) * BINS;
            double fx = Math.clamp((x + 0.5) / tile - 0.5, 0, across - 1);
            left[x] = (int) fx;
            blend[x] = (float) (fx - left[x]);
        }
    }

    static int lookahead(int width, int height, int window) {
        int tile = Math.max(window, MIN_TILE);
        int reach = reach(width, height, tile);
        return (int) Math.min(height, ((long) reach + soften(reach) + 2) * tile);
    }

    private static int soften(int reach) {
        return Math.ceilDiv(reach, 2);
    }

    private static int reach(int width, int height, int window) {
        int wanted = Math.ceilDiv(Math.max(2 * window, Math.min(width, height) / 3), window);
        long affordable = (LOOKAHEAD_PIXELS / ((long) width * window) - 2) * 2 / 3;
        return (int) Math.max(1, Math.min(wanted, affordable));
    }

    void feed(float[] buffer, int first, int until) {
        for (int y = fed; y < until; y++) {
            int base = (y - first) * width;
            for (int x = 0; x < width; x++) {
                histogram[bin[x] + Math.clamp((int) (buffer[base + x] * BIN_SCALE), 0, BINS - 1)]++;
            }
            if ((y + 1) % tile == 0 || y + 1 == height) {
                finishRow(y / tile);
            }
        }
        fed = Math.max(fed, until);
    }

    void row(int y, float[] out) {
        double fy = Math.clamp((y + 0.5) / tile - 0.5, 0, down - 1);
        int y0 = (int) fy;
        int y1 = Math.min(down - 1, y0 + 1);
        float wy = (float) (fy - y0);
        int top = levels(y0);
        int bottom = levels(y1);
        float[] column = new float[across];
        for (int tx = 0; tx < across; tx++) {
            column[tx] = level[top + tx] + (level[bottom + tx] - level[top + tx]) * wy;
        }
        for (int x = 0; x < width; x++) {
            int x0 = left[x];
            int x1 = Math.min(across - 1, x0 + 1);
            out[x] = column[x0] + (column[x1] - column[x0]) * blend[x];
        }
    }

    private void finishRow(int ty) {
        int[] bright = new int[across];
        for (int tx = 0; tx < across; tx++) {
            bright[tx] = percentile(tx * BINS);
        }
        Arrays.fill(histogram, 0);
        System.arraycopy(bright, 0, widened, ty * across, across);
    }

    private int raisedRow(int ty) {
        int offset = ty * across;
        if (!raisedReady[ty]) {
            int last = Math.min(down - 1, ty + reach);
            if (fed < Math.min(height, (last + 1) * tile)) {
                throw new IllegalStateException("tile row " + last + " is not complete");
            }
            for (int tx = 0; tx < across; tx++) {
                int best = 0;
                for (int t = Math.max(0, ty - reach); t <= last; t++) {
                    int dy = t - ty;
                    int span = (int) Math.sqrt((double) reach * reach - dy * dy);
                    int row = t * across;
                    for (int x = Math.max(0, tx - span); x <= Math.min(across - 1, tx + span); x++) {
                        best = Math.max(best, widened[row + x]);
                    }
                }
                raised[offset + tx] = best;
            }
            raisedReady[ty] = true;
        }
        return offset;
    }

    private int levels(int ty) {
        int offset = ty * across;
        if (!ready[ty]) {
            int from = Math.max(0, ty - soften);
            int to = Math.min(down - 1, ty + soften);
            float[] sums = new float[across];
            for (int t = from; t <= to; t++) {
                int row = raisedRow(t);
                for (int tx = 0; tx < across; tx++) {
                    sums[tx] += raised[row + tx];
                }
            }
            int rows = to - from + 1;
            for (int tx = 0; tx < across; tx++) {
                int x0 = Math.max(0, tx - soften);
                int x1 = Math.min(across - 1, tx + soften);
                float total = 0;
                for (int x = x0; x <= x1; x++) {
                    total += sums[x];
                }
                level[offset + tx] = total / (rows * (x1 - x0 + 1));
            }
            ready[ty] = true;
        }
        return offset;
    }

    private int percentile(int offset) {
        long count = 0;
        for (int b = 0; b < BINS; b++) {
            count += histogram[offset + b];
        }
        if (count == 0) {
            return 255;
        }
        long target = (long) Math.ceil(count * PERCENTILE);
        long seen = 0;
        for (int b = 0; b < BINS; b++) {
            seen += histogram[offset + b];
            if (seen >= target) {
                return (b << SHIFT) + (1 << SHIFT) / 2;
            }
        }
        return 255;
    }

    static void grow(Bitmap out, Bitmap core, Bitmap edge, int radius) {
        int w = core.width();
        int h = core.height();
        int block = Math.max(1, radius / 8);
        int cw = Math.ceilDiv(w, block);
        int ch = Math.ceilDiv(h, block);
        byte[] near = new byte[cw * ch];
        byte[] cores = core.packed();
        int stride = core.stride();
        for (int y = 0; y < h; y++) {
            int row = y * stride;
            int cy = y / block * cw;
            for (int i = 0; i < stride; i++) {
                int bits = cores[row + i] & 0xFF;
                while (bits != 0) {
                    int bit = Integer.numberOfLeadingZeros(bits) - 24;
                    near[cy + (i * 8 + bit) / block] = 1;
                    bits &= ~(0x80 >>> bit);
                }
            }
        }
        byte[] wide = new byte[near.length];
        byte[] spare = new byte[near.length];
        int steps = Math.ceilDiv(radius, block);
        for (int step = 0; step < steps; step++) {
            LineArt.checkpoint();
            for (int cy = 0; cy < ch; cy++) {
                int row = cy * cw;
                for (int cx = 0; cx < cw; cx++) {
                    int c = row + cx;
                    wide[c] = (byte) (near[c] | (cx > 0 ? near[c - 1] : 0) | (cx + 1 < cw ? near[c + 1] : 0));
                }
            }
            byte[] vertical = step % 2 == 0 ? wide : near;
            for (int c = 0; c < near.length; c++) {
                spare[c] = (byte) (wide[c] | (c >= cw ? vertical[c - cw] : 0)
                        | (c + cw < near.length ? vertical[c + cw] : 0));
            }
            byte[] swap = near;
            near = spare;
            spare = swap;
        }
        byte[] ink = out.packed();
        byte[] dark = edge.packed();
        for (int y = 0; y < h; y++) {
            int row = y * stride;
            int cy = y / block * cw;
            for (int i = 0; i < stride; i++) {
                int bits = dark[row + i] & 0xFF;
                int keep = 0;
                while (bits != 0) {
                    int bit = Integer.numberOfLeadingZeros(bits) - 24;
                    if (near[cy + (i * 8 + bit) / block] != 0) {
                        keep |= 0x80 >>> bit;
                    }
                    bits &= ~(0x80 >>> bit);
                }
                ink[row + i] |= (byte) keep;
            }
        }
    }
}

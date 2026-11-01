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

    private static final int LANES = 4;

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

    private final int[] segmentEnd;

    private final float[] column;

    private final int[] bright;

    private final int[] forward;

    private final int[] prefix;

    private int fed;

    private static final long MAX_TILES = 1 << 16;

    Paper(int width, int height, int window) {
        this.width = width;
        this.height = height;
        this.tile = tile(width, height, window);
        this.across = Math.ceilDiv(width, tile);
        this.down = Math.ceilDiv(height, tile);
        this.reach = reach(width, height, tile);
        this.soften = soften(reach);
        this.widened = new int[across * down];
        this.raised = new int[across * down];
        this.raisedReady = new boolean[down];
        this.level = new float[across * down];
        this.ready = new boolean[down];
        this.histogram = new int[across * BINS * LANES];
        this.bin = new int[width];
        this.left = new int[width];
        this.blend = new float[width];
        this.segmentEnd = new int[across];
        this.column = new float[across];
        this.bright = new int[across];
        this.forward = new int[across + 2 * reach + 1];
        this.prefix = new int[across + 2 * reach + 1];
        for (int x = 0; x < width; x++) {
            bin[x] = ((x / tile) * BINS) * LANES + (x & (LANES - 1));
            double fx = Math.clamp((x + 0.5) / tile - 0.5, 0, across - 1);
            left[x] = (int) fx;
            blend[x] = (float) (fx - left[x]);
            segmentEnd[left[x]] = x + 1;
        }
    }

    static int lookahead(int width, int height, int window) {
        int tile = tile(width, height, window);
        int reach = reach(width, height, tile);
        return (int) Math.min(height, ((long) reach + soften(reach) + 2) * tile);
    }

    static int tile(int width, int height, int window) {
        return Math.max(window, (int) Math.ceil(Math.sqrt((double) width * height / MAX_TILES)));
    }

    private static int soften(int reach) {
        return Math.ceilDiv(reach, 2);
    }

    private static int reach(int width, int height, int window) {
        int wanted = Math.ceilDiv(Math.max(2 * window, Math.min(width, height) / 3), window);
        long affordable = (LOOKAHEAD_PIXELS / ((long) width * window) - 2) * 2 / 3;
        return (int) Math.max(1, Math.min(wanted, affordable));
    }

    void feed(float[] rows, int capacity, int until) {
        for (int y = fed; y < until; y++) {
            int base = (y % capacity) * width;
            for (int x = 0; x < width; x++) {
                histogram[bin[x] + Math.clamp((int) (rows[base + x] * BIN_SCALE), 0, BINS - 1) * LANES]++;
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
        for (int tx = 0; tx < across; tx++) {
            column[tx] = level[top + tx] + (level[bottom + tx] - level[top + tx]) * wy;
        }
        for (int x = 0; x < width; ) {
            int x0 = left[x];
            float from = column[x0];
            float rise = column[Math.min(across - 1, x0 + 1)] - from;
            for (int end = segmentEnd[x0]; x < end; x++) {
                out[x] = from + rise * blend[x];
            }
        }
    }

    private void finishRow(int ty) {
        for (int tx = 0; tx < across; tx++) {
            bright[tx] = percentile(tx * BINS * LANES);
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
            Arrays.fill(raised, offset, offset + across, 0);
            for (int t = Math.max(0, ty - reach); t <= last; t++) {
                int dy = t - ty;
                widen(t * across, (int) Math.sqrt((double) reach * reach - dy * dy), offset);
            }
            raisedReady[ty] = true;
        }
        return offset;
    }

    private void widen(int row, int span, int into) {
        int size = across + 2 * span;
        int block = 2 * span + 1;
        int[] padded = forward;
        for (int i = 0; i < size; i++) {
            int x = i - span;
            padded[i] = x >= 0 && x < across ? widened[row + x] : 0;
        }
        for (int start = 0; start < size; start += block) {
            int end = Math.min(size, start + block);
            int best = 0;
            for (int i = start; i < end; i++) {
                best = Math.max(best, padded[i]);
                prefix[i] = best;
            }
            best = 0;
            for (int i = end - 1; i >= start; i--) {
                best = Math.max(best, padded[i]);
                padded[i] = best;
            }
        }
        for (int tx = 0; tx < across; tx++) {
            int windowMax = Math.max(padded[tx], prefix[tx + block - 1]);
            raised[into + tx] = Math.max(raised[into + tx], windowMax);
        }
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
        for (int b = 0; b < BINS; b++) {
            int at = offset + b * LANES;
            int total = 0;
            for (int lane = 0; lane < LANES; lane++) {
                total += histogram[at + lane];
            }
            histogram[offset + b] = total;
        }
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
        int words = (cw + 63) >>> 6;
        long[] near = cells(core, block, cw, ch, words);
        long[] wide = new long[near.length];
        long[] spare = new long[near.length];
        long last = (cw & 63) == 0 ? -1L : (1L << (cw & 63)) - 1;
        int steps = Math.ceilDiv(radius, block);
        for (int step = 0; step < steps; step++) {
            LineArt.checkpoint();
            for (int row = 0; row < near.length; row += words) {
                for (int i = 0; i < words; i++) {
                    long n = near[row + i];
                    long before = i > 0 ? near[row + i - 1] >>> 63 : 0;
                    long after = i + 1 < words ? near[row + i + 1] << 63 : 0;
                    long grown = n | n << 1 | before | n >>> 1 | after;
                    wide[row + i] = i + 1 == words ? grown & last : grown;
                }
            }
            long[] vertical = step % 2 == 0 ? wide : near;
            for (int c = 0; c < near.length; c++) {
                spare[c] = wide[c] | (c >= words ? vertical[c - words] : 0)
                        | (c + words < near.length ? vertical[c + words] : 0);
            }
            long[] swap = near;
            near = spare;
            spare = swap;
        }
        byte[] ink = out.packed();
        byte[] dark = edge.packed();
        int stride = core.stride();
        byte[] mask = new byte[stride];
        for (int cy = 0; cy < ch; cy++) {
            Arrays.fill(mask, (byte) 0);
            boolean any = false;
            for (int i = 0; i < words; i++) {
                long bits = near[cy * words + i];
                while (bits != 0) {
                    int c = i * 64 + Long.numberOfTrailingZeros(bits);
                    bits &= bits - 1;
                    for (int x = c * block, end = Math.min(w, x + block); x < end; x++) {
                        mask[x >>> 3] |= (byte) (0x80 >>> (x & 7));
                    }
                    any = true;
                }
            }
            if (!any) {
                continue;
            }
            for (int y = cy * block, end = Math.min(h, y + block); y < end; y++) {
                int row = y * stride;
                for (int i = 0; i < stride; i++) {
                    ink[row + i] |= (byte) (dark[row + i] & mask[i]);
                }
            }
        }
    }

    private static long[] cells(Bitmap core, int block, int cw, int ch, int words) {
        int w = core.width();
        int h = core.height();
        int stride = core.stride();
        byte[] cores = core.packed();
        long[] near = new long[words * ch];
        byte[] merged = new byte[stride];
        for (int cy = 0; cy < ch; cy++) {
            Arrays.fill(merged, (byte) 0);
            for (int y = cy * block, end = Math.min(h, y + block); y < end; y++) {
                int row = y * stride;
                for (int i = 0; i < stride; i++) {
                    merged[i] |= cores[row + i];
                }
            }
            int offset = cy * words;
            for (int i = 0; i < stride; i++) {
                int bits = merged[i] & 0xFF;
                while (bits != 0) {
                    int bit = Integer.numberOfLeadingZeros(bits) - 24;
                    bits &= ~(0x80 >>> bit);
                    int c = (i * 8 + bit) / block;
                    near[offset + (c >>> 6)] |= 1L << c;
                }
            }
        }
        return near;
    }
}

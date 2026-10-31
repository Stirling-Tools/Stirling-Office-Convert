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

    private final int[] widened;

    private final int[] level;

    private final boolean[] ready;

    private final int[] histogram;

    private final int[] bin;

    private final int[] left;

    private final float[] blend;

    private int fed;

    Paper(int width, int height, int window) {
        this.width = width;
        this.height = height;
        this.tile = window;
        this.across = Math.ceilDiv(width, tile);
        this.down = Math.ceilDiv(height, tile);
        this.reach = reach(width, height, window);
        this.widened = new int[across * down];
        this.level = new int[across * down];
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
        return (int) Math.min(height, (reach(width, height, window) + 2L) * window);
    }

    private static int reach(int width, int height, int window) {
        int wanted = Math.ceilDiv(Math.max(2 * window, Math.min(width, height) / 3), window);
        long affordable = LOOKAHEAD_PIXELS / ((long) width * window) - 2;
        return (int) Math.max(1, Math.min(wanted, affordable));
    }

    void feed(float[] buffer, int first, int until) {
        for (int y = fed; y < until; y++) {
            LineArt.checkpoint();
            int base = (y - first) * width;
            for (int x = 0; x < width; x++) {
                histogram[bin[x] + Math.clamp((int) (buffer[base + x] * BIN_SCALE), 0, BINS - 1)]++;
            }
            if ((y + 1) % tile == 0 || y + 1 == height) {
                finishRow(y / tile, y % tile + 1);
            }
        }
        fed = Math.max(fed, until);
    }

    void row(int y, int[] out) {
        double fy = Math.clamp((y + 0.5) / tile - 0.5, 0, down - 1);
        int y0 = (int) fy;
        int y1 = Math.min(down - 1, y0 + 1);
        double wy = fy - y0;
        int top = levels(y0);
        int bottom = levels(y1);
        float[] column = new float[across];
        for (int tx = 0; tx < across; tx++) {
            column[tx] = (float) (level[top + tx] * (1 - wy) + level[bottom + tx] * wy);
        }
        for (int x = 0; x < width; x++) {
            int x0 = left[x];
            int x1 = Math.min(across - 1, x0 + 1);
            out[x] = Math.round(column[x0] + (column[x1] - column[x0]) * blend[x]);
        }
    }

    private void finishRow(int ty, int rows) {
        int[] bright = new int[across];
        for (int tx = 0; tx < across; tx++) {
            int count = (Math.min(width, (tx + 1) * tile) - tx * tile) * rows;
            bright[tx] = percentile(tx * BINS, count);
        }
        Arrays.fill(histogram, 0);
        for (int tx = 0; tx < across; tx++) {
            int best = 0;
            for (int x = Math.max(0, tx - reach); x <= Math.min(across - 1, tx + reach); x++) {
                best = Math.max(best, bright[x]);
            }
            widened[ty * across + tx] = best;
        }
    }

    private int levels(int ty) {
        int offset = ty * across;
        if (!ready[ty]) {
            int last = Math.min(down - 1, ty + reach);
            if (fed < Math.min(height, (last + 1) * tile)) {
                throw new IllegalStateException("tile row " + last + " is not complete");
            }
            for (int tx = 0; tx < across; tx++) {
                int best = 0;
                for (int t = Math.max(0, ty - reach); t <= last; t++) {
                    best = Math.max(best, widened[t * across + tx]);
                }
                level[offset + tx] = best;
            }
            ready[ty] = true;
        }
        return offset;
    }

    private int percentile(int offset, int count) {
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

    static void fill(Bitmap out, Bitmap core, Bitmap dark, int radius) {
        int w = out.width();
        int h = out.height();
        int[] counts = new int[w];
        for (int y = 0; y < Math.min(h, radius); y++) {
            addRow(core, y, counts, 1);
        }
        for (int y = 0; y < h; y++) {
            LineArt.checkpoint();
            if (y + radius < h) {
                addRow(core, y + radius, counts, 1);
            }
            if (y - radius - 1 >= 0) {
                addRow(core, y - radius - 1, counts, -1);
            }
            int near = 0;
            for (int x = 0; x < Math.min(w, radius); x++) {
                near += counts[x] > 0 ? 1 : 0;
            }
            for (int x = 0; x < w; x++) {
                if (x + radius < w && counts[x + radius] > 0) {
                    near++;
                }
                if (x - radius - 1 >= 0 && counts[x - radius - 1] > 0) {
                    near--;
                }
                if (near > 0 && dark.ink(x, y)) {
                    out.set(x, y);
                }
            }
        }
    }

    private static void addRow(Bitmap bitmap, int y, int[] counts, int sign) {
        for (int x = 0; x < counts.length; x++) {
            if (bitmap.ink(x, y)) {
                counts[x] += sign;
            }
        }
    }
}

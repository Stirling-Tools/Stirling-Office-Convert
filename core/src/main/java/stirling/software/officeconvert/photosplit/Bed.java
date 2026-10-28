package stirling.software.officeconvert.photosplit;

import java.util.Arrays;
import java.util.function.IntConsumer;

final class Bed {

    private static final int ROUNDS = 3;
    private static final int DESPECKLE = 2;
    private static final int CELL_STEP = 1;
    private static final float SHADE_MIN_BED = 100;
    private static final float SHADE_MAX = 0.98f;
    private static final float SHADE_SPREAD = 0.06f;

    final int global;
    final double noise;
    private final int cell;
    private final int gw;
    private final int gh;
    private final float[][] grid;

    private Bed(int global, double noise, int cell, int gw, int gh, float[][] grid) {
        this.global = global;
        this.noise = noise;
        this.cell = cell;
        this.gw = gw;
        this.gh = gh;
        this.grid = grid;
    }

    static Bed fit(ScanImage scan, int[] smooth, int tolerance) {
        int w = scan.width;
        int h = scan.height;
        int global = globalColour(scan);
        double noise = noise(scan, smooth, global, tolerance);
        int band = (int) Math.round(Math.clamp(4 * noise + 3, 5, Math.max(5, tolerance)));
        int cell = Math.clamp(Math.min(w, h) / 48, 12, 96);
        int gw = (w + cell - 1) / cell;
        int gh = (h + cell - 1) / cell;
        float[][] ref = new float[3][gw * gh];
        for (int k = 0; k < 3; k++) {
            Arrays.fill(ref[k], global >> (16 - 8 * k) & 0xFF);
        }
        for (int round = 0; round < ROUNDS; round++) {
            ref = cells(smooth, w, h, cell, gw, gh, ref, round == 0 ? tolerance : band);
        }
        return new Bed(global, noise, cell, gw, gh, ref);
    }

    int threshold(int tolerance) {
        return (int) Math.round(Math.clamp(4 * noise + 4, 6, Math.max(6, tolerance)));
    }

    int faintThreshold(int strong, int radius, int wideRadius) {
        double wideNoise = noise * (2 * radius + 1) / (2 * wideRadius + 1);
        return (int) Math.round(Math.clamp(4 * wideNoise + 1.5, 3, Math.max(3, strong)));
    }

    byte[] foreground(int[] smooth, int w, int h, int threshold, byte[] photo) {
        byte[] mask = new byte[w * h];
        int[] left = new int[w];
        int[] right = new int[w];
        float[] fx = new float[w];
        for (int x = 0; x < w; x++) {
            double gx = Math.clamp((x + 0.5) / cell - 0.5, 0, gw - 1);
            left[x] = (int) gx;
            right[x] = Math.min(left[x] + 1, gw - 1);
            fx[x] = (float) (gx - left[x]);
        }
        float[] top = new float[3 * w];
        float[] bottom = new float[3 * w];
        int band = -1;
        for (int y = 0; y < h; y++) {
            PhotoSplitter.checkpoint();
            double gy = Math.clamp((y + 0.5) / cell - 0.5, 0, gh - 1);
            int y0 = (int) gy;
            float fy = (float) (gy - y0);
            if (y0 != band) {
                band = y0;
                interpolate(y0 * gw, left, right, fx, top);
                interpolate(Math.min(y0 + 1, gh - 1) * gw, left, right, fx, bottom);
            }
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float mr = top[3 * x] + (bottom[3 * x] - top[3 * x]) * fy;
                float mg = top[3 * x + 1] + (bottom[3 * x + 1] - top[3 * x + 1]) * fy;
                float mb = top[3 * x + 2] + (bottom[3 * x + 2] - top[3 * x + 2]) * fy;
                int p = smooth[i];
                int r = p >> 16 & 0xFF;
                int g = p >> 8 & 0xFF;
                int b = p & 0xFF;
                float d = Math.max(Math.abs(r - mr), Math.max(Math.abs(g - mg), Math.abs(b - mb)));
                if (d > threshold) {
                    mask[i] = 1;
                    if (photo != null && !shade(r, g, b, mr, mg, mb)) {
                        photo[i] = 1;
                    }
                }
            }
        }
        return mask;
    }

    private static boolean shade(int r, int g, int b, float mr, float mg, float mb) {
        if (mr + mg + mb < 3 * SHADE_MIN_BED) {
            return false;
        }
        float kr = r / Math.max(1f, mr);
        float kg = g / Math.max(1f, mg);
        float kb = b / Math.max(1f, mb);
        float hi = Math.max(kr, Math.max(kg, kb));
        float lo = Math.min(kr, Math.min(kg, kb));
        return hi < SHADE_MAX && hi - lo < SHADE_SPREAD;
    }

    private void interpolate(int base, int[] left, int[] right, float[] fx, float[] out) {
        for (int x = 0; x < left.length; x++) {
            for (int k = 0; k < 3; k++) {
                float[] g = grid[k];
                out[3 * x + k] = g[base + left[x]] + (g[base + right[x]] - g[base + left[x]]) * fx[x];
            }
        }
    }

    private static float[][] cells(int[] smooth, int w, int h, int cell, int gw, int gh, float[][] ref, int band) {
        float[][] out = new float[3][gw * gh];
        boolean[] valid = new boolean[gw * gh];
        boolean any = false;
        for (int cy = 0; cy < gh; cy++) {
            PhotoSplitter.checkpoint();
            for (int cx = 0; cx < gw; cx++) {
                int c = cy * gw + cx;
                long[] sum = new long[3];
                int count = 0;
                int total = 0;
                for (int y = cy * cell; y < Math.min(h, (cy + 1) * cell); y += CELL_STEP) {
                    for (int x = cx * cell; x < Math.min(w, (cx + 1) * cell); x += CELL_STEP) {
                        int p = smooth[y * w + x];
                        int r = p >> 16 & 0xFF;
                        int g = p >> 8 & 0xFF;
                        int b = p & 0xFF;
                        total++;
                        if (Math.abs(r - ref[0][c]) <= band && Math.abs(g - ref[1][c]) <= band && Math.abs(b - ref[2][c]) <= band) {
                            sum[0] += r;
                            sum[1] += g;
                            sum[2] += b;
                            count++;
                        }
                    }
                }
                if (count * 4 >= total) {
                    valid[c] = true;
                    any = true;
                    for (int k = 0; k < 3; k++) {
                        out[k][c] = (float) sum[k] / count;
                    }
                }
            }
        }
        if (!any) {
            return ref;
        }
        despeckle(out, valid, gw, gh, band / 2f);
        fill(out, valid, gw, gh);
        return out;
    }

    private static void despeckle(float[][] grid, boolean[] valid, int gw, int gh, float limit) {
        float[][] copy = {grid[0].clone(), grid[1].clone(), grid[2].clone()};
        float[] near = new float[(2 * DESPECKLE + 1) * (2 * DESPECKLE + 1)];
        for (int cy = 0; cy < gh; cy++) {
            PhotoSplitter.checkpoint();
            for (int cx = 0; cx < gw; cx++) {
                int c = cy * gw + cx;
                if (!valid[c]) {
                    continue;
                }
                for (int k = 0; k < 3; k++) {
                    int n = 0;
                    for (int dy = -DESPECKLE; dy <= DESPECKLE; dy++) {
                        for (int dx = -DESPECKLE; dx <= DESPECKLE; dx++) {
                            int nx = cx + dx;
                            int ny = cy + dy;
                            if (nx >= 0 && ny >= 0 && nx < gw && ny < gh && valid[ny * gw + nx]) {
                                near[n++] = copy[k][ny * gw + nx];
                            }
                        }
                    }
                    if (n < 3) {
                        continue;
                    }
                    Arrays.sort(near, 0, n);
                    float median = n % 2 == 1 ? near[n / 2] : (near[n / 2 - 1] + near[n / 2]) / 2;
                    if (Math.abs(copy[k][c] - median) > limit) {
                        grid[k][c] = median;
                    }
                }
            }
        }
    }

    private static void fill(float[][] grid, boolean[] valid, int gw, int gh) {
        boolean[] next = valid.clone();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int cy = 0; cy < gh; cy++) {
                PhotoSplitter.checkpoint();
                for (int cx = 0; cx < gw; cx++) {
                    int c = cy * gw + cx;
                    if (valid[c]) {
                        continue;
                    }
                    float[] sum = new float[3];
                    int n = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = cx + dx;
                            int ny = cy + dy;
                            if (nx >= 0 && ny >= 0 && nx < gw && ny < gh && valid[ny * gw + nx]) {
                                for (int k = 0; k < 3; k++) {
                                    sum[k] += grid[k][ny * gw + nx];
                                }
                                n++;
                            }
                        }
                    }
                    if (n > 0) {
                        for (int k = 0; k < 3; k++) {
                            grid[k][c] = sum[k] / n;
                        }
                        next[c] = true;
                        changed = true;
                    }
                }
            }
            System.arraycopy(next, 0, valid, 0, valid.length);
        }
    }

    static int globalColour(ScanImage scan) {
        int w = scan.width;
        int h = scan.height;
        int ring = Math.max(2, Math.min(w, h) / 100);
        int[] bins = new int[4096];
        forRing(w, h, ring, i -> bins[bin(scan.rgb[i])]++);
        int best = 0;
        int bestScore = -1;
        for (int b = 0; b < bins.length; b++) {
            if (bins[b] == 0) {
                continue;
            }
            int score = 0;
            int r = b >> 8;
            int g = b >> 4 & 15;
            int bl = b & 15;
            for (int dr = -1; dr <= 1; dr++) {
                for (int dg = -1; dg <= 1; dg++) {
                    for (int db = -1; db <= 1; db++) {
                        int rr = r + dr;
                        int gg = g + dg;
                        int bb = bl + db;
                        if (rr >= 0 && gg >= 0 && bb >= 0 && rr < 16 && gg < 16 && bb < 16) {
                            score += bins[rr << 8 | gg << 4 | bb];
                        }
                    }
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = b;
            }
        }
        int[] centre = {(best >> 8) * 16 + 8, (best >> 4 & 15) * 16 + 8, (best & 15) * 16 + 8};
        int[][] hist = new int[3][256];
        int[] count = new int[1];
        forRing(w, h, ring, i -> {
            int p = scan.rgb[i];
            int r = p >> 16 & 0xFF;
            int g = p >> 8 & 0xFF;
            int b = p & 0xFF;
            if (Math.abs(r - centre[0]) <= 24 && Math.abs(g - centre[1]) <= 24 && Math.abs(b - centre[2]) <= 24) {
                hist[0][r]++;
                hist[1][g]++;
                hist[2][b]++;
                count[0]++;
            }
        });
        int out = 0;
        for (int k = 0; k < 3; k++) {
            int seen = 0;
            int v = 0;
            while (v < 255 && (seen += hist[k][v]) * 2 < count[0]) {
                v++;
            }
            out |= v << (16 - 8 * k);
        }
        return out;
    }

    private static double noise(ScanImage scan, int[] smooth, int global, int tolerance) {
        int w = scan.width;
        int h = scan.height;
        int ring = Math.max(8, Math.min(w, h) / 25);
        int step = 5;
        int[] diffs = new int[512];
        int n = 0;
        int stride = Math.max(1, (int) Math.sqrt((double) ring * (w + h) * 2 / 40000));
        for (int y = 0; y < h; y += stride) {
            PhotoSplitter.checkpoint();
            for (int x = 0; x + step < w; x += stride) {
                if (y >= ring && y < h - ring && x >= ring && x < w - ring - step) {
                    continue;
                }
                int a = smooth[y * w + x];
                int b = smooth[y * w + x + step];
                if (near(scan.rgb[y * w + x], global, tolerance) && near(scan.rgb[y * w + x + step], global, tolerance)) {
                    for (int s = 16; s >= 0; s -= 8) {
                        int d = Math.abs((a >> s & 0xFF) - (b >> s & 0xFF));
                        diffs[Math.min(d, 511)]++;
                        n++;
                    }
                }
            }
        }
        if (n == 0) {
            return 1;
        }
        int seen = 0;
        int median = 0;
        while (median < 511 && (seen += diffs[median]) * 2 < n) {
            median++;
        }
        return Math.max(0.5, median * 1.4826 / Math.sqrt(2));
    }

    private static boolean near(int p, int q, int tolerance) {
        return Math.abs((p >> 16 & 0xFF) - (q >> 16 & 0xFF)) <= tolerance && Math.abs((p >> 8 & 0xFF) - (q >> 8 & 0xFF)) <= tolerance
                && Math.abs((p & 0xFF) - (q & 0xFF)) <= tolerance;
    }

    private static int bin(int p) {
        return (p >> 20 & 15) << 8 | (p >> 12 & 15) << 4 | (p >> 4 & 15);
    }

    private static void forRing(int w, int h, int ring, IntConsumer action) {
        for (int y = 0; y < h; y++) {
            PhotoSplitter.checkpoint();
            boolean band = y < ring || y >= h - ring;
            for (int x = 0; x < w; x++) {
                if (band || x < ring || x >= w - ring) {
                    action.accept(y * w + x);
                }
            }
        }
    }
}

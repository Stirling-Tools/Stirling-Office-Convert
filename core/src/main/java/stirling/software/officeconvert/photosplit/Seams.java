package stirling.software.officeconvert.photosplit;

import java.util.ArrayList;
import java.util.List;

final class Seams {

    private static final int MAX_DEPTH = 4;
    private static final int SEARCH = 6;
    private static final int SUBSAMPLE = 2;
    private static final double SEAM_SHARE = 0.04;
    private static final double SIDE_DENSITY = 0.3;
    private static final double CHORD_SHARE = 0.5;
    private static final double RUN_SHARE = 0.06;
    private static final int MIN_RUN = 16;
    private static final int MIN_REACH = 48;
    private static final int REACH_PER_RUN = 3;
    private static final int EDGE_SKIP = 2;
    private static final int EDGE_BINS = 4;
    private static final double EDGE_DENSITY = 0.5;

    private record Cut(double nx, double ny, double from, double to, double balance) {}

    private Seams() {}

    static List<Region> split(List<Region> regions, byte[] photo, int w, PhotoSplitter.Options options) {
        List<Region> out = new ArrayList<>();
        for (Region r : regions) {
            out.addAll(split(r, photo, w, options, 0));
        }
        return out;
    }

    private static List<Region> split(Region region, byte[] photo, int w, PhotoSplitter.Options options, int depth) {
        if (depth >= MAX_DEPTH || region.count < 2L * Math.max(1, options.minArea())) {
            return List.of(region);
        }
        Cut cut = find(region, photo, w, options);
        if (cut == null) {
            return List.of(region);
        }
        List<Region> out = new ArrayList<>();
        for (Region half : apply(region, cut)) {
            out.addAll(split(half, photo, w, options, depth + 1));
        }
        return out;
    }

    private static Cut find(Region region, byte[] photo, int w, PhotoSplitter.Options options) {
        RotatedRect rect = region.rect();
        int bw = region.width();
        int bh = region.height();
        int maxRun = (int) Math.max(MIN_RUN, RUN_SHARE * Math.min(rect.width(), rect.height()));
        long minSide = Math.max(1, options.minArea()) / (SUBSAMPLE * SUBSAMPLE);
        Cut best = null;
        for (int axis = 0; axis < 2; axis++) {
            for (int delta = -SEARCH; delta <= SEARCH; delta++) {
                double phi = Math.toRadians(rect.angle() + axis * 90 + delta);
                double nx = -Math.sin(phi);
                double ny = Math.cos(phi);
                double lo = Math.min(Math.min(0, bw * nx), Math.min(bh * ny, bw * nx + bh * ny));
                double hi = Math.max(Math.max(0, bw * nx), Math.max(bh * ny, bw * nx + bh * ny));
                int n = (int) Math.ceil(hi - lo) + 2;
                int[] reg = new int[n];
                int[] pho = new int[n];
                for (int y = 0; y < bh; y += SUBSAMPLE) {
                    int row = (region.minY + y) * w + region.minX;
                    for (int x = 0; x < bw; x += SUBSAMPLE) {
                        if (region.mask[y * bw + x] == 0) {
                            continue;
                        }
                        int i = (int) (x * nx + y * ny - lo);
                        reg[i]++;
                        pho[i] += photo[row + x];
                    }
                }
                Cut cut = bestRun(reg, pho, maxRun, minSide);
                if (cut != null && (best == null || cut.balance() > best.balance())) {
                    best = new Cut(nx, ny, cut.from() + lo, cut.to() + lo, cut.balance());
                }
            }
        }
        return best;
    }

    private static Cut bestRun(int[] reg, int[] pho, int maxRun, long minSide) {
        int n = reg.length;
        int peak = 0;
        long total = 0;
        long totalPhoto = 0;
        for (int i = 0; i < n; i++) {
            peak = Math.max(peak, reg[i]);
            total += reg[i];
            totalPhoto += pho[i];
        }
        int first = 0;
        while (first < n && reg[first] == 0) {
            first++;
        }
        int last = n - 1;
        while (last > 0 && reg[last] == 0) {
            last--;
        }
        Cut best = null;
        long before = 0;
        long beforePhoto = 0;
        int i = 0;
        while (i < n) {
            if (!seam(reg[i], pho[i], peak)) {
                before += reg[i];
                beforePhoto += pho[i];
                i++;
                continue;
            }
            int start = i;
            long run = 0;
            long runPhoto = 0;
            while (i < n && seam(reg[i], pho[i], peak)) {
                run += reg[i];
                runPhoto += pho[i];
                i++;
            }
            long after = total - before - run;
            long afterPhoto = totalPhoto - beforePhoto - runPhoto;
            int width = i - start;
            int reachBefore = start - first;
            int reachAfter = last - (i - 1);
            int minReach = Math.max(MIN_REACH, width * REACH_PER_RUN);
            if (width <= maxRun && before >= minSide && after >= minSide && beforePhoto >= SIDE_DENSITY * before
                    && afterPhoto >= SIDE_DENSITY * after && reachBefore >= minReach && reachAfter >= minReach
                    && edge(reg, pho, start - 1 - EDGE_SKIP, -1) && edge(reg, pho, i + EDGE_SKIP, 1)) {
                double balance = Math.min(before, after);
                if (best == null || balance > best.balance()) {
                    best = new Cut(0, 0, start, i - 1, balance);
                }
            }
            before += run;
            beforePhoto += runPhoto;
        }
        return best;
    }

    private static boolean edge(int[] reg, int[] pho, int from, int step) {
        long r = 0;
        long p = 0;
        for (int k = 0, i = from; k < EDGE_BINS && i >= 0 && i < reg.length; k++, i += step) {
            r += reg[i];
            p += pho[i];
        }
        return r > 0 && p >= EDGE_DENSITY * r;
    }

    private static boolean seam(int reg, int pho, int peak) {
        return reg > 0 && reg >= CHORD_SHARE * peak && pho <= SEAM_SHARE * reg;
    }

    private static List<Region> apply(Region region, Cut cut) {
        int bw = region.width();
        int bh = region.height();
        List<Region> out = new ArrayList<>();
        for (int side = 0; side < 2; side++) {
            int minX = bw;
            int minY = bh;
            int maxX = -1;
            int maxY = -1;
            for (int y = 0; y < bh; y++) {
                for (int x = 0; x < bw; x++) {
                    if (region.mask[y * bw + x] != 0 && on(cut, x, y, side)) {
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y);
                        maxY = Math.max(maxY, y);
                    }
                }
            }
            if (maxX < 0) {
                continue;
            }
            int pw = maxX - minX + 1;
            byte[] sub = new byte[pw * (maxY - minY + 1)];
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    if (region.mask[y * bw + x] != 0 && on(cut, x, y, side)) {
                        sub[(y - minY) * pw + x - minX] = 1;
                    }
                }
            }
            out.add(new Region(region.minX + minX, region.minY + minY, region.minX + maxX, region.minY + maxY, sub));
        }
        return out;
    }

    private static boolean on(Cut cut, int x, int y, int side) {
        double off = x * cut.nx() + y * cut.ny();
        return side == 0 ? off < cut.from() : off >= cut.to() + 1;
    }
}

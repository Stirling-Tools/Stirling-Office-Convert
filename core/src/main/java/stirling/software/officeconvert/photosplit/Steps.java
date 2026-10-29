package stirling.software.officeconvert.photosplit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class Steps {

    private static final int SEARCH = 6;
    private static final int SUBSAMPLE = 2;
    private static final int SEGMENTS = 6;
    private static final int MIN_SEGMENTS = 3;
    private static final int REACH = 3;
    private static final double MIN_STEP = 36;
    private static final double CHORD_SHARE = 0.6;
    private static final int MIN_REACH = 48;
    private static final double PART_SOLID = 0.85;
    private static final double LENGTH_GAP = 6;
    private static final double LENGTH_SHARE = 0.03;
    private static final double TURN_GAP = 0.5;
    private static final double SHARPNESS = 0.5;
    private static final int SLIVER = 4;
    private static final double MAX_FILL = 0.95;
    private static final double PART_BALANCE = 0.25;

    private record Line(double nx, double ny, double at, double score) {}

    private Steps() {}

    static List<Region> cut(Region region, ScanImage scan, PhotoSplitter.Options options) {
        if (region.count >= MAX_FILL * region.rect().area()) {
            return null;
        }
        Line line = find(region, scan);
        if (line == null) {
            return null;
        }
        List<Region> parts = apply(region, line);
        if (parts.size() != 2) {
            return null;
        }
        RotatedRect a = parts.get(0).rect();
        RotatedRect b = parts.get(1).rect();
        for (int k = 0; k < 2; k++) {
            Region part = parts.get(k);
            RotatedRect r = k == 0 ? a : b;
            if (r.area() < options.minArea() || part.count < PART_SOLID * r.area()
                    || Math.min(r.width(), r.height()) < MIN_REACH) {
                return null;
            }
        }
        double shortA = Math.min(a.width(), a.height());
        double shortB = Math.min(b.width(), b.height());
        if (Math.min(shortA, shortB) < PART_BALANCE * Math.max(shortA, shortB)) {
            return null;
        }
        double seam = Math.toDegrees(Math.atan2(line.nx(), -line.ny()));
        double la = along(a, seam);
        double lb = along(b, seam);
        double turn = Math.abs(a.angle() - b.angle());
        boolean distinct = Math.abs(la - lb) > Math.max(LENGTH_GAP, LENGTH_SHARE * Math.max(la, lb)) || turn > TURN_GAP;
        return distinct ? parts : null;
    }

    private static double along(RotatedRect r, double seamDegrees) {
        double d = Math.abs(((r.angle() - seamDegrees) % 180 + 180) % 180);
        return d < 45 || d > 135 ? r.width() : r.height();
    }

    private static Line find(Region region, ScanImage scan) {
        RotatedRect rect = region.rect();
        int bw = region.width();
        int bh = region.height();
        int angles = 4 * SEARCH + 1;
        Line[] lines = new Line[2 * angles];
        Parallel.each(lines.length, index -> {
            int axis = index / angles;
            int step = index % angles - 2 * SEARCH;
            Line best = null;
            double phi = Math.toRadians(rect.angle() + axis * 90 + step * 0.5);
            double tx = Math.cos(phi);
            double ty = Math.sin(phi);
            double nx = -ty;
            double ny = tx;
            double lo = Math.min(Math.min(0, bw * nx), Math.min(bh * ny, bw * nx + bh * ny));
            double hi = Math.max(Math.max(0, bw * nx), Math.max(bh * ny, bw * nx + bh * ny));
            double tlo = Math.min(Math.min(0, bw * tx), Math.min(bh * ty, bw * tx + bh * ty));
            double thi = Math.max(Math.max(0, bw * tx), Math.max(bh * ty, bw * tx + bh * ty));
            int n = (int) Math.ceil(hi - lo) + 2;
            double segLength = (thi - tlo) / SEGMENTS + 1e-9;
            int[] count = new int[n * SEGMENTS];
            int[] sums = new int[n * SEGMENTS * 3];
            int[] reg = new int[n];
            for (int y = 0; y < bh; y += SUBSAMPLE) {
                PhotoSplitter.checkpoint();
                int row = (region.minY + y) * scan.width + region.minX;
                for (int x = 0; x < bw; x += SUBSAMPLE) {
                    if (region.mask[y * bw + x] == 0) {
                        continue;
                    }
                    int i = (int) (x * nx + y * ny - lo);
                    int k = Math.min(SEGMENTS - 1, (int) ((x * tx + y * ty - tlo) / segLength));
                    int cell = i * SEGMENTS + k;
                    int p = scan.rgb[row + x];
                    count[cell]++;
                    sums[3 * cell] += p >> 16 & 0xFF;
                    sums[3 * cell + 1] += p >> 8 & 0xFF;
                    sums[3 * cell + 2] += p & 0xFF;
                    reg[i]++;
                }
            }
            int peak = 0;
            int first = n;
            int last = -1;
            for (int i = 0; i < n; i++) {
                peak = Math.max(peak, reg[i]);
                if (reg[i] > 0) {
                    first = Math.min(first, i);
                    last = i;
                }
            }
            double[] score = new double[n];
            for (int i = first + MIN_REACH; i <= last - MIN_REACH; i++) {
                if (reg[i] < CHORD_SHARE * peak) {
                    continue;
                }
                double[] steps = new double[SEGMENTS];
                int valid = 0;
                for (int k = 0; k < SEGMENTS; k++) {
                    double wide = step(count, sums, i - REACH, i + REACH, k);
                    double near = step(count, sums, i - 1, i + 1, k);
                    if (wide < 0 || near < 0) {
                        continue;
                    }
                    steps[valid++] = near >= SHARPNESS * wide ? wide : 0;
                }
                if (valid >= MIN_SEGMENTS) {
                    Arrays.sort(steps, 0, valid);
                    score[i] = steps[valid / 2 - (valid % 2 == 0 ? 1 : 0)];
                }
            }
            for (int i = first + MIN_REACH; i <= last - MIN_REACH; i++) {
                if (score[i] >= MIN_STEP && score[i] >= score[i - 1] && score[i] >= score[i + 1]
                        && (best == null || score[i] > best.score())) {
                    best = new Line(nx, ny, i + lo, score[i]);
                }
            }
            lines[index] = best;
        });
        Line best = null;
        for (Line line : lines) {
            if (line != null && (best == null || line.score() > best.score())) {
                best = line;
            }
        }
        return best;
    }

    private static double step(int[] count, int[] sums, int from, int to, int k) {
        int before = from * SEGMENTS + k;
        int after = to * SEGMENTS + k;
        if (count[before] < 2 || count[after] < 2) {
            return -1;
        }
        double step = 0;
        for (int ch = 0; ch < 3; ch++) {
            step += Math.abs((double) sums[3 * after + ch] / count[after] - (double) sums[3 * before + ch] / count[before]);
        }
        return step;
    }

    static Region clean(Region part, int radius) {
        int w = part.width();
        int h = part.height();
        byte[] mask = part.mask.clone();
        Morphology.erode(mask, w, h, radius);
        Morphology.dilate(mask, w, h, radius);
        Region best = null;
        for (Region r : Region.label(mask, w, h)) {
            if (best == null || r.count > best.count) {
                best = r;
            }
        }
        if (best == null) {
            return null;
        }
        return new Region(part.minX + best.minX, part.minY + best.minY, part.minX + best.maxX, part.minY + best.maxY, best.mask);
    }

    private static List<Region> apply(Region region, Line line) {
        int bw = region.width();
        int bh = region.height();
        List<Region> out = new ArrayList<>();
        for (int side = 0; side < 2; side++) {
            int minX = bw;
            int minY = bh;
            int maxX = -1;
            int maxY = -1;
            for (int y = 0; y < bh; y++) {
                PhotoSplitter.checkpoint();
                for (int x = 0; x < bw; x++) {
                    if (region.mask[y * bw + x] != 0 && on(line, x, y, side)) {
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
                PhotoSplitter.checkpoint();
                for (int x = minX; x <= maxX; x++) {
                    if (region.mask[y * bw + x] != 0 && on(line, x, y, side)) {
                        sub[(y - minY) * pw + x - minX] = 1;
                    }
                }
            }
            Region part = clean(new Region(region.minX + minX, region.minY + minY, region.minX + maxX, region.minY + maxY, sub), SLIVER);
            if (part != null) {
                out.add(part);
            }
        }
        return out;
    }

    private static boolean on(Line line, int x, int y, int side) {
        double off = x * line.nx() + y * line.ny();
        return side == 0 ? off < line.at() : off >= line.at() + 1;
    }
}

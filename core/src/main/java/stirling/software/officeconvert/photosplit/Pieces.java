package stirling.software.officeconvert.photosplit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class Pieces {

    private static final int MAX_DEPTH = 4;
    private static final double PART_SOLID = 0.85;
    private static final double SEED_SHARE = 0.002;

    private Pieces() {}

    static List<Region> separate(Region region, PhotoSplitter.Options options) {
        return separate(region, options, 0);
    }

    private static List<Region> separate(Region region, PhotoSplitter.Options options, int depth) {
        if (depth >= MAX_DEPTH || region.count < 2L * Math.max(1, options.minContourArea())
                || StraightenMethod.solid(region, region.rect())) {
            return List.of(region);
        }
        int bw = region.width();
        int bh = region.height();
        int[] dist = chamfer(region.mask, bw, bh);
        int max = 0;
        for (int d : dist) {
            max = Math.max(max, d);
        }
        int minSeed = (int) Math.max(25, region.count * SEED_SHARE);
        for (int r = 6; r < max * 0.9; r = (int) (r * 1.2) + 3) {
            List<int[]> seeds = seeds(dist, bw, bh, r, minSeed);
            if (seeds.size() < 2) {
                continue;
            }
            List<Region> parts = grow(region, seeds);
            List<Region> valid = new ArrayList<>();
            for (Region part : parts) {
                RotatedRect rect = part.rect();
                if (rect.area() >= options.minArea() && part.count >= options.minContourArea()
                        && part.count >= PART_SOLID * rect.area()) {
                    valid.add(part);
                }
            }
            if (valid.size() >= 2) {
                List<Region> out = new ArrayList<>();
                for (Region part : valid) {
                    out.addAll(separate(part, options, depth + 1));
                }
                return out;
            }
        }
        return List.of(region);
    }

    private static int[] chamfer(byte[] mask, int w, int h) {
        int big = Integer.MAX_VALUE / 4;
        int[] d = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (mask[i] == 0) {
                    continue;
                }
                int v = big;
                v = Math.min(v, x > 0 ? d[i - 1] + 3 : 3);
                v = Math.min(v, y > 0 ? d[i - w] + 3 : 3);
                v = Math.min(v, x > 0 && y > 0 ? d[i - w - 1] + 4 : 4);
                v = Math.min(v, x < w - 1 && y > 0 ? d[i - w + 1] + 4 : 4);
                d[i] = v;
            }
        }
        for (int y = h - 1; y >= 0; y--) {
            for (int x = w - 1; x >= 0; x--) {
                int i = y * w + x;
                if (mask[i] == 0) {
                    continue;
                }
                int v = d[i];
                v = Math.min(v, x < w - 1 ? d[i + 1] + 3 : 3);
                v = Math.min(v, y < h - 1 ? d[i + w] + 3 : 3);
                v = Math.min(v, x < w - 1 && y < h - 1 ? d[i + w + 1] + 4 : 4);
                v = Math.min(v, x > 0 && y < h - 1 ? d[i + w - 1] + 4 : 4);
                d[i] = v;
            }
        }
        return d;
    }

    private static List<int[]> seeds(int[] dist, int w, int h, int r, int minSeed) {
        byte[] core = new byte[w * h];
        for (int i = 0; i < core.length; i++) {
            core[i] = dist[i] > r ? (byte) 1 : 0;
        }
        List<int[]> out = new ArrayList<>();
        IntStack stack = new IntStack();
        for (int start = 0; start < core.length; start++) {
            if (core[start] != 1) {
                continue;
            }
            int[] members = new int[64];
            int n = 0;
            core[start] = 2;
            stack.push(start);
            while (!stack.isEmpty()) {
                int p = stack.pop();
                if (n == members.length) {
                    members = Arrays.copyOf(members, n * 2);
                }
                members[n++] = p;
                int px = p % w;
                int py = p / w;
                for (int ny = Math.max(0, py - 1); ny <= Math.min(h - 1, py + 1); ny++) {
                    for (int nx = Math.max(0, px - 1); nx <= Math.min(w - 1, px + 1); nx++) {
                        int q = ny * w + nx;
                        if (core[q] == 1) {
                            core[q] = 2;
                            stack.push(q);
                        }
                    }
                }
            }
            if (n >= minSeed) {
                out.add(Arrays.copyOf(members, n));
            }
        }
        return out;
    }

    private static List<Region> grow(Region region, List<int[]> seeds) {
        int w = region.width();
        int h = region.height();
        int[] owner = new int[w * h];
        int[] queue = new int[w * h];
        int head = 0;
        int tail = 0;
        for (int s = 0; s < seeds.size(); s++) {
            for (int p : seeds.get(s)) {
                owner[p] = s + 1;
                queue[tail++] = p;
            }
        }
        while (head < tail) {
            int p = queue[head++];
            int px = p % w;
            int py = p / w;
            for (int ny = Math.max(0, py - 1); ny <= Math.min(h - 1, py + 1); ny++) {
                for (int nx = Math.max(0, px - 1); nx <= Math.min(w - 1, px + 1); nx++) {
                    int q = ny * w + nx;
                    if (region.mask[q] != 0 && owner[q] == 0) {
                        owner[q] = owner[p];
                        queue[tail++] = q;
                    }
                }
            }
        }
        List<Region> parts = new ArrayList<>();
        for (int s = 1; s <= seeds.size(); s++) {
            int minX = w;
            int minY = h;
            int maxX = -1;
            int maxY = -1;
            for (int i = 0; i < owner.length; i++) {
                if (owner[i] == s) {
                    minX = Math.min(minX, i % w);
                    maxX = Math.max(maxX, i % w);
                    minY = Math.min(minY, i / w);
                    maxY = Math.max(maxY, i / w);
                }
            }
            if (maxX < 0) {
                continue;
            }
            int pw = maxX - minX + 1;
            byte[] sub = new byte[pw * (maxY - minY + 1)];
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    if (owner[y * w + x] == s) {
                        sub[(y - minY) * pw + x - minX] = 1;
                    }
                }
            }
            parts.add(new Region(region.minX + minX, region.minY + minY, region.minX + maxX, region.minY + maxY, sub));
        }
        return parts;
    }
}

package stirling.software.officeconvert.photosplit;

final class Filters {

    private static final int ROWS_PER_STRIP = 64;
    private static final int COLUMNS_PER_STRIP = 64;

    private Filters() {}

    static int[] boxBlur(int[] rgb, int w, int h, int radius) {
        int[] tmp = new int[rgb.length];
        int size = 2 * radius + 1;
        Parallel.strips(h, ROWS_PER_STRIP, (from, to) -> {
            for (int y = from; y < to; y++) {
                PhotoSplitter.checkpoint();
                int base = y * w;
                int r = 0;
                int g = 0;
                int b = 0;
                for (int k = -radius; k <= radius; k++) {
                    int p = rgb[base + Math.clamp(k, 0, w - 1)];
                    r += p >> 16 & 0xFF;
                    g += p >> 8 & 0xFF;
                    b += p & 0xFF;
                }
                for (int x = 0; x < w; x++) {
                    tmp[base + x] = (r + size / 2) / size << 16 | (g + size / 2) / size << 8 | (b + size / 2) / size;
                    int add = rgb[base + Math.min(x + radius + 1, w - 1)];
                    int drop = rgb[base + Math.max(x - radius, 0)];
                    r += (add >> 16 & 0xFF) - (drop >> 16 & 0xFF);
                    g += (add >> 8 & 0xFF) - (drop >> 8 & 0xFF);
                    b += (add & 0xFF) - (drop & 0xFF);
                }
            }
        });
        int[] out = new int[rgb.length];
        Parallel.strips(w, COLUMNS_PER_STRIP, (from, to) -> {
            int span = to - from;
            int[] sr = new int[span];
            int[] sg = new int[span];
            int[] sb = new int[span];
            for (int k = -radius; k <= radius; k++) {
                int base = Math.clamp(k, 0, h - 1) * w + from;
                for (int x = 0; x < span; x++) {
                    int p = tmp[base + x];
                    sr[x] += p >> 16 & 0xFF;
                    sg[x] += p >> 8 & 0xFF;
                    sb[x] += p & 0xFF;
                }
            }
            int half = size / 2;
            for (int y = 0; y < h; y++) {
                PhotoSplitter.checkpoint();
                int base = y * w + from;
                int add = Math.min(y + radius + 1, h - 1) * w + from;
                int drop = Math.max(y - radius, 0) * w + from;
                for (int x = 0; x < span; x++) {
                    out[base + x] = (sr[x] + half) / size << 16 | (sg[x] + half) / size << 8 | (sb[x] + half) / size;
                    int a = tmp[add + x];
                    int d = tmp[drop + x];
                    sr[x] += (a >> 16 & 0xFF) - (d >> 16 & 0xFF);
                    sg[x] += (a >> 8 & 0xFF) - (d >> 8 & 0xFF);
                    sb[x] += (a & 0xFF) - (d & 0xFF);
                }
            }
        });
        return out;
    }

    static void grow(byte[] mask, byte[] reach, int w, int h) {
        IntStack stack = new IntStack();
        for (int i = 0; i < mask.length; i++) {
            if (mask[i] != 0) {
                stack.push(i);
            }
        }
        while (!stack.isEmpty()) {
            PhotoSplitter.checkpoint();
            int p = stack.pop();
            int x = p % w;
            int y = p / w;
            for (int ny = Math.max(0, y - 1); ny <= Math.min(h - 1, y + 1); ny++) {
                for (int nx = Math.max(0, x - 1); nx <= Math.min(w - 1, x + 1); nx++) {
                    int q = ny * w + nx;
                    if (mask[q] == 0 && reach[q] != 0) {
                        mask[q] = 1;
                        stack.push(q);
                    }
                }
            }
        }
    }

    static void fillHoles(byte[] mask, int w, int h) {
        byte outside = 2;
        IntStack stack = new IntStack();
        for (int x = 0; x < w; x++) {
            seed(mask, x, outside, stack);
            seed(mask, (h - 1) * w + x, outside, stack);
        }
        for (int y = 0; y < h; y++) {
            PhotoSplitter.checkpoint();
            seed(mask, y * w, outside, stack);
            seed(mask, y * w + w - 1, outside, stack);
        }
        while (!stack.isEmpty()) {
            PhotoSplitter.checkpoint();
            int p = stack.pop();
            int x = p % w;
            int y = p / w;
            if (x > 0) {
                seed(mask, p - 1, outside, stack);
            }
            if (x < w - 1) {
                seed(mask, p + 1, outside, stack);
            }
            if (y > 0) {
                seed(mask, p - w, outside, stack);
            }
            if (y < h - 1) {
                seed(mask, p + w, outside, stack);
            }
        }
        for (int i = 0; i < mask.length; i++) {
            mask[i] = mask[i] == outside ? 0 : (byte) 1;
        }
    }

    private static void seed(byte[] mask, int i, byte outside, IntStack stack) {
        if (mask[i] == 0) {
            mask[i] = outside;
            stack.push(i);
        }
    }
}

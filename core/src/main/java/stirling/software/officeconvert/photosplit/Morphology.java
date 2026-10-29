package stirling.software.officeconvert.photosplit;

final class Morphology {

    private static final int ROWS_PER_STRIP = 64;
    private static final int COLUMNS_PER_STRIP = 64;

    private Morphology() {}

    static void dilate(byte[] mask, int w, int h, int radius) {
        spread(mask, w, h, radius, true);
    }

    static void erode(byte[] mask, int w, int h, int radius) {
        spread(mask, w, h, radius, false);
    }

    private static void spread(byte[] mask, int w, int h, int radius, boolean dilate) {
        byte hit = dilate ? (byte) 1 : 0;
        byte miss = dilate ? 0 : (byte) 1;
        Parallel.strips(h, ROWS_PER_STRIP, (top, bottom) -> {
            byte[] line = new byte[w];
            for (int y = top; y < bottom; y++) {
                PhotoSplitter.checkpoint();
                int start = y * w;
                int count = 0;
                for (int k = 0; k < Math.min(radius, w); k++) {
                    if (mask[start + k] == hit) {
                        count++;
                    }
                }
                for (int x = 0; x < w; x++) {
                    int add = x + radius;
                    if (add < w && mask[start + add] == hit) {
                        count++;
                    }
                    int drop = x - radius - 1;
                    if (drop >= 0 && mask[start + drop] == hit) {
                        count--;
                    }
                    line[x] = count > 0 ? hit : miss;
                }
                System.arraycopy(line, 0, mask, start, w);
            }
        });
        Parallel.strips(w, COLUMNS_PER_STRIP, (from, to) -> {
            int span = to - from;
            int[] counts = new int[span];
            int rows = 2 * radius + 1;
            byte[] window = new byte[rows * span];
            for (int y = 0; y < Math.min(radius, h); y++) {
                System.arraycopy(mask, y * w + from, window, (y % rows) * span, span);
                for (int x = 0; x < span; x++) {
                    if (mask[y * w + from + x] == hit) {
                        counts[x]++;
                    }
                }
            }
            for (int y = 0; y < h; y++) {
                PhotoSplitter.checkpoint();
                int drop = y - radius - 1;
                if (drop >= 0) {
                    int base = (drop % rows) * span;
                    for (int x = 0; x < span; x++) {
                        if (window[base + x] == hit) {
                            counts[x]--;
                        }
                    }
                }
                int add = y + radius;
                if (add < h) {
                    int base = add * w + from;
                    System.arraycopy(mask, base, window, (add % rows) * span, span);
                    for (int x = 0; x < span; x++) {
                        if (mask[base + x] == hit) {
                            counts[x]++;
                        }
                    }
                }
                int out = y * w + from;
                for (int x = 0; x < span; x++) {
                    mask[out + x] = counts[x] > 0 ? hit : miss;
                }
            }
        });
    }
}

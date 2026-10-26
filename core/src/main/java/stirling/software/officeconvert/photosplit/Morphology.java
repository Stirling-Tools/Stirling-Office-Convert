package stirling.software.officeconvert.photosplit;

final class Morphology {

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
        byte[] line = new byte[w];
        for (int y = 0; y < h; y++) {
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
        int[] counts = new int[w];
        byte[] window = new byte[(2 * radius + 1) * w];
        int rows = 2 * radius + 1;
        for (int y = 0; y < Math.min(radius, h); y++) {
            System.arraycopy(mask, y * w, window, (y % rows) * w, w);
            for (int x = 0; x < w; x++) {
                if (mask[y * w + x] == hit) {
                    counts[x]++;
                }
            }
        }
        for (int y = 0; y < h; y++) {
            int drop = y - radius - 1;
            if (drop >= 0) {
                int base = (drop % rows) * w;
                for (int x = 0; x < w; x++) {
                    if (window[base + x] == hit) {
                        counts[x]--;
                    }
                }
            }
            int add = y + radius;
            if (add < h) {
                int base = add * w;
                System.arraycopy(mask, base, window, (add % rows) * w, w);
                for (int x = 0; x < w; x++) {
                    if (mask[base + x] == hit) {
                        counts[x]++;
                    }
                }
            }
            int out = y * w;
            for (int x = 0; x < w; x++) {
                mask[out + x] = counts[x] > 0 ? hit : miss;
            }
        }
    }
}

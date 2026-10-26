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
        byte[] line = new byte[Math.max(w, h)];
        for (int y = 0; y < h; y++) {
            run(mask, y * w, 1, w, radius, hit, miss, line);
        }
        for (int x = 0; x < w; x++) {
            run(mask, x, w, h, radius, hit, miss, line);
        }
    }

    private static void run(byte[] mask, int start, int step, int n, int radius, byte hit, byte miss, byte[] line) {
        int count = 0;
        for (int k = 0; k < Math.min(radius, n); k++) {
            if (mask[start + k * step] == hit) {
                count++;
            }
        }
        for (int i = 0; i < n; i++) {
            int add = i + radius;
            if (add < n && mask[start + add * step] == hit) {
                count++;
            }
            int drop = i - radius - 1;
            if (drop >= 0 && mask[start + drop * step] == hit) {
                count--;
            }
            line[i] = count > 0 ? hit : miss;
        }
        for (int i = 0; i < n; i++) {
            mask[start + i * step] = line[i];
        }
    }
}

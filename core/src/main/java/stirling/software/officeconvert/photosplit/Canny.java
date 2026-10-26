package stirling.software.officeconvert.photosplit;

final class Canny {

    private static final int TG22 = 13573;
    private static final int SHIFT = 15;

    private Canny() {}

    static byte[] edges(int[] gray, int w, int h, int low, int high) {
        int[] dx = new int[w * h];
        int[] dy = new int[w * h];
        int[] mag = new int[w * h];
        for (int y = 0; y < h; y++) {
            int up = Math.max(y - 1, 0) * w;
            int mid = y * w;
            int down = Math.min(y + 1, h - 1) * w;
            for (int x = 0; x < w; x++) {
                int l = Math.max(x - 1, 0);
                int r = Math.min(x + 1, w - 1);
                int gx = gray[up + r] + 2 * gray[mid + r] + gray[down + r] - gray[up + l] - 2 * gray[mid + l] - gray[down + l];
                int gy = gray[down + l] + 2 * gray[down + x] + gray[down + r] - gray[up + l] - 2 * gray[up + x] - gray[up + r];
                dx[mid + x] = gx;
                dy[mid + x] = gy;
                mag[mid + x] = Math.abs(gx) + Math.abs(gy);
            }
        }
        byte[] map = new byte[w * h];
        IntStack strong = new IntStack();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int m = mag[i];
                if (m <= low || !peak(mag, w, h, x, y, dx[i], dy[i], m)) {
                    continue;
                }
                map[i] = 1;
                if (m > high) {
                    map[i] = 2;
                    strong.push(i);
                }
            }
        }
        while (!strong.isEmpty()) {
            int p = strong.pop();
            int px = p % w;
            int py = p / w;
            for (int ny = Math.max(py - 1, 0); ny <= Math.min(py + 1, h - 1); ny++) {
                for (int nx = Math.max(px - 1, 0); nx <= Math.min(px + 1, w - 1); nx++) {
                    int n = ny * w + nx;
                    if (map[n] == 1) {
                        map[n] = 2;
                        strong.push(n);
                    }
                }
            }
        }
        for (int i = 0; i < map.length; i++) {
            map[i] = map[i] == 2 ? (byte) 1 : 0;
        }
        return map;
    }

    private static boolean peak(int[] mag, int w, int h, int x, int y, int xs, int ys, int m) {
        int ax = Math.abs(xs);
        int ay = Math.abs(ys) << SHIFT;
        int tg22x = ax * TG22;
        if (ay < tg22x) {
            return m > at(mag, w, h, x - 1, y) && m >= at(mag, w, h, x + 1, y);
        }
        int tg67x = tg22x + (ax << (SHIFT + 1));
        if (ay > tg67x) {
            return m > at(mag, w, h, x, y - 1) && m >= at(mag, w, h, x, y + 1);
        }
        int s = (xs ^ ys) < 0 ? -1 : 1;
        return m > at(mag, w, h, x - s, y - 1) && m > at(mag, w, h, x + s, y + 1);
    }

    private static int at(int[] mag, int w, int h, int x, int y) {
        return x < 0 || y < 0 || x >= w || y >= h ? 0 : mag[y * w + x];
    }
}

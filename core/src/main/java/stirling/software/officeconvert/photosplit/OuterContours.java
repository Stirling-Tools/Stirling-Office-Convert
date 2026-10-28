package stirling.software.officeconvert.photosplit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class OuterContours {

    private static final byte BACKGROUND = 0;
    private static final byte FOREGROUND = 1;
    private static final byte OUTSIDE = 2;
    private static final byte SEEN = 3;

    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DY = {0, -1, -1, -1, 0, 1, 1, 1};

    record Contour(int x, int y, int width, int height, int startX, int startY) {}

    private OuterContours() {}

    static List<Contour> find(byte[] mask, int w, int h) {
        markOutside(mask, w, h);
        List<Contour> found = new ArrayList<>();
        IntStack stack = new IntStack();
        for (int y = 0; y < h; y++) {
            PhotoSplitter.checkpoint();
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (mask[i] != FOREGROUND) {
                    continue;
                }
                boolean outer = x == 0 || mask[i - 1] == OUTSIDE;
                int minX = x;
                int maxX = x;
                int maxY = y;
                mask[i] = SEEN;
                stack.push(i);
                while (!stack.isEmpty()) {
                    int p = stack.pop();
                    int px = p % w;
                    int py = p / w;
                    minX = Math.min(minX, px);
                    maxX = Math.max(maxX, px);
                    maxY = Math.max(maxY, py);
                    for (int dy = -1; dy <= 1; dy++) {
                        int ny = py + dy;
                        if (ny < 0 || ny >= h) {
                            continue;
                        }
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = px + dx;
                            if (nx >= 0 && nx < w && mask[ny * w + nx] == FOREGROUND) {
                                mask[ny * w + nx] = SEEN;
                                stack.push(ny * w + nx);
                            }
                        }
                    }
                }
                if (outer) {
                    found.add(new Contour(minX, y, maxX - minX + 1, maxY - y + 1, x, y));
                }
            }
        }
        Collections.reverse(found);
        return found;
    }

    static double area(byte[] mask, int w, int h, Contour c) {
        int x0 = c.startX();
        int y0 = c.startY();
        int s = 4;
        int x1;
        int y1;
        do {
            s = (s - 1) & 7;
            x1 = x0 + DX[s];
            y1 = y0 + DY[s];
        } while (!set(mask, w, h, x1, y1) && s != 4);
        if (s == 4) {
            return 0;
        }
        long twice = 0;
        int x3 = x0;
        int y3 = y0;
        while (true) {
            int x4 = x3;
            int y4 = y3;
            for (int k = 0; k < 8; k++) {
                s = (s + 1) & 7;
                x4 = x3 + DX[s];
                y4 = y3 + DY[s];
                if (set(mask, w, h, x4, y4)) {
                    break;
                }
            }
            twice += (long) x3 * y4 - (long) x4 * y3;
            if (x4 == x0 && y4 == y0 && x3 == x1 && y3 == y1) {
                break;
            }
            x3 = x4;
            y3 = y4;
            s = (s + 4) & 7;
        }
        return Math.abs(twice) / 2.0;
    }

    private static boolean set(byte[] mask, int w, int h, int x, int y) {
        if (x < 0 || y < 0 || x >= w || y >= h) {
            return false;
        }
        byte v = mask[y * w + x];
        return v == FOREGROUND || v == SEEN;
    }

    private static void markOutside(byte[] mask, int w, int h) {
        PhotoSplitter.checkpoint();
        IntStack stack = new IntStack();
        for (int x = 0; x < w; x++) {
            seed(mask, x, stack);
            seed(mask, (h - 1) * w + x, stack);
        }
        for (int y = 0; y < h; y++) {
            seed(mask, y * w, stack);
            seed(mask, y * w + w - 1, stack);
        }
        while (!stack.isEmpty()) {
            int p = stack.pop();
            int px = p % w;
            int py = p / w;
            if (px > 0) {
                seed(mask, p - 1, stack);
            }
            if (px < w - 1) {
                seed(mask, p + 1, stack);
            }
            if (py > 0) {
                seed(mask, p - w, stack);
            }
            if (py < h - 1) {
                seed(mask, p + w, stack);
            }
        }
    }

    private static void seed(byte[] mask, int i, IntStack stack) {
        if (mask[i] == BACKGROUND) {
            mask[i] = OUTSIDE;
            stack.push(i);
        }
    }
}

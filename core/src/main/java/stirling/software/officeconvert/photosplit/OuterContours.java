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
                    int row = py * w;
                    int left = px;
                    while (left > 0 && mask[row + left - 1] == FOREGROUND) {
                        mask[row + --left] = SEEN;
                    }
                    int right = px;
                    while (right < w - 1 && mask[row + right + 1] == FOREGROUND) {
                        mask[row + ++right] = SEEN;
                    }
                    minX = Math.min(minX, left);
                    maxX = Math.max(maxX, right);
                    maxY = Math.max(maxY, py);
                    for (int ny = py - 1; ny <= py + 1; ny += 2) {
                        if (ny >= 0 && ny < h) {
                            seedRuns(mask, ny * w, Math.max(0, left - 1), Math.min(w - 1, right + 1), FOREGROUND, SEEN, stack);
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

    private static void seedRuns(byte[] mask, int row, int from, int to, byte match, byte mark, IntStack stack) {
        boolean run = false;
        for (int x = from; x <= to; x++) {
            if (mask[row + x] != match) {
                run = false;
            } else if (!run) {
                mask[row + x] = mark;
                stack.push(row + x);
                run = true;
            }
        }
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
            fillOutside(mask, w, h, x, stack);
            fillOutside(mask, w, h, (h - 1) * w + x, stack);
        }
        for (int y = 0; y < h; y++) {
            fillOutside(mask, w, h, y * w, stack);
            fillOutside(mask, w, h, y * w + w - 1, stack);
        }
    }

    private static void fillOutside(byte[] mask, int w, int h, int i, IntStack stack) {
        if (mask[i] != BACKGROUND) {
            return;
        }
        mask[i] = OUTSIDE;
        stack.push(i);
        while (!stack.isEmpty()) {
            int p = stack.pop();
            int px = p % w;
            int py = p / w;
            int row = py * w;
            int left = px;
            while (left > 0 && mask[row + left - 1] == BACKGROUND) {
                mask[row + --left] = OUTSIDE;
            }
            int right = px;
            while (right < w - 1 && mask[row + right + 1] == BACKGROUND) {
                mask[row + ++right] = OUTSIDE;
            }
            if (py > 0) {
                seedRuns(mask, row - w, left, right, BACKGROUND, OUTSIDE, stack);
            }
            if (py < h - 1) {
                seedRuns(mask, row + w, left, right, BACKGROUND, OUTSIDE, stack);
            }
        }
    }
}

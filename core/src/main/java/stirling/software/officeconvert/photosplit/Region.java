package stirling.software.officeconvert.photosplit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class Region {

    final int minX;
    final int minY;
    final int maxX;
    final int maxY;
    final int count;
    final byte[] mask;

    Region(int minX, int minY, int maxX, int maxY, byte[] mask) {
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
        this.mask = mask;
        int n = 0;
        for (byte b : mask) {
            n += b;
        }
        this.count = n;
    }

    int width() {
        return maxX - minX + 1;
    }

    int height() {
        return maxY - minY + 1;
    }

    static List<Region> label(byte[] image, int w, int h) {
        List<Region> out = new ArrayList<>();
        IntStack stack = new IntStack();
        int[] pixels = new int[1024];
        for (int start = 0; start < image.length; start++) {
            if (image[start] != 1) {
                continue;
            }
            int n = 0;
            int minX = start % w;
            int maxX = minX;
            int minY = start / w;
            int maxY = minY;
            image[start] = 2;
            stack.push(start);
            while (!stack.isEmpty()) {
                int p = stack.pop();
                if (n == pixels.length) {
                    pixels = Arrays.copyOf(pixels, n * 2);
                }
                pixels[n++] = p;
                int px = p % w;
                int py = p / w;
                minX = Math.min(minX, px);
                maxX = Math.max(maxX, px);
                maxY = Math.max(maxY, py);
                for (int ny = Math.max(0, py - 1); ny <= Math.min(h - 1, py + 1); ny++) {
                    for (int nx = Math.max(0, px - 1); nx <= Math.min(w - 1, px + 1); nx++) {
                        int q = ny * w + nx;
                        if (image[q] == 1) {
                            image[q] = 2;
                            stack.push(q);
                        }
                    }
                }
            }
            int bw = maxX - minX + 1;
            byte[] sub = new byte[Math.multiplyExact(bw, maxY - minY + 1)];
            for (int i = 0; i < n; i++) {
                sub[(pixels[i] / w - minY) * bw + pixels[i] % w - minX] = 1;
            }
            out.add(new Region(minX, minY, maxX, maxY, sub));
        }
        return out;
    }

    RotatedRect rect() {
        int bw = width();
        int bh = height();
        long[] points = new long[4 * bh];
        int n = 0;
        for (int y = 0; y < bh; y++) {
            int first = -1;
            int last = -1;
            for (int x = 0; x < bw; x++) {
                if (mask[y * bw + x] != 0) {
                    if (first < 0) {
                        first = x;
                    }
                    last = x;
                }
            }
            if (first < 0) {
                continue;
            }
            points[n++] = RotatedRect.point(minX + first, minY + y);
            points[n++] = RotatedRect.point(minX + first, minY + y + 1);
            points[n++] = RotatedRect.point(minX + last + 1, minY + y);
            points[n++] = RotatedRect.point(minX + last + 1, minY + y + 1);
        }
        return RotatedRect.enclosing(points, n);
    }
}

package stirling.software.officeconvert.optimize;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class Palette {

    static final int MAX_COLOURS = 256;

    private static final int BIN_BITS = 5;

    private static final int BINS = 1 << BIN_BITS * 3;

    private Palette() {}

    static int[] exact(BufferedImage image) {
        Set<Integer> colours = new LinkedHashSet<>();
        int[] row = new int[image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            rgbRow(image, y, row);
            for (int x = 0; x < image.getWidth(); x++) {
                if (colours.add(row[x] & 0xFFFFFF) && colours.size() > MAX_COLOURS) {
                    return null;
                }
            }
        }
        int[] palette = colours.stream().mapToInt(Integer::intValue).toArray();
        Arrays.sort(palette);
        return palette;
    }

    static int[] medianCut(BufferedImage image, int colours) {
        long[] count = new long[BINS];
        long[][] sums = new long[3][BINS];
        int[] row = new int[image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            rgbRow(image, y, row);
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = row[x];
                int bin = bin(rgb);
                count[bin]++;
                sums[0][bin] += rgb >> 16 & 0xFF;
                sums[1][bin] += rgb >> 8 & 0xFF;
                sums[2][bin] += rgb & 0xFF;
            }
        }
        int used = 0;
        int[] bins = new int[BINS];
        for (int b = 0; b < BINS; b++) {
            if (count[b] > 0) {
                bins[used++] = b;
            }
        }
        List<int[]> boxes = new ArrayList<>();
        boxes.add(Arrays.copyOf(bins, used));
        while (boxes.size() < colours) {
            int[] widest = null;
            int axis = 0;
            int widestRange = 0;
            for (int[] box : boxes) {
                for (int a = 0; a < 3; a++) {
                    int range = range(box, a);
                    if (range > widestRange) {
                        widestRange = range;
                        widest = box;
                        axis = a;
                    }
                }
            }
            if (widest == null) {
                break;
            }
            boxes.remove(widest);
            int splitAxis = axis;
            Integer[] sorted = Arrays.stream(widest).boxed().toArray(Integer[]::new);
            Arrays.sort(sorted, Comparator.comparingInt(b -> component(b, splitAxis)));
            long total = 0;
            for (int b : sorted) {
                total += count[b];
            }
            long half = 0;
            int cut = 1;
            for (int i = 0; i < sorted.length - 1; i++) {
                half += count[sorted[i]];
                cut = i + 1;
                if (half * 2 >= total) {
                    break;
                }
            }
            boxes.add(Arrays.stream(sorted, 0, cut).mapToInt(Integer::intValue).toArray());
            boxes.add(Arrays.stream(sorted, cut, sorted.length).mapToInt(Integer::intValue).toArray());
        }
        int[] palette = new int[boxes.size()];
        for (int i = 0; i < palette.length; i++) {
            long n = 0;
            long r = 0;
            long g = 0;
            long b = 0;
            for (int bin : boxes.get(i)) {
                n += count[bin];
                r += sums[0][bin];
                g += sums[1][bin];
                b += sums[2][bin];
            }
            palette[i] = (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
        }
        return palette;
    }

    static byte[] indices(BufferedImage image, int[] palette) {
        int w = image.getWidth();
        byte[] out = new byte[w * image.getHeight()];
        Nearest nearest = new Nearest(palette);
        int[] row = new int[w];
        int i = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            rgbRow(image, y, row);
            for (int x = 0; x < w; x++) {
                out[i++] = nearest.of(row[x] & 0xFFFFFF);
            }
        }
        return out;
    }

    // Open-addressed colour to index memo; a boxed HashMap cost about 100 bytes per distinct colour
    static final class Nearest {

        private final int[] palette;

        private int[] keys = new int[1 << 10];

        private byte[] values = new byte[1 << 10];

        private int shift = 32 - 10;

        private int size;

        Nearest(int[] palette) {
            this.palette = palette;
            Arrays.fill(keys, -1);
        }

        byte of(int rgb) {
            int mask = keys.length - 1;
            for (int at = rgb * 0x9E3779B1 >>> shift; ; at = at + 1 & mask) {
                if (keys[at] == rgb) {
                    return values[at];
                }
                if (keys[at] < 0) {
                    byte index = (byte) nearest(palette, rgb);
                    keys[at] = rgb;
                    values[at] = index;
                    if (++size * 2 > keys.length) {
                        grow();
                    }
                    return index;
                }
            }
        }

        int capacity() {
            return keys.length;
        }

        private void grow() {
            int[] oldKeys = keys;
            byte[] oldValues = values;
            keys = new int[oldKeys.length * 2];
            values = new byte[oldKeys.length * 2];
            shift--;
            Arrays.fill(keys, -1);
            int mask = keys.length - 1;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] >= 0) {
                    int at = oldKeys[i] * 0x9E3779B1 >>> shift;
                    while (keys[at] >= 0) {
                        at = at + 1 & mask;
                    }
                    keys[at] = oldKeys[i];
                    values[at] = oldValues[i];
                }
            }
        }
    }

    static double meanError(BufferedImage image, int[] palette, byte[] indices) {
        long total = 0;
        int i = 0;
        int[] row = new int[image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            rgbRow(image, y, row);
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = row[x];
                int p = palette[indices[i++] & 0xFF];
                total += Math.abs((rgb >> 16 & 0xFF) - (p >> 16 & 0xFF)) + Math.abs((rgb >> 8 & 0xFF) - (p >> 8 & 0xFF))
                        + Math.abs((rgb & 0xFF) - (p & 0xFF));
            }
        }
        return i == 0 ? 0 : total / (3.0 * i);
    }

    // Same values as getRGB(x, y), without its per-pixel array allocation
    static void rgbRow(BufferedImage image, int y, int[] row) {
        if (image.getType() != BufferedImage.TYPE_INT_RGB || !image.getColorModel().getColorSpace().isCS_sRGB()) {
            image.getRGB(0, y, row.length, 1, row, 0, row.length);
            return;
        }
        // Packed RGB pixels: getRGB keeps the low 24 bits and reports them as opaque
        image.getRaster().getDataElements(0, y, row.length, 1, row);
        for (int x = 0; x < row.length; x++) {
            row[x] = row[x] & 0xFFFFFF | 0xFF000000;
        }
    }

    static int bitsFor(int colours) {
        return colours <= 2 ? 1 : colours <= 4 ? 2 : colours <= 16 ? 4 : 8;
    }

    static byte[] pack(byte[] indices, int width, int height, int bits) {
        if (bits == 8) {
            return indices;
        }
        int rowBytes = (width * bits + 7) / 8;
        byte[] out = new byte[rowBytes * height];
        int perByte = 8 / bits;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = indices[y * width + x] & 0xFF;
                int shift = 8 - bits * (x % perByte + 1);
                out[y * rowBytes + x / perByte] |= (byte) (value << shift);
            }
        }
        return out;
    }

    static byte[] lookup(int[] palette) {
        byte[] out = new byte[palette.length * 3];
        for (int i = 0; i < palette.length; i++) {
            out[i * 3] = (byte) (palette[i] >> 16);
            out[i * 3 + 1] = (byte) (palette[i] >> 8);
            out[i * 3 + 2] = (byte) palette[i];
        }
        return out;
    }

    private static int nearest(int[] palette, int rgb) {
        int r = rgb >> 16 & 0xFF;
        int g = rgb >> 8 & 0xFF;
        int b = rgb & 0xFF;
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            // Terms are never negative, so stop adding once this entry cannot win
            int dg = g - (palette[i] >> 8 & 0xFF);
            int distance = 4 * dg * dg;
            if (distance >= bestDistance) {
                continue;
            }
            int db = b - (palette[i] & 0xFF);
            distance += 3 * db * db;
            if (distance >= bestDistance) {
                continue;
            }
            int dr = r - (palette[i] >> 16 & 0xFF);
            distance += 2 * dr * dr;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private static int bin(int rgb) {
        int shift = 8 - BIN_BITS;
        return (rgb >> 16 & 0xFF) >> shift << 2 * BIN_BITS | (rgb >> 8 & 0xFF) >> shift << BIN_BITS
                | (rgb & 0xFF) >> shift;
    }

    private static int component(int bin, int axis) {
        return bin >> (2 - axis) * BIN_BITS & (1 << BIN_BITS) - 1;
    }

    private static int range(int[] box, int axis) {
        if (box.length < 2) {
            return 0;
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int b : box) {
            int c = component(b, axis);
            min = Math.min(min, c);
            max = Math.max(max, c);
        }
        return max - min;
    }
}

package stirling.software.officeconvert.lineart;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;

final class Binarizer {

    private static final double QUANTUM = 65535.0;

    private Binarizer() {}

    static Bitmap binarize(BufferedImage image, LineArt.Options options) {
        int w = image.getWidth();
        int h = image.getHeight();
        float[] grey = grey(image);
        Bitmap bitmap = switch (options.method()) {
            case EDGES -> edges(grey, w, h, options.edgeLevel(), options.threshold());
            case FIXED -> fixed(grey, w, h, options.threshold());
            case OTSU -> otsu(grey, w, h);
            case SAUVOLA -> sauvola(grey, w, h, window(options.window(), w, h), options.k());
        };
        return options.inkColour() ? bitmap.withInk(meanInk(image, bitmap)) : bitmap;
    }

    static int window(int requested, int w, int h) {
        if (requested > 0) {
            return requested | 1;
        }
        return Math.clamp(Math.min(w, h) / 48, 15, 127) | 1;
    }

    static float[] grey(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        float[] grey = new float[Math.multiplyExact(w, h)];
        Raster raster = image.getRaster();
        if (raster.getNumBands() == 1 && image.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_GRAY
                && image.getColorModel().getPixelSize() >= 8) {
            float scale = raster.getTransferType() == DataBuffer.TYPE_BYTE ? 257f : 1f;
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                raster.getSamples(0, y, w, 1, 0, row);
                for (int x = 0; x < w; x++) {
                    grey[y * w + x] = row[x] * scale;
                }
            }
            return grey;
        }
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            image.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                grey[y * w + x] = (float) (0.212656 * (((p >>> 16) & 0xFF) * 257) + 0.715158 * (((p >>> 8) & 0xFF) * 257)
                        + 0.072186 * ((p & 0xFF) * 257));
            }
        }
        return grey;
    }

    static Bitmap edges(float[] grey, int w, int h, int edgeLevel, double threshold) {
        float[] v = negatedEdges(grey, w, h, edgeLevel);
        int n = v.length;
        double[] histogram = new double[65536];
        for (float f : v) {
            histogram[bin(f)]++;
        }
        double blackPoint = 0.02 * n;
        double whitePoint = 0.99 * n;
        int black = 0;
        double sum = 0;
        for (; black <= 65535; black++) {
            sum += histogram[black];
            if (sum > blackPoint) {
                break;
            }
        }
        int white = 65535;
        sum = 0;
        for (; white != 0; white--) {
            sum += histogram[white];
            if (sum > n - whitePoint) {
                break;
            }
        }
        double level = threshold / 100 * QUANTUM;
        Bitmap out = Bitmap.blank(w, h);
        byte[] bits = out.packed();
        int stride = out.stride();
        double gamma = white == black ? 0 : 1.0 / (white - black);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float f = v[y * w + x];
                double s;
                if (black == white) {
                    s = f;
                } else {
                    int j = bin(f);
                    s = j < black ? 0 : j > white ? QUANTUM : (float) Math.clamp(QUANTUM * gamma * (j - black), 0, QUANTUM);
                }
                if (s < level) {
                    bits[y * stride + (x >>> 3)] |= (byte) (0x80 >>> (x & 7));
                }
            }
        }
        return out;
    }

    private static int bin(float f) {
        if (!(f > 0)) {
            return 0;
        }
        if (f >= 65535f) {
            return 65535;
        }
        return (int) (f + 0.5);
    }

    private static float[] negatedEdges(float[] grey, int w, int h, int level) {
        int r = level;
        double area = (2 * r + 1) * (2 * r + 1);
        float[] out = new float[grey.length];
        double[][] across = new double[h][];
        for (int y = 0; y < h; y++) {
            for (int dy = -r; dy <= r; dy++) {
                int yy = Math.clamp(y + dy, 0, h - 1);
                if (across[yy] == null) {
                    across[yy] = across(grey, w, yy, r);
                }
            }
            for (int x = 0; x < w; x++) {
                double total = 0;
                for (int dy = -r; dy <= r; dy++) {
                    total += across[Math.clamp(y + dy, 0, h - 1)][x];
                }
                float edge = (float) (area * grey[y * w + x] - total);
                out[y * w + x] = (float) (QUANTUM - edge);
            }
            if (y - r >= 0 && y - r < h - 1) {
                across[y - r] = null;
            }
        }
        return out;
    }

    private static double[] across(float[] grey, int w, int y, int r) {
        double[] sums = new double[w];
        int row = y * w;
        for (int x = 0; x < w; x++) {
            double s = 0;
            for (int dx = -r; dx <= r; dx++) {
                s += grey[row + Math.clamp(x + dx, 0, w - 1)];
            }
            sums[x] = s;
        }
        return sums;
    }

    static Bitmap fixed(float[] grey, int w, int h, double threshold) {
        double level = threshold / 100 * QUANTUM;
        Bitmap out = Bitmap.blank(w, h);
        byte[] bits = out.packed();
        int stride = out.stride();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (grey[y * w + x] <= level) {
                    bits[y * stride + (x >>> 3)] |= (byte) (0x80 >>> (x & 7));
                }
            }
        }
        return out;
    }

    static int otsuLevel(float[] grey) {
        long[] histogram = new long[256];
        for (float f : grey) {
            histogram[eight(f)]++;
        }
        long total = grey.length;
        double sumAll = 0;
        for (int i = 0; i < 256; i++) {
            sumAll += (double) i * histogram[i];
        }
        double sumBelow = 0;
        long below = 0;
        double best = -1;
        int level = 127;
        for (int t = 0; t < 255; t++) {
            below += histogram[t];
            sumBelow += (double) t * histogram[t];
            long above = total - below;
            if (below == 0 || above == 0) {
                continue;
            }
            double meanBelow = sumBelow / below;
            double meanAbove = (sumAll - sumBelow) / above;
            double between = (double) below * above * (meanBelow - meanAbove) * (meanBelow - meanAbove);
            if (between > best) {
                best = between;
                level = t;
            }
        }
        return level;
    }

    static Bitmap otsu(float[] grey, int w, int h) {
        int level = otsuLevel(grey);
        Bitmap out = Bitmap.blank(w, h);
        byte[] bits = out.packed();
        int stride = out.stride();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (eight(grey[y * w + x]) <= level) {
                    bits[y * stride + (x >>> 3)] |= (byte) (0x80 >>> (x & 7));
                }
            }
        }
        return out;
    }

    static Bitmap sauvola(float[] grey, int w, int h, int window, double k) {
        int r = window / 2;
        byte[] eight = new byte[grey.length];
        for (int i = 0; i < grey.length; i++) {
            eight[i] = (byte) eight(grey[i]);
        }
        long[] columnSum = new long[w];
        long[] columnSquares = new long[w];
        Bitmap out = Bitmap.blank(w, h);
        byte[] bits = out.packed();
        int stride = out.stride();
        for (int y = 0; y < Math.min(r, h); y++) {
            addRow(eight, w, y, columnSum, columnSquares, 1);
        }
        for (int y = 0; y < h; y++) {
            if (y + r < h) {
                addRow(eight, w, y + r, columnSum, columnSquares, 1);
            }
            if (y - r - 1 >= 0) {
                addRow(eight, w, y - r - 1, columnSum, columnSquares, -1);
            }
            int rows = Math.min(h - 1, y + r) - Math.max(0, y - r) + 1;
            long sum = 0;
            long squares = 0;
            for (int x = 0; x < Math.min(r, w); x++) {
                sum += columnSum[x];
                squares += columnSquares[x];
            }
            for (int x = 0; x < w; x++) {
                if (x + r < w) {
                    sum += columnSum[x + r];
                    squares += columnSquares[x + r];
                }
                if (x - r - 1 >= 0) {
                    sum -= columnSum[x - r - 1];
                    squares -= columnSquares[x - r - 1];
                }
                long count = (long) rows * (Math.min(w - 1, x + r) - Math.max(0, x - r) + 1);
                double mean = (double) sum / count;
                double deviation = Math.sqrt(Math.max(0, (double) squares / count - mean * mean));
                double level = mean * (1 + k * (deviation / 128 - 1));
                if ((eight[y * w + x] & 0xFF) <= level) {
                    bits[y * stride + (x >>> 3)] |= (byte) (0x80 >>> (x & 7));
                }
            }
        }
        return out;
    }

    private static void addRow(byte[] eight, int w, int y, long[] sum, long[] squares, int sign) {
        int row = y * w;
        for (int x = 0; x < w; x++) {
            int v = eight[row + x] & 0xFF;
            sum[x] += sign * v;
            squares[x] += sign * v * v;
        }
    }

    private static int eight(float f) {
        return Math.clamp(Math.round(f / 257.0), 0, 255);
    }

    private static int meanInk(BufferedImage image, Bitmap bitmap) {
        if (image.getRaster().getNumBands() == 1) {
            return 0;
        }
        int w = bitmap.width();
        int[] row = new int[w];
        long r = 0;
        long g = 0;
        long b = 0;
        long n = 0;
        for (int y = 0; y < bitmap.height(); y++) {
            image.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                if (bitmap.ink(x, y)) {
                    int p = row[x];
                    r += (p >>> 16) & 0xFF;
                    g += (p >>> 8) & 0xFF;
                    b += p & 0xFF;
                    n++;
                }
            }
        }
        if (n == 0) {
            return 0;
        }
        return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }
}

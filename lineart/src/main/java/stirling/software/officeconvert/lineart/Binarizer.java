package stirling.software.officeconvert.lineart;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.SinglePixelPackedSampleModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;

final class Binarizer {

    private static final double QUANTUM = 65535.0;

    private static final double[] RED = weights(0.212656);

    private static final double[] GREEN = weights(0.715158);

    private static final double[] BLUE = weights(0.072186);

    interface Rows {
        void process(float[] buffer, int first, int from, int to);
    }

    interface GreyBand {
        void accept(int y, float[] grey, int rows) throws IOException;
    }

    interface Greys {
        void each(GreyBand band) throws IOException;
    }

    private Binarizer() {}

    static Bitmap binarize(BufferedImage image, LineArt.Options options) {
        try {
            return binarize(Source.of(image), options, Long.MAX_VALUE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Bitmap binarize(Source source, LineArt.Options options, long bandPixels) throws IOException {
        int w = source.width;
        int h = source.height;
        int rows = Source.bandRows(w, h, bandPixels);
        Greys greys = greys(source, rows);
        Bitmap out = Bitmap.blank(w, h);
        switch (options.method()) {
            case FIXED -> {
                double level = options.threshold() / 100 * QUANTUM;
                scan(greys, w, h, 0, (buffer, first, from, to) -> {
                    for (int y = from; y < to; y++) {
                        int base = (y - first) * w;
                        for (int x = 0; x < w; x++) {
                            if (buffer[base + x] <= level) {
                                out.set(x, y);
                            }
                        }
                    }
                });
            }
            case OTSU -> {
                long[] histogram = new long[256];
                scan(greys, w, h, 0, (buffer, first, from, to) -> {
                    for (int i = (from - first) * w; i < (to - first) * w; i++) {
                        histogram[eight(buffer[i])]++;
                    }
                });
                int level = otsuLevel(histogram);
                scan(greys, w, h, 0, (buffer, first, from, to) -> {
                    for (int y = from; y < to; y++) {
                        int base = (y - first) * w;
                        for (int x = 0; x < w; x++) {
                            if (eight(buffer[base + x]) <= level) {
                                out.set(x, y);
                            }
                        }
                    }
                });
            }
            case SAUVOLA -> {
                int window = window(options.window(), w, h);
                scan(greys, w, h, window / 2,
                        (buffer, first, from, to) -> sauvola(buffer, first, w, h, from, to, window, options.k(), out));
            }
            case EDGES -> edges(greys, w, h, options.edgeLevel(), options.threshold(), out);
        }
        return options.inkColour() ? out.withInk(meanInk(source, rows, out)) : out;
    }

    static int window(int requested, int w, int h) {
        if (requested > 0) {
            return requested | 1;
        }
        return Math.clamp(Math.min(w, h) / 48, 15, 127) | 1;
    }

    static Greys greys(Source source, int rows) throws IOException {
        if (rows < source.height) {
            return band -> source.bands(rows, false, (y, image) -> band.accept(y, grey(image), image.getHeight()));
        }
        float[][] whole = new float[1][];
        source.bands(rows, false, (y, image) -> whole[0] = grey(image));
        return band -> band.accept(0, whole[0], source.height);
    }

    static void scan(Greys greys, int w, int h, int radius, Rows sink) throws IOException {
        int[] state = new int[3];
        float[][] holder = {new float[0]};
        greys.each((y, grey, n) -> {
            int first = state[0];
            int count = state[1];
            float[] buffer = holder[0];
            if (count == 0) {
                buffer = grey;
                first = y;
            } else {
                if (buffer.length < (count + n) * w) {
                    buffer = Arrays.copyOf(buffer, Math.multiplyExact(count + n, w));
                }
                System.arraycopy(grey, 0, buffer, count * w, n * w);
            }
            count += n;
            int available = first + count;
            int done = state[2];
            int to = available >= h ? h : available - radius;
            if (to > done) {
                sink.process(buffer, first, done, to);
                done = to;
            }
            int keep = Math.max(first, done - radius);
            if (keep > first && done < h) {
                int drop = keep - first;
                System.arraycopy(buffer, drop * w, buffer, 0, (count - drop) * w);
                count -= drop;
                first = keep;
            }
            holder[0] = buffer;
            state[0] = first;
            state[1] = count;
            state[2] = done;
        });
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
        boolean packed = (image.getType() == BufferedImage.TYPE_INT_RGB || image.getType() == BufferedImage.TYPE_INT_ARGB)
                && raster.getDataBuffer() instanceof DataBufferInt
                && raster.getSampleModel() instanceof SinglePixelPackedSampleModel;
        int[] data = packed ? ((DataBufferInt) raster.getDataBuffer()).getData() : null;
        int scan = packed ? ((SinglePixelPackedSampleModel) raster.getSampleModel()).getScanlineStride() : 0;
        int dx = -raster.getSampleModelTranslateX();
        int dy = -raster.getSampleModelTranslateY();
        int[] row = packed ? null : new int[w];
        for (int y = 0; y < h; y++) {
            int offset = 0;
            if (packed) {
                offset = (y + dy) * scan + dx + raster.getDataBuffer().getOffset();
            } else {
                image.getRGB(0, y, w, 1, row, 0, w);
            }
            for (int x = 0; x < w; x++) {
                int p = packed ? data[offset + x] : row[x];
                grey[y * w + x] = (float) (RED[(p >>> 16) & 0xFF] + GREEN[(p >>> 8) & 0xFF] + BLUE[p & 0xFF]);
            }
        }
        return grey;
    }

    private static double[] weights(double weight) {
        double[] table = new double[256];
        for (int i = 0; i < 256; i++) {
            table[i] = weight * (i * 257);
        }
        return table;
    }

    private static void edges(Greys greys, int w, int h, int level, double threshold, Bitmap out) throws IOException {
        double[] histogram = new double[65536];
        float[] line = new float[w];
        double[][] ring = new double[2 * level + 1][];
        int[] ringRow = new int[2 * level + 1];
        Arrays.fill(ringRow, -1);
        scan(greys, w, h, level, (buffer, first, from, to) -> {
            for (int y = from; y < to; y++) {
                negatedEdges(buffer, first, w, h, y, level, ring, ringRow, line);
                for (float f : line) {
                    histogram[bin(f)]++;
                }
            }
        });
        double n = (double) w * h;
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
        double cut = threshold / 100 * QUANTUM;
        double gamma = white == black ? 0 : 1.0 / (white - black);
        int b = black;
        int wh = white;
        Arrays.fill(ringRow, -1);
        scan(greys, w, h, level, (buffer, first, from, to) -> {
            for (int y = from; y < to; y++) {
                negatedEdges(buffer, first, w, h, y, level, ring, ringRow, line);
                for (int x = 0; x < w; x++) {
                    float f = line[x];
                    double s;
                    if (b == wh) {
                        s = f;
                    } else {
                        int j = bin(f);
                        s = j < b ? 0 : j > wh ? QUANTUM : (float) Math.clamp(QUANTUM * gamma * (j - b), 0, QUANTUM);
                    }
                    if (s < cut) {
                        out.set(x, y);
                    }
                }
            }
        });
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

    private static void negatedEdges(float[] buffer, int first, int w, int h, int y, int r, double[][] ring,
            int[] ringRow, float[] line) {
        int k = 2 * r + 1;
        double area = k * k;
        for (int dy = -r; dy <= r; dy++) {
            int yy = Math.clamp(y + dy, 0, h - 1);
            int slot = yy % k;
            if (ringRow[slot] != yy) {
                ring[slot] = across(buffer, (yy - first) * w, w, r, ring[slot]);
                ringRow[slot] = yy;
            }
        }
        int row = (y - first) * w;
        for (int x = 0; x < w; x++) {
            double total = 0;
            for (int dy = -r; dy <= r; dy++) {
                total += ring[Math.clamp(y + dy, 0, h - 1) % k][x];
            }
            float edge = (float) (area * buffer[row + x] - total);
            line[x] = (float) (QUANTUM - edge);
        }
    }

    private static double[] across(float[] buffer, int row, int w, int r, double[] reuse) {
        double[] sums = reuse != null && reuse.length == w ? reuse : new double[w];
        double s = 0;
        for (int dx = -r; dx <= r; dx++) {
            s += buffer[row + Math.clamp(dx, 0, w - 1)];
        }
        sums[0] = s;
        for (int x = 1; x < w; x++) {
            s += (double) buffer[row + Math.min(x + r, w - 1)] - buffer[row + Math.max(x - r - 1, 0)];
            sums[x] = s;
        }
        return sums;
    }

    static int otsuLevel(long[] histogram) {
        long total = 0;
        double sumAll = 0;
        for (int i = 0; i < 256; i++) {
            total += histogram[i];
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

    private static void sauvola(float[] buffer, int first, int w, int h, int from, int to, int window, double k, Bitmap out) {
        int r = window / 2;
        long[] columnSum = new long[w];
        long[] columnSquares = new long[w];
        int top = Math.max(0, from - r);
        int bottom = Math.min(h - 1, from + r);
        for (int y = top; y <= bottom; y++) {
            addRow(buffer, (y - first) * w, w, columnSum, columnSquares, 1);
        }
        for (int y = from; y < to; y++) {
            if (y > from) {
                if (y + r < h) {
                    addRow(buffer, (y + r - first) * w, w, columnSum, columnSquares, 1);
                }
                if (y - r - 1 >= 0) {
                    addRow(buffer, (y - r - 1 - first) * w, w, columnSum, columnSquares, -1);
                }
            }
            int rows = Math.min(h - 1, y + r) - Math.max(0, y - r) + 1;
            long sum = 0;
            long squares = 0;
            for (int x = 0; x < Math.min(r, w); x++) {
                sum += columnSum[x];
                squares += columnSquares[x];
            }
            int base = (y - first) * w;
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
                if (eight(buffer[base + x]) <= level) {
                    out.set(x, y);
                }
            }
        }
    }

    private static void addRow(float[] buffer, int row, int w, long[] sum, long[] squares, int sign) {
        for (int x = 0; x < w; x++) {
            int v = eight(buffer[row + x]);
            sum[x] += sign * v;
            squares[x] += sign * v * v;
        }
    }

    private static int eight(float f) {
        return Math.clamp(Math.round(f / 257.0), 0, 255);
    }

    private static int meanInk(Source source, int rows, Bitmap bitmap) throws IOException {
        int w = bitmap.width();
        int[] row = new int[w];
        long[] sums = new long[4];
        boolean[] grey = {false};
        source.bands(rows, false, (y0, image) -> {
            if (image.getRaster().getNumBands() == 1) {
                grey[0] = true;
                return;
            }
            for (int y = 0; y < image.getHeight(); y++) {
                image.getRGB(0, y, w, 1, row, 0, w);
                for (int x = 0; x < w; x++) {
                    if (bitmap.ink(x, y0 + y)) {
                        int p = row[x];
                        sums[0] += (p >>> 16) & 0xFF;
                        sums[1] += (p >>> 8) & 0xFF;
                        sums[2] += p & 0xFF;
                        sums[3]++;
                    }
                }
            }
        });
        if (grey[0] || sums[3] == 0) {
            return 0;
        }
        long n = sums[3];
        return (int) (sums[0] / n) << 16 | (int) (sums[1] / n) << 8 | (int) (sums[2] / n);
    }
}

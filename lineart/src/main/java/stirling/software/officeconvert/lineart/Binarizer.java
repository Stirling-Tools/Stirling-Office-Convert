package stirling.software.officeconvert.lineart;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.PixelInterleavedSampleModel;
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
        void process(float[] buffer, int first, int from, int to) throws IOException;
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
            if (options.method() == LineArt.Method.EDGES && image.getColorModel().hasAlpha()) {
                Bitmap out = AlphaEdges.binarize(image, options);
                return options.inkColour() ? out.withInk(meanInk(Source.of(image), image.getHeight(), out)) : out;
            }
            return binarize(Source.of(image), options, image.getHeight());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Bitmap binarize(Source source, LineArt.Options options, int rows) throws IOException {
        return binarize(source, options, rows, rows >= source.height);
    }

    static Bitmap binarize(Source source, LineArt.Options options, int rows, boolean keepEdges) throws IOException {
        LineArt.checkpoint();
        int w = source.width;
        int h = source.height;
        Greys greys = greys(source, rows);
        Bitmap out = Bitmap.blank(w, h);
        switch (options.method()) {
            case FIXED -> {
                double level = options.threshold() / 100 * QUANTUM;
                scan(greys, w, h, 0, (buffer, first, from, to) -> {
                    for (int y = from; y < to; y++) {
                        LineArt.checkpoint();
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
                        LineArt.checkpoint();
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
            case EDGES -> edges(greys, w, h, options.edgeLevel(), options.threshold(), out, keepEdges);
        }
        return options.inkColour() ? out.withInk(meanInk(source, rows, out)) : out;
    }

    static int context(LineArt.Options options, int w, int h) {
        return switch (options.method()) {
            case SAUVOLA -> window(options.window(), w, h) / 2 + 1;
            case EDGES -> 2 * options.edgeLevel() + 1;
            case FIXED, OTSU -> 0;
        };
    }

    static int window(int requested, int w, int h) {
        if (requested > 0) {
            return requested | 1;
        }
        return Math.clamp(Math.min(w, h) / 48, 15, 127) | 1;
    }

    static Greys greys(Source source, int rows) throws IOException {
        if (rows < source.height) {
            float[][] reuse = new float[2][];
            return band -> source.bands(rows, false, (y, image) -> {
                int slot = y == 0 ? 0 : 1;
                float[] values = grey(image, reuse[slot]);
                band.accept(y, values, image.getHeight());
                reuse[slot] = values;
            });
        }
        float[][] whole = new float[1][];
        source.bands(rows, false, (y, image) -> whole[0] = grey(image));
        return band -> band.accept(0, whole[0], source.height);
    }

    static void scan(Greys greys, int w, int h, int radius, Rows sink) throws IOException {
        int[] state = new int[3];
        float[][] holder = {new float[0]};
        greys.each((y, grey, n) -> {
            LineArt.checkpoint();
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
        return grey(image, null);
    }

    static float[] grey(BufferedImage image, float[] reuse) {
        int w = image.getWidth();
        int h = image.getHeight();
        int size = Math.multiplyExact(w, h);
        float[] grey = reuse != null && reuse.length >= size ? reuse : new float[size];
        Raster raster = image.getRaster();
        if (raster.getNumBands() == 1 && image.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_GRAY
                && image.getColorModel().getPixelSize() >= 8) {
            float scale = raster.getTransferType() == DataBuffer.TYPE_BYTE ? 257f : 1f;
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                LineArt.checkpoint();
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
        int dx = -raster.getSampleModelTranslateX();
        int dy = -raster.getSampleModelTranslateY();
        if (image.getType() == BufferedImage.TYPE_3BYTE_BGR && raster.getDataBuffer() instanceof DataBufferByte bytes
                && raster.getSampleModel() instanceof PixelInterleavedSampleModel model) {
            byte[] data = bytes.getData();
            int step = model.getPixelStride();
            int[] bands = model.getBandOffsets();
            for (int y = 0; y < h; y++) {
                LineArt.checkpoint();
                int offset = (y + dy) * model.getScanlineStride() + dx * step + bytes.getOffset();
                for (int x = 0; x < w; x++, offset += step) {
                    grey[y * w + x] = (float) (RED[data[offset + bands[0]] & 0xFF] + GREEN[data[offset + bands[1]] & 0xFF]
                            + BLUE[data[offset + bands[2]] & 0xFF]);
                }
            }
            return grey;
        }
        int[] data = packed ? ((DataBufferInt) raster.getDataBuffer()).getData() : null;
        int scan = packed ? ((SinglePixelPackedSampleModel) raster.getSampleModel()).getScanlineStride() : 0;
        int[] row = packed ? null : new int[w];
        for (int y = 0; y < h; y++) {
            LineArt.checkpoint();
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

    private static void edges(Greys greys, int w, int h, int level, double threshold, Bitmap out, boolean keep)
            throws IOException {
        float[] line = new float[w];
        double[][] ring = new double[2 * level + 1][];
        int[] ringRow = new int[2 * level + 1];
        thresholdEdges(band -> {
            Arrays.fill(ringRow, -1);
            scan(greys, w, h, level, (buffer, first, from, to) -> {
                for (int y = from; y < to; y++) {
                    LineArt.checkpoint();
                    negatedEdges(buffer, first, w, h, y, level, ring, ringRow, line);
                    band.accept(y, line, 1);
                }
            });
        }, w, h, threshold, out, keep);
    }

    static void thresholdEdges(Greys lines, int w, int h, double threshold, Bitmap out) throws IOException {
        thresholdEdges(lines, w, h, threshold, out, false);
    }

    static void thresholdEdges(Greys lines, int w, int h, double threshold, Bitmap out, boolean keep)
            throws IOException {
        double[] histogram = new double[65536];
        char[] bins = keep ? new char[Math.multiplyExact(w, h)] : null;
        lines.each((y, line, rows) -> {
            int at = y * w;
            for (float f : line) {
                int j = bin(f);
                histogram[j]++;
                if (bins != null && at < bins.length) {
                    bins[at++] = (char) j;
                }
            }
        });
        double[] first = stretch(histogram, (double) w * h);
        double[] again = new double[65536];
        for (int j = 0; j < first.length; j++) {
            again[bin((float) first[j])] += histogram[j];
        }
        double[] second = stretch(again, (double) w * h);
        boolean[] ink = new boolean[65536];
        double level = threshold / 100 * QUANTUM;
        for (int j = 0; j < ink.length; j++) {
            ink[j] = second[bin((float) first[j])] <= level;
        }
        if (bins != null) {
            for (int y = 0; y < h; y++) {
                LineArt.checkpoint();
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    if (ink[bins[row + x]]) {
                        out.set(x, y);
                    }
                }
            }
            return;
        }
        lines.each((y, line, rows) -> {
            for (int x = 0; x < w; x++) {
                if (ink[bin(line[x])]) {
                    out.set(x, y);
                }
            }
        });
    }

    private static double[] stretch(double[] histogram, double n) {
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
        double[] map = new double[65536];
        double gamma = white == black ? 0 : 1.0 / (white - black);
        for (int j = 0; j < map.length; j++) {
            map[j] = white == black ? j : j < black ? 0 : j > white ? QUANTUM
                    : (float) Math.clamp(QUANTUM * gamma * (j - black), 0, QUANTUM);
        }
        return map;
    }

    static long edgeBytes(int w, int h) {
        return 2L * w * h;
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
            LineArt.checkpoint();
            addRow(buffer, (y - first) * w, w, columnSum, columnSquares, 1);
        }
        for (int y = from; y < to; y++) {
            LineArt.checkpoint();
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
                LineArt.checkpoint();
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

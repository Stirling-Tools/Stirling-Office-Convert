package stirling.software.officeconvert.lineart;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

final class Sauvola {

    private static final long SURELY_FLAT = 143;

    private static final double ABOVE = 1 + 1e-9;

    private static final double BELOW = 1 - 1e-9;

    private static final VarHandle EIGHT_CODES = MethodHandles.byteArrayViewVarHandle(long[].class,
            ByteOrder.LITTLE_ENDIAN);

    private static final long LOW_BITS = 0x0101010101010101L;

    private static final long GATHER = 0x8040201008040201L;

    private static final double BLUE_DEFICIT_WEIGHT = 0.2;

    private final int width;

    private final int height;

    private final int radius;

    private final int span;

    private final double k;

    private final byte[] ring;

    private float[] greys;

    private int greysCapacity;

    private final float[] paper;

    private final int[] columnSum;

    private final int[] columnSquares;

    private final int[] spans;

    private final double paperCeiling;

    private final int pad;

    private final byte[] codes;

    private final long[] counts;

    private final long[] flatLimits;

    private final Tint tint;

    private int counted = -1;

    private int next = -1;

    Sauvola(int width, int height, int window, double k, Tint tint) {
        this.width = width;
        this.height = height;
        this.radius = window / 2;
        this.span = window + 1;
        this.k = k;
        this.tint = tint;
        this.ring = new byte[Math.multiplyExact(span, width)];
        this.paper = new float[width];
        this.pad = radius + 1;
        this.columnSum = new int[width + 2 * pad];
        this.columnSquares = new int[width + 2 * pad];
        this.spans = new int[width];
        this.codes = new byte[(width + 7) & ~7];
        this.counts = new long[width];
        this.flatLimits = new long[width];
        this.paperCeiling = (1 - k * (1 - Paper.FLAT / 128)) * (1 + 1e-6);
        for (int x = 0; x < width; x++) {
            spans[x] = Math.min(width - 1, x + radius) - Math.max(0, x - radius) + 1;
        }
    }

    void rows(float[] buffer, int capacity, int from, int to, Paper surface, Bitmap out, Bitmap core, Bitmap edge) {
        greys = buffer;
        greysCapacity = capacity;
        int r = radius;
        if (from != next) {
            Arrays.fill(columnSum, 0);
            Arrays.fill(columnSquares, 0);
            for (int y = Math.max(0, from - r); y <= Math.min(height - 1, from + r); y++) {
                LineArt.checkpoint();
                enter(buffer, (y % capacity) * width, y);
            }
        } else {
            slide(buffer, capacity, from);
        }
        for (int y = from; y < to; y++) {
            LineArt.checkpoint();
            if (y > from) {
                slide(buffer, capacity, y);
            }
            surface.row(y, paper);
            classify(y, Math.min(height - 1, y + r) - Math.max(0, y - r) + 1, y * out.stride(), out.packed(),
                    core.packed(), edge.packed());
        }
        next = to;
    }

    private void slide(float[] buffer, int capacity, int y) {
        int leaving = y - radius - 1;
        int entering = y + radius;
        if (entering >= height) {
            if (leaving >= 0) {
                leave(leaving);
            }
            return;
        }
        if (leaving < 0) {
            enter(buffer, (entering % capacity) * width, entering);
            return;
        }
        int row = (entering % capacity) * width;
        int in = (entering % span) * width;
        int out = (leaving % span) * width;
        for (int x = 0; x < width; x++) {
            int v = Binarizer.eight(buffer[row + x]);
            int old = ring[out + x] & 0xFF;
            ring[in + x] = (byte) v;
            columnSum[pad + x] += v - old;
            columnSquares[pad + x] += v * v - old * old;
        }
    }

    private void enter(float[] buffer, int row, int y) {
        int slot = (y % span) * width;
        for (int x = 0; x < width; x++) {
            int v = Binarizer.eight(buffer[row + x]);
            ring[slot + x] = (byte) v;
            columnSum[pad + x] += v;
            columnSquares[pad + x] += v * v;
        }
    }

    private void leave(int y) {
        int slot = (y % span) * width;
        for (int x = 0; x < width; x++) {
            int v = ring[slot + x] & 0xFF;
            columnSum[pad + x] -= v;
            columnSquares[pad + x] -= v * v;
        }
    }

    private void classify(int y, int rows, int bits, byte[] ink, byte[] cores, byte[] edges) {
        if (rows != counted) {
            for (int x = 0; x < width; x++) {
                long count = (long) rows * spans[x];
                counts[x] = count;
                flatLimits[x] = SURELY_FLAT * count * count;
            }
            counted = rows;
        }
        int w = width;
        int reach = 2 * radius + 1;
        int[] sums = columnSum;
        int[] squaresByColumn = columnSquares;
        int values = (y % span) * w;
        long sum = 0;
        long squares = 0;
        for (int x = 0; x < Math.min(radius, w); x++) {
            sum += sums[pad + x];
            squares += squaresByColumn[pad + x];
        }
        boolean coloured = tint != null && tint.row(y);
        float[] source = greys;
        int deficitRow = (y % greysCapacity) * w;
        for (int x = 0; x < w; x++) {
            sum += sums[x + reach] - sums[x];
            squares += squaresByColumn[x + reach] - squaresByColumn[x];
            int v = ring[values + x] & 0xFF;
            long count = counts[x];
            double solid = Paper.SOLID * paper[x];
            if (coloured) {
                solid += BLUE_DEFICIT_WEIGHT * Binarizer.blueDeficit(source[deficitRow + x]);
            }
            int code;
            if (squares * count - sum * sum < flatLimits[x]) {
                double edge = solid * count;
                if (sum > edge * ABOVE && v * count > sum * paperCeiling) {
                    code = 0;
                } else if (sum < edge * BELOW) {
                    code = 3;
                } else {
                    code = code(sum, squares, count, v, solid);
                }
            } else {
                code = code(sum, squares, count, v, solid);
            }
            codes[x] = (byte) code;
        }
        for (int i = 0, stride = (w + 7) >>> 3; i < stride; i++) {
            long word = (long) EIGHT_CODES.get(codes, i * 8);
            ink[bits + i] |= (byte) (((word & LOW_BITS) * GATHER) >>> 56);
            cores[bits + i] |= (byte) (((word >>> 1 & LOW_BITS) * GATHER) >>> 56);
            edges[bits + i] |= (byte) (((word >>> 2 & LOW_BITS) * GATHER) >>> 56);
        }
    }

    private int code(long sum, long squares, double count, int v, double solid) {
        double mean = sum / count;
        double deviation = Math.sqrt(Math.max(0, squares / count - mean * mean));
        double level = mean * (1 + k * (deviation / 128 - 1));
        boolean flat = deviation < Paper.FLAT;
        boolean core = flat & mean <= solid;
        boolean ink = core | v <= level;
        boolean edge = !ink & !flat & v <= solid;
        return (ink ? 1 : 0) | (core ? 2 : 0) | (edge ? 4 : 0);
    }
}

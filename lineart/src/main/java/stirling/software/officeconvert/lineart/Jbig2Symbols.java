package stirling.software.officeconvert.lineart;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Jbig2Symbols {

    private static final int MAX_SIDE = 255;

    private static final int MAX_RUNS = 1 << 21;

    private record Shape(int w, int h, byte[] bits) {

        @Override
        public boolean equals(Object o) {
            return o instanceof Shape s && s.w == w && s.h == h && Arrays.equals(s.bits, bits);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * w + h) + Arrays.hashCode(bits);
        }

        int stride() {
            return (w + 7) >>> 3;
        }
    }

    private record Instance(int x, int y, int component) {}

    private static final class Integers {
        final MqEncoder.Contexts contexts = new MqEncoder.Contexts(512);
        private int prev;

        void encode(MqEncoder coder, int v) {
            prev = 1;
            long a = Math.abs((long) v);
            bit(coder, v < 0 ? 1 : 0);
            if (a < 4) {
                prefix(coder, 0, 1);
                bits(coder, a, 2);
            } else if (a < 20) {
                prefix(coder, 0b10, 2);
                bits(coder, a - 4, 4);
            } else if (a < 84) {
                prefix(coder, 0b110, 3);
                bits(coder, a - 20, 6);
            } else if (a < 340) {
                prefix(coder, 0b1110, 4);
                bits(coder, a - 84, 8);
            } else if (a < 4436) {
                prefix(coder, 0b11110, 5);
                bits(coder, a - 340, 12);
            } else {
                prefix(coder, 0b11111, 5);
                bits(coder, a - 4436, 32);
            }
        }

        void outOfBand(MqEncoder coder) {
            prev = 1;
            bit(coder, 1);
            bit(coder, 0);
            bits(coder, 0, 2);
        }

        private void prefix(MqEncoder coder, int code, int length) {
            bits(coder, code, length);
        }

        private void bits(MqEncoder coder, long value, int length) {
            for (int i = length - 1; i >= 0; i--) {
                bit(coder, (int) (value >>> i) & 1);
            }
        }

        private void bit(MqEncoder coder, int b) {
            coder.encode(contexts, prev, b);
            prev = prev < 256 ? prev << 1 | b : (prev << 1 | b) & 511 | 256;
        }
    }

    private final Bitmap bitmap;
    private int[] runY = new int[1024];
    private int[] runStart = new int[1024];
    private int[] runEnd = new int[1024];
    private int[] parent = new int[1024];
    private int runs;

    private Jbig2Symbols(Bitmap bitmap) {
        this.bitmap = bitmap;
    }

    static byte[] encode(Bitmap bitmap) {
        return new Jbig2Symbols(bitmap).encode();
    }

    private byte[] encode() {
        if (!findRuns()) {
            return null;
        }
        int[] component = new int[runs];
        int components = 0;
        int[] rootComponent = new int[runs];
        Arrays.fill(rootComponent, -1);
        for (int r = 0; r < runs; r++) {
            int root = find(r);
            if (rootComponent[root] < 0) {
                rootComponent[root] = components++;
            }
            component[r] = rootComponent[root];
        }
        rootComponent = null;
        int[] left = new int[components];
        int[] top = new int[components];
        int[] right = new int[components];
        int[] bottom = new int[components];
        Arrays.fill(left, Integer.MAX_VALUE);
        Arrays.fill(top, Integer.MAX_VALUE);
        for (int r = 0; r < runs; r++) {
            int k = component[r];
            left[k] = Math.min(left[k], runStart[r]);
            right[k] = Math.max(right[k], runEnd[r]);
            top[k] = Math.min(top[k], runY[r]);
            bottom[k] = Math.max(bottom[k], runY[r]);
        }
        Shape[] shapes = new Shape[components];
        for (int k = 0; k < components; k++) {
            int w = right[k] - left[k] + 1;
            int h = bottom[k] - top[k] + 1;
            if (w <= MAX_SIDE && h <= MAX_SIDE) {
                shapes[k] = new Shape(w, h, new byte[((w + 7) >>> 3) * h]);
            }
        }
        for (int r = 0; r < runs; r++) {
            Shape s = shapes[component[r]];
            if (s != null) {
                int k = component[r];
                int row = (runY[r] - top[k]) * s.stride();
                for (int x = runStart[r]; x <= runEnd[r]; x++) {
                    int sx = x - left[k];
                    s.bits()[row + (sx >>> 3)] |= (byte) (0x80 >>> (sx & 7));
                }
            }
        }
        Map<Shape, List<Instance>> uses = new HashMap<>();
        for (int k = 0; k < components; k++) {
            if (shapes[k] != null) {
                uses.computeIfAbsent(shapes[k], key -> new ArrayList<>()).add(new Instance(left[k], top[k], k));
            }
        }
        List<Shape> dictionary = new ArrayList<>();
        for (Map.Entry<Shape, List<Instance>> e : uses.entrySet()) {
            if (e.getValue().size() > 1) {
                dictionary.add(e.getKey());
            }
        }
        if (dictionary.size() < 2) {
            return null;
        }
        dictionary.sort(Comparator.comparingInt(Shape::h).thenComparingInt(Shape::w));
        Map<Shape, Integer> ids = new HashMap<>();
        boolean[] placed = new boolean[components];
        List<int[]> instances = new ArrayList<>();
        for (Shape s : dictionary) {
            int id = ids.size();
            ids.put(s, id);
            for (Instance i : uses.get(s)) {
                instances.add(new int[] {i.y(), i.x(), id, s.w()});
                placed[i.component()] = true;
            }
        }
        uses = null;
        byte[] residual = bitmap.packed().clone();
        int stride = bitmap.stride();
        for (int r = 0; r < runs; r++) {
            if (placed[component[r]]) {
                for (int x = runStart[r]; x <= runEnd[r]; x++) {
                    residual[runY[r] * stride + (x >>> 3)] &= (byte) ~(0x80 >>> (x & 7));
                }
            }
        }
        ByteArrayOutputStream s = new ByteArrayOutputStream(bitmap.packed().length / 16 + 64);
        Jbig2Generic.pageInformation(s, bitmap.width(), bitmap.height());
        dictionary(s, dictionary);
        textRegion(s, instances, dictionary.size());
        residual(s, residual, stride);
        return s.toByteArray();
    }

    private boolean findRuns() {
        int w = bitmap.width();
        int h = bitmap.height();
        int stride = bitmap.stride();
        byte[] bits = bitmap.packed();
        int previousFirst = 0;
        int previousEnd = 0;
        for (int y = 0; y < h; y++) {
            int first = runs;
            int row = y * stride;
            int x = 0;
            while (x < w) {
                int b = bits[row + (x >>> 3)] & 0xFF;
                if ((x & 7) == 0 && b == 0) {
                    x += 8;
                    continue;
                }
                if ((b & (0x80 >>> (x & 7))) == 0) {
                    x++;
                    continue;
                }
                int start = x;
                while (x < w && (bits[row + (x >>> 3)] & (0x80 >>> (x & 7))) != 0) {
                    x++;
                }
                if (!addRun(y, start, x - 1)) {
                    return false;
                }
            }
            int p = previousFirst;
            for (int r = first; r < runs; r++) {
                while (p < previousEnd && runEnd[p] < runStart[r] - 1) {
                    p++;
                }
                for (int q = p; q < previousEnd && runStart[q] <= runEnd[r] + 1; q++) {
                    union(r, q);
                }
            }
            previousFirst = first;
            previousEnd = runs;
        }
        return runs > 0;
    }

    private boolean addRun(int y, int start, int end) {
        if (runs == runY.length) {
            if (runs >= MAX_RUNS) {
                return false;
            }
            int size = runs * 2;
            runY = Arrays.copyOf(runY, size);
            runStart = Arrays.copyOf(runStart, size);
            runEnd = Arrays.copyOf(runEnd, size);
            parent = Arrays.copyOf(parent, size);
        }
        runY[runs] = y;
        runStart[runs] = start;
        runEnd[runs] = end;
        parent[runs] = runs;
        runs++;
        return true;
    }

    private int find(int r) {
        while (parent[r] != r) {
            parent[r] = parent[parent[r]];
            r = parent[r];
        }
        return r;
    }

    private void union(int a, int b) {
        int ra = find(a);
        int rb = find(b);
        if (ra != rb) {
            parent[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }

    private static void dictionary(ByteArrayOutputStream s, List<Shape> shapes) {
        MqEncoder coder = new MqEncoder(1 << 12);
        MqEncoder.Contexts gb = new MqEncoder.Contexts(1 << 16);
        Integers heights = new Integers();
        Integers widths = new Integers();
        Integers exports = new Integers();
        int height = 0;
        int i = 0;
        while (i < shapes.size()) {
            int h = shapes.get(i).h();
            heights.encode(coder, h - height);
            height = h;
            int width = 0;
            while (i < shapes.size() && shapes.get(i).h() == h) {
                Shape shape = shapes.get(i);
                widths.encode(coder, shape.w() - width);
                width = shape.w();
                Jbig2Generic.generic(coder, gb, shape.bits(), shape.stride(), shape.w(), h, false);
                i++;
            }
            widths.outOfBand(coder);
        }
        exports.encode(coder, 0);
        exports.encode(coder, shapes.size());
        byte[] data = coder.finish();
        Jbig2Generic.header(s, 1, 0, 2 + Jbig2Generic.AT.length + 8 + data.length, -1);
        s.write(0);
        s.write(0);
        s.writeBytes(Jbig2Generic.AT);
        Jbig2Generic.int32(s, shapes.size());
        Jbig2Generic.int32(s, shapes.size());
        s.writeBytes(data);
    }

    private void textRegion(ByteArrayOutputStream s, List<int[]> instances, int symbols) {
        instances.sort(Comparator.<int[]>comparingInt(a -> a[0]).thenComparingInt(a -> a[1]));
        int length = 32 - Integer.numberOfLeadingZeros(symbols - 1);
        MqEncoder coder = new MqEncoder(instances.size() * 3);
        Integers strips = new Integers();
        Integers firsts = new Integers();
        Integers steps = new Integers();
        MqEncoder.Contexts idContexts = new MqEncoder.Contexts(1 << (length + 1));
        strips.encode(coder, 0);
        int stripT = 0;
        int firstS = 0;
        int i = 0;
        while (i < instances.size()) {
            int t = instances.get(i)[0];
            strips.encode(coder, t - stripT);
            stripT = t;
            int curS = 0;
            boolean first = true;
            while (i < instances.size() && instances.get(i)[0] == t) {
                int[] inst = instances.get(i);
                if (first) {
                    firsts.encode(coder, inst[1] - firstS);
                    firstS = inst[1];
                    first = false;
                } else {
                    steps.encode(coder, inst[1] - curS);
                }
                symbolId(coder, idContexts, length, inst[2]);
                curS = inst[1] + inst[3] - 1;
                i++;
            }
            steps.outOfBand(coder);
        }
        byte[] data = coder.finish();
        Jbig2Generic.header(s, 2, 6, 17 + 2 + 4 + data.length, 1);
        Jbig2Generic.regionInformation(s, bitmap.width(), bitmap.height(), 0, 0);
        s.write(0x00);
        s.write(0x10);
        Jbig2Generic.int32(s, instances.size());
        s.writeBytes(data);
    }

    private static void symbolId(MqEncoder coder, MqEncoder.Contexts contexts, int length, int id) {
        int prev = 1;
        for (int b = length - 1; b >= 0; b--) {
            int bit = (id >>> b) & 1;
            coder.encode(contexts, prev, bit);
            prev = prev << 1 | bit;
        }
    }

    private void residual(ByteArrayOutputStream s, byte[] residual, int stride) {
        int w = bitmap.width();
        int h = bitmap.height();
        int top = -1;
        int bottom = -1;
        int left = w;
        int right = -1;
        for (int y = 0; y < h; y++) {
            for (int i = 0; i < stride; i++) {
                int b = residual[y * stride + i] & 0xFF;
                if (b != 0) {
                    if (top < 0) {
                        top = y;
                    }
                    bottom = y;
                    left = Math.min(left, i * 8 + Integer.numberOfLeadingZeros(b) - 24);
                    right = Math.max(right, i * 8 + 7 - Integer.numberOfTrailingZeros(b));
                }
            }
        }
        if (top < 0) {
            return;
        }
        int cw = right - left + 1;
        int ch = bottom - top + 1;
        int cstride = (cw + 7) >>> 3;
        byte[] crop = new byte[cstride * ch];
        for (int y = 0; y < ch; y++) {
            int row = (y + top) * stride;
            for (int x = 0; x < cw; x++) {
                int sx = x + left;
                if ((residual[row + (sx >>> 3)] & (0x80 >>> (sx & 7))) != 0) {
                    crop[y * cstride + (x >>> 3)] |= (byte) (0x80 >>> (x & 7));
                }
            }
        }
        Jbig2Generic.region(s, 3, crop, cstride, cw, ch, left, top);
    }
}

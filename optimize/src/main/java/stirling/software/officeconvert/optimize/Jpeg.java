package stirling.software.officeconvert.optimize;

import java.util.Arrays;

final class Jpeg {

    static final class Unsupported extends Exception {
        Unsupported(String message) {
            super(message, null, false, false);
        }
    }

    private static final int MAX_BLOCKS = 1 << 22;

    private static final int FAST_BITS = 9;

    private static final int MAX_SYMBOLS = (int) Math.max(1 << 12,
            Math.min(1 << 22, Runtime.getRuntime().maxMemory() / (Rewrite.MAX_THREADS * 64L)));

    private static final class Table {

        final int[] maxCode = new int[18];

        final int[] valPtr = new int[17];

        final int[] minCode = new int[17];

        final int[] values;

        final int[] fast = new int[1 << FAST_BITS];

        Table(int[] counts, int[] values) throws Unsupported {
            this.values = values;
            int code = 0;
            int k = 0;
            for (int l = 1; l <= 16; l++) {
                valPtr[l] = k;
                minCode[l] = code;
                code += counts[l];
                k += counts[l];
                maxCode[l] = counts[l] > 0 ? code - 1 : -1;
                if (code > 1 << l) {
                    throw new Unsupported("a Huffman table is over-subscribed");
                }
                code <<= 1;
            }
            maxCode[17] = Integer.MAX_VALUE;
            for (int prefix = 0; prefix < fast.length; prefix++) {
                int c = 0;
                for (int l = 1; l <= FAST_BITS; l++) {
                    c = c << 1 | prefix >> (FAST_BITS - l) & 1;
                    if (maxCode[l] >= 0 && c <= maxCode[l] && c >= minCode[l]) {
                        int at = valPtr[l] + c - minCode[l];
                        fast[prefix] = at < values.length ? l << 8 | values[at] : -1;
                        break;
                    }
                }
            }
        }
    }

    private record Component(int id, int h, int v) {}

    private static final class Scan {

        int[] slots;

        int[] componentIndex;

        byte[] header;

        int headerStart;
    }

    private static final class Symbols {

        int[] packed;

        int size;

        private final int limit;

        final Symbols expected;

        Symbols(int limit, Symbols expected, int initial) {
            this.limit = limit;
            this.expected = expected;
            packed = expected == null ? new int[Math.min(Math.max(1 << 12, initial), limit)] : expected.packed;
        }

        void add(int slot, int symbol, int length, int value) throws Unsupported {
            if (size >= limit) {
                throw new Unsupported("the JPEG holds too many symbols to rewrite");
            }
            int p = slot << 29 | symbol << 21 | length << 16 | value & 0xFFFF;
            if (expected != null) {
                if (size >= expected.size || packed[size] != p) {
                    throw new Unsupported("the rewritten JPEG changes its symbols");
                }
                size++;
                return;
            }
            if (size == packed.length) {
                packed = Arrays.copyOf(packed, (int) Math.min(limit, size * 2L));
            }
            packed[size++] = p;
        }

        static int slot(int p) {
            return p >>> 29;
        }

        static int symbol(int p) {
            return (p >>> 21) & 0xFF;
        }

        static int length(int p) {
            return (p >>> 16) & 0x1F;
        }

        static int value(int p) {
            return p & 0xFFFF;
        }

        boolean same(Symbols o) {
            return size == o.size && Arrays.equals(packed, 0, size, o.packed, 0, size);
        }
    }

    private final byte[] in;

    private int pos;

    private final Table[] tables = new Table[8];

    private Component[] components;

    private int width;

    private int height;

    private int precision;

    private int restartInterval;

    private int hMax;

    private int vMax;

    private final Symbols symbols;

    private long bitBuffer;

    private int bitCount;

    private Jpeg(byte[] in, int maxSymbols, Symbols expected) {
        this.in = in;
        symbols = new Symbols(maxSymbols, expected, in.length);
    }

    static byte[] optimize(byte[] jpeg) {
        return optimize(jpeg, MAX_SYMBOLS);
    }

    static byte[] optimize(byte[] jpeg, int maxSymbols) {
        if (maxSymbols < 1) {
            return null;
        }
        maxSymbols = Math.min(maxSymbols, MAX_SYMBOLS);
        try {
            Jpeg first = new Jpeg(jpeg, maxSymbols, null);
            Bytes out = first.rewrite();
            if (out == null || out.size() >= jpeg.length) {
                return null;
            }
            byte[] result = out.toByteArray();
            Jpeg check = new Jpeg(result, maxSymbols, first.symbols);
            if (check.rewrite() == null || !check.symbols.same(first.symbols)) {
                return null;
            }
            return result;
        } catch (Unsupported | RuntimeException e) {
            return null;
        }
    }

    private int u8() throws Unsupported {
        if (pos >= in.length) {
            throw new Unsupported("the JPEG ends early");
        }
        return in[pos++] & 0xFF;
    }

    private int u16() throws Unsupported {
        return u8() << 8 | u8();
    }

    private Bytes rewrite() throws Unsupported {
        if (in.length < 4 || (in[0] & 0xFF) != 0xFF || (in[1] & 0xFF) != 0xD8) {
            throw new Unsupported("not a JPEG");
        }
        pos = 2;
        Bytes out = new Bytes(in.length);
        out.write(0xFF);
        out.write(0xD8);
        boolean frame = false;
        boolean scanned = false;
        while (true) {
            int b = u8();
            if (b != 0xFF) {
                throw new Unsupported("a JPEG segment does not start with a marker");
            }
            int marker = u8();
            while (marker == 0xFF) {
                marker = u8();
            }
            if (marker == 0xD9) {
                out.write(0xFF);
                out.write(0xD9);
                return scanned ? out : null;
            }
            if (marker == 0x01 || marker >= 0xD0 && marker <= 0xD7) {
                throw new Unsupported("a stray marker");
            }
            int start = pos;
            int length = u16();
            if (length < 2 || start + length > in.length) {
                throw new Unsupported("a JPEG segment runs past the end");
            }
            int end = start + length;
            pos = start;
            if (marker == 0xC4) {
                defineTables(end);
                pos = end;
                continue;
            }
            if (marker == 0xC0 || marker == 0xC1) {
                frame(end);
                frame = true;
            } else if (marker >= 0xC2 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                return null;
            } else if (marker == 0xCC) {
                return null;
            } else if (marker == 0xDD) {
                pos = start + 2;
                restartInterval = u16();
            } else if (marker == 0xDA) {
                if (!frame) {
                    throw new Unsupported("a scan before the frame");
                }
                Scan scan = scanHeader(end);
                pos = end;
                int symbolsBefore = symbols.size;
                decodeScan(scan);
                int dataEnd = pos;
                if (symbols.expected == null) {
                    writeTables(out, scan, symbolsBefore);
                    out.write(0xFF);
                    out.write(0xDA);
                    out.write(in, start, length);
                    encodeScan(out, scan, symbolsBefore);
                }
                pos = dataEnd;
                scanned = true;
                continue;
            }
            out.write(0xFF);
            out.write(marker);
            out.write(in, start, length);
            pos = end;
        }
    }

    private void defineTables(int end) throws Unsupported {
        pos += 2;
        while (pos < end) {
            int tcth = u8();
            int tc = tcth >> 4;
            int th = tcth & 15;
            if (tc > 1 || th > 3) {
                throw new Unsupported("a Huffman table id is out of range");
            }
            int[] counts = new int[17];
            int total = 0;
            for (int l = 1; l <= 16; l++) {
                counts[l] = u8();
                total += counts[l];
            }
            if (total > 256 || pos + total > end) {
                throw new Unsupported("a Huffman table is too long");
            }
            int[] values = new int[total];
            for (int i = 0; i < total; i++) {
                values[i] = u8();
            }
            tables[tc * 4 + th] = new Table(counts, values);
        }
    }

    private void frame(int end) throws Unsupported {
        pos += 2;
        precision = u8();
        height = u16();
        width = u16();
        int n = u8();
        if (precision != 8 && precision != 12 || width == 0 || height == 0 || n < 1 || n > 4) {
            throw new Unsupported("an unsupported frame");
        }
        components = new Component[n];
        hMax = 1;
        vMax = 1;
        for (int i = 0; i < n; i++) {
            int id = u8();
            int hv = u8();
            u8();
            int h = hv >> 4;
            int v = hv & 15;
            if (h < 1 || h > 4 || v < 1 || v > 4) {
                throw new Unsupported("an unsupported sampling factor");
            }
            components[i] = new Component(id, h, v);
            hMax = Math.max(hMax, h);
            vMax = Math.max(vMax, v);
        }
        if (pos != end) {
            throw new Unsupported("a frame header with extra bytes");
        }
    }

    private Scan scanHeader(int end) throws Unsupported {
        pos += 2;
        int n = u8();
        if (n < 1 || n > components.length) {
            throw new Unsupported("a scan with too many components");
        }
        Scan s = new Scan();
        s.slots = new int[n * 2];
        s.componentIndex = new int[n];
        for (int i = 0; i < n; i++) {
            int id = u8();
            int t = u8();
            int index = -1;
            for (int c = 0; c < components.length; c++) {
                if (components[c].id == id) {
                    index = c;
                }
            }
            if (index < 0 || tables[t >> 4] == null || tables[4 + (t & 15)] == null || t >> 4 > 3
                    || (t & 15) > 3) {
                throw new Unsupported("a scan names a component or table that is not defined");
            }
            s.componentIndex[i] = index;
            s.slots[2 * i] = t >> 4;
            s.slots[2 * i + 1] = 4 + (t & 15);
        }
        int ss = u8();
        int se = u8();
        int a = u8();
        if (ss != 0 || se != 63 || a != 0 || pos != end) {
            throw new Unsupported("not a sequential scan");
        }
        return s;
    }

    private void decodeScan(Scan s) throws Unsupported {
        bitBuffer = 0;
        bitCount = 0;
        int n = s.componentIndex.length;
        long mcus;
        int[] blocksPerMcu = new int[n];
        if (n == 1) {
            Component c = components[s.componentIndex[0]];
            long bw = ceil(ceil((long) width * c.h, hMax), 8);
            long bh = ceil(ceil((long) height * c.v, vMax), 8);
            mcus = bw * bh;
            blocksPerMcu[0] = 1;
        } else {
            mcus = ceil(width, 8L * hMax) * ceil(height, 8L * vMax);
            for (int i = 0; i < n; i++) {
                Component c = components[s.componentIndex[i]];
                blocksPerMcu[i] = c.h * c.v;
            }
        }
        long blocks = 0;
        for (int b : blocksPerMcu) {
            blocks += b * mcus;
        }
        if (blocks > MAX_BLOCKS) {
            throw new Unsupported("the JPEG is too large");
        }
        int dcLimit = precision == 8 ? 11 : 15;
        for (long m = 0; m < mcus; m++) {
            if (restartInterval > 0 && m > 0 && m % restartInterval == 0) {
                restart((int) ((m / restartInterval - 1) & 7));
            }
            for (int i = 0; i < n; i++) {
                int dc = s.slots[2 * i];
                int ac = s.slots[2 * i + 1];
                for (int b = 0; b < blocksPerMcu[i]; b++) {
                    int t = decode(tables[dc]);
                    if (t > dcLimit) {
                        throw new Unsupported("a DC difference is out of range");
                    }
                    symbols.add(dc, t, t, bits(t));
                    for (int k = 1; k < 64; ) {
                        int rs = decode(tables[ac]);
                        int size = rs & 15;
                        int run = rs >> 4;
                        symbols.add(ac, rs, size, bits(size));
                        if (size == 0) {
                            if (run != 15) {
                                break;
                            }
                            k += 16;
                        } else {
                            k += run + 1;
                        }
                        if (k > 64) {
                            throw new Unsupported("an AC run passes the end of a block");
                        }
                    }
                }
            }
        }
        while (pos + 1 < in.length && !((in[pos] & 0xFF) == 0xFF && (in[pos + 1] & 0xFF) != 0
                && ((in[pos + 1] & 0xFF) < 0xD0 || (in[pos + 1] & 0xFF) > 0xD7))) {
            pos++;
        }
    }

    private void restart(int expected) throws Unsupported {
        bitBuffer = 0;
        bitCount = 0;
        while (pos + 1 < in.length && (in[pos] & 0xFF) != 0xFF) {
            pos++;
        }
        if (pos + 1 >= in.length || (in[pos] & 0xFF) != 0xFF || (in[pos + 1] & 0xFF) != 0xD0 + expected) {
            throw new Unsupported("a restart marker is missing");
        }
        pos += 2;
    }

    private void fill() {
        while (bitCount <= 56 && pos < in.length) {
            int b = in[pos] & 0xFF;
            if (b == 0xFF) {
                if (pos + 1 >= in.length || in[pos + 1] != 0) {
                    return;
                }
                pos += 2;
            } else {
                pos++;
            }
            bitBuffer = bitBuffer << 8 | b;
            bitCount += 8;
        }
    }

    private int bits(int n) throws Unsupported {
        if (n == 0) {
            return 0;
        }
        if (bitCount < n) {
            fill();
            if (bitCount < n) {
                throw new Unsupported("the scan ends early");
            }
        }
        bitCount -= n;
        return (int) (bitBuffer >>> bitCount) & ((1 << n) - 1);
    }

    private int decode(Table t) throws Unsupported {
        if (bitCount < 16) {
            fill();
        }
        int prefix = bitCount >= FAST_BITS ? (int) (bitBuffer >>> (bitCount - FAST_BITS)) & ((1 << FAST_BITS) - 1)
                : (int) (bitBuffer << (FAST_BITS - bitCount)) & ((1 << FAST_BITS) - 1);
        int hit = t.fast[prefix];
        if (hit > 0 && hit >> 8 <= bitCount) {
            bitCount -= hit >> 8;
            return hit & 0xFF;
        }
        int code = 0;
        for (int l = 1; l <= 16; l++) {
            code = code << 1 | bits(1);
            if (t.maxCode[l] >= 0 && code <= t.maxCode[l] && code >= t.minCode[l]) {
                return t.values[t.valPtr[l] + code - t.minCode[l]];
            }
        }
        throw new Unsupported("a Huffman code is not in its table");
    }

    private int[][] lengthsFor(Scan s, int from) throws Unsupported {
        int[][] lengths = new int[8][];
        int[][] counts = new int[8][];
        for (int slot : s.slots) {
            if (counts[slot] == null) {
                counts[slot] = new int[257];
            }
        }
        for (int i = from; i < symbols.size; i++) {
            int p = symbols.packed[i];
            counts[Symbols.slot(p)][Symbols.symbol(p)] += 2;
        }
        for (int slot = 0; slot < 8; slot++) {
            if (counts[slot] == null) {
                continue;
            }
            counts[slot][256] = 1;
            int[] l = Huffman.lengths(counts[slot], 16);
            int max = 0;
            for (int x : l) {
                max = Math.max(max, x);
            }
            if (l[256] != max) {
                throw new Unsupported("the reserved code did not take the longest length");
            }
            lengths[slot] = l;
        }
        return lengths;
    }

    private int[][] codeTables;

    private int[][] codeLengths;

    private void writeTables(Bytes out, Scan s, int from) throws Unsupported {
        codeLengths = lengthsFor(s, from);
        codeTables = new int[8][];
        Bytes body = new Bytes(512);
        for (int slot = 0; slot < 8; slot++) {
            int[] l = codeLengths[slot];
            if (l == null) {
                continue;
            }
            codeTables[slot] = Huffman.codes(l);
            body.write((slot >= 4 ? 0x10 : 0) | (slot & 3));
            int[] counts = new int[17];
            for (int sym = 0; sym < 256; sym++) {
                if (l[sym] > 0) {
                    counts[l[sym]]++;
                }
            }
            for (int len = 1; len <= 16; len++) {
                body.write(counts[len]);
            }
            for (int len = 1; len <= 16; len++) {
                for (int sym = 0; sym < 256; sym++) {
                    if (l[sym] == len) {
                        body.write(sym);
                    }
                }
            }
        }
        out.write(0xFF);
        out.write(0xC4);
        out.write((body.size() + 2) >> 8);
        out.write((body.size() + 2) & 0xFF);
        out.write(body.array(), 0, body.size());
    }

    private long acc;

    private int accBits;

    private byte[] scanBytes;

    private int scanSize;

    private void encodeScan(Bytes out, Scan s, int from) {
        acc = 0;
        accBits = 0;
        scanBytes = new byte[Math.max(1 << 12, in.length + 64)];
        scanSize = 0;
        int n = s.componentIndex.length;
        long mcus;
        int[] blocksPerMcu = new int[n];
        if (n == 1) {
            Component c = components[s.componentIndex[0]];
            mcus = ceil(ceil((long) width * c.h, hMax), 8) * ceil(ceil((long) height * c.v, vMax), 8);
            blocksPerMcu[0] = 1;
        } else {
            mcus = ceil(width, 8L * hMax) * ceil(height, 8L * vMax);
            for (int i = 0; i < n; i++) {
                Component c = components[s.componentIndex[i]];
                blocksPerMcu[i] = c.h * c.v;
            }
        }
        int k = from;
        for (long m = 0; m < mcus; m++) {
            if (restartInterval > 0 && m > 0 && m % restartInterval == 0) {
                flush();
                room(2);
                scanBytes[scanSize++] = (byte) 0xFF;
                scanBytes[scanSize++] = (byte) (0xD0 + (int) ((m / restartInterval - 1) & 7));
            }
            for (int i = 0; i < n; i++) {
                for (int b = 0; b < blocksPerMcu[i]; b++) {
                    k = emit(k);
                    for (int c = 1; c < 64; ) {
                        int rs = Symbols.symbol(symbols.packed[k]);
                        k = emit(k);
                        int size = rs & 15;
                        int run = rs >> 4;
                        if (size == 0) {
                            if (run != 15) {
                                break;
                            }
                            c += 16;
                        } else {
                            c += run + 1;
                        }
                    }
                }
            }
        }
        flush();
        out.write(scanBytes, 0, scanSize);
        scanBytes = null;
    }

    private int emit(int k) {
        int p = symbols.packed[k];
        int slot = Symbols.slot(p);
        int sym = Symbols.symbol(p);
        int extra = Symbols.length(p);
        put(codeTables[slot][sym] << extra | Symbols.value(p) & ((1 << extra) - 1), codeLengths[slot][sym] + extra);
        return k + 1;
    }

    private void put(int value, int n) {
        if (n == 0) {
            return;
        }
        acc = acc << n | (value & ((1L << n) - 1));
        accBits += n;
        if (accBits >= 32) {
            drain();
        }
    }

    private void drain() {
        room(2 * (accBits >> 3));
        while (accBits >= 8) {
            int b = (int) (acc >> (accBits - 8)) & 0xFF;
            scanBytes[scanSize++] = (byte) b;
            if (b == 0xFF) {
                scanBytes[scanSize++] = 0;
            }
            accBits -= 8;
        }
    }

    private void room(int more) {
        if (scanSize + more > scanBytes.length) {
            scanBytes = Arrays.copyOf(scanBytes, Math.max(scanSize + more, scanBytes.length * 2));
        }
    }

    private void flush() {
        if (accBits > 0) {
            int pad = 8 - (accBits & 7);
            if (pad < 8) {
                put((1 << pad) - 1, pad);
            }
        }
        drain();
        acc = 0;
        accBits = 0;
    }

    private static long ceil(long a, long b) {
        return (a + b - 1) / b;
    }
}

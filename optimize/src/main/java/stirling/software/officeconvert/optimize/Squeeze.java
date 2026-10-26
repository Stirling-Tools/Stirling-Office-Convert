package stirling.software.officeconvert.optimize;

import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.zip.Adler32;

import stirling.software.officeconvert.extract.PdfFiles;

final class Squeeze {

    record Settings(int iterations, int chain, int nice, int maxBlocks) {}

    static final int WINDOW = 32768;

    private static final int WMASK = WINDOW - 1;

    private static final int MIN_MATCH = 3;

    private static final int MAX_MATCH = 258;

    private static final int MASTER_BLOCK = 1 << 20;

    private static final int HASH_BITS = 15;

    private static final int MIN_SPLIT = 512;

    private static final int[] LENGTH_BASE = {3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59,
            67, 83, 99, 115, 131, 163, 195, 227, 258};

    private static final int[] LENGTH_EXTRA = {0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4,
            5, 5, 5, 5, 0};

    private static final int[] DIST_BASE = {1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513,
            769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577};

    private static final int[] DIST_EXTRA = {0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10,
            11, 11, 12, 12, 13, 13};

    private static final int[] LENGTH_SYMBOL = new int[MAX_MATCH + 1];

    private static final int[] DIST_SYMBOL_SMALL = new int[512];

    private static final int[] CL_ORDER = {16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15};

    static {
        for (int code = 0; code < 29; code++) {
            int end = code == 28 ? 258 : LENGTH_BASE[code] + (1 << LENGTH_EXTRA[code]) - 1;
            for (int l = LENGTH_BASE[code]; l <= Math.min(end, 258); l++) {
                LENGTH_SYMBOL[l] = code;
            }
        }
        LENGTH_SYMBOL[258] = 28;
        for (int d = 1; d <= 512; d++) {
            DIST_SYMBOL_SMALL[d - 1] = distSymbolSlow(d);
        }
    }

    private final byte[] data;

    private final Settings settings;

    private final int[] head = new int[1 << HASH_BITS];

    private final int[] prev = new int[WINDOW];

    private int inserted;

    private final BitWriter out;

    private Squeeze(byte[] data, Settings settings) {
        this.data = data;
        this.settings = settings;
        this.out = new BitWriter(Math.max(64, data.length / 3));
        Arrays.fill(head, -1);
    }

    static byte[] zlib(byte[] data, Settings settings) throws InterruptedIOException {
        Squeeze s = new Squeeze(data, settings);
        s.out.writeByte(0x78);
        s.out.writeByte(0xDA);
        if (data.length == 0) {
            s.out.bits(1, 1);
            s.out.bits(1, 2);
            s.out.bits(0, 7);
        } else {
            for (int start = 0; start < data.length; start += MASTER_BLOCK) {
                int end = Math.min(data.length, start + MASTER_BLOCK);
                s.master(start, end, end == data.length);
            }
        }
        s.out.align();
        Adler32 adler = new Adler32();
        adler.update(data);
        long a = adler.getValue();
        s.out.writeByte((int) (a >>> 24));
        s.out.writeByte((int) (a >>> 16));
        s.out.writeByte((int) (a >>> 8));
        s.out.writeByte((int) a);
        return s.out.toByteArray();
    }

    private int hash(int i) {
        return ((data[i] & 0xFF) << 10 ^ (data[i + 1] & 0xFF) << 5 ^ (data[i + 2] & 0xFF)) & ((1 << HASH_BITS) - 1);
    }

    private void insert(int i) {
        if (i + 2 < data.length) {
            int h = hash(i);
            prev[i & WMASK] = head[h];
            head[h] = i;
        }
    }

    private Matches matches(int start, int end) throws InterruptedIOException {
        while (inserted < start) {
            insert(inserted++);
        }
        Matches m = new Matches(end - start);
        for (int i = start; i < end; i++) {
            if (((i - start) & 0xFFFF) == 0) {
                PdfFiles.stopIfInterrupted();
            }
            m.begin(i - start);
            int limit = Math.min(MAX_MATCH, data.length - i);
            if (limit >= MIN_MATCH) {
                int best = MIN_MATCH - 1;
                int p = head[hash(i)];
                int hits = 0;
                while (p >= 0 && i - p <= WINDOW && hits++ < settings.chain) {
                    if (p < i && data[p + best] == data[i + best]) {
                        int l = 0;
                        while (l < limit && data[p + l] == data[i + l]) {
                            l++;
                        }
                        if (l > best) {
                            best = l;
                            m.add(l, i - p);
                            if (l >= settings.nice || l == limit) {
                                break;
                            }
                        }
                    }
                    int q = prev[p & WMASK];
                    if (q >= p) {
                        break;
                    }
                    p = q;
                }
            }
            insert(i);
            inserted = i + 1;
        }
        m.finish();
        return m;
    }

    private void master(int start, int end, boolean last) throws InterruptedIOException {
        Matches m = matches(start, end);
        Store greedy = greedy(m, start, end, start, end);
        int[] splits = split(greedy);
        int blocks = splits.length + 1;
        for (int b = 0; b < blocks; b++) {
            int ls = b == 0 ? 0 : splits[b - 1];
            int le = b == blocks - 1 ? greedy.size : splits[b];
            int bs = greedy.position(ls, start);
            int be = le == greedy.size ? end : greedy.position(le, start);
            if (be <= bs) {
                continue;
            }
            Store best = optimize(m, start, bs, be, greedy.slice(ls, le));
            emit(best, bs, be, last && be == end);
        }
    }

    private Store greedy(Matches m, int base, int end, int from, int to) {
        Store s = new Store(Math.max(16, (to - from) / 2));
        int i = from;
        while (i < to) {
            int longest = Math.min(m.longest(i - base), to - i);
            if (longest >= MIN_MATCH) {
                s.add(longest, m.distance(i - base, longest));
                i += longest;
            } else {
                s.add(data[i] & 0xFF, 0);
                i++;
            }
        }
        return s;
    }

    private Store optimize(Matches m, int base, int from, int to, Store initial) throws InterruptedIOException {
        Store best = initial;
        double bestCost = dynamicCost(initial, 0, initial.size);
        Store current = initial;
        int stale = 0;
        for (int it = 0; it < settings.iterations; it++) {
            PdfFiles.stopIfInterrupted();
            Costs costs = Costs.of(current);
            Store next = parse(m, base, from, to, costs);
            double c = dynamicCost(next, 0, next.size);
            if (c < bestCost - 0.5) {
                best = next;
                bestCost = c;
                stale = 0;
            } else if (++stale >= 3) {
                break;
            }
            current = next;
        }
        return best;
    }

    private Store parse(Matches m, int base, int from, int to, Costs costs) {
        int n = to - from;
        double[] cost = new double[n + 1];
        int[] length = new int[n + 1];
        int[] dist = new int[n + 1];
        Arrays.fill(cost, Double.MAX_VALUE);
        cost[0] = 0;
        double[] lengthCost = new double[MAX_MATCH + 1];
        for (int l = MIN_MATCH; l <= MAX_MATCH; l++) {
            int sym = LENGTH_SYMBOL[l];
            lengthCost[l] = costs.litlen[257 + sym] + LENGTH_EXTRA[sym];
        }
        for (int k = 0; k < n; k++) {
            double here = cost[k];
            if (here == Double.MAX_VALUE) {
                continue;
            }
            int i = from + k;
            double lit = here + costs.litlen[data[i] & 0xFF];
            if (lit < cost[k + 1]) {
                cost[k + 1] = lit;
                length[k + 1] = 1;
                dist[k + 1] = 0;
            }
            int at = m.start[i - base];
            int stop = m.start[i - base + 1];
            int previous = MIN_MATCH - 1;
            int room = n - k;
            for (int p = at; p < stop && previous < room; p += 2) {
                int upTo = Math.min(m.pairs[p], room);
                int d = m.pairs[p + 1];
                int ds = distSymbol(d);
                double dc = here + costs.dist[ds] + DIST_EXTRA[ds];
                for (int l = previous + 1; l <= upTo; l++) {
                    double c = dc + lengthCost[l];
                    if (c < cost[k + l]) {
                        cost[k + l] = c;
                        length[k + l] = l;
                        dist[k + l] = d;
                    }
                }
                previous = upTo;
            }
        }
        int count = 0;
        for (int k = n; k > 0; k -= length[k]) {
            count++;
        }
        int[] lens = new int[count];
        int[] dists = new int[count];
        int j = count;
        for (int k = n; k > 0; k -= length[k]) {
            j--;
            if (dist[k] == 0) {
                lens[j] = data[from + k - 1] & 0xFF;
                dists[j] = 0;
            } else {
                lens[j] = length[k];
                dists[j] = dist[k];
            }
        }
        Store s = new Store(count);
        for (int q = 0; q < count; q++) {
            s.add(lens[q], dists[q]);
        }
        return s;
    }

    private int[] split(Store s) {
        if (settings.maxBlocks <= 1 || s.size < 2 * MIN_SPLIT) {
            return new int[0];
        }
        java.util.TreeSet<Integer> points = new java.util.TreeSet<>();
        splitRange(s, 0, s.size, points);
        return points.stream().mapToInt(Integer::intValue).toArray();
    }

    private void splitRange(Store s, int from, int to, java.util.TreeSet<Integer> points) {
        if (to - from < MIN_SPLIT || points.size() + 1 >= settings.maxBlocks) {
            return;
        }
        Histogram total = Histogram.of(s, from, to);
        double whole = total.bits();
        int lo = from + 1;
        int hi = to - 1;
        int bestAt = -1;
        double best = Double.MAX_VALUE;
        while (hi - lo > 8) {
            int step = Math.max(1, (hi - lo) / 10);
            Histogram left = Histogram.of(s, from, lo);
            int at = lo;
            int localBest = -1;
            double localCost = Double.MAX_VALUE;
            for (int p = lo; p <= hi; p += step) {
                while (at < p) {
                    left.add(s, at++, 1);
                }
                double c = left.bits() + total.minus(left).bits();
                if (c < localCost) {
                    localCost = c;
                    localBest = p;
                }
            }
            if (localCost < best) {
                best = localCost;
                bestAt = localBest;
            }
            lo = Math.max(from + 1, localBest - step);
            hi = Math.min(to - 1, localBest + step);
        }
        if (bestAt < 0 || best > whole - 10 * 8) {
            return;
        }
        points.add(bestAt);
        splitRange(s, from, bestAt, points);
        splitRange(s, bestAt, to, points);
    }

    private static double dynamicCost(Store s, int from, int to) {
        return Histogram.of(s, from, to).bits();
    }

    private static final class Histogram {

        final int[] ll = new int[288];

        final int[] dd = new int[30];

        long extra;

        static Histogram of(Store s, int from, int to) {
            Histogram h = new Histogram();
            for (int i = from; i < to; i++) {
                h.add(s, i, 1);
            }
            return h;
        }

        void add(Store s, int i, int sign) {
            if (s.dists[i] == 0) {
                ll[s.lens[i]] += sign;
            } else {
                int ls = LENGTH_SYMBOL[s.lens[i]];
                ll[257 + ls] += sign;
                int ds = distSymbol(s.dists[i]);
                dd[ds] += sign;
                extra += sign * (long) (LENGTH_EXTRA[ls] + DIST_EXTRA[ds]);
            }
        }

        Histogram minus(Histogram o) {
            Histogram h = new Histogram();
            for (int i = 0; i < 288; i++) {
                h.ll[i] = ll[i] - o.ll[i];
            }
            for (int i = 0; i < 30; i++) {
                h.dd[i] = dd[i] - o.dd[i];
            }
            h.extra = extra - o.extra;
            return h;
        }

        double bits() {
            int[] llc = ll.clone();
            llc[256] = 1;
            int[] llLengths = Huffman.lengths(llc, 15);
            int[] ddLengths = Huffman.lengths(dd, 15);
            patchDistances(ddLengths);
            long bits = extra;
            for (int i = 0; i < 288; i++) {
                bits += (long) llc[i] * llLengths[i];
            }
            for (int i = 0; i < 30; i++) {
                bits += (long) dd[i] * ddLengths[i];
            }
            return bits + treeBits(llLengths, ddLengths, null);
        }
    }

    private void emit(Store s, int from, int to, boolean last) {
        int[] ll = new int[288];
        int[] dd = new int[30];
        for (int i = 0; i < s.size; i++) {
            if (s.dists[i] == 0) {
                ll[s.lens[i]]++;
            } else {
                ll[257 + LENGTH_SYMBOL[s.lens[i]]]++;
                dd[distSymbol(s.dists[i])]++;
            }
        }
        ll[256] = 1;
        int[] llLengths = Huffman.lengths(ll, 15);
        int[] ddLengths = Huffman.lengths(dd, 15);
        patchDistances(ddLengths);
        patchLiterals(llLengths);
        long dynamic = 3 + treeBits(llLengths, ddLengths, null) + dataBits(s, llLengths, ddLengths);
        int[] fixedLl = fixedLiteralLengths();
        int[] fixedDd = new int[30];
        Arrays.fill(fixedDd, 5);
        long fixed = 3 + dataBits(s, fixedLl, fixedDd);
        long stored = storedBits(to - from);
        if (stored <= dynamic && stored <= fixed) {
            emitStored(from, to, last);
        } else if (fixed <= dynamic) {
            out.bits(last ? 1 : 0, 1);
            out.bits(1, 2);
            emitData(s, fixedLl, fixedDd);
        } else {
            out.bits(last ? 1 : 0, 1);
            out.bits(2, 2);
            treeBits(llLengths, ddLengths, out);
            emitData(s, llLengths, ddLengths);
        }
    }

    private long storedBits(int length) {
        int blocks = Math.max(1, (length + 65534) / 65535);
        return (long) blocks * (5 * 8 + 7) + length * 8L;
    }

    private void emitStored(int from, int to, boolean last) {
        int pos = from;
        do {
            int len = Math.min(65535, to - pos);
            boolean final_ = last && pos + len == to;
            out.bits(final_ ? 1 : 0, 1);
            out.bits(0, 2);
            out.align();
            out.writeByte(len & 0xFF);
            out.writeByte(len >>> 8);
            out.writeByte(~len & 0xFF);
            out.writeByte((~len >>> 8) & 0xFF);
            out.writeBytes(data, pos, len);
            pos += len;
        } while (pos < to);
    }

    private long dataBits(Store s, int[] llLengths, int[] ddLengths) {
        long bits = llLengths[256];
        for (int i = 0; i < s.size; i++) {
            if (s.dists[i] == 0) {
                bits += llLengths[s.lens[i]];
            } else {
                int ls = LENGTH_SYMBOL[s.lens[i]];
                int ds = distSymbol(s.dists[i]);
                bits += llLengths[257 + ls] + LENGTH_EXTRA[ls] + ddLengths[ds] + DIST_EXTRA[ds];
            }
        }
        return bits;
    }

    private void emitData(Store s, int[] llLengths, int[] ddLengths) {
        int[] llCodes = Huffman.codes(llLengths);
        int[] ddCodes = Huffman.codes(ddLengths);
        for (int i = 0; i < s.size; i++) {
            if (s.dists[i] == 0) {
                out.huffman(llCodes[s.lens[i]], llLengths[s.lens[i]]);
            } else {
                int l = s.lens[i];
                int ls = LENGTH_SYMBOL[l];
                out.huffman(llCodes[257 + ls], llLengths[257 + ls]);
                out.bits(l - LENGTH_BASE[ls], LENGTH_EXTRA[ls]);
                int d = s.dists[i];
                int ds = distSymbol(d);
                out.huffman(ddCodes[ds], ddLengths[ds]);
                out.bits(d - DIST_BASE[ds], DIST_EXTRA[ds]);
            }
        }
        out.huffman(llCodes[256], llLengths[256]);
    }

    private static long treeBits(int[] llLengths, int[] ddLengths, BitWriter w) {
        int hlit = 286;
        while (hlit > 257 && llLengths[hlit - 1] == 0) {
            hlit--;
        }
        int hdist = 30;
        while (hdist > 1 && ddLengths[hdist - 1] == 0) {
            hdist--;
        }
        int[] all = new int[hlit + hdist];
        System.arraycopy(llLengths, 0, all, 0, hlit);
        System.arraycopy(ddLengths, 0, all, hlit, hdist);
        int[] symbols = new int[all.length];
        int[] extras = new int[all.length];
        int count = 0;
        int[] clCounts = new int[19];
        for (int i = 0; i < all.length; ) {
            int v = all[i];
            int run = 1;
            while (i + run < all.length && all[i + run] == v) {
                run++;
            }
            if (v == 0 && run >= 3) {
                int r = run;
                while (r >= 3) {
                    if (r >= 11) {
                        int take = Math.min(138, r);
                        symbols[count] = 18;
                        extras[count++] = take - 11;
                        clCounts[18]++;
                        r -= take;
                    } else {
                        symbols[count] = 17;
                        extras[count++] = r - 3;
                        clCounts[17]++;
                        r = 0;
                    }
                }
                for (int k = 0; k < r; k++) {
                    symbols[count++] = 0;
                    clCounts[0]++;
                }
            } else if (v != 0 && run >= 4) {
                symbols[count++] = v;
                clCounts[v]++;
                int r = run - 1;
                while (r >= 3) {
                    int take = Math.min(6, r);
                    symbols[count] = 16;
                    extras[count++] = take - 3;
                    clCounts[16]++;
                    r -= take;
                }
                for (int k = 0; k < r; k++) {
                    symbols[count++] = v;
                    clCounts[v]++;
                }
            } else {
                for (int k = 0; k < run; k++) {
                    symbols[count++] = v;
                    clCounts[v]++;
                }
            }
            i += run;
        }
        int[] clLengths = Huffman.lengths(clCounts, 7);
        int used = 0;
        for (int c : clLengths) {
            used += c > 0 ? 1 : 0;
        }
        if (used == 1) {
            for (int k = 0; k < 19; k++) {
                if (clLengths[k] == 0) {
                    clLengths[k] = 1;
                    break;
                }
            }
            for (int k = 0; k < 19; k++) {
                if (clLengths[k] > 0) {
                    clLengths[k] = 1;
                }
            }
        }
        int hclen = 19;
        while (hclen > 4 && clLengths[CL_ORDER[hclen - 1]] == 0) {
            hclen--;
        }
        long bits = 14 + 4 + 3L * hclen;
        for (int k = 0; k < count; k++) {
            int sym = symbols[k];
            bits += clLengths[sym] + (sym == 16 ? 2 : sym == 17 ? 3 : sym == 18 ? 7 : 0);
        }
        if (w != null) {
            w.bits(hlit - 257, 5);
            w.bits(hdist - 1, 5);
            w.bits(hclen - 4, 4);
            for (int k = 0; k < hclen; k++) {
                w.bits(clLengths[CL_ORDER[k]], 3);
            }
            int[] clCodes = Huffman.codes(clLengths);
            for (int k = 0; k < count; k++) {
                int sym = symbols[k];
                w.huffman(clCodes[sym], clLengths[sym]);
                if (sym == 16) {
                    w.bits(extras[k], 2);
                } else if (sym == 17) {
                    w.bits(extras[k], 3);
                } else if (sym == 18) {
                    w.bits(extras[k], 7);
                }
            }
        }
        return bits;
    }

    private static void patchDistances(int[] lengths) {
        int used = 0;
        for (int i = 0; i < 30; i++) {
            used += lengths[i] > 0 ? 1 : 0;
        }
        if (used == 0) {
            lengths[0] = 1;
            lengths[1] = 1;
        } else if (used == 1) {
            lengths[lengths[0] > 0 ? 1 : 0] = 1;
        }
    }

    private static void patchLiterals(int[] lengths) {
        int used = 0;
        for (int i = 0; i < 286; i++) {
            used += lengths[i] > 0 ? 1 : 0;
        }
        if (used == 1) {
            lengths[lengths[0] > 0 ? 1 : 0] = 1;
        }
    }

    private static int[] fixedLiteralLengths() {
        int[] l = new int[288];
        for (int i = 0; i < 288; i++) {
            l[i] = i < 144 ? 8 : i < 256 ? 9 : i < 280 ? 7 : 8;
        }
        return l;
    }

    static int distSymbol(int d) {
        return d <= 512 ? DIST_SYMBOL_SMALL[d - 1] : distSymbolSlow(d);
    }

    private static int distSymbolSlow(int d) {
        for (int c = 29; c >= 0; c--) {
            if (d >= DIST_BASE[c]) {
                return c;
            }
        }
        return 0;
    }

    private static final class Costs {

        final double[] litlen = new double[288];

        final double[] dist = new double[30];

        static Costs of(Store s) {
            int[] ll = new int[288];
            int[] dd = new int[30];
            for (int i = 0; i < s.size; i++) {
                if (s.dists[i] == 0) {
                    ll[s.lens[i]]++;
                } else {
                    ll[257 + LENGTH_SYMBOL[s.lens[i]]]++;
                    dd[distSymbol(s.dists[i])]++;
                }
            }
            ll[256] = 1;
            Costs c = new Costs();
            entropy(ll, c.litlen);
            entropy(dd, c.dist);
            return c;
        }

        private static void entropy(int[] counts, double[] bits) {
            long sum = 0;
            for (int c : counts) {
                sum += c;
            }
            double log2sum = sum == 0 ? Math.log(counts.length) / Math.log(2) : Math.log(sum) / Math.log(2);
            for (int i = 0; i < counts.length; i++) {
                bits[i] = counts[i] == 0 ? log2sum : log2sum - Math.log(counts[i]) / Math.log(2);
                if (bits[i] < 0) {
                    bits[i] = 0;
                }
            }
        }
    }

    private static final class Store {

        int[] lens;

        int[] dists;

        int size;

        Store(int capacity) {
            lens = new int[Math.max(16, capacity)];
            dists = new int[Math.max(16, capacity)];
        }

        void add(int len, int dist) {
            if (size == lens.length) {
                lens = Arrays.copyOf(lens, size * 2);
                dists = Arrays.copyOf(dists, size * 2);
            }
            lens[size] = len;
            dists[size++] = dist;
        }

        int position(int index, int base) {
            int p = base;
            for (int i = 0; i < index; i++) {
                p += dists[i] == 0 ? 1 : lens[i];
            }
            return p;
        }

        Store slice(int from, int to) {
            Store s = new Store(to - from);
            System.arraycopy(lens, from, s.lens, 0, to - from);
            System.arraycopy(dists, from, s.dists, 0, to - from);
            s.size = to - from;
            return s;
        }
    }

    private static final class Matches {

        final int[] start;

        int[] pairs = new int[1024];

        int used;

        Matches(int n) {
            start = new int[n + 1];
        }

        void begin(int k) {
            start[k] = used;
        }

        void add(int len, int dist) {
            if (used + 2 > pairs.length) {
                pairs = Arrays.copyOf(pairs, pairs.length * 2);
            }
            pairs[used++] = len;
            pairs[used++] = dist;
        }

        void finish() {
            start[start.length - 1] = used;
        }

        int longest(int k) {
            int end = start[k + 1];
            return end > start[k] ? pairs[end - 2] : 0;
        }

        int distance(int k, int length) {
            for (int p = start[k]; p < start[k + 1]; p += 2) {
                if (pairs[p] >= length) {
                    return pairs[p + 1];
                }
            }
            return 0;
        }
    }

    private static final class BitWriter {

        private byte[] buf;

        private int size;

        private long acc;

        private int count;

        BitWriter(int capacity) {
            buf = new byte[capacity];
        }

        void bits(int value, int n) {
            acc |= (long) (value & ((1 << n) - 1)) << count;
            count += n;
            while (count >= 8) {
                put((int) acc);
                acc >>>= 8;
                count -= 8;
            }
        }

        void huffman(int code, int length) {
            int reversed = Integer.reverse(code) >>> (32 - length);
            bits(reversed, length);
        }

        void align() {
            if (count > 0) {
                put((int) acc);
                acc = 0;
                count = 0;
            }
        }

        void writeByte(int b) {
            put(b);
        }

        void writeBytes(byte[] b, int off, int len) {
            if (size + len > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(size + len, buf.length * 2));
            }
            System.arraycopy(b, off, buf, size, len);
            size += len;
        }

        private void put(int b) {
            if (size == buf.length) {
                buf = Arrays.copyOf(buf, buf.length * 2);
            }
            buf[size++] = (byte) b;
        }

        byte[] toByteArray() {
            return Arrays.copyOf(buf, size);
        }
    }
}

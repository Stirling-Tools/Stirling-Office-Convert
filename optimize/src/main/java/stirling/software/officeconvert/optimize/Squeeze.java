package stirling.software.officeconvert.optimize;

import static stirling.software.officeconvert.optimize.DeflateTables.CL_ORDER;
import static stirling.software.officeconvert.optimize.DeflateTables.DIST_BASE;
import static stirling.software.officeconvert.optimize.DeflateTables.DIST_EXTRA;
import static stirling.software.officeconvert.optimize.DeflateTables.LENGTH_BASE;
import static stirling.software.officeconvert.optimize.DeflateTables.LENGTH_EXTRA;
import static stirling.software.officeconvert.optimize.DeflateTables.LENGTH_SYMBOL;
import static stirling.software.officeconvert.optimize.DeflateTables.MAX_MATCH;
import static stirling.software.officeconvert.optimize.DeflateTables.MIN_MATCH;
import static stirling.software.officeconvert.optimize.DeflateTables.NEXT;
import static stirling.software.officeconvert.optimize.DeflateTables.distSymbol;

import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.zip.Adler32;

import stirling.software.officeconvert.extract.PdfFiles;

final class Squeeze {

    record Settings(int iterations, int chain, int nice, int maxBlocks) {}

    static final int WINDOW = 32768;

    private static final int WMASK = WINDOW - 1;

    private static final int MASTER_BLOCK = 1 << 20;

    private static final int HASH_BITS = 15;

    private static final int MIN_SPLIT = 512;

    private static final int MAX_PAIRS = 8;

    private static final int DENSE = 32;

    private final byte[] data;

    private final Settings settings;

    private final int[] head = new int[1 << HASH_BITS];

    private final int[] prev = new int[WINDOW];

    private int inserted;

    private final BitWriter out;

    private char[] same;

    private int sameBase;

    private final Scratch scratch;

    private static final ThreadLocal<Scratch> SCRATCH = new ThreadLocal<>();

    private Squeeze(byte[] data, Settings settings, int capacity, Scratch scratch) {
        this.data = data;
        this.settings = settings;
        this.out = new BitWriter(capacity);
        this.scratch = scratch;
        Arrays.fill(head, -1);
    }

    static byte[] zlib(byte[] data, Settings settings) throws InterruptedIOException {
        int blocks = Math.max(1, (data.length + MASTER_BLOCK - 1) / MASTER_BLOCK);
        byte[][] parts = new byte[blocks][];
        if (blocks > 1 && java.util.concurrent.ForkJoinTask.inForkJoinPool()) {
            java.util.List<java.util.concurrent.RecursiveAction> tasks = new java.util.ArrayList<>();
            for (int k = 0; k < blocks; k++) {
                int part = k;
                tasks.add(new java.util.concurrent.RecursiveAction() {
                    @Override
                    protected void compute() {
                        try {
                            parts[part] = part(data, settings, part, blocks);
                        } catch (InterruptedIOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }
                });
            }
            try {
                java.util.concurrent.ForkJoinTask.invokeAll(tasks);
            } catch (java.io.UncheckedIOException e) {
                throw (InterruptedIOException) e.getCause();
            }
        } else {
            for (int k = 0; k < blocks; k++) {
                parts[k] = part(data, settings, k, blocks);
            }
        }
        Adler32 adler = new Adler32();
        adler.update(data);
        long a = adler.getValue();
        int total = 6;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] result = new byte[total];
        result[0] = 0x78;
        result[1] = (byte) 0xDA;
        int at = 2;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, result, at, p.length);
            at += p.length;
        }
        result[at] = (byte) (a >>> 24);
        result[at + 1] = (byte) (a >>> 16);
        result[at + 2] = (byte) (a >>> 8);
        result[at + 3] = (byte) a;
        return result;
    }

    private static byte[] part(byte[] data, Settings settings, int k, int blocks) throws InterruptedIOException {
        int start = k * MASTER_BLOCK;
        int end = Math.min(data.length, start + MASTER_BLOCK);
        boolean worker = Thread.currentThread() instanceof java.util.concurrent.ForkJoinWorkerThread;
        Scratch scratch = worker ? SCRATCH.get() : null;
        if (scratch == null) {
            scratch = new Scratch();
        } else {
            SCRATCH.remove();
        }
        try {
            return part(new Squeeze(data, settings, Math.max(64, (end - start) / 3), scratch), start, end,
                    k == blocks - 1);
        } finally {
            if (worker) {
                SCRATCH.set(scratch);
            }
        }
    }

    private static byte[] part(Squeeze s, int start, int end, boolean last) throws InterruptedIOException {
        byte[] data = s.data;
        if (data.length == 0) {
            s.out.bits(1, 1);
            s.out.bits(1, 2);
            s.out.bits(0, 7);
        } else {
            s.inserted = Math.max(0, start - WINDOW);
            s.master(start, end, last);
            if (!last) {
                s.out.bits(0, 1);
                s.out.bits(0, 2);
                s.out.align();
                s.out.writeByte(0);
                s.out.writeByte(0);
                s.out.writeByte(0xFF);
                s.out.writeByte(0xFF);
            }
        }
        s.out.align();
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
        Matches m = new Matches(end - start, scratch);
        int runEnd = start;
        for (int i = start; i < end; i++) {
            if (((i - start) & 0xFFFF) == 0) {
                PdfFiles.stopIfInterrupted();
            }
            m.begin(i - start);
            int limit = Math.min(MAX_MATCH, data.length - i);
            if (i > 0 && data[i] == data[i - 1]) {
                runEnd = Math.max(runEnd, i);
                while (runEnd < data.length && runEnd - i < MAX_MATCH && data[runEnd] == data[i]) {
                    runEnd++;
                }
                if (runEnd - i >= limit && limit >= MIN_MATCH) {
                    m.add(limit, 1);
                    insert(i);
                    inserted = i + 1;
                    continue;
                }
            }
            if (limit >= MIN_MATCH) {
                int best = MIN_MATCH - 1;
                int p = head[hash(i)];
                int hits = 0;
                int pairs = 0;
                while (p >= 0 && i - p <= WINDOW && hits++ < settings.chain) {
                    if (p < i && data[p + best] == data[i + best]) {
                        int l = Arrays.mismatch(data, p, p + limit, data, i, i + limit);
                        if (l < 0) {
                            l = limit;
                        }
                        if (l > best) {
                            best = l;
                            m.add(l, i - p);
                            if (l >= settings.nice || l == limit || ++pairs == MAX_PAIRS) {
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
        sameBase = Math.max(0, start - MAX_MATCH);
        int sameEnd = Math.min(data.length, end + 2 * MAX_MATCH + 2);
        if (scratch.same.length < sameEnd - sameBase) {
            scratch.same = new char[sameEnd - sameBase];
        }
        same = scratch.same;
        for (int i = sameEnd - 1; i >= sameBase; i--) {
            int run = i + 1 < sameEnd && data[i] == data[i + 1] ? same[i + 1 - sameBase] + 1 : 1;
            same[i - sameBase] = (char) Math.min(65535, run);
        }
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
        scratch.fit(n + 1);
        double[] cost = scratch.cost;
        int[] length = scratch.length;
        int[] dist = scratch.dist;
        Arrays.fill(cost, 0, n + 1, Double.MAX_VALUE);
        cost[0] = 0;
        double[] lengthCost = new double[MAX_MATCH + 1];
        for (int l = MIN_MATCH; l <= MAX_MATCH; l++) {
            int sym = LENGTH_SYMBOL[l];
            lengthCost[l] = costs.litlen[257 + sym] + LENGTH_EXTRA[sym];
        }
        double runCost = lengthCost[MAX_MATCH] + costs.dist[0];
        for (int k = 0; k < n; k++) {
            double here = cost[k];
            if (here == Double.MAX_VALUE) {
                continue;
            }
            int i = from + k;
            if (k > MAX_MATCH && k + 2 * MAX_MATCH + 1 < n && same[i - sameBase] > 2 * MAX_MATCH
                    && same[i - MAX_MATCH - sameBase] > MAX_MATCH) {
                for (int t = 0; t < MAX_MATCH && cost[k] != Double.MAX_VALUE; t++, k++) {
                    cost[k + MAX_MATCH] = cost[k] + runCost;
                    length[k + MAX_MATCH] = MAX_MATCH;
                    dist[k + MAX_MATCH] = 1;
                }
                k--;
                continue;
            }
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
                for (int l = previous + 1; l <= upTo; l = l < DENSE || l == upTo ? l + 1 : Math.min(upTo, NEXT[l])) {
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
        Store s = new Store(count);
        s.size = count;
        int j = count;
        for (int k = n; k > 0; k -= length[k]) {
            j--;
            if (dist[k] == 0) {
                s.lens[j] = data[from + k - 1] & 0xFF;
                s.dists[j] = 0;
            } else {
                s.lens[j] = length[k];
                s.dists[j] = dist[k];
            }
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
        double whole = total.estimate();
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
                double c = left.estimate() + total.minus(left).estimate();
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

    static long treeBits(int[] llLengths, int[] ddLengths, BitWriter w) {
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

    static void patchDistances(int[] lengths) {
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
}

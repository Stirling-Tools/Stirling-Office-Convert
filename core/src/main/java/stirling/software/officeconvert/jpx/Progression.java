package stirling.software.officeconvert.jpx;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class Progression {

    static final int LRCP = 0;
    static final int RLCP = 1;
    static final int RPCL = 2;
    static final int PCRL = 3;
    static final int CPRL = 4;

    static final long MAX_STEPS = 1L << 26;

    interface Visitor {
        boolean packet(int layer, int resolution, int component, int precinct) throws IOException;
    }

    private record Entry(int c, int r, int k, long y, long x) {
    }

    private final TileComponent[] comps;

    private final Siz siz;

    private final long[] tile;

    private final Visitor visitor;

    private int layers;

    private long steps;

    private Progression(TileComponent[] comps, Siz siz, long[] tile, Visitor visitor) {
        this.comps = comps;
        this.siz = siz;
        this.tile = tile;
        this.visitor = visitor;
    }

    static void run(TileComponent[] comps, Siz siz, long[] tile, int layers, int order, List<Poc> pocs,
            Visitor visitor) throws IOException {
        Progression p = new Progression(comps, siz, tile, visitor);
        p.layers = layers;
        if (pocs.isEmpty()) {
            p.run(new Poc(0, 0, layers, 33, comps.length, order));
            return;
        }
        for (Poc poc : pocs) {
            if (!p.run(poc)) {
                return;
            }
        }
    }

    private boolean run(Poc poc) throws IOException {
        int le = Math.min(poc.layerEnd(), layers);
        int rs = poc.resStart();
        int re = Math.min(poc.resEnd(), 33);
        int cs = poc.compStart();
        int ce = Math.min(poc.compEnd(), comps.length);
        return switch (poc.order()) {
            case LRCP -> {
                for (int l = 0; l < le; l++) {
                    for (int r = rs; r < re; r++) {
                        if (!components(l, r, cs, ce)) {
                            yield false;
                        }
                    }
                }
                yield true;
            }
            case RLCP -> {
                for (int r = rs; r < re; r++) {
                    for (int l = 0; l < le; l++) {
                        if (!components(l, r, cs, ce)) {
                            yield false;
                        }
                    }
                }
                yield true;
            }
            default -> positional(poc.order(), le, rs, re, cs, ce);
        };
    }

    private boolean components(int l, int r, int cs, int ce) throws IOException {
        for (int c = cs; c < ce; c++) {
            Resolution[] res = comps[c].resolutions;
            if (r >= res.length) {
                continue;
            }
            int n = res[r].precincts();
            for (int k = 0; k < n; k++) {
                if (!emit(l, r, c, k)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean emit(int l, int r, int c, int k) throws IOException {
        if ((++steps & 0xFFF) == 0) {
            if (steps > MAX_STEPS) {
                throw new JpxException("JPEG 2000 progression is too long");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("JPEG 2000 decoding interrupted");
            }
        }
        Resolution res = comps[c].resolutions[r];
        if (res.nextLayer[k] != l) {
            return true;
        }
        res.nextLayer[k]++;
        return visitor.packet(l, r, c, k);
    }

    private boolean positional(int order, int le, int rs, int re, int cs, int ce) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (int c = cs; c < ce; c++) {
            Resolution[] res = comps[c].resolutions;
            int levels = res.length - 1;
            for (int r = rs; r < Math.min(re, res.length); r++) {
                Resolution rr = res[r];
                if (rr.precincts() == 0) {
                    continue;
                }
                int shift = levels - r;
                long dx = (long) siz.dx()[c] << shift;
                long dy = (long) siz.dy()[c] << shift;
                for (int j = 0; j < rr.precinctsHigh; j++) {
                    long y = trigger(j, rr.y0, rr.ppy, dy, tile[1]);
                    for (int i = 0; i < rr.precinctsWide; i++) {
                        long x = trigger(i, rr.x0, rr.ppx, dx, tile[0]);
                        entries.add(new Entry(c, r, j * rr.precinctsWide + i, y, x));
                    }
                }
            }
        }
        Comparator<Entry> cmp = switch (order) {
            case RPCL -> Comparator.comparingInt(Entry::r).thenComparingLong(Entry::y).thenComparingLong(Entry::x)
                    .thenComparingInt(Entry::c);
            case PCRL -> Comparator.comparingLong(Entry::y).thenComparingLong(Entry::x).thenComparingInt(Entry::c)
                    .thenComparingInt(Entry::r);
            default -> Comparator.comparingInt(Entry::c).thenComparingLong(Entry::y).thenComparingLong(Entry::x)
                    .thenComparingInt(Entry::r);
        };
        entries.sort(cmp);
        for (Entry e : entries) {
            for (int l = 0; l < le; l++) {
                if (!emit(l, e.r(), e.c(), e.k())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static long trigger(int index, int r0, int pp, long scale, long tileOrigin) {
        long first = (long) (r0 >> pp) << pp;
        if (index == 0) {
            return first == r0 ? r0 * scale : tileOrigin;
        }
        return (first + ((long) index << pp)) * scale;
    }
}

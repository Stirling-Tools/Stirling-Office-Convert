package stirling.software.officeconvert.jpx;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.PriorityQueue;

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
            default -> le <= 0 || positional(poc.order(), le, rs, re, cs, ce);
        };
    }

    private boolean components(int l, int r, int cs, int ce) throws IOException {
        step();
        for (int c = cs; c < ce; c++) {
            step();
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
        step();
        Resolution res = comps[c].resolutions[r];
        if (res.nextLayer[k] != l) {
            return true;
        }
        res.nextLayer[k]++;
        return visitor.packet(l, r, c, k);
    }

    private void step() throws IOException {
        if ((++steps & 0xFFF) == 0) {
            if (steps > MAX_STEPS) {
                throw new JpxException("JPEG 2000 progression is too long");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("JPEG 2000 decoding interrupted");
            }
        }
    }

    private boolean positional(int order, int le, int rs, int re, int cs, int ce) throws IOException {
        PriorityQueue<PrecinctCursor> queue = new PriorityQueue<>(PrecinctCursor.order(order));
        for (int c = cs; c < ce; c++) {
            Resolution[] res = comps[c].resolutions;
            int levels = res.length - 1;
            for (int r = rs; r < Math.min(re, res.length); r++) {
                step();
                if (res[r].precincts() > 0) {
                    int shift = levels - r;
                    queue.add(new PrecinctCursor(c, r, res[r], (long) siz.dx()[c] << shift,
                            (long) siz.dy()[c] << shift, tile));
                }
            }
        }
        while (!queue.isEmpty()) {
            step();
            PrecinctCursor e = queue.poll();
            for (int l = 0; l < le; l++) {
                if (!emit(l, e.r, e.c, e.precinct())) {
                    return false;
                }
            }
            if (e.advance()) {
                queue.add(e);
            }
        }
        return true;
    }
}

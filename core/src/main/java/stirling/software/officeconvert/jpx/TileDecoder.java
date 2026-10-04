package stirling.software.officeconvert.jpx;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;

final class TileDecoder {

    private final Codestream cs;

    private final Siz siz;

    private final JpxRaster out;

    private final int reduce;

    private final long maxSamples;

    private final BlockDecoder block = new BlockDecoder();

    TileDecoder(Codestream cs, JpxRaster out, int reduce, long maxSamples) {
        this.cs = cs;
        this.siz = cs.siz;
        this.out = out;
        this.reduce = reduce;
        this.maxSamples = maxSamples;
    }

    void decode(int t) throws IOException {
        TileStream ts = cs.tiles[t];
        MarkerSet tile = ts.markers;
        MarkerSet main = cs.main;
        CodingDefaults cod = tile.cod != null ? tile.cod : main.cod;
        long[] rect = siz.tileRect(t);
        TileComponent[] comps = new TileComponent[siz.components()];
        long samples = 0;
        BlockBudget budget = new BlockBudget();
        for (int c = 0; c < comps.length; c++) {
            comps[c] = new TileComponent(siz, c, rect, style(c, tile, main), quant(c, tile, main), roi(c, tile, main),
                    reduce, budget);
            samples += (long) comps[c].width() * comps[c].height();
        }
        if (samples > maxSamples) {
            throw new JpxException("JPEG 2000 tile is too large");
        }
        if (ts.present()) {
            byte[][] holder = new byte[1][];
            int[] range = ts.body(cs.data, holder);
            PacketReader reader = new PacketReader(comps, holder[0], range[0], range[1],
                    ts.separateHeaders() ? ts.headers() : null, cod.sop(), cod.eph());
            List<Poc> pocs = tile.pocs.isEmpty() ? main.pocs : tile.pocs;
            Progression.run(comps, siz, rect, cod.layers(), cod.order(), pocs, reader);
            reconstruct(comps, cod, holder[0]);
        } else {
            reconstruct(comps, cod, new byte[0]);
        }
    }

    private void reconstruct(TileComponent[] comps, CodingDefaults cod, byte[] source) throws IOException {
        Object[] buffers = new Object[comps.length];
        for (int c = 0; c < comps.length; c++) {
            buffers[c] = coefficients(comps[c], source);
        }
        if (cod.mct() && comps.length >= 3 && sameSize(comps)) {
            if (buffers[0] instanceof int[] a && buffers[1] instanceof int[] b && buffers[2] instanceof int[] d) {
                Colour.inverseRct(a, b, d);
            } else if (buffers[0] instanceof float[] a && buffers[1] instanceof float[] b
                    && buffers[2] instanceof float[] d) {
                Colour.inverseIct(a, b, d);
            }
        }
        for (int c = 0; c < comps.length; c++) {
            store(comps[c], buffers[c]);
        }
    }

    private static boolean sameSize(TileComponent[] comps) {
        return comps[1].width() == comps[0].width() && comps[2].width() == comps[0].width()
                && comps[1].height() == comps[0].height() && comps[2].height() == comps[0].height();
    }

    private Object coefficients(TileComponent tc, byte[] source) throws IOException {
        int w = tc.width();
        int h = tc.height();
        boolean reversible = tc.style.reversible();
        int[] ints = reversible ? new int[w * h] : null;
        float[] floats = reversible ? null : new float[w * h];
        for (int r = 0; r <= tc.top; r++) {
            Resolution res = tc.resolutions[r];
            for (int k = 0; k < res.precincts(); k++) {
                Precinct p = res.existing(k);
                if (p == null) {
                    continue;
                }
                for (int b = 0; b < res.bands.length; b++) {
                    Band band = res.bands[b];
                    int ox = r > 0 && (band.orient & 1) != 0 ? tc.resolutions[r - 1].width() : 0;
                    int oy = r > 0 && (band.orient & 2) != 0 ? tc.resolutions[r - 1].height() : 0;
                    for (CodeBlock cb : p.bands()[b].blocks) {
                        if (!cb.included || cb.passes == 0) {
                            continue;
                        }
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedIOException("JPEG 2000 decoding interrupted");
                        }
                        int planes = tc.roi + band.magnitudeBits - cb.zeroPlanes;
                        if (!block.decode(cb, band.orient, tc.style.blockStyle(), planes, source)) {
                            continue;
                        }
                        int at = (oy + cb.y0 - band.y0) * w + ox + cb.x0 - band.x0;
                        if (reversible) {
                            Dequantize.reversible(block.data(), block.width(), block.height(), tc.roi, ints, at, w);
                        } else {
                            Dequantize.irreversible(block.data(), block.width(), block.height(), tc.roi,
                                    band.step * 0.5f, floats, at, w);
                        }
                    }
                }
            }
        }
        if (reversible) {
            Idwt.reversible(ints, w, tc.resolutions, tc.top);
            return ints;
        }
        Idwt.irreversible(floats, w, tc.resolutions, tc.top);
        return floats;
    }

    private void store(TileComponent tc, Object buffer) {
        int c = tc.index;
        int depth = siz.depth()[c];
        int max = (1 << depth) - 1;
        int shift = 1 << (depth - 1);
        int down = Math.max(0, depth - 16);
        Resolution res = tc.resolutions[tc.top];
        int w = res.width();
        int h = res.height();
        int ox = res.x0 - out.originX(c);
        int oy = res.y0 - out.originY(c);
        int pw = out.width(c);
        int[] ints = buffer instanceof int[] a ? a : null;
        float[] floats = buffer instanceof float[] f ? f : null;
        for (int y = 0; y < h; y++) {
            int row = (oy + y) * pw + ox;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int v = ints != null ? ints[i] : rint(floats[i]);
                v = Math.clamp((long) v + shift, 0, max);
                out.set(c, row + x, v >> down);
            }
        }
    }

    private static int rint(float f) {
        if (f >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (f <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) Math.rint(f);
    }

    private static ComponentStyle style(int c, MarkerSet tile, MarkerSet main) {
        ComponentStyle s = tile.coc.get(c);
        if (s != null) {
            return s;
        }
        if (tile.cod != null) {
            return tile.cod.style();
        }
        return main.coc.getOrDefault(c, main.cod.style());
    }

    private static Quant quant(int c, MarkerSet tile, MarkerSet main) {
        Quant q = tile.qcc.get(c);
        if (q != null) {
            return q;
        }
        if (tile.qcd != null) {
            return tile.qcd;
        }
        return main.qcc.getOrDefault(c, main.qcd);
    }

    private static int roi(int c, MarkerSet tile, MarkerSet main) {
        Integer r = tile.roi.get(c);
        return r != null ? r : main.roi.getOrDefault(c, 0);
    }
}

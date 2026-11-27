package stirling.software.officeconvert.jpx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class EncodeJob {

    private static final long AUTO_TILE_PIXELS = 1L << 24;

    private static final int AUTO_TILE = 1024;

    private static final double FINENESS = 0.5;

    private static final double RATE_FINENESS = 2;

    private static final double MAX_FINENESS = 4;

    private static final double PSNR_HEADROOM = 6;

    private static final int FILL_STEPS = 48;

    private final JpxEncoder.Planes planes;

    private final JpxEncoder.Options options;

    private final boolean reversible;

    private final boolean mct;

    private final int comps;

    private final int width;

    private final int[] dx;

    private final int[] dy;

    private final int height;

    private final int tileW;

    private final int tileH;

    private final int levels;

    private final int blockLog;

    private final int order;

    private Quantiser quant;

    private TileCoder[] tiles;

    private Tier2 tier2;

    private final List<EncodedBlock> blocks = new ArrayList<>();

    private double[] slopes;

    private double fineness;

    private boolean saturated;

    EncodeJob(JpxEncoder.Planes planes, JpxEncoder.Options options) {
        this.planes = planes;
        this.options = options;
        reversible = options.lossless();
        comps = planes.count();
        width = planes.width();
        height = planes.height();
        dx = planes.dx();
        dy = planes.dy();
        int tile = options.tileSize();
        if (tile == 0 && (long) width * height > AUTO_TILE_PIXELS) {
            tile = AUTO_TILE;
        }
        tileW = tile == 0 ? width : Math.min(tile, width);
        tileH = tile == 0 ? height : Math.min(tile, height);
        int smallest = Integer.MAX_VALUE;
        for (int c = 0; c < comps; c++) {
            smallest = Math.min(smallest, Math.min(Math.ceilDiv(tileW, dx[c]), Math.ceilDiv(tileH, dy[c])));
        }
        levels = Math.min(options.levels(), 31 - Integer.numberOfLeadingZeros(Math.max(1, smallest)));
        blockLog = Integer.numberOfTrailingZeros(options.blockSize());
        order = options.order().ordinal();
        mct = colourTransform();
        fineness = fineness();
    }

    private boolean colourTransform() {
        if (comps < 3 || planes.alpha() >= 0 && planes.alpha() < 3) {
            return false;
        }
        JpxEncoder.Colour colour = planes.colour();
        boolean rgb = colour.enumerated() == JpxEncoder.Colour.SRGB.enumerated()
                || colour.icc() != null && iccSpace(colour.icc()) == 0x52474220;
        if (!rgb) {
            return false;
        }
        for (int c = 1; c < 3; c++) {
            if (planes.depth(c) != planes.depth(0) || planes.isSigned(c) != planes.isSigned(0)
                    || dx[c] != dx[0] || dy[c] != dy[0]) {
                return false;
            }
        }
        return true;
    }

    private static int iccSpace(byte[] icc) {
        return (icc[16] & 0xFF) << 24 | (icc[17] & 0xFF) << 16 | (icc[18] & 0xFF) << 8 | icc[19] & 0xFF;
    }

    byte[] run() throws IOException {
        prepare();
        byte[] out = emit(options.psnr(), options.bitsPerPixel());
        if (saturated && !reversible && fineness > FINENESS) {
            fineness = FINENESS;
            prepare();
            out = emit(options.psnr(), options.bitsPerPixel());
        }
        return out;
    }

    void prepare() throws IOException {
        int[] depths = new int[comps];
        double[] weights = new double[comps];
        for (int c = 0; c < comps; c++) {
            depths[c] = planes.depth(c);
            weights[c] = mct && c < 3 ? (reversible ? Quantiser.RCT_WEIGHTS[c] : Quantiser.ICT_WEIGHTS[c]) : 1;
        }
        blocks.clear();
        quant = new Quantiser(reversible, levels, depths, weights, fineness);
        int tilesWide = Math.ceilDiv(width, tileW);
        int tilesHigh = Math.ceilDiv(height, tileH);
        if ((long) tilesWide * tilesHigh > 65535) {
            throw new IOException("A JPEG 2000 image can have at most 65,535 tiles");
        }
        tiles = new TileCoder[tilesWide * tilesHigh];
        BlockPool pool = new BlockPool(options.threads(), 1 << (2 * blockLog));
        for (int t = 0; t < tiles.length; t++) {
            int tx = t % tilesWide;
            int ty = t / tilesWide;
            int x0 = tx * tileW;
            int y0 = ty * tileH;
            TileCoder tc = new TileCoder(t, x0, y0, Math.min(width, x0 + tileW), Math.min(height, y0 + tileH),
                    levels, blockLog, dx, dy);
            tiles[t] = tc;
            for (int c = 0; c < comps; c++) {
                int[][] boxes = tc.resolutionBoxes(c);
                if (reversible) {
                    int[] a = integers(tc, c);
                    Fdwt.reversible(a, tc.components[c].x1 - tc.components[c].x0, boxes);
                    tc.codeReversible(c, a, quant, pool, options.layers() > 1);
                } else {
                    float[] a = floats(tc, c);
                    Fdwt.irreversible(a, tc.components[c].x1 - tc.components[c].x0, boxes);
                    tc.codeIrreversible(c, a, quant, pool);
                }
            }
            collect(tc);
        }
        if (quant.settle() > 7) {
            throw new IOException("The image needs more JPEG 2000 guard bits than the format allows");
        }
        tier2 = new Tier2(tiles, options.layers(), order, levels, quant, dx, dy);
        slopes = slopes();
    }

    byte[] emit(double psnr, double bitsPerPixel) throws IOException {
        allocate(psnr, bitsPerPixel);
        return assemble();
    }

    private double fineness() {
        if (options.psnr() <= 0) {
            return RATE_FINENESS;
        }
        double mse = 255.0 * 255.0 / Math.pow(10, (options.psnr() + PSNR_HEADROOM) / 10);
        return Math.clamp(Math.sqrt(mse) * FINENESS, FINENESS, MAX_FINENESS);
    }

    private void collect(TileCoder tc) {
        for (TileCoder.Component comp : tc.components) {
            for (TileCoder.Resolution res : comp.resolutions) {
                for (TileCoder.Blocks[] precinct : res.precincts) {
                    for (TileCoder.Blocks b : precinct) {
                        blocks.addAll(Arrays.asList(b.coded));
                    }
                }
            }
        }
    }

    private int shifted(int c, int i) {
        int v = planes.sample(c, i);
        return planes.isSigned(c) ? v : v - (1 << (planes.depth(c) - 1));
    }

    private int[] integers(TileCoder tc, int c) {
        TileCoder.Component comp = tc.components[c];
        int w = comp.x1 - comp.x0;
        int h = comp.y1 - comp.y0;
        int stride = planes.width(c);
        int[] a = new int[w * h];
        for (int y = 0; y < h; y++) {
            int row = (comp.y0 + y) * stride + comp.x0;
            int at = y * w;
            if (mct && c < 3) {
                for (int x = 0; x < w; x++) {
                    int i = row + x;
                    int r = shifted(0, i);
                    int g = shifted(1, i);
                    int b = shifted(2, i);
                    a[at + x] = c == 0 ? (r + 2 * g + b) >> 2 : c == 1 ? b - g : r - g;
                }
            } else {
                for (int x = 0; x < w; x++) {
                    a[at + x] = shifted(c, row + x);
                }
            }
        }
        return a;
    }

    private float[] floats(TileCoder tc, int c) {
        TileCoder.Component comp = tc.components[c];
        int w = comp.x1 - comp.x0;
        int h = comp.y1 - comp.y0;
        int stride = planes.width(c);
        float[] a = new float[w * h];
        for (int y = 0; y < h; y++) {
            int row = (comp.y0 + y) * stride + comp.x0;
            int at = y * w;
            if (mct && c < 3) {
                for (int x = 0; x < w; x++) {
                    int i = row + x;
                    float r = shifted(0, i);
                    float g = shifted(1, i);
                    float b = shifted(2, i);
                    a[at + x] = switch (c) {
                        case 0 -> 0.299f * r + 0.587f * g + 0.114f * b;
                        case 1 -> -0.168736f * r - 0.331264f * g + 0.5f * b;
                        default -> 0.5f * r - 0.418688f * g - 0.081312f * b;
                    };
                }
            } else {
                for (int x = 0; x < w; x++) {
                    a[at + x] = shifted(c, row + x);
                }
            }
        }
        return a;
    }

    private void allocate(double psnr, double bitsPerPixel) throws IOException {
        int layers = options.layers();
        int last = layers - 1;
        if (reversible) {
            for (EncodedBlock e : blocks) {
                e.layerPasses[last] = e.passes();
            }
            intermediate(slopes, -1);
            return;
        }
        int pick = -1;
        if (psnr > 0) {
            pick = Math.max(pick, qualityIndex(slopes, psnr));
        }
        if (bitsPerPixel > 0) {
            int rate = rateIndex(slopes, bitsPerPixel);
            saturated = rate < 0 && psnr <= 0;
            pick = Math.max(pick, rate);
        }
        select(slopes, pick);
    }

    private void select(double[] slopes, int pick) {
        int last = options.layers() - 1;
        double lambda = pick < 0 ? 0 : slopes[pick];
        for (EncodedBlock e : blocks) {
            e.layerPasses[last] = e.passesAt(lambda);
        }
        intermediate(slopes, pick);
    }

    private void intermediate(double[] slopes, int pick) {
        int last = options.layers() - 1;
        if (last == 0) {
            return;
        }
        long body = 0;
        for (EncodedBlock e : blocks) {
            body += e.length(e.layerPasses[last]);
        }
        int from = Math.max(0, pick + 1);
        for (int l = 0; l < last; l++) {
            long budget = body >> (last - l);
            int lo = from;
            int hi = slopes.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (bodyAt(slopes[mid], last) <= budget) {
                    hi = mid;
                } else {
                    lo = mid + 1;
                }
            }
            double lambda = lo < slopes.length ? slopes[lo] : Double.POSITIVE_INFINITY;
            for (EncodedBlock e : blocks) {
                e.layerPasses[l] = Math.min(e.passesAt(lambda), e.layerPasses[last]);
            }
        }
        for (EncodedBlock e : blocks) {
            for (int l = 1; l <= last; l++) {
                e.layerPasses[l] = Math.max(e.layerPasses[l], e.layerPasses[l - 1]);
            }
        }
    }

    private long bodyAt(double lambda, int last) {
        long n = 0;
        for (EncodedBlock e : blocks) {
            n += e.length(Math.min(e.passesAt(lambda), e.layerPasses[last]));
        }
        return n;
    }

    private double[] slopes() {
        int n = 0;
        for (EncodedBlock e : blocks) {
            n += e.slopes.length;
        }
        double[] all = new double[n];
        int k = 0;
        for (EncodedBlock e : blocks) {
            for (double s : e.slopes) {
                if (Double.isFinite(s)) {
                    all[k++] = s;
                }
            }
        }
        all = Arrays.copyOf(all, k);
        Arrays.sort(all);
        int distinct = 0;
        for (int i = 0; i < all.length; i++) {
            if (distinct == 0 || all[i] != all[distinct - 1]) {
                all[distinct++] = all[i];
            }
        }
        return Arrays.copyOf(all, distinct);
    }

    private int qualityIndex(double[] slopes, double psnr) {
        double peak = (1 << planes.depth(0)) - 1;
        double mse = peak * peak / Math.pow(10, psnr / 10);
        double samples = 0;
        for (int c = 0; c < comps; c++) {
            samples += (double) planes.width(c) * planes.height(c);
        }
        double allowed = mse * samples - samples / 12;
        int lo = -1;
        int hi = slopes.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >> 1;
            if (distortion(slopes[mid]) <= allowed) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private double distortion(double lambda) {
        double d = 0;
        for (EncodedBlock e : blocks) {
            d += e.initial - e.gain(e.passesAt(lambda));
        }
        return d;
    }

    private int rateIndex(double[] slopes, double bitsPerPixel) throws IOException {
        long budget = (long) Math.floor(bitsPerPixel * width * height / 8);
        int lo = -1;
        int hi = slopes.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >> 1;
            select(slopes, mid);
            if (size() <= budget) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        int best = lo;
        for (int i = lo - 1; i >= Math.max(-1, lo - FILL_STEPS); i--) {
            select(slopes, i);
            if (size() <= budget) {
                best = i;
            }
        }
        return best;
    }

    private long size() throws IOException {
        long n = overhead();
        for (int t = 0; t < tiles.length; t++) {
            n += tier2.tileSize(t);
        }
        return n;
    }

    private long overhead() {
        long n = mainHeader().length + 2 + 14L * tiles.length;
        if (!options.codestream()) {
            n += boxes(0).length;
        }
        return n;
    }

    private byte[] assemble() throws IOException {
        ByteArrayOutputStream cs = new ByteArrayOutputStream();
        cs.write(mainHeader());
        for (int t = 0; t < tiles.length; t++) {
            byte[] data = tier2.tile(t);
            Markers m = new Markers();
            m.u16(0xFF90);
            m.u16(10);
            m.u16(t);
            m.u32(12 + 2 + data.length);
            m.u8(0);
            m.u8(1);
            m.u16(0xFF93);
            cs.write(m.bytes());
            cs.write(data);
        }
        cs.write(0xFF);
        cs.write(0xD9);
        byte[] codestream = cs.toByteArray();
        if (options.codestream()) {
            return codestream;
        }
        byte[] head = boxes(codestream.length);
        byte[] out = Arrays.copyOf(head, head.length + codestream.length);
        System.arraycopy(codestream, 0, out, head.length, codestream.length);
        return out;
    }

    private byte[] mainHeader() {
        Markers m = new Markers();
        m.u16(0xFF4F);
        m.u16(0xFF51);
        m.u16(38 + 3 * comps);
        m.u16(0);
        m.u32(width);
        m.u32(height);
        m.u32(0);
        m.u32(0);
        m.u32(tileW);
        m.u32(tileH);
        m.u32(0);
        m.u32(0);
        m.u16(comps);
        for (int c = 0; c < comps; c++) {
            m.u8(planes.depth(c) - 1 | (planes.isSigned(c) ? 0x80 : 0));
            m.u8(dx[c]);
            m.u8(dy[c]);
        }
        m.u16(0xFF52);
        m.u16(12);
        m.u8(0);
        m.u8(order);
        m.u16(options.layers());
        m.u8(mct ? 1 : 0);
        m.u8(levels);
        m.u8(blockLog - 2);
        m.u8(blockLog - 2);
        m.u8(0);
        m.u8(reversible ? 1 : 0);
        quantisation(m, 0, -1);
        for (int c = 1; c < comps; c++) {
            if (!quant.sameAs(c, 0)) {
                quantisation(m, c, c);
            }
        }
        return m.bytes();
    }

    private void quantisation(Markers m, int c, int qcc) {
        int[] eps = quant.exponents(c);
        int[] mu = quant.mantissas(c);
        int body = reversible ? eps.length : 2 * eps.length;
        int index = comps < 257 ? 1 : 2;
        m.u16(qcc < 0 ? 0xFF5C : 0xFF5D);
        m.u16(3 + body + (qcc < 0 ? 0 : index));
        if (qcc >= 0) {
            if (index == 1) {
                m.u8(qcc);
            } else {
                m.u16(qcc);
            }
        }
        m.u8(quant.guard() << 5 | (reversible ? 0 : 2));
        for (int i = 0; i < eps.length; i++) {
            if (reversible) {
                m.u8(eps[i] << 3);
            } else {
                m.u16(eps[i] << 11 | mu[i]);
            }
        }
    }

    private byte[] boxes(long codestream) {
        JpxEncoder.Colour colour = planes.colour();
        Markers m = new Markers();
        m.u32(12);
        m.tag("jP  ");
        m.u32(0x0D0A870A);
        m.u32(20);
        m.tag("ftyp");
        m.tag("jp2 ");
        m.u32(0);
        m.tag("jp2 ");
        boolean mixed = false;
        for (int c = 1; c < comps; c++) {
            mixed |= planes.depth(c) != planes.depth(0) || planes.isSigned(c) != planes.isSigned(0);
        }
        Markers h = new Markers();
        h.u32(22);
        h.tag("ihdr");
        h.u32(height);
        h.u32(width);
        h.u16(comps);
        h.u8(mixed ? 255 : planes.depth(0) - 1 | (planes.isSigned(0) ? 0x80 : 0));
        h.u8(7);
        h.u8(0);
        h.u8(0);
        if (mixed) {
            h.u32(8 + comps);
            h.tag("bpcc");
            for (int c = 0; c < comps; c++) {
                h.u8(planes.depth(c) - 1 | (planes.isSigned(c) ? 0x80 : 0));
            }
        }
        byte[] icc = colour.icc();
        if (icc == null) {
            h.u32(15);
            h.tag("colr");
            h.u8(1);
            h.u8(0);
            h.u8(0);
            h.u32(colour.enumerated());
        } else {
            h.u32(11 + icc.length);
            h.tag("colr");
            h.u8(JpxEncoder.restrictedIcc(icc) ? 2 : 3);
            h.u8(0);
            h.u8(0);
            h.raw(icc);
        }
        int alpha = planes.alpha();
        if (alpha >= 0) {
            h.u32(10 + 6 * comps);
            h.tag("cdef");
            h.u16(comps);
            int colourIndex = 1;
            for (int c = 0; c < comps; c++) {
                h.u16(c);
                if (c == alpha) {
                    h.u16(1);
                    h.u16(0);
                } else {
                    h.u16(0);
                    h.u16(colourIndex++);
                }
            }
        }
        byte[] header = h.bytes();
        m.u32(8 + header.length);
        m.tag("jp2h");
        m.raw(header);
        m.u32(8 + codestream);
        m.tag("jp2c");
        return m.bytes();
    }

    private static final class Markers {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void u8(int v) {
            out.write(v);
        }

        void u16(int v) {
            out.write(v >> 8);
            out.write(v);
        }

        void u32(long v) {
            u16((int) (v >> 16) & 0xFFFF);
            u16((int) v & 0xFFFF);
        }

        void tag(String t) {
            for (int i = 0; i < 4; i++) {
                out.write(t.charAt(i));
            }
        }

        void raw(byte[] b) {
            out.writeBytes(b);
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }
}

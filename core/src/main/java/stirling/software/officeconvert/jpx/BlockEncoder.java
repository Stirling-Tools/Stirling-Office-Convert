package stirling.software.officeconvert.jpx;

import static stirling.software.officeconvert.jpx.Contexts.E;
import static stirling.software.officeconvert.jpx.Contexts.E_NEG;
import static stirling.software.officeconvert.jpx.Contexts.N;
import static stirling.software.officeconvert.jpx.Contexts.NE;
import static stirling.software.officeconvert.jpx.Contexts.NEG;
import static stirling.software.officeconvert.jpx.Contexts.NEIGHBOURS;
import static stirling.software.officeconvert.jpx.Contexts.NW;
import static stirling.software.officeconvert.jpx.Contexts.N_NEG;
import static stirling.software.officeconvert.jpx.Contexts.REFINED;
import static stirling.software.officeconvert.jpx.Contexts.S;
import static stirling.software.officeconvert.jpx.Contexts.SE;
import static stirling.software.officeconvert.jpx.Contexts.SIG;
import static stirling.software.officeconvert.jpx.Contexts.SW;
import static stirling.software.officeconvert.jpx.Contexts.S_NEG;
import static stirling.software.officeconvert.jpx.Contexts.VISIT;
import static stirling.software.officeconvert.jpx.Contexts.W;
import static stirling.software.officeconvert.jpx.Contexts.W_NEG;

import java.util.Arrays;

import stirling.software.officeconvert.mq.MqEncoder;

final class BlockEncoder {

    private static final int RUN_BLOCKERS = SIG | VISIT | NEIGHBOURS;

    private final MqEncoder mq = new MqEncoder(1 << 14);

    private final MqEncoder.Contexts cx = new MqEncoder.Contexts(Mq.CONTEXTS);

    private final int[] flags = new int[66 * 66];

    private final int[] data = new int[4096];

    private final boolean[] negative = new boolean[4096];

    private int w;

    private int h;

    private int fw;

    private int orient;

    private int fraction;

    private double gain;

    private boolean measure;

    EncodedBlock encode(int[] values, int w, int h, int orient, int fraction, boolean measure) {
        this.measure = measure;
        this.w = w;
        this.h = h;
        this.fw = w + 2;
        this.orient = orient;
        this.fraction = fraction;
        int max = 0;
        double initial = 0;
        for (int y = 0; y < h; y++) {
            int at = (y & ~3) * w + (y & 3);
            for (int x = 0; x < w; x++) {
                int v = values[y * w + x];
                int m = Math.abs(v);
                int i = at + 4 * x;
                data[i] = m;
                negative[i] = v < 0;
                max |= m;
                initial += (double) m * m;
            }
        }
        int planes = 32 - Integer.numberOfLeadingZeros(max >> fraction);
        if (planes == 0) {
            return new EncodedBlock(0, new byte[0], new int[0], new double[0], initial);
        }
        Arrays.fill(flags, 0, fw * (h + 2), 0);
        cx.reset();
        cx.set(0, 4);
        cx.set(Mq.RUN, 3);
        cx.set(Mq.UNIFORM, 46);
        mq.reset();
        int passes = 3 * planes - 2;
        double[] gains = new double[passes];
        int pass = 0;
        double total = 0;
        for (int p = planes - 1; p >= 0; p--) {
            int shift = p + fraction;
            if (p < planes - 1) {
                gain = 0;
                significance(shift);
                total += gain;
                gains[pass] = total;
                mq.mark();
                pass++;
                gain = 0;
                refinement(shift);
                total += gain;
                gains[pass] = total;
                mq.mark();
                pass++;
            }
            gain = 0;
            cleanup(shift);
            total += gain;
            gains[pass] = total;
            mq.mark();
            pass++;
        }
        byte[] bytes = mq.flush();
        int[] lengths = mq.truncations(bytes, bytes.length);
        lengths[passes - 1] = bytes.length;
        return new EncodedBlock(planes, bytes, lengths, gains, initial);
    }

    private double half(int shift) {
        return shift == 0 ? 0 : 1 << (shift - 1);
    }

    private boolean noNeighbours(int fi, int rows) {
        int any = 0;
        for (int k = 0; k < rows; k++) {
            int f = flags[fi + k * fw];
            any |= (f & SIG) != 0 ? 0 : f & NEIGHBOURS;
        }
        return any == 0;
    }

    private boolean noneSignificant(int fi, int rows) {
        int any = 0;
        for (int k = 0; k < rows; k++) {
            any |= flags[fi + k * fw];
        }
        return (any & SIG) == 0;
    }

    private void significance(int shift) {
        double half = half(shift);
        for (int y0 = 0; y0 < h; y0 += 4) {
            int yEnd = Math.min(y0 + 4, h);
            int base = y0 * w;
            for (int x = 0; x < w; x++) {
                int top = (y0 + 1) * fw + x + 1;
                if (noNeighbours(top, yEnd - y0)) {
                    continue;
                }
                for (int y = y0; y < yEnd; y++) {
                    int fi = (y + 1) * fw + x + 1;
                    int f = flags[fi];
                    if ((f & SIG) != 0 || (f & NEIGHBOURS) == 0) {
                        continue;
                    }
                    int i = base + 4 * x + y - y0;
                    int m = data[i];
                    int bit = m >> shift & 1;
                    mq.encode(cx, Contexts.zero(orient, f), bit);
                    if (bit != 0) {
                        boolean neg = negative[i];
                        sign(f, neg);
                        becomeSignificant(fi, neg);
                        if (measure) {
                            double r = ((m >> shift) << shift) + half;
                            gain += (double) m * m - (m - r) * (m - r);
                        }
                    }
                    flags[fi] |= VISIT;
                }
            }
        }
    }

    private void refinement(int shift) {
        double half = half(shift);
        double up = 1 << shift;
        for (int y0 = 0; y0 < h; y0 += 4) {
            int yEnd = Math.min(y0 + 4, h);
            int base = y0 * w;
            for (int x = 0; x < w; x++) {
                if (noneSignificant((y0 + 1) * fw + x + 1, yEnd - y0)) {
                    continue;
                }
                for (int y = y0; y < yEnd; y++) {
                    int fi = (y + 1) * fw + x + 1;
                    int f = flags[fi];
                    if ((f & (SIG | VISIT)) != SIG) {
                        continue;
                    }
                    int m = data[base + 4 * x + y - y0];
                    int bit = m >> shift & 1;
                    int ctx = (f & REFINED) != 0 ? 16 : (f & NEIGHBOURS) != 0 ? 15 : 14;
                    mq.encode(cx, ctx, bit);
                    if (measure) {
                        double before = ((m >> (shift + 1)) << (shift + 1)) + up;
                        double after = ((m >> shift) << shift) + half;
                        gain += (m - before) * (m - before) - (m - after) * (m - after);
                    }
                    flags[fi] = f | REFINED;
                }
            }
        }
    }

    private void cleanup(int shift) {
        double half = half(shift);
        for (int y0 = 0; y0 < h; y0 += 4) {
            int yEnd = Math.min(y0 + 4, h);
            int base = y0 * w;
            for (int x = 0; x < w; x++) {
                int start = y0;
                int col = base + 4 * x;
                if (yEnd - y0 == 4 && runnable(x, y0)) {
                    int r = -1;
                    for (int k = 0; k < 4; k++) {
                        if ((data[col + k] >> shift & 1) != 0) {
                            r = k;
                            break;
                        }
                    }
                    if (r < 0) {
                        mq.encode(cx, Mq.RUN, 0);
                        continue;
                    }
                    mq.encode(cx, Mq.RUN, 1);
                    mq.encode(cx, Mq.UNIFORM, r >> 1);
                    mq.encode(cx, Mq.UNIFORM, r & 1);
                    int y = y0 + r;
                    int fi = (y + 1) * fw + x + 1;
                    int i = col + r;
                    int m = data[i];
                    sign(flags[fi], negative[i]);
                    becomeSignificant(fi, negative[i]);
                    if (measure) {
                        double rec = ((m >> shift) << shift) + half;
                        gain += (double) m * m - (m - rec) * (m - rec);
                    }
                    start = y + 1;
                }
                for (int y = start; y < yEnd; y++) {
                    int fi = (y + 1) * fw + x + 1;
                    int f = flags[fi];
                    if ((f & (SIG | VISIT)) != 0) {
                        flags[fi] = f & ~VISIT;
                        continue;
                    }
                    int i = col + y - y0;
                    int m = data[i];
                    int bit = m >> shift & 1;
                    mq.encode(cx, Contexts.zero(orient, f), bit);
                    if (bit != 0) {
                        boolean neg = negative[i];
                        sign(f, neg);
                        becomeSignificant(fi, neg);
                        if (measure) {
                            double rec = ((m >> shift) << shift) + half;
                            gain += (double) m * m - (m - rec) * (m - rec);
                        }
                    }
                }
            }
        }
    }

    private boolean runnable(int x, int y0) {
        int fi = (y0 + 1) * fw + x + 1;
        return (flags[fi] & RUN_BLOCKERS) == 0 && (flags[fi + fw] & RUN_BLOCKERS) == 0
                && (flags[fi + 2 * fw] & RUN_BLOCKERS) == 0 && (flags[fi + 3 * fw] & RUN_BLOCKERS) == 0;
    }

    private void sign(int f, boolean negative) {
        int sc = Contexts.sign(f);
        mq.encode(cx, sc & 0x1F, (negative ? 1 : 0) ^ sc >> 5);
    }

    private void becomeSignificant(int fi, boolean negative) {
        flags[fi] |= SIG | (negative ? NEG : 0);
        flags[fi - fw] |= S | (negative ? S_NEG : 0);
        flags[fi + fw] |= N | (negative ? N_NEG : 0);
        flags[fi - 1] |= E | (negative ? E_NEG : 0);
        flags[fi + 1] |= W | (negative ? W_NEG : 0);
        flags[fi - fw - 1] |= SE;
        flags[fi - fw + 1] |= SW;
        flags[fi + fw - 1] |= NE;
        flags[fi + fw + 1] |= NW;
    }
}

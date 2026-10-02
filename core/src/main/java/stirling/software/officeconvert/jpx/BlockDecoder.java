package stirling.software.officeconvert.jpx;

import static stirling.software.officeconvert.jpx.Contexts.BELOW;
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

final class BlockDecoder {

    static final int MAX_PLANES = 30;

    private static final int RUN_BLOCKERS = SIG | VISIT | NEIGHBOURS;

    private final Mq mq = new Mq();

    private final RawBits raw = new RawBits();

    private final int[] data = new int[4096];

    private final int[] flags = new int[1026 * 6 + 4096];

    private byte[] scratch = new byte[8192];

    private int w;

    private int h;

    private int fw;

    private int orient;

    private boolean causal;

    private boolean bypassing;

    int[] data() {
        return data;
    }

    boolean decode(CodeBlock cb, int orient, int style, int planes, byte[] source) {
        w = cb.x1 - cb.x0;
        h = cb.y1 - cb.y0;
        fw = w + 2;
        this.orient = orient;
        causal = (style & ComponentStyle.CAUSAL) != 0;
        Arrays.fill(data, 0, w * h, 0);
        Arrays.fill(flags, 0, fw * (h + 2), 0);
        int bpn = planes;
        if (cb.passes == 0 || bpn < 1) {
            return false;
        }
        if (bpn > MAX_PLANES) {
            return false;
        }
        boolean bypass = (style & ComponentStyle.BYPASS) != 0;
        boolean reset = (style & ComponentStyle.RESET) != 0;
        boolean segmark = (style & ComponentStyle.SEGMARK) != 0;
        mq.resetContexts();
        int type = 2;
        for (Segment seg : cb.segments) {
            if (bpn < 1) {
                break;
            }
            int length = seg.length();
            byte[] bytes = seg.bytes(source, scratch);
            if (bytes.length > scratch.length) {
                scratch = bytes;
            }
            bypassing = bypass && seg.firstPass >= 10 && type != 2;
            if (bypassing) {
                raw.init(bytes, length);
            } else {
                mq.init(bytes, length);
            }
            for (int p = 0; p < seg.passes && bpn >= 1; p++) {
                int one = 1 << bpn;
                switch (type) {
                    case 0 -> significance(one);
                    case 1 -> refinement(one);
                    default -> cleanup(one, segmark);
                }
                if (reset) {
                    mq.resetContexts();
                }
                if (++type == 3) {
                    type = 0;
                    bpn--;
                }
            }
        }
        return true;
    }

    int width() {
        return w;
    }

    int height() {
        return h;
    }

    private int masked(int f, int row) {
        return causal && (row & 3) == 3 ? f & ~BELOW : f;
    }

    private void significance(int one) {
        int value = one | one >> 1;
        for (int y0 = 0; y0 < h; y0 += 4) {
            int yEnd = Math.min(y0 + 4, h);
            for (int x = 0; x < w; x++) {
                for (int y = y0; y < yEnd; y++) {
                    int fi = (y + 1) * fw + x + 1;
                    int f = flags[fi];
                    if ((f & SIG) != 0) {
                        continue;
                    }
                    int m = masked(f, y - y0);
                    if ((m & NEIGHBOURS) == 0) {
                        continue;
                    }
                    int bit = bypassing ? raw.bit() : mq.decode(Contexts.zero(orient, m));
                    if (bit != 0) {
                        int neg = bypassing ? raw.bit() : signBit(m);
                        becomeSignificant(x, y, fi, neg != 0);
                        data[y * w + x] = neg != 0 ? -value : value;
                    }
                    flags[fi] |= VISIT;
                }
            }
        }
    }

    private void refinement(int one) {
        int half = one >> 1;
        for (int y0 = 0; y0 < h; y0 += 4) {
            int yEnd = Math.min(y0 + 4, h);
            for (int x = 0; x < w; x++) {
                for (int y = y0; y < yEnd; y++) {
                    int fi = (y + 1) * fw + x + 1;
                    int f = flags[fi];
                    if ((f & (SIG | VISIT)) != SIG) {
                        continue;
                    }
                    int bit;
                    if (bypassing) {
                        bit = raw.bit();
                    } else {
                        int cx = (f & REFINED) != 0 ? 16 : (masked(f, y - y0) & NEIGHBOURS) != 0 ? 15 : 14;
                        bit = mq.decode(cx);
                    }
                    int i = y * w + x;
                    int t = bit != 0 ? half : -half;
                    data[i] += data[i] < 0 ? -t : t;
                    flags[fi] = f | REFINED;
                }
            }
        }
    }

    private void cleanup(int one, boolean segmark) {
        int value = one | one >> 1;
        for (int y0 = 0; y0 < h; y0 += 4) {
            int yEnd = Math.min(y0 + 4, h);
            for (int x = 0; x < w; x++) {
                int start = y0;
                if (yEnd - y0 == 4 && runnable(x, y0)) {
                    if (mq.decode(Mq.RUN) == 0) {
                        continue;
                    }
                    int r = mq.decode(Mq.UNIFORM) << 1;
                    r |= mq.decode(Mq.UNIFORM);
                    int y = y0 + r;
                    int fi = (y + 1) * fw + x + 1;
                    int neg = signBit(masked(flags[fi], r));
                    becomeSignificant(x, y, fi, neg != 0);
                    data[y * w + x] = neg != 0 ? -value : value;
                    start = y + 1;
                }
                for (int y = start; y < yEnd; y++) {
                    int fi = (y + 1) * fw + x + 1;
                    int f = flags[fi];
                    if ((f & (SIG | VISIT)) != 0) {
                        flags[fi] = f & ~VISIT;
                        continue;
                    }
                    int m = masked(f, y - y0);
                    if (mq.decode(Contexts.zero(orient, m)) != 0) {
                        int neg = signBit(m);
                        becomeSignificant(x, y, fi, neg != 0);
                        data[y * w + x] = neg != 0 ? -value : value;
                    }
                }
            }
        }
        if (segmark) {
            for (int i = 0; i < 4; i++) {
                mq.decode(Mq.UNIFORM);
            }
        }
    }

    private boolean runnable(int x, int y0) {
        int fi = (y0 + 1) * fw + x + 1;
        return (flags[fi] & RUN_BLOCKERS) == 0 && (flags[fi + fw] & RUN_BLOCKERS) == 0
                && (flags[fi + 2 * fw] & RUN_BLOCKERS) == 0
                && (masked(flags[fi + 3 * fw], 3) & RUN_BLOCKERS) == 0;
    }

    private int signBit(int m) {
        int sc = Contexts.sign(m);
        return mq.decode(sc & 0x1F) ^ sc >> 5;
    }

    private void becomeSignificant(int x, int y, int fi, boolean negative) {
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

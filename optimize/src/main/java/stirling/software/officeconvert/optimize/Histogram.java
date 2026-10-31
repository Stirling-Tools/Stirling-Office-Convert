package stirling.software.officeconvert.optimize;

import static stirling.software.officeconvert.optimize.DeflateTables.DIST_EXTRA;
import static stirling.software.officeconvert.optimize.DeflateTables.LENGTH_EXTRA;
import static stirling.software.officeconvert.optimize.DeflateTables.LENGTH_SYMBOL;
import static stirling.software.officeconvert.optimize.DeflateTables.distSymbol;

final class Histogram {

    final int[] ll = new int[288];

    final int[] dd = new int[30];

    long extra;

    private static final double[] LOG2 = new double[1 << 16];

    static {
        for (int i = 1; i < LOG2.length; i++) {
            LOG2[i] = Math.log(i) / Math.log(2);
        }
    }

    private static double log2(long v) {
        return v < LOG2.length ? LOG2[(int) v] : Math.log(v) / Math.log(2);
    }

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

    double estimate() {
        return entropy(ll, 1) + entropy(dd, 0) + extra;
    }

    private static double entropy(int[] counts, int extraSymbol) {
        long total = extraSymbol;
        int used = extraSymbol;
        for (int c : counts) {
            total += c;
            used += c > 0 ? 1 : 0;
        }
        if (total == 0) {
            return 0;
        }
        double logTotal = log2(total);
        double bits = extraSymbol * logTotal;
        for (int c : counts) {
            if (c > 0) {
                bits += c * (logTotal - log2(c));
            }
        }
        return bits + used * 4.0 + 40;
    }

    double bits() {
        int[] llc = ll.clone();
        llc[256] = 1;
        int[] llLengths = Huffman.lengths(llc, 15);
        int[] ddLengths = Huffman.lengths(dd, 15);
        Squeeze.patchDistances(ddLengths);
        long bits = extra;
        for (int i = 0; i < 288; i++) {
            bits += (long) llc[i] * llLengths[i];
        }
        for (int i = 0; i < 30; i++) {
            bits += (long) dd[i] * ddLengths[i];
        }
        return bits + Squeeze.treeBits(llLengths, ddLengths, null);
    }
}

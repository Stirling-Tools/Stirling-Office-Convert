package stirling.software.officeconvert.optimize;

import static stirling.software.officeconvert.optimize.DeflateTables.LENGTH_SYMBOL;
import static stirling.software.officeconvert.optimize.DeflateTables.distSymbol;

final class Costs {

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

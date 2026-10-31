package stirling.software.officeconvert.optimize;

import java.util.Arrays;

final class Matches {

    final int[] start;

    final int n;

    final Scratch scratch;

    int[] pairs;

    int used;

    Matches(int n, Scratch scratch) {
        this.n = n;
        this.scratch = scratch;
        if (scratch.start.length < n + 1) {
            scratch.start = new int[n + 1];
        }
        if (scratch.pairs.length < Math.max(1024, 4L * n)) {
            scratch.pairs = new int[(int) Math.min(Integer.MAX_VALUE - 8, Math.max(1024, 4L * n))];
        }
        start = scratch.start;
        pairs = scratch.pairs;
    }

    void begin(int k) {
        start[k] = used;
    }

    void add(int len, int dist) {
        if (used + 2 > pairs.length) {
            pairs = Arrays.copyOf(pairs, pairs.length * 2);
            scratch.pairs = pairs;
        }
        pairs[used++] = len;
        pairs[used++] = dist;
    }

    void finish() {
        start[n] = used;
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

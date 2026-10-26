package stirling.software.officeconvert.optimize;

import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import stirling.software.officeconvert.extract.PdfFiles;

final class Merge {

    static final int MAX_ROUNDS = 128;

    private Merge() {}

    static int[] representatives(byte[][] signatures, int[][] edges, boolean[] mergeable)
            throws InterruptedIOException {
        int n = signatures.length;
        int[] rep = new int[n];
        int[] cls = new int[n];
        Map<ByteBuffer, Integer> first = new HashMap<>();
        int classes = 0;
        for (int i = 0; i < n; i++) {
            if (mergeable[i]) {
                Integer c = first.putIfAbsent(ByteBuffer.wrap(signatures[i]), classes);
                cls[i] = c == null ? classes++ : c;
            } else {
                cls[i] = classes++;
            }
        }
        for (int round = 0; ; round++) {
            if (round == MAX_ROUNDS || classes == n) {
                for (int i = 0; i < n; i++) {
                    rep[i] = i;
                }
                return rep;
            }
            PdfFiles.stopIfInterrupted();
            Map<Key, Integer> keys = new HashMap<>();
            int[] next = new int[n];
            int count = 0;
            for (int i = 0; i < n; i++) {
                int[] k = new int[edges[i].length + 1];
                k[0] = cls[i];
                for (int e = 0; e < edges[i].length; e++) {
                    k[e + 1] = cls[edges[i][e]];
                }
                Integer c = keys.putIfAbsent(new Key(k), count);
                next[i] = c == null ? count++ : c;
            }
            boolean stable = count == classes;
            cls = next;
            classes = count;
            if (stable) {
                break;
            }
        }
        int[] firstOf = new int[classes];
        Arrays.fill(firstOf, -1);
        for (int i = 0; i < n; i++) {
            if (firstOf[cls[i]] < 0) {
                firstOf[cls[i]] = i;
            }
            rep[i] = firstOf[cls[i]];
        }
        return rep;
    }

    private record Key(int[] values) {

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Arrays.equals(values, k.values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values);
        }
    }
}

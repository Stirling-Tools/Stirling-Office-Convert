package stirling.software.officeconvert.optimize;

import java.io.InterruptedIOException;
import java.util.Arrays;

import stirling.software.officeconvert.extract.PdfFiles;

final class Merge {

    static final int MAX_ROUNDS = 128;

    private Merge() {}

    static int[] representatives(long[] signatures, int[] edgeStart, int[] edgeTargets, boolean[] mergeable)
            throws InterruptedIOException {
        int n = signatures.length / 2;
        int[] rep = new int[n];
        int[] cls = new int[n];
        int[] table = new int[tableSize(n)];
        int mask = table.length - 1;
        Arrays.fill(table, -1);
        int classes = 0;
        for (int i = 0; i < n; i++) {
            if (!mergeable[i]) {
                cls[i] = classes++;
                continue;
            }
            int slot = mix(signatures[2 * i] ^ signatures[2 * i + 1] * 31) & mask;
            while (table[slot] >= 0 && (signatures[2 * table[slot]] != signatures[2 * i]
                    || signatures[2 * table[slot] + 1] != signatures[2 * i + 1])) {
                slot = slot + 1 & mask;
            }
            if (table[slot] < 0) {
                table[slot] = i;
                cls[i] = classes++;
            } else {
                cls[i] = cls[table[slot]];
            }
        }
        int[] next = new int[n];
        for (int round = 0; ; round++) {
            if (round == MAX_ROUNDS || classes == n) {
                for (int i = 0; i < n; i++) {
                    rep[i] = i;
                }
                return rep;
            }
            PdfFiles.stopIfInterrupted();
            Arrays.fill(table, -1);
            int count = 0;
            for (int i = 0; i < n; i++) {
                int slot = hash(cls, edgeStart, edgeTargets, i) & mask;
                while (table[slot] >= 0 && !sameKey(cls, edgeStart, edgeTargets, table[slot], i)) {
                    slot = slot + 1 & mask;
                }
                if (table[slot] < 0) {
                    table[slot] = i;
                    next[i] = count++;
                } else {
                    next[i] = next[table[slot]];
                }
            }
            boolean stable = count == classes;
            int[] swap = cls;
            cls = next;
            next = swap;
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

    private static int tableSize(int n) {
        int size = 16;
        while (size < 2L * n && size < 1 << 30) {
            size <<= 1;
        }
        return size;
    }

    private static int mix(long v) {
        long h = v * 0x9E3779B97F4A7C15L;
        return (int) (h ^ h >>> 32);
    }

    private static int hash(int[] cls, int[] edgeStart, int[] edgeTargets, int i) {
        int h = 31 + cls[i];
        for (int e = edgeStart[i]; e < edgeStart[i + 1]; e++) {
            h = 31 * h + cls[edgeTargets[e]];
        }
        return h ^ h >>> 16;
    }

    private static boolean sameKey(int[] cls, int[] edgeStart, int[] edgeTargets, int a, int b) {
        int length = edgeStart[a + 1] - edgeStart[a];
        if (cls[a] != cls[b] || edgeStart[b + 1] - edgeStart[b] != length) {
            return false;
        }
        for (int e = 0; e < length; e++) {
            if (cls[edgeTargets[edgeStart[a] + e]] != cls[edgeTargets[edgeStart[b] + e]]) {
                return false;
            }
        }
        return true;
    }
}

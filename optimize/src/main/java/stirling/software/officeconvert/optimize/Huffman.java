package stirling.software.officeconvert.optimize;

import java.util.Arrays;

final class Huffman {

    private static final ThreadLocal<Workspace> WORKSPACE = ThreadLocal.withInitial(Workspace::new);

    private static final class Workspace {

        long[] weight = new long[0];

        int[] left = new int[0];

        int[] right = new int[0];

        int[] symbol = new int[0];

        int[] stack = new int[0];

        void fit(int capacity) {
            if (weight.length < capacity) {
                weight = new long[capacity];
                left = new int[capacity];
                right = new int[capacity];
                symbol = new int[capacity];
                stack = new int[capacity + 16];
            }
        }
    }

    private Huffman() {}

    static int[] lengths(int[] counts, int maxBits) {
        int n = counts.length;
        int[] lengths = new int[n];
        int m = 0;
        for (int c : counts) {
            m += c > 0 ? 1 : 0;
        }
        if (m == 0) {
            return lengths;
        }
        long[] order = new long[m];
        int k = 0;
        for (int s = 0; s < n; s++) {
            if (counts[s] > 0) {
                order[k++] = (long) counts[s] << 16 | s;
            }
        }
        Arrays.sort(order);
        if (m == 1) {
            lengths[(int) (order[0] & 0xFFFF)] = 1;
            return lengths;
        }
        if (unlimited(order, m, lengths) <= maxBits) {
            return lengths;
        }
        Arrays.fill(lengths, 0);
        int capacity = m * 2 * maxBits + m;
        Workspace w = WORKSPACE.get();
        w.fit(capacity);
        long[] weight = w.weight;
        int[] left = w.left;
        int[] right = w.right;
        int[] symbol = w.symbol;
        int nodes = 0;
        int[] leaves = new int[m];
        for (int i = 0; i < m; i++) {
            weight[nodes] = order[i] >>> 16;
            left[nodes] = -1;
            right[nodes] = -1;
            symbol[nodes] = (int) (order[i] & 0xFFFF);
            leaves[i] = nodes++;
        }
        int[] list = leaves.clone();
        int listSize = m;
        for (int level = 1; level < maxBits; level++) {
            int packages = listSize / 2;
            int[] packed = new int[packages];
            for (int p = 0; p < packages; p++) {
                int a = list[2 * p];
                int b = list[2 * p + 1];
                weight[nodes] = weight[a] + weight[b];
                left[nodes] = a;
                right[nodes] = b;
                symbol[nodes] = -1;
                packed[p] = nodes++;
            }
            int[] merged = new int[m + packages];
            int i = 0;
            int j = 0;
            int o = 0;
            while (i < m || j < packages) {
                if (j >= packages || i < m && weight[leaves[i]] <= weight[packed[j]]) {
                    merged[o++] = leaves[i++];
                } else {
                    merged[o++] = packed[j++];
                }
            }
            list = merged;
            listSize = o;
        }
        int[] stack = w.stack;
        for (int t = 0; t < 2 * m - 2; t++) {
            int top = 0;
            stack[top++] = list[t];
            while (top > 0) {
                int node = stack[--top];
                if (left[node] < 0) {
                    lengths[symbol[node]]++;
                } else {
                    stack[top++] = left[node];
                    stack[top++] = right[node];
                }
            }
        }
        return lengths;
    }

    private static int unlimited(long[] order, int m, int[] lengths) {
        long[] weight = new long[2 * m];
        int[] parent = new int[2 * m];
        for (int i = 0; i < m; i++) {
            weight[i] = order[i] >>> 16;
        }
        int leaf = 0;
        int inner = m;
        int next = m;
        for (int step = 0; step < m - 1; step++) {
            int a = leaf < m && (inner >= next || weight[leaf] <= weight[inner]) ? leaf++ : inner++;
            int b = leaf < m && (inner >= next || weight[leaf] <= weight[inner]) ? leaf++ : inner++;
            weight[next] = weight[a] + weight[b];
            parent[a] = next;
            parent[b] = next;
            next++;
        }
        int root = next - 1;
        int[] depth = new int[2 * m];
        depth[root] = 0;
        int max = 0;
        for (int node = root - 1; node >= 0; node--) {
            depth[node] = depth[parent[node]] + 1;
            if (node < m) {
                lengths[(int) (order[node] & 0xFFFF)] = depth[node];
                max = Math.max(max, depth[node]);
            }
        }
        return max;
    }

    static int[] codes(int[] lengths) {
        int max = 0;
        for (int l : lengths) {
            max = Math.max(max, l);
        }
        int[] count = new int[max + 2];
        for (int l : lengths) {
            if (l > 0) {
                count[l]++;
            }
        }
        int[] next = new int[max + 2];
        int code = 0;
        for (int bits = 1; bits <= max; bits++) {
            code = (code + count[bits - 1]) << 1;
            next[bits] = code;
        }
        int[] codes = new int[lengths.length];
        for (int s = 0; s < lengths.length; s++) {
            if (lengths[s] > 0) {
                codes[s] = next[lengths[s]]++;
            }
        }
        return codes;
    }
}

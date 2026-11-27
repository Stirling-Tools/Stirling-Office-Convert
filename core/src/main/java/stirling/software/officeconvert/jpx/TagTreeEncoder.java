package stirling.software.officeconvert.jpx;

import java.util.Arrays;

final class TagTreeEncoder {

    private final int[] value;

    private final int[] low;

    private final boolean[] known;

    private final int[] parent;

    private final int leaves;

    private final int[] path = new int[40];

    TagTreeEncoder(int width, int height) {
        int total = 0;
        int w = width;
        int h = height;
        while (true) {
            total += w * h;
            if (w * h <= 1) {
                break;
            }
            w = (w + 1) >> 1;
            h = (h + 1) >> 1;
        }
        leaves = width * height;
        value = new int[total];
        low = new int[total];
        known = new boolean[total];
        parent = new int[total];
        int level = 0;
        w = width;
        h = height;
        while (w * h > 1) {
            int pw = (w + 1) >> 1;
            int next = level + w * h;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    parent[level + y * w + x] = next + (y >> 1) * pw + (x >> 1);
                }
            }
            level = next;
            w = pw;
            h = (h + 1) >> 1;
        }
        parent[level] = -1;
    }

    void set(int[] leafValues) {
        Arrays.fill(value, Integer.MAX_VALUE);
        Arrays.fill(low, 0);
        Arrays.fill(known, false);
        for (int i = 0; i < leaves; i++) {
            int v = leafValues[i];
            int n = i;
            while (n >= 0 && value[n] > v) {
                value[n] = v;
                n = parent[n];
            }
        }
    }

    void restart() {
        Arrays.fill(low, 0);
        Arrays.fill(known, false);
    }

    void encode(int leaf, int threshold, HeaderWriter out) {
        int depth = 0;
        for (int n = leaf; n >= 0; n = parent[n]) {
            path[depth++] = n;
        }
        int lo = 0;
        for (int k = depth - 1; k >= 0; k--) {
            int n = path[k];
            if (lo > low[n]) {
                low[n] = lo;
            } else {
                lo = low[n];
            }
            while (lo < threshold) {
                if (lo >= value[n]) {
                    if (!known[n]) {
                        out.bit(1);
                        known[n] = true;
                    }
                    break;
                }
                out.bit(0);
                lo++;
            }
            low[n] = lo;
        }
    }
}

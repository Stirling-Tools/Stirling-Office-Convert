package stirling.software.officeconvert.jpx;

import java.util.Arrays;

final class TagTree {

    private static final int UNKNOWN = Integer.MAX_VALUE;

    private final int[] value;

    private final int[] low;

    private final int[] parent;

    private final int[] path = new int[32];

    TagTree(int width, int height) {
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
        value = new int[total];
        low = new int[total];
        parent = new int[total];
        Arrays.fill(value, UNKNOWN);
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

    boolean below(int leaf, int threshold, HeaderBits bits) throws JpxException {
        int depth = 0;
        int node = leaf;
        while (parent[node] >= 0) {
            path[depth++] = node;
            node = parent[node];
        }
        int l = 0;
        while (true) {
            if (l > low[node]) {
                low[node] = l;
            } else {
                l = low[node];
            }
            while (l < threshold && l < value[node]) {
                if (bits.bit() != 0) {
                    value[node] = l;
                } else {
                    l++;
                }
            }
            low[node] = l;
            if (depth == 0) {
                break;
            }
            node = path[--depth];
        }
        return value[node] < threshold;
    }

    int value(int leaf, int limit, HeaderBits bits) throws JpxException {
        if (!below(leaf, Math.max(1, limit), bits)) {
            throw new JpxException("JPEG 2000 tag tree value out of range");
        }
        return value[leaf];
    }
}

package stirling.software.officeconvert.optimize;

import java.util.Arrays;

final class Store {

    int[] lens;

    int[] dists;

    int size;

    Store(int capacity) {
        lens = new int[Math.max(16, capacity)];
        dists = new int[Math.max(16, capacity)];
    }

    void add(int len, int dist) {
        if (size == lens.length) {
            lens = Arrays.copyOf(lens, size * 2);
            dists = Arrays.copyOf(dists, size * 2);
        }
        lens[size] = len;
        dists[size++] = dist;
    }

    int position(int index, int base) {
        int p = base;
        for (int i = 0; i < index; i++) {
            p += dists[i] == 0 ? 1 : lens[i];
        }
        return p;
    }

    Store slice(int from, int to) {
        Store s = new Store(to - from);
        System.arraycopy(lens, from, s.lens, 0, to - from);
        System.arraycopy(dists, from, s.dists, 0, to - from);
        s.size = to - from;
        return s;
    }
}

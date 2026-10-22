package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageData;

final class LinkMarker {

    private LinkMarker() {}

    static void mark(List<Glyph> glyphs, List<PageData.Link> links) {
        if (links.isEmpty() || glyphs.isEmpty()) {
            return;
        }
        int n = links.size();
        float[] left = new float[n];
        float[] right = new float[n];
        float[] top = new float[n];
        float[] bottom = new float[n];
        List<Integer> usable = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            PageData.Link l = links.get(i);
            left[i] = (l.x() - 0.5f) + 0.0f;
            right[i] = (l.right() + 0.5f) + 0.0f;
            top[i] = l.top() - 1;
            bottom[i] = l.bottom() + 1;
            if (left[i] <= right[i] && top[i] <= bottom[i]) {
                usable.add(i);
            }
        }
        if (usable.isEmpty()) {
            return;
        }
        float[] edges = edges(usable, left, right);
        Tree tree = new Tree(2 * edges.length - 1);
        Integer[] byTop = usable.toArray(Integer[]::new);
        Arrays.sort(byTop, Comparator.comparingDouble(i -> top[i]));
        Integer[] byBottom = usable.toArray(Integer[]::new);
        Arrays.sort(byBottom, Comparator.comparingDouble(i -> bottom[i]));
        boolean[] active = new boolean[n];

        int g = glyphs.size();
        float[] cx = new float[g];
        float[] cy = new float[g];
        List<Integer> queries = new ArrayList<>(g);
        for (int i = 0; i < g; i++) {
            Glyph glyph = glyphs.get(i);
            cx[i] = glyph.centreX() + 0.0f;
            cy[i] = glyph.baseline - glyph.size * 0.3f;
            if (!Float.isNaN(cx[i]) && !Float.isNaN(cy[i])) {
                queries.add(i);
            }
        }
        Integer[] order = queries.toArray(Integer[]::new);
        Arrays.sort(order, Comparator.comparingDouble(i -> cy[i]));

        int opened = 0;
        int closed = 0;
        for (int q : order) {
            float y = cy[q];
            while (opened < byTop.length && top[byTop[opened]] <= y) {
                int l = byTop[opened++];
                active[l] = true;
                tree.add(slot(edges, left[l]), slot(edges, right[l]), l);
            }
            while (closed < byBottom.length && bottom[byBottom[closed]] < y) {
                active[byBottom[closed++]] = false;
            }
            int at = slot(edges, cx[q]);
            if (at < 0) {
                continue;
            }
            int hit = tree.first(at, active);
            if (hit >= 0) {
                PageData.Link l = links.get(hit);
                glyphs.get(q).link = l.uri() != null ? l.uri() : "#page" + l.targetPage();
            }
        }
    }

    private static float[] edges(List<Integer> usable, float[] left, float[] right) {
        float[] all = new float[usable.size() * 2];
        int k = 0;
        for (int i : usable) {
            all[k++] = left[i];
            all[k++] = right[i];
        }
        Arrays.sort(all);
        int unique = 0;
        for (int i = 0; i < all.length; i++) {
            if (i == 0 || all[i] != all[unique - 1]) {
                all[unique++] = all[i];
            }
        }
        return Arrays.copyOf(all, unique);
    }

    private static int slot(float[] edges, float x) {
        int found = Arrays.binarySearch(edges, x);
        if (found >= 0) {
            return 2 * found;
        }
        int after = -found - 1;
        return after == 0 || after == edges.length ? -1 : 2 * after - 1;
    }

    private static final class Tree {

        private final int size;

        private final List<PriorityQueue<Integer>> nodes;

        Tree(int slots) {
            int s = 1;
            while (s < slots) {
                s <<= 1;
            }
            size = s;
            nodes = new ArrayList<>(2 * s);
            for (int i = 0; i < 2 * s; i++) {
                nodes.add(null);
            }
        }

        void add(int from, int to, int link) {
            int lo = from + size;
            int hi = to + size + 1;
            while (lo < hi) {
                if ((lo & 1) == 1) {
                    push(lo++, link);
                }
                if ((hi & 1) == 1) {
                    push(--hi, link);
                }
                lo >>= 1;
                hi >>= 1;
            }
        }

        int first(int slot, boolean[] active) {
            int best = -1;
            for (int node = slot + size; node >= 1; node >>= 1) {
                PriorityQueue<Integer> q = nodes.get(node);
                if (q == null) {
                    continue;
                }
                while (!q.isEmpty() && !active[q.peek()]) {
                    q.poll();
                }
                if (!q.isEmpty() && (best < 0 || q.peek() < best)) {
                    best = q.peek();
                }
            }
            return best;
        }

        private void push(int node, int link) {
            PriorityQueue<Integer> q = nodes.get(node);
            if (q == null) {
                q = new PriorityQueue<>();
                nodes.set(node, q);
            }
            q.add(link);
        }
    }
}

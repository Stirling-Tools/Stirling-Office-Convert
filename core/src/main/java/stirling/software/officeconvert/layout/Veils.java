package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import stirling.software.officeconvert.extract.PageGraphics.VectorMark;
import stirling.software.officeconvert.extract.PageGraphics;

final class Veils {

    private static final float MIN_SIZE = 12f;
    private static final int CURVED_LETTER = 25;
    private static final int MIN_LETTERS = 3;
    private static final int MAX_COMPARED = 300;

    record Split(List<VectorMark> art, List<PageLayout.Veil> veils) {}

    private Veils() {}

    static Split split(List<VectorMark> marks, PageGraphics gfx) {
        List<VectorMark> letters = marks.stream().filter(m -> letter(m, gfx)).toList();
        if (letters.size() < MIN_LETTERS || letters.size() > MAX_COMPARED) {
            return new Split(marks, List.of());
        }
        int[] parent = new int[letters.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < letters.size(); i++) {
            for (int j = i + 1; j < letters.size(); j++) {
                if (neighbours(letters.get(i), letters.get(j))) {
                    parent[root(parent, i)] = root(parent, j);
                }
            }
        }
        Map<Integer, List<VectorMark>> words = new LinkedHashMap<>();
        for (int i = 0; i < letters.size(); i++) {
            words.computeIfAbsent(root(parent, i), k -> new ArrayList<>()).add(letters.get(i));
        }
        Set<VectorMark> veiled = Collections.newSetFromMap(new IdentityHashMap<>());
        List<PageLayout.Veil> veils = new ArrayList<>();
        for (List<VectorMark> word : words.values()) {
            if (word.size() >= MIN_LETTERS && word.stream().filter(m -> m.segments() >= CURVED_LETTER).count() >= 2) {
                veiled.addAll(word);
                veils.add(new PageLayout.Veil(bounds(word), word, word.stream().mapToInt(gfx::order).min().getAsInt()));
            }
        }
        if (veils.isEmpty()) {
            return new Split(marks, List.of());
        }
        return new Split(marks.stream().filter(m -> !veiled.contains(m)).toList(), veils);
    }

    private static boolean letter(VectorMark m, PageGraphics gfx) {
        return gfx.seeThrough(m) && (m.filled() || m.stroked()) && !m.shading() && Math.max(m.width(), m.height()) >= MIN_SIZE;
    }

    private static boolean neighbours(VectorMark a, VectorMark b) {
        float sa = Math.max(a.width(), a.height());
        float sb = Math.max(b.width(), b.height());
        if (a.rgb() != b.rgb() || Math.max(sa, sb) > 3 * Math.min(sa, sb)) {
            return false;
        }
        float gapX = Math.max(0, Math.max(a.x(), b.x()) - Math.min(a.right(), b.right()));
        float gapY = Math.max(0, Math.max(a.top(), b.top()) - Math.min(a.bottom(), b.bottom()));
        if (Math.hypot(gapX, gapY) > 0.6f * Math.min(sa, sb)) {
            return false;
        }
        float w = Math.min(a.right(), b.right()) - Math.max(a.x(), b.x());
        float h = Math.min(a.bottom(), b.bottom()) - Math.max(a.top(), b.top());
        float shared = w > 0 && h > 0 ? w * h : 0;
        return shared <= 0.6f * Math.min(a.width() * a.height(), b.width() * b.height());
    }

    private static Box bounds(List<VectorMark> word) {
        float x = Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        for (VectorMark m : word) {
            x = Math.min(x, m.x());
            top = Math.min(top, m.top());
            right = Math.max(right, m.right());
            bottom = Math.max(bottom, m.bottom());
        }
        return new Box(x, top, right, bottom);
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }
}

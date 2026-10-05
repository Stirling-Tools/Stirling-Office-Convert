package stirling.software.officeconvert.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.layout.Box;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.PageLayout;
import stirling.software.officeconvert.layout.ParaDraft;

final class ShapeAnchors {

    private static final float ABOVE = 3f;
    private static final float BELOW = 2f;
    private static final float TALL = 0.25f;
    private static final float WIDE = 0.9f;
    private static final float TOUCH = 1f;
    private static final float THIN = 3f;
    private static final int MAX_GROUPED = 2000;

    private ShapeAnchors() {}

    static List<ParaDraft> hosts(PageLayout layout, List<PageLayout.Decoration> decorations, ParagraphFactory paragraphs,
            float bodySize) {
        List<Box> shapes = decorations.stream().map(PageLayout.Decoration::box).toList();
        boolean[] art = new boolean[shapes.size()];
        for (int i = 0; i < art.length; i++) {
            Box b = shapes.get(i);
            art[i] = b.height() > TALL * layout.page().height() || b.width() > WIDE * layout.page().width();
        }
        for (boolean changed = true; changed; ) {
            changed = false;
            for (int i = 0; i < art.length; i++) {
                if (!art[i] && alongArt(shapes.get(i), shapes, art)) {
                    art[i] = true;
                    changed = true;
                }
            }
        }
        ParaDraft[] under = new ParaDraft[shapes.size()];
        for (int i = 0; i < art.length; i++) {
            under[i] = art[i] || unpainted(decorations.get(i)) ? null : firstOn(layout, shapes.get(i), paragraphs, bodySize);
        }
        for (int j = 0; j < art.length; j++) {
            for (int i = 0; i < art.length && under[j] == null && !art[j]; i++) {
                if (under[i] != null && !unpainted(decorations.get(i)) && same(shapes.get(i), shapes.get(j))) {
                    under[j] = under[i];
                }
            }
        }
        int[] row = rows(shapes, art, under);
        Map<Integer, ParaDraft> byRow = new HashMap<>();
        List<ParaDraft> hosts = new ArrayList<>(shapes.size());
        for (int i = 0; i < art.length; i++) {
            int r = row[i];
            hosts.add(under[i] != null ? under[i]
                    : art[i] ? null : byRow.computeIfAbsent(r, k -> host(layout, span(shapes, row, k), paragraphs, bodySize)));
        }
        return hosts;
    }

    private static final float OUTLINE = 3f;

    private static boolean unpainted(PageLayout.Decoration d) {
        return d.rgb() < 0 && d.lineRgb() < 0;
    }

    private static boolean same(Box a, Box b) {
        return Math.abs(a.x() - b.x()) <= OUTLINE && Math.abs(a.top() - b.top()) <= OUTLINE
                && Math.abs(a.right() - b.right()) <= OUTLINE && Math.abs(a.bottom() - b.bottom()) <= OUTLINE;
    }

    static boolean alone(PageLayout layout, Box panel, ParaDraft d, ParagraphFactory paragraphs) {
        for (PageLayout.Band band : layout.bands()) {
            for (PageLayout.Column c : band.columns()) {
                for (PageLayout.Item it : c.items()) {
                    if (it instanceof PageLayout.ParaItem pi && pi.para() != d && on(panel, it, pi.para(), paragraphs)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean on(Box panel, PageLayout.Item it, ParaDraft para, ParagraphFactory paragraphs) {
        return paragraphs.wordTop(para) >= panel.top() - ABOVE && paragraphs.wordBottom(para) <= panel.bottom() + ABOVE
                && it.x() >= panel.x() - 1 && it.right() <= panel.right() + 1;
    }

    private static final float PANEL_PAD = 3f;

    private static ParaDraft firstOn(PageLayout layout, Box panel, ParagraphFactory paragraphs, float bodySize) {
        ParaDraft first = null;
        float firstTop = Float.MAX_VALUE;
        for (PageLayout.Band band : layout.bands()) {
            for (PageLayout.Column c : band.columns()) {
                for (PageLayout.Item it : c.items()) {
                    if (it instanceof PageLayout.ParaItem pi && paragraphs.wordTop(pi.para()) < firstTop
                            && on(panel, it, pi.para(), paragraphs)) {
                        first = pi.para();
                        firstTop = paragraphs.wordTop(pi.para());
                    }
                }
            }
        }
        return firstTop <= panel.top() + PANEL_PAD * bodySize ? first : null;
    }

    private static int[] rows(List<Box> shapes, boolean[] art, ParaDraft[] under) {
        int[] parent = new int[shapes.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        if (parent.length <= MAX_GROUPED) {
            for (int i = 0; i < parent.length; i++) {
                for (int j = i + 1; j < parent.length; j++) {
                    if (!art[i] && !art[j] && under[i] == null && under[j] == null && sameRow(shapes.get(i), shapes.get(j))) {
                        parent[root(parent, j)] = root(parent, i);
                    }
                }
            }
        }
        for (int i = 0; i < parent.length; i++) {
            parent[i] = root(parent, i);
        }
        return parent;
    }

    private static boolean sameRow(Box a, Box b) {
        float gap = Math.max(a.x(), b.x()) - Math.min(a.right(), b.right());
        return Math.abs(a.top() - b.top()) <= 0.5f && Math.abs(a.bottom() - b.bottom()) <= 0.5f
                && gap <= Math.max(6f, 3 * a.height());
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static Box span(List<Box> shapes, int[] row, int leader) {
        Box span = null;
        for (int i = 0; i < row.length; i++) {
            if (row[i] == leader) {
                span = span == null ? shapes.get(i) : span.union(shapes.get(i));
            }
        }
        return span;
    }

    private static boolean alongArt(Box b, List<Box> shapes, boolean[] art) {
        for (int j = 0; j < art.length; j++) {
            Box a = shapes.get(j);
            if (art[j] && a != b && b.near(a, TOUCH) && !inside(b, a)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inside(Box b, Box a) {
        return b.x() > a.x() + TOUCH && b.right() < a.right() - TOUCH && b.top() > a.top() + TOUCH
                && b.bottom() < a.bottom() - TOUCH;
    }

    private static ParaDraft host(PageLayout layout, Box shape, ParagraphFactory paragraphs, float bodySize) {
        PageLayout.Item best = null;
        float bestTop = -Float.MAX_VALUE;
        for (PageLayout.Band band : layout.bands()) {
            for (PageLayout.Column c : band.columns()) {
                for (PageLayout.Item it : c.items()) {
                    if (!ColumnFlow.inFlow(it) || shape.right() < it.x() - 1 || shape.x() > it.right() + 1) {
                        continue;
                    }
                    float top = it instanceof PageLayout.ParaItem pi ? paragraphs.wordTop(pi.para()) : it.top();
                    if (top <= shape.top() + ABOVE && top > bestTop) {
                        best = it;
                        bestTop = top;
                    }
                }
            }
        }
        if (best instanceof PageLayout.ParaItem pi && shape.top() <= paragraphs.wordBottom(pi.para()) + BELOW * bodySize
                && !(Math.min(shape.width(), shape.height()) <= THIN && crossed(layout, shape, pi.para()))) {
            return pi.para();
        }
        return null;
    }

    private static boolean crossed(PageLayout layout, Box shape, ParaDraft host) {
        for (PageLayout.Band band : layout.bands()) {
            for (PageLayout.Column c : band.columns()) {
                for (PageLayout.Item it : c.items()) {
                    if (!(it instanceof PageLayout.ParaItem pi) || pi.para() == host) {
                        continue;
                    }
                    for (Line l : pi.para().lines) {
                        if (l.top < shape.bottom() && l.bottom > shape.top() && l.x < shape.right() && l.right > shape.x()) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}

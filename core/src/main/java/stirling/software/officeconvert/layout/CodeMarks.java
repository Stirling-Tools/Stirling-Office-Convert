package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics.Rule;

// QR and matrix codes are dense square packs of small dark boxes; their edges must not read as a ruled table.
final class CodeMarks {

    private static final int MIN_PIECES = 30;
    private static final float MAX_PIECE = 12f;
    private static final float MAX_SIDE = 260f;
    private static final float TOUCH = 1.2f;
    private static final int MAX_INPUT = 20_000;
    private static final float SPLIT_GAP = 6f;
    private static final float MAX_BAR = 4f;
    private static final float MIN_BAR_HEIGHT = 15f;
    private static final float BAR_GAP = 6f;
    private static final int MIN_BARS = 20;

    private CodeMarks() {}

    static List<Box> regions(List<Rule> rules, List<Fill> fills) {
        List<Box> pieces = new ArrayList<>();
        for (Fill f : fills) {
            if (dark(f.rgb()) && Math.min(f.right() - f.x(), f.bottom() - f.top()) <= MAX_PIECE) {
                pieces.add(new Box(f.x(), f.top(), f.right(), f.bottom()));
            }
        }
        for (Rule r : rules) {
            if (dark(r.rgb()) && r.length() <= 80f) {
                float half = r.thickness() / 2f;
                pieces.add(r.horizontal() ? new Box(r.start(), r.pos() - half, r.end(), r.pos() + half)
                        : new Box(r.pos() - half, r.start(), r.pos() + half, r.end()));
            }
        }
        if (pieces.size() < MIN_PIECES || pieces.size() > MAX_INPUT) {
            return List.of();
        }
        int[] parent = new int[pieces.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        Map<Long, List<Integer>> grid = new HashMap<>();
        float cell = 8f;
        for (int i = 0; i < pieces.size(); i++) {
            Box b = pieces.get(i);
            for (int x = (int) Math.floor((b.x() - TOUCH) / cell); x <= (int) Math.floor((b.right() + TOUCH) / cell); x++) {
                for (int y = (int) Math.floor((b.top() - TOUCH) / cell); y <= (int) Math.floor((b.bottom() + TOUCH) / cell); y++) {
                    List<Integer> here = grid.computeIfAbsent(((long) x << 32) ^ (y & 0xffffffffL), k -> new ArrayList<>());
                    for (int j : here) {
                        if (touches(pieces.get(j), b)) {
                            parent[root(parent, i)] = root(parent, j);
                        }
                    }
                    here.add(i);
                }
            }
        }
        Map<Integer, List<Box>> groups = new HashMap<>();
        for (int i = 0; i < pieces.size(); i++) {
            groups.computeIfAbsent(root(parent, i), k -> new ArrayList<>()).add(pieces.get(i));
        }
        List<Box> out = new ArrayList<>();
        List<Box> rest = new ArrayList<>();
        for (List<Box> g : groups.values()) {
            Box bounds = g.getFirst();
            float ink = 0;
            for (Box b : g) {
                bounds = bounds.union(b);
                ink += b.width() * b.height();
            }
            float area = Math.max(1f, bounds.width() * bounds.height());
            boolean square = Math.max(bounds.width(), bounds.height()) <= 1.5f * Math.min(bounds.width(), bounds.height());
            if (g.size() >= MIN_PIECES && square && bounds.width() <= MAX_SIDE && bounds.height() <= MAX_SIDE && ink >= 0.25f * area
                    && ink <= 0.8f * area) {
                out.add(bounds);
            } else {
                rest.add(bounds);
            }
        }
        // A blank module row can cut a code in two; the smaller piece joins the code it sits against.
        for (int i = 0; i < out.size(); i++) {
            for (Box b : rest) {
                if (out.get(i).near(b, SPLIT_GAP) && out.get(i).union(b).width() <= MAX_SIDE && out.get(i).union(b).height() <= MAX_SIDE
                        && b.width() <= out.get(i).width() * 1.1f) {
                    out.set(i, out.get(i).union(b));
                }
            }
        }
        return out;
    }

    // Barcodes: a row of thin dark bars sharing top and bottom, close together.
    static List<Box> strips(List<Rule> rules, List<Fill> fills) {
        List<Box> bars = new ArrayList<>();
        for (Fill f : fills) {
            if (dark(f.rgb()) && f.right() - f.x() <= MAX_BAR && f.bottom() - f.top() >= MIN_BAR_HEIGHT) {
                bars.add(new Box(f.x(), f.top(), f.right(), f.bottom()));
            }
        }
        for (Rule r : rules) {
            if (dark(r.rgb()) && !r.horizontal() && r.thickness() <= MAX_BAR && r.length() >= MIN_BAR_HEIGHT) {
                float half = r.thickness() / 2f;
                bars.add(new Box(r.pos() - half, r.start(), r.pos() + half, r.end()));
            }
        }
        if (bars.size() > MAX_INPUT) {
            return List.of();
        }
        bars.sort(java.util.Comparator.comparingDouble(Box::top).thenComparingDouble(Box::x));
        List<Box> out = new ArrayList<>();
        boolean[] used = new boolean[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            if (used[i]) {
                continue;
            }
            Box first = bars.get(i);
            List<Box> row = new ArrayList<>();
            for (int j = i; j < bars.size(); j++) {
                Box b = bars.get(j);
                if (!used[j] && Math.abs(b.top() - first.top()) <= 0.5f && Math.abs(b.bottom() - first.bottom()) <= 0.5f) {
                    row.add(b);
                    used[j] = true;
                }
            }
            row.sort(java.util.Comparator.comparingDouble(Box::x));
            Box run = row.getFirst();
            int n = 1;
            for (int k = 1; k <= row.size(); k++) {
                if (k < row.size() && row.get(k).x() - run.right() <= BAR_GAP) {
                    run = run.union(row.get(k));
                    n++;
                    continue;
                }
                if (n >= MIN_BARS) {
                    out.add(run);
                }
                if (k < row.size()) {
                    run = row.get(k);
                    n = 1;
                }
            }
        }
        return out;
    }

    private static boolean touches(Box a, Box b) {
        return a.x() - TOUCH <= b.right() && b.x() <= a.right() + TOUCH && a.top() - TOUCH <= b.bottom() && b.top() <= a.bottom() + TOUCH;
    }

    private static boolean dark(int rgb) {
        return 0.299f * ((rgb >> 16) & 0xFF) + 0.587f * ((rgb >> 8) & 0xFF) + 0.114f * (rgb & 0xFF) < 90f;
    }

    private static int root(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }
}

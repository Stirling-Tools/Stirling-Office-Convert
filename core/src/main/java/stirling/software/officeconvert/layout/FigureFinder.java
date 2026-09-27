package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics.Rule;
import stirling.software.officeconvert.extract.PageGraphics.VectorMark;

final class FigureFinder {

    private static final float JOIN_GAP = 4f;

    static final float MIN_SIDE = 12f;
    private static final float MARK_MIN = 2.5f;

    private FigureFinder() {}

    static List<Box> strongRegions(List<VectorMark> marks, float pageW, float pageH) {
        List<Box> boxes = new ArrayList<>();
        for (VectorMark m : marks) {
            if (m.width() < 0.5f && m.height() < 0.5f) {
                continue;
            }
            if (m.width() > pageW * 0.9f && m.height() > pageH * 0.9f) {
                continue;
            }
            boxes.add(new Box(m.x(), m.top(), m.right(), m.bottom()));
        }
        return cluster(boxes, JOIN_GAP);
    }

    static List<Box> figures(
            List<Box> strong,
            List<Fill> fills,
            List<Rule> rules,
            List<Line> segments,
            List<VectorMark> marks,
            float pageW,
            float pageH) {
        List<Box> weak = new ArrayList<>();
        for (Fill f : fills) {
            if (f.width() > pageW * 0.8f && f.height() > pageH * 0.5f || isWhite(f.rgb())) {
                continue;
            }
            weak.add(new Box(f.x(), f.top(), f.right(), f.bottom()));
        }
        for (Rule r : rules) {
            if (r.length() > pageW * 0.6f) {
                continue;
            }
            float h = Math.max(r.thickness(), 0.5f) / 2f;
            weak.add(
                    r.horizontal()
                            ? new Box(r.start(), r.pos() - h, r.end(), r.pos() + h)
                            : new Box(r.pos() - h, r.start(), r.pos() + h, r.end()));
        }
        List<Box> grown = new ArrayList<>(strong);
        List<Box> pending = new ArrayList<>();
        for (Box w : weak) {
            boolean absorbed = false;
            for (int i = 0; i < grown.size(); i++) {
                if (grown.get(i).near(w, JOIN_GAP)) {
                    grown.set(i, grown.get(i).union(w));
                    absorbed = true;
                    break;
                }
            }
            if (!absorbed) {
                pending.add(w);
            }
        }
        grown = cluster(grown, JOIN_GAP);

        List<Box> out = new ArrayList<>();
        for (Box b : grown) {
            boolean big = b.width() >= MIN_SIDE && b.height() >= MIN_SIDE;
            if (big ? !isProse(b, segments) : smallFigure(b, segments, marks)) {
                out.add(b);
            }
        }
        for (Box c : clusterCounted(pending, JOIN_GAP)) {
            if (c.width() >= 60 && c.height() >= 40 && !isProse(c, segments) && overlapsNone(c, out)
                    && countInside(pending, c) >= 8 && wordsInside(c, segments) <= countInside(pending, c)) {
                out.add(c);
            }
        }
        return cluster(out, 1f);
    }

    private static boolean isWhite(int rgb) {
        return ((rgb >> 16) & 0xFF) >= 0xFB && ((rgb >> 8) & 0xFF) >= 0xFB && (rgb & 0xFF) >= 0xFB;
    }

    private static boolean overlapsNone(Box c, List<Box> boxes) {
        for (Box b : boxes) {
            if (b.overlapArea(c) > 0) {
                return false;
            }
        }
        return true;
    }

    private static int countInside(List<Box> boxes, Box region) {
        int n = 0;
        for (Box b : boxes) {
            if (region.contains(b.centreX(), b.centreY())) {
                n++;
            }
        }
        return n;
    }

    private static int wordsInside(Box region, List<Line> segments) {
        int n = 0;
        for (Line l : segments) {
            for (Word w : l.words) {
                if (region.contains((w.x + w.right) / 2f, l.baseline - l.size * 0.3f)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean smallFigure(Box b, List<Line> segments, List<VectorMark> marks) {
        int dots = 0;
        int other = 0;
        for (VectorMark m : marks) {
            if (b.contains((m.x() + m.right()) / 2f, (m.top() + m.bottom()) / 2f)) {
                if (m.round()) {
                    dots++;
                } else {
                    other++;
                }
            }
        }
        if (dots > 0) {
            return other > 0;
        }
        return besideText(b, segments);
    }

    private static boolean besideText(Box b, List<Line> segments) {
        if (Math.min(b.width(), b.height()) < MARK_MIN || Math.max(b.width(), b.height()) < 2 * MARK_MIN) {
            return false;
        }
        boolean level = false;
        for (Line l : segments) {
            if (b.centreY() < l.top || b.centreY() > l.bottom || b.height() > 2 * l.size) {
                continue;
            }
            for (Word w : l.words) {
                if (w.x < b.right() && w.right > b.x()) {
                    return false;
                }
                level |= Math.max(w.x - b.right(), b.x() - w.right) <= 12 * l.size;
            }
        }
        return level;
    }

    static boolean isProse(Box region, List<Line> segments) {
        int longLines = 0;
        int chars = 0;
        for (Line l : segments) {
            if (!region.contains(l.centre(), l.baseline - l.size * 0.3f)) {
                continue;
            }
            chars += l.chars;
            if (l.chars >= 40 && l.words.size() >= 6) {
                longLines++;
            }
        }
        return longLines >= 3 || chars > 350;
    }

    static float runningShare(Box region, List<Line> segments) {
        int all = 0;
        int running = 0;
        for (Line l : segments) {
            int words = 0;
            int chars = 0;
            for (int i = 0; i <= l.words.size(); i++) {
                Word w = i < l.words.size() ? l.words.get(i) : null;
                boolean in = w != null && region.contains(Regions.wordX(w), Regions.wordY(l));
                if (!in || i > 0 && l.gaps[i] != Line.SPACE) {
                    if (words >= 6 && chars >= 40) {
                        running += chars;
                    }
                    words = 0;
                    chars = 0;
                }
                if (in) {
                    all += w.text.length();
                    words++;
                    chars += w.text.length();
                }
            }
        }
        return all == 0 ? 0 : (float) running / all;
    }

    private static final int MAX_CLUSTERED = 20_000;

    static List<Box> cluster(List<Box> boxes, float gap) {
        List<Box> current = new ArrayList<>(new LinkedHashSet<>(boxes));
        if (current.size() > MAX_CLUSTERED) {
            Box all = current.getFirst();
            for (Box b : current) {
                all = all.union(b);
            }
            return List.of(all);
        }
        while (true) {
            List<Box> merged = clusterOnce(current, gap);
            if (merged.size() == current.size()) {
                return merged;
            }
            current = merged;
        }
    }

    private static List<Box> clusterOnce(List<Box> boxes, float gap) {
        int n = boxes.size();
        if (n < 2) {
            return boxes;
        }
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        final float cell = 48f;
        Map<Long, List<Integer>> grid = new HashMap<>();
        for (int i = 0; i < n; i++) {
            Box b = boxes.get(i);
            int x0 = (int) Math.floor((b.x() - gap) / cell);
            int x1 = (int) Math.floor((b.right() + gap) / cell);
            int y0 = (int) Math.floor((b.top() - gap) / cell);
            int y1 = (int) Math.floor((b.bottom() + gap) / cell);
            if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) > 400) {
                for (int j = 0; j < n; j++) {
                    if (j != i && b.near(boxes.get(j), gap)) {
                        union(parent, i, j);
                    }
                }
                continue;
            }
            for (int gx = x0; gx <= x1; gx++) {
                for (int gy = y0; gy <= y1; gy++) {
                    long key = (((long) gx << 32) ^ (gy & 0xFFFFFFFFL)) * 0x9E3779B97F4A7C15L;
                    List<Integer> bucket = grid.computeIfAbsent(key, k -> new ArrayList<>());
                    for (int j : bucket) {
                        if (find(parent, i) != find(parent, j) && b.near(boxes.get(j), gap)) {
                            union(parent, i, j);
                        }
                    }
                    bucket.add(i);
                }
            }
        }
        Map<Integer, Box> roots = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            roots.merge(find(parent, i), boxes.get(i), Box::union);
        }
        return new ArrayList<>(roots.values());
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[ra] = rb;
        }
    }

    private static List<Box> clusterCounted(List<Box> boxes, float gap) {
        return cluster(boxes, gap);
    }
}

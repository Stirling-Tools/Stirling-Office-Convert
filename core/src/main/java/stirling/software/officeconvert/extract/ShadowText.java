package stirling.software.officeconvert.extract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ShadowText {

    private static final float REACH = 0.2f;

    private static final float DRIFT = 0.03f;

    private static final int MAX_CANDIDATES = 16;

    private ShadowText() {}

    private record Run(List<Glyph> glyphs, int text, Glyph first) {}

    static List<Glyph> drop(List<Glyph> glyphs) {
        if (glyphs.size() < 4) {
            return glyphs;
        }
        Map<Long, List<Run>> index = new HashMap<>();
        Set<Glyph> dropped = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Run run : runs(glyphs)) {
            Run twin = twin(index, run);
            if (twin == null) {
                index.computeIfAbsent(key(run, 0, 0), k -> new ArrayList<>(1)).add(run);
            } else if (twin.first.rgb != run.first.rgb) {
                dropped.addAll(twin.glyphs);
                index.get(key(twin, 0, 0)).remove(twin);
                index.computeIfAbsent(key(run, 0, 0), k -> new ArrayList<>(1)).add(run);
            } else {
                if (Math.abs(twin.first.x - run.first.x) > 0.05f
                        || Math.abs(twin.first.baseline - run.first.baseline) > 0.05f) {
                    for (Glyph g : twin.glyphs) {
                        g.bold = true;
                    }
                }
                dropped.addAll(run.glyphs);
            }
        }
        if (dropped.isEmpty()) {
            return glyphs;
        }
        List<Glyph> out = new ArrayList<>(glyphs.size() - dropped.size());
        for (Glyph g : glyphs) {
            if (!dropped.contains(g)) {
                out.add(g);
            }
        }
        return out;
    }

    private static List<Run> runs(List<Glyph> glyphs) {
        List<Glyph> bySeq = new ArrayList<>(glyphs);
        bySeq.sort(Comparator.comparingInt(g -> g.seq));
        List<Run> out = new ArrayList<>();
        List<Glyph> current = new ArrayList<>();
        for (Glyph g : bySeq) {
            if (!current.isEmpty() && !continues(current.getLast(), g)) {
                addRun(out, current);
                current = new ArrayList<>();
            }
            current.add(g);
        }
        addRun(out, current);
        return out;
    }

    private static boolean continues(Glyph prev, Glyph g) {
        return g.rgb == prev.rgb
                && Math.abs(g.size - prev.size) < 0.01f * prev.size
                && Math.abs(g.baseline - prev.baseline) < 0.1f * prev.size
                && g.x > prev.x - 0.1f * prev.size
                && g.x - prev.right() < prev.size;
    }

    private static void addRun(List<Run> out, List<Glyph> glyphs) {
        int text = 1;
        Glyph first = null;
        int printing = 0;
        for (Glyph g : glyphs) {
            if (!g.isSpace()) {
                printing++;
                first = first == null ? g : first;
            }
            text = 31 * text + g.text.hashCode();
        }
        if (printing >= 2 && Float.isFinite(first.x) && Float.isFinite(first.baseline) && first.size > 0) {
            out.add(new Run(glyphs, text, first));
        }
    }

    private static Run twin(Map<Long, List<Run>> index, Run run) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                List<Run> near = index.get(key(run, dx, dy));
                if (near == null) {
                    continue;
                }
                for (int i = 0; i < near.size() && i < MAX_CANDIDATES; i++) {
                    if (shadows(near.get(i), run)) {
                        return near.get(i);
                    }
                }
            }
        }
        return null;
    }

    private static boolean shadows(Run a, Run b) {
        float size = b.first.size;
        if (a.text != b.text || a.glyphs.size() != b.glyphs.size() || Math.abs(a.first.size - size) >= 0.5f) {
            return false;
        }
        float dx = b.first.x - a.first.x;
        float dy = b.first.baseline - a.first.baseline;
        if (Math.abs(dx) > REACH * size || Math.abs(dy) > REACH * size) {
            return false;
        }
        float drift = DRIFT * size + 0.05f;
        for (int i = 0; i < a.glyphs.size(); i++) {
            Glyph p = a.glyphs.get(i);
            Glyph q = b.glyphs.get(i);
            if (!p.text.equals(q.text) || Math.abs(q.x - p.x - dx) > drift
                    || Math.abs(q.baseline - p.baseline - dy) > drift) {
                return false;
            }
        }
        return true;
    }

    private static long key(Run run, int dx, int dy) {
        float cell = run.first.size;
        long cx = (long) Math.floor(run.first.x / cell) + dx;
        long cy = (long) Math.floor(run.first.baseline / cell) + dy;
        return ((cx * 0x9E3779B97F4A7C15L + cy) * 0x9E3779B97F4A7C15L + run.text) * 0x9E3779B97F4A7C15L
                + Math.round(run.first.size);
    }
}

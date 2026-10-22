package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import stirling.software.officeconvert.extract.Glyph;

final class LineGroups {

    private LineGroups() {}

    static List<Line> joinRows(List<Line> segs) {
        segs.sort(Comparator.comparingDouble((Line l) -> l.baseline).thenComparingDouble(l -> l.x));
        List<Line> out = new ArrayList<>();
        int i = 0;
        while (i < segs.size()) {
            int j = i + 1;
            while (j < segs.size()
                    && Math.abs(segs.get(j).baseline - segs.get(i).baseline)
                            < Math.max(1f, segs.get(i).size * 0.2f)) {
                j++;
            }
            out.add(LineBuilder.join(segs.subList(i, j)));
            i = j;
        }
        return out;
    }

    static List<Line> alignedTabs(List<Line> lines) {
        List<float[]> starts = new ArrayList<>();
        for (int li = 0; li < lines.size(); li++) {
            Line l = lines.get(li);
            for (int i = 2; i < l.words.size(); i++) {
                if (wideSpace(l, i)) {
                    starts.add(new float[] {l.words.get(i).x, li});
                }
            }
        }
        if (starts.size() < ALIGNED_LINES) {
            return lines;
        }
        for (Line l : lines) {
            for (int i = 2; i < l.words.size(); i++) {
                if (wideSpace(l, i) && alignedLines(starts, l.words.get(i).x) >= ALIGNED_LINES) {
                    l.gaps[i] = Line.TAB;
                }
            }
        }
        return lines;
    }

    private static boolean wideSpace(Line l, int i) {
        Word prev = l.words.get(i - 1);
        Word w = l.words.get(i);
        float gap = w.x - prev.right;
        return l.gaps[i] == Line.SPACE && gap > ALIGNED_GAP_EM * Math.max(prev.size(), w.size())
                && gap > STANDOUT * typicalSpace(l);
    }

    private static float typicalSpace(Line l) {
        float[] gaps = new float[l.words.size() - 1];
        for (int i = 1; i < l.words.size(); i++) {
            gaps[i - 1] = l.words.get(i).x - l.words.get(i - 1).right;
        }
        Arrays.sort(gaps);
        return gaps[(gaps.length - 1) / 2];
    }

    private static final float STANDOUT = 2f;

    private static int alignedLines(List<float[]> starts, float x) {
        int count = 0;
        float last = -1;
        for (float[] s : starts) {
            if (Math.abs(s[0] - x) <= ALIGN_TOLERANCE && s[1] != last) {
                count++;
                last = s[1];
            }
        }
        return count;
    }

    private static final int ALIGNED_LINES = 3;

    private static final float ALIGNED_GAP_EM = 1.0f;

    private static final float ALIGN_TOLERANCE = 1f;

    static Line fromWords(List<Word> words) {
        List<Word> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble(w -> w.x));
        return Leaders.classifyLeaders(new Line(sorted, new byte[sorted.size()]));
    }

    static List<Line> linesOf(List<Word> words) {
        List<Word> sorted = new ArrayList<>();
        List<Word> scripts = new ArrayList<>();
        for (Word w : words) {
            (w.first().vertAlign != 0 && Float.isNaN(hostBaseline(w)) ? scripts : sorted).add(w);
        }
        sorted.sort(Comparator.comparingDouble((Word w) -> w.first().baseline).thenComparingDouble(w -> w.x));
        List<List<Word>> groups = new ArrayList<>();
        int i = 0;
        while (i < sorted.size()) {
            int j = i + 1;
            float base = sorted.get(i).first().baseline;
            float tol = Math.max(1f, sorted.get(i).size() * 0.3f);
            while (j < sorted.size() && Math.abs(sorted.get(j).first().baseline - base) <= tol) {
                j++;
            }
            groups.add(new ArrayList<>(sorted.subList(i, j)));
            i = j;
        }
        for (Word s : scripts) {
            List<Word> host = hostOf(s, groups);
            if (host != null) {
                host.add(s);
            } else {
                groups.add(new ArrayList<>(List.of(s)));
            }
        }
        groups.sort(Comparator.comparingDouble(g -> g.getFirst().first().baseline));
        List<Line> out = new ArrayList<>();
        for (List<Word> g : groups) {
            out.add(fromWords(g));
        }
        return out;
    }

    private static float hostBaseline(Word w) {
        for (Glyph g : w.glyphs) {
            if (g.vertAlign == 0) {
                return g.baseline;
            }
        }
        return Float.NaN;
    }

    private static List<Word> hostOf(Word s, List<List<Word>> groups) {
        List<Word> best = null;
        float bestGap = Float.MAX_VALUE;
        for (List<Word> g : groups) {
            for (Word w : g) {
                float size = w.size();
                float rise = w.first().baseline - s.first().baseline;
                boolean reaches = s.first().vertAlign > 0
                        ? rise > 0 && rise <= SCRIPT_REACH * size
                        : rise < 0 && -rise <= SCRIPT_REACH * size;
                float gap = Math.max(s.x - w.right, w.x - s.right);
                if (reaches && gap <= SCRIPT_GAP * size && s.size() <= SCRIPT_SIZE * size
                        && s.text.length() <= SCRIPT_CHARS && gap < bestGap) {
                    bestGap = gap;
                    best = g;
                }
            }
        }
        return best;
    }

    private static final float SCRIPT_REACH = 0.62f;

    private static final float SCRIPT_GAP = 0.8f;

    private static final float SCRIPT_SIZE = 0.86f;

    private static final int SCRIPT_CHARS = 4;
}

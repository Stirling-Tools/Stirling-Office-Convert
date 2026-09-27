package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import stirling.software.officeconvert.extract.Glyph;

public final class LineBuilder {

    private static final float SEGMENT_GAP_EM = 0.9f;

    private LineBuilder() {}

    public static List<Line> build(List<Glyph> glyphs) {
        List<Glyph> ink = new ArrayList<>(glyphs.size());
        List<Glyph> spaces = new ArrayList<>();
        for (Glyph g : glyphs) {
            (g.isSpace() ? spaces : ink).add(g);
        }
        if (ink.isEmpty()) {
            return List.of();
        }
        List<Line> segments = new ArrayList<>();
        for (GlyphRows.Row row : GlyphRows.of(ink, spaces)) {
            segments.addAll(segment(row));
        }
        segments.sort(Comparator.comparingDouble((Line l) -> l.baseline).thenComparingDouble(l -> l.x));
        return segments;
    }

    private static List<Line> segment(GlyphRows.Row row) {
        List<Word> words = words(row);
        if (words.isEmpty()) {
            return List.of();
        }
        float drawnSpace = medianSpace(row.spaces);
        float tracking = trackingOf(row.glyphs);
        List<Line> out = new ArrayList<>();
        int start = 0;
        for (int i = 1; i < words.size(); i++) {
            Word prev = words.get(i - 1);
            Word w = words.get(i);
            float size = Math.min(prev.size(), w.size());
            float gap = w.x - prev.right;
            if (gap > SEGMENT_GAP_EM * size + tracking && !isLeaderRun(words, start, i)) {
                out.add(toLine(words.subList(start, i)));
                start = i;
            }
        }
        out.add(toLine(words.subList(start, words.size())));
        for (Line l : out) {
            l.drawnSpace = drawnSpace;
        }
        return out;
    }

    private static float medianSpace(List<Glyph> spaces) {
        if (spaces.isEmpty()) {
            return Float.NaN;
        }
        float[] w = new float[spaces.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = spaces.get(i).width;
        }
        Arrays.sort(w);
        float m = w[w.length / 2];
        return m > 0.5f ? m : Float.NaN;
    }

    private static boolean isLeaderRun(List<Word> words, int start, int i) {
        return Leaders.isLeader(words.get(i - 1).text) || Leaders.isLeader(words.get(i).text);
    }

    private static List<Word> words(GlyphRows.Row row) {
        List<Glyph> glyphs = row.glyphs;
        List<Glyph> spaces = row.spaces;
        spaces.sort(Comparator.comparingDouble((Glyph g) -> g.x));
        List<Word> words = new ArrayList<>();
        List<Glyph> current = new ArrayList<>();
        float tracking = trackingOf(glyphs);
        int si = 0;
        Glyph prev = null;
        for (Glyph g : glyphs) {
            if (prev != null) {
                boolean spaceBetween = false;
                while (si < spaces.size() && spaces.get(si).x < g.x - 0.1f) {
                    Glyph sp = spaces.get(si++);
                    if (sp.x >= prev.x + prev.width * 0.5f) {
                        spaceBetween = true;
                    }
                }
                float gap = g.x - prev.right();
                float size = Math.max(Math.min(prev.size, g.size), 1f);
                float threshold = Math.max(0.1f * size, 0.4f * Math.min(prev.spaceWidth, g.spaceWidth));
                threshold = Math.max(threshold, tracking + 0.1f * size);
                if (prev.vertAlign != g.vertAlign && gap > 0.05f * size) {
                    threshold = Math.min(threshold, 0.18f * size);
                }
                if (ideographic(prev) != ideographic(g)) {
                    threshold = Math.max(threshold, 0.3f * size);
                }
                boolean split = spaceBetween && gap > -0.05f * size || gap > threshold;
                if (split) {
                    words.add(new Word(current));
                    current = new ArrayList<>();
                }
            }
            current.add(g);
            prev = g;
        }
        if (!current.isEmpty()) {
            words.add(new Word(current));
        }
        return words;
    }

    private static boolean ideographic(Glyph g) {
        if (g.text.isEmpty()) {
            return false;
        }
        Character.UnicodeScript script = Character.UnicodeScript.of(g.text.codePointAt(0));
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL;
    }

    private static float trackingOf(List<Glyph> glyphs) {
        if (glyphs.size() < 8) {
            return 0f;
        }
        float[] gaps = new float[glyphs.size() - 1];
        for (int i = 1; i < glyphs.size(); i++) {
            gaps[i - 1] = glyphs.get(i).x - glyphs.get(i - 1).right();
        }
        Arrays.sort(gaps);
        float median = gaps[gaps.length / 2];
        return median > 0.05f * glyphs.getFirst().size ? median : 0f;
    }

    private static Line toLine(List<Word> words) {
        List<Word> ws = new ArrayList<>(words);
        byte[] gaps = new byte[ws.size()];
        return new Line(ws, gaps);
    }

    public static Line join(List<Line> segments) {
        if (segments.size() == 1) {
            return Leaders.classifyLeaders(segments.getFirst());
        }
        List<Line> sorted = new ArrayList<>(segments);
        sorted.sort(Comparator.comparingDouble(l -> l.x));
        List<Word> words = new ArrayList<>();
        List<Byte> gaps = new ArrayList<>();
        for (Line seg : sorted) {
            for (int i = 0; i < seg.words.size(); i++) {
                Word w = seg.words.get(i);
                if (words.isEmpty()) {
                    gaps.add(Line.SPACE);
                } else if (i == 0) {
                    Word prev = words.getLast();
                    float size = Math.max(prev.size(), w.size());
                    gaps.add(w.x - prev.right > Leaders.TAB_GAP_EM * size ? Line.TAB : Line.SPACE);
                } else {
                    gaps.add(seg.gaps[i]);
                }
                words.add(w);
            }
        }
        byte[] g = new byte[gaps.size()];
        for (int i = 0; i < g.length; i++) {
            g[i] = gaps.get(i);
        }
        Line joined = new Line(words, g);
        joined.drawnSpace = sorted.getFirst().drawnSpace;
        return Leaders.classifyLeaders(joined);
    }
}

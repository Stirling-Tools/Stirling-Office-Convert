package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;

public final class Line {

    public static final byte SPACE = 0;
    public static final byte TAB = 1;
    public static final byte LEADER = 2;

    public final List<Word> words;
    public final byte[] gaps;
    public char[] leaders;
    public float drawnSpace = Float.NaN;
    public final float baseline;
    public final float x;
    public final float right;
    public final float top;
    public final float bottom;
    public final float size;

    public final FontInfo font;
    public final boolean bold;
    public final boolean italic;
    public final int rgb;
    public final int chars;

    public Line(List<Word> words, byte[] gaps) {
        this.words = words;
        this.gaps = gaps;
        float lo = Float.MAX_VALUE;
        float hi = -Float.MAX_VALUE;
        float t = Float.MAX_VALUE;
        float b = -Float.MAX_VALUE;
        Map<Float, Integer> sizes = new HashMap<>();
        Map<FontInfo, Integer> fonts = new HashMap<>();
        Map<Integer, Integer> colours = new HashMap<>();
        int boldChars = 0;
        int italicChars = 0;
        int n = 0;
        float baseSum = 0;
        int baseCount = 0;
        for (Word w : words) {
            lo = Math.min(lo, w.x);
            hi = Math.max(hi, w.right);
            for (Glyph g : w.glyphs) {
                int len = g.text.length();
                n += len;
                if (g.vertAlign == 0) {
                    sizes.merge(Math.round(g.size * 2f) / 2f, len, Integer::sum);
                    baseSum += g.baseline;
                    baseCount++;
                    t = Math.min(t, g.top());
                    b = Math.max(b, g.bottom());
                }
                fonts.merge(g.font, len, Integer::sum);
                colours.merge(g.rgb, len, Integer::sum);
                if (g.bold) {
                    boldChars += len;
                }
                if (g.italic) {
                    italicChars += len;
                }
            }
        }
        if (baseCount == 0) {
            for (Word w : words) {
                for (Glyph g : w.glyphs) {
                    baseSum += g.baseline;
                    baseCount++;
                    t = Math.min(t, g.top());
                    b = Math.max(b, g.bottom());
                    sizes.merge(Math.round(g.size * 2f) / 2f, g.text.length(), Integer::sum);
                }
            }
        }
        this.x = lo;
        this.right = hi;
        this.top = t;
        this.bottom = b;
        this.baseline = baseSum / Math.max(1, baseCount);
        this.size = mode(sizes, 10f);
        this.font = mode(fonts, words.getFirst().first().font);
        this.rgb = mode(colours, 0);
        this.chars = n;
        this.bold = n > 0 && boldChars * 2 > n;
        this.italic = n > 0 && italicChars * 2 > n;
    }

    static <K> K mode(Map<K, Integer> counts, K fallback) {
        K best = fallback;
        int bestCount = -1;
        for (Map.Entry<K, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    public float width() {
        return right - x;
    }

    public float centre() {
        return (x + right) / 2f;
    }

    public String text() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) {
                sb.append(gaps[i] == SPACE ? " " : "\t");
            }
            sb.append(words.get(i).text);
        }
        return sb.toString();
    }

    public boolean hasTabs() {
        for (int i = 1; i < gaps.length; i++) {
            if (gaps[i] != SPACE) {
                return true;
            }
        }
        return false;
    }

    public Line slice(int from, int to) {
        List<Word> sub = new ArrayList<>(words.subList(from, to));
        byte[] g = new byte[to - from];
        for (int i = 1; i < g.length; i++) {
            g[i] = gaps[from + i];
        }
        Line l = new Line(sub, g);
        l.drawnSpace = drawnSpace;
        return l;
    }

    @Override
    public String toString() {
        return text();
    }

    public boolean sentenceSpace(int wi) {
        Word prev = words.get(wi - 1);
        char end = prev.text.charAt(prev.text.length() - 1);
        if (end != '.' && end != '?' && end != '!' && end != ':') {
            return false;
        }
        float gap = words.get(wi).x - prev.right;
        List<Float> others = new ArrayList<>();
        for (int i = 1; i < words.size(); i++) {
            Word a = words.get(i - 1);
            char c = a.text.charAt(a.text.length() - 1);
            if (i != wi && gaps[i] == Line.SPACE && c != '.' && c != '?' && c != '!' && c != ':') {
                others.add(words.get(i).x - a.right);
            }
        }
        float usual = prev.last().spaceWidth;
        if (others.size() >= 2) {
            others.sort(null);
            usual = Math.max(usual * 0.5f, others.get(others.size() / 2));
        }
        return gap >= SENTENCE_GAP * usual && gap >= SENTENCE_GAP * 0.8f * prev.last().spaceWidth;
    }

    private static final float SENTENCE_GAP = 1.75f;
}

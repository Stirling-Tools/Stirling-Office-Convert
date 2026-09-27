package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.PageData;

public final class DocStats {

    public record Tier(float size, boolean bold, int lines, int firstPage) {}

    private final Map<Float, Integer> sizeChars = new HashMap<>();
    private final Map<FontInfo, Integer> fontChars = new HashMap<>();
    private final Map<Float, Map<Float, Integer>> pitches = new HashMap<>();
    private final Map<String, Integer> tierLines = new HashMap<>();
    private final Map<String, Integer> tierFirstPage = new HashMap<>();
    private final Map<String, Integer> tierChars = new HashMap<>();
    private final PageFrames frames = new PageFrames();
    final HeaderFooter headerFooter = new HeaderFooter();
    private int totalChars;
    private int boldChars;

    private int wrappedLines;
    private int hyphenatedLines;

    public boolean autoHyphenated() {
        return hyphenatedLines >= 3 && hyphenatedLines > wrappedLines * 0.02f;
    }

    public float bodySize = 11f;
    public FontInfo bodyFont = FontInfo.DEFAULT;
    public boolean bodyBold;
    public List<Tier> tiers = List.of();

    private final Map<String, int[]> fontUse = new HashMap<>();

    public boolean usedOnlyAlone(FontInfo font) {
        int[] u = fontUse.get(font.postScriptName());
        return u == null || u[1] >= 0.9f * u[0];
    }

    public void add(PageData page, List<Line> segments) {
        headerFooter.addPage(page, segments);
        for (Line l : segments) {
            for (Word w : l.words) {
                for (stirling.software.officeconvert.extract.Glyph g : w.glyphs) {
                    int[] u = fontUse.computeIfAbsent(g.font.postScriptName(), k -> new int[2]);
                    u[0]++;
                    if (w.text.length() <= 2) {
                        u[1]++;
                    }
                }
            }
        }
        for (Line l : segments) {
            sizeChars.merge(l.size, l.chars, Integer::sum);
            fontChars.merge(l.font, l.chars, Integer::sum);
            totalChars += l.chars;
            if (l.bold) {
                boldChars += l.chars;
            }
            String key = tierKey(l.size, l.bold);
            tierLines.merge(key, 1, Integer::sum);
            tierChars.merge(key, l.chars, Integer::sum);
            tierFirstPage.merge(key, page.index(), Math::min);
        }
        float minX = Float.MAX_VALUE;
        float maxRight = -Float.MAX_VALUE;
        for (Line l : segments) {
            minX = Math.min(minX, l.x);
            maxRight = Math.max(maxRight, l.right);
        }
        float wrapZone = 0.12f * (maxRight - minX);
        List<Line> sorted = new ArrayList<>(segments);
        sorted.sort(Comparator.comparingDouble((Line l) -> Math.round(l.x)).thenComparingDouble(l -> l.baseline));
        for (int i = 1; i < sorted.size(); i++) {
            Line a = sorted.get(i - 1);
            Line b = sorted.get(i);
            if (Math.abs(a.x - b.x) > 2f || Math.abs(a.size - b.size) > 0.3f
                    || a.right < maxRight - wrapZone || a.words.size() < 4) {
                continue;
            }
            float pitch = b.baseline - a.baseline;
            if (pitch > a.size * 0.8f && pitch < a.size * 2.6f) {
                pitches.computeIfAbsent(a.size, k -> new HashMap<>())
                        .merge(Math.round(pitch * 4f) / 4f, 1, Integer::sum);
                wrappedLines++;
                String last = a.words.getLast().text;
                String next = b.words.getFirst().text;
                if (last.length() > 2 && last.endsWith("-") && Character.isLetter(last.charAt(last.length() - 2))
                        && Character.isLowerCase(next.charAt(0))) {
                    hyphenatedLines++;
                }
            }
        }
        frames.add(page, segments);
    }

    public PageFrame frame(float width, float height) {
        return frames.frame(width, height);
    }

    private static String tierKey(float size, boolean bold) {
        return size + (bold ? "b" : "r");
    }

    public void finish(int pageCount) {
        if (!sizeChars.isEmpty()) {
            bodySize = Line.mode(sizeChars, 11f);
        }
        if (!fontChars.isEmpty()) {
            Map<FontInfo, Integer> bodyFonts = new HashMap<>();
            fontChars.forEach(bodyFonts::put);
            bodyFont = Line.mode(bodyFonts, FontInfo.DEFAULT);
        }
        bodyBold = totalChars > 0 && boldChars * 2 > totalChars;
        headerFooter.finish(pageCount);
        frames.compute(headerFooter, size -> lineHeightFor((float) size));

        List<Tier> candidates = new ArrayList<>();
        for (Map.Entry<String, Integer> e : tierLines.entrySet()) {
            String key = e.getKey();
            boolean bold = key.endsWith("b");
            float size = Float.parseFloat(key.substring(0, key.length() - 1));
            int chars = tierChars.get(key);
            boolean bigger = size >= bodySize * 1.12f;
            boolean boldBody = bold && !bodyBold && Math.abs(size - bodySize) < 0.6f;
            if ((bigger || boldBody) && chars < totalChars * 0.25f) {
                candidates.add(new Tier(size, bold, e.getValue(), tierFirstPage.get(key)));
            }
        }
        candidates.sort(
                Comparator.comparingDouble((Tier t) -> -t.size()).thenComparing(t -> !t.bold()));
        tiers = candidates.size() > 6 ? candidates.subList(0, 6) : candidates;
    }

    public int headingLevel(float size, boolean bold) {
        for (int i = 0; i < tiers.size(); i++) {
            Tier t = tiers.get(i);
            if (Math.abs(t.size() - size) < 0.3f && t.bold() == bold) {
                return i + 1;
            }
        }
        if (size >= bodySize * 1.12f) {
            int level = 1;
            for (Tier t : tiers) {
                if (t.size() > size + 0.3f) {
                    level++;
                }
            }
            return Math.min(level, 6);
        }
        return 0;
    }

    public static final float WORD_BASELINE = 0.8f;

    public float lineHeightFor(float size) {
        return Math.clamp(pitchFor(size), size * 0.95f, size * 1.25f);
    }

    public float pitchFor(float size) {
        Map<Float, Integer> p = pitches.get(size);
        if (p != null && sampleCount(p) >= 2) {
            return Line.mode(p, size * 1.2f);
        }
        Map<Float, Integer> body = pitches.get(bodySize);
        if (body != null && sampleCount(body) >= 3) {
            float ratio = Line.mode(body, bodySize * 1.2f) / bodySize;
            return size * Math.min(ratio, 1.5f);
        }
        return size * 1.2f;
    }

    private static int sampleCount(Map<Float, Integer> p) {
        int n = 0;
        for (int v : p.values()) {
            n += v;
        }
        return n;
    }

    public HeaderFooter headerFooterInfo() {
        return headerFooter;
    }

    public TreeMap<Float, Integer> sizes() {
        return new TreeMap<>(sizeChars);
    }
}

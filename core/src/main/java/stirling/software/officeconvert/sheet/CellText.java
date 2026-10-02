package stirling.software.officeconvert.sheet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.LogicalOrder;
import stirling.software.officeconvert.layout.ParaDraft;
import stirling.software.officeconvert.layout.Word;

record CellText(
        String text,
        float size,
        boolean bold,
        boolean italic,
        boolean underline,
        boolean strike,
        int rgb,
        boolean scripted,
        float width,
        int lines) {

    static final CellText EMPTY = new CellText("", 0, false, false, false, false, -1, false, 0, 0);

    static CellText of(List<ParaDraft> paras, boolean dropHyphens) {
        if (paras.isEmpty()) {
            return EMPTY;
        }
        StringBuilder sb = new StringBuilder();
        Map<Float, Integer> sizes = new HashMap<>();
        Map<Integer, Integer> colours = new HashMap<>();
        int chars = 0;
        int bold = 0;
        int italic = 0;
        int underline = 0;
        int strike = 0;
        boolean scripted = false;
        float width = 0;
        int lines = 0;
        for (ParaDraft p : paras) {
            lines += p.lines.size();
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            appendParagraph(sb, p, dropHyphens);
            for (Line l : p.lines) {
                width += l.right - l.x + (width > 0 ? l.size * 0.28f : 0);
                for (Word w : l.words) {
                    for (Glyph g : w.glyphs) {
                        int n = g.text.length();
                        chars += n;
                        sizes.merge(Math.round(g.size * 2f) / 2f, n, Integer::sum);
                        colours.merge(g.rgb, n, Integer::sum);
                        bold += g.bold ? n : 0;
                        italic += g.italic ? n : 0;
                        underline += g.underline ? n : 0;
                        strike += g.strike ? n : 0;
                        scripted |= g.vertAlign != 0 || g.footnote >= 0;
                    }
                }
            }
        }
        String text = clean(sb.toString());
        if (text.isEmpty() || chars == 0) {
            return EMPTY;
        }
        int rgb = mode(colours, 0);
        return new CellText(text, mode(sizes, 10f), bold * 2 > chars, italic * 2 > chars, underline * 2 > chars,
                strike * 2 > chars, rgb == 0 ? -1 : rgb & 0xFFFFFF, scripted, width, lines);
    }

    private static void appendParagraph(StringBuilder sb, ParaDraft p, boolean dropHyphens) {
        int rtlLines = 0;
        for (Line line : p.lines) {
            rtlLines += LogicalOrder.rtlBase(line) ? 1 : 0;
        }
        boolean rtl = p.rtl || rtlLines * 2 > p.lines.size();
        for (int i = 0; i < p.lines.size(); i++) {
            Line line = p.lines.get(i);
            String text = LogicalText.of(line, 0, rtl);
            String marker = line.words.isEmpty() ? "" : line.words.getFirst().text;
            if (i == 0 && p.marker != null && p.marker.isBullet() && !marker.isEmpty() && text.startsWith(marker)) {
                text = bullet(marker) + text.substring(marker.length());
            }
            if (i > 0) {
                if (p.hardBreaks.get(i)) {
                    sb.append('\n');
                } else {
                    join(sb, text, dropHyphens);
                }
            }
            sb.append(text);
        }
    }

    private static void join(StringBuilder sb, String next, boolean dropHyphens) {
        if (sb.isEmpty() || next.isEmpty()) {
            return;
        }
        char last = sb.charAt(sb.length() - 1);
        char first = next.charAt(0);
        boolean hyphen = last == '-' || last == '\u00AD' || last == '‐';
        if (hyphen && Character.isLowerCase(first)) {
            if (dropHyphens || last == '\u00AD') {
                sb.setLength(sb.length() - 1);
            }
            return;
        }
        if (hyphen || isCjk(last) && isCjk(first) || last == '\n') {
            return;
        }
        sb.append(' ');
    }

    private static String bullet(String marker) {
        if (marker.isEmpty()) {
            return marker;
        }
        char c = marker.charAt(0);
        return c >= 0xE000 && c <= 0xF8FF ? "•" : marker;
    }

    private static String clean(String s) {
        return s.replace("\u00AD", "").strip();
    }

    private static boolean isCjk(char c) {
        return c >= 0x2E80 && c <= 0x9FFF || c >= 0xAC00 && c <= 0xD7A3 || c >= 0xF900 && c <= 0xFAFF
                || c >= 0xFF00 && c <= 0xFF60 || c >= 0x3000 && c <= 0x303F;
    }

    private static <K> K mode(Map<K, Integer> counts, K fallback) {
        K best = fallback;
        int n = -1;
        for (Map.Entry<K, Integer> e : counts.entrySet()) {
            if (e.getValue() > n || e.getValue() == n && e.getKey().hashCode() < best.hashCode()) {
                best = e.getKey();
                n = e.getValue();
            }
        }
        return best;
    }
}

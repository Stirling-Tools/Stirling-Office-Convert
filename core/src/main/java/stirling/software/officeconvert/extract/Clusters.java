package stirling.software.officeconvert.extract;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class Clusters {

    private static final String PRE_BASE = "\u093F\u094E\u09BF\u09C7\u09C8\u0A3F\u0ABF\u0B47\u0BC6\u0BC7\u0BC8\u0D46\u0D47\u0D48"
            + "\u0DD9\u0DDA\u0DDB\u1031\u1084\u17C1\u17C2\u17C3";

    private static final String REPH = "\u0930\u094D|\u09B0\u09CD|\u0AB0\u0ACD|\u0B30\u0B4D|\u0C30\u0C4D|\u0CB0\u0CCD";

    private static final String VIRAMA = "\u094D\u09CD\u0A4D\u0ACD\u0B4D\u0BCD\u0C4D\u0CCD\u0D4D\u0DCA\u1039\u17D2";

    private Clusters() {}

    static List<Glyph> join(List<Glyph> glyphs) {
        boolean marks = false;
        boolean indic = false;
        for (Glyph g : glyphs) {
            marks |= GlyphCollector.combiningMarks(g.text);
            indic |= preBase(g.text) || reph(g.text);
        }
        List<Glyph> out = marks ? attachMarks(glyphs) : glyphs;
        return indic ? reorderIndic(out) : out;
    }

    private static List<Glyph> attachMarks(List<Glyph> glyphs) {
        List<Glyph> bases = new ArrayList<>();
        float widest = 0;
        for (Glyph g : glyphs) {
            if (!g.isSpace() && !GlyphCollector.combiningMarks(g.text)) {
                bases.add(g);
                widest = Math.max(widest, g.width);
            }
        }
        bases.sort(Comparator.comparingDouble((Glyph g) -> g.x));
        List<Glyph> marks = new ArrayList<>();
        List<List<Glyph>> near = new ArrayList<>();
        int[] after = new int[2];
        int[] before = new int[2];
        for (Glyph m : glyphs) {
            if (!GlyphCollector.combiningMarks(m.text)) {
                continue;
            }
            List<Glyph> candidates = candidates(m, bases, widest);
            marks.add(m);
            near.add(candidates);
            Glyph clear = clearBase(m, candidates);
            if (clear != null && Math.abs(clear.seq - m.seq) <= 3) {
                int side = LogicalRtl.of(clear.text) ? 1 : 0;
                if (clear.seq < m.seq) {
                    after[side]++;
                } else {
                    before[side]++;
                }
            }
        }
        Map<Glyph, List<Glyph>> attached = new IdentityHashMap<>();
        for (int i = 0; i < marks.size(); i++) {
            Glyph base = baseOf(marks.get(i), near.get(i), after, before);
            if (base != null) {
                attached.computeIfAbsent(base, k -> new ArrayList<>()).add(marks.get(i));
            }
        }
        if (attached.isEmpty()) {
            return glyphs;
        }
        Map<Glyph, Glyph> merged = new IdentityHashMap<>();
        for (Map.Entry<Glyph, List<Glyph>> e : attached.entrySet()) {
            Glyph base = e.getKey();
            List<Glyph> on = e.getValue();
            on.sort(Comparator.comparingInt((Glyph m) -> Math.abs(m.seq - base.seq)).thenComparingInt(m -> m.seq));
            StringBuilder text = new StringBuilder(base.text);
            for (Glyph m : on) {
                text.append(m.text);
                merged.put(m, null);
            }
            merged.put(base, copy(base, Normalizer.normalize(text, Normalizer.Form.NFC), base.x, base.width));
        }
        List<Glyph> out = new ArrayList<>(glyphs.size());
        for (Glyph g : glyphs) {
            if (!merged.containsKey(g)) {
                out.add(g);
            } else if (merged.get(g) != null) {
                out.add(merged.get(g));
            }
        }
        return out;
    }

    private static List<Glyph> candidates(Glyph m, List<Glyph> bases, float widest) {
        List<Glyph> out = new ArrayList<>();
        for (int i = firstFrom(bases, m.x - widest - m.size); i < bases.size() && bases.get(i).x <= m.right() + m.size * 0.15f; i++) {
            Glyph b = bases.get(i);
            if (Math.abs(b.baseline - m.baseline) <= 0.75f * Math.max(b.size, m.size) && overlap(m, b) >= -0.15f * b.size) {
                out.add(b);
            }
        }
        return out;
    }

    private static float overlap(Glyph m, Glyph b) {
        return Math.min(m.right(), b.right()) - Math.max(m.x, b.x);
    }

    private static Glyph clearBase(Glyph m, List<Glyph> candidates) {
        Glyph clear = null;
        for (Glyph b : candidates) {
            float o = overlap(m, b);
            if (o >= 0.5f * Math.min(m.width, b.width)) {
                if (clear != null) {
                    return null;
                }
                clear = b;
            } else if (o > 0) {
                return null;
            }
        }
        return clear;
    }

    private static Glyph baseOf(Glyph m, List<Glyph> candidates, int[] after, int[] before) {
        Glyph widest = null;
        float best = -Float.MAX_VALUE;
        Glyph prev = null;
        Glyph next = null;
        for (Glyph b : candidates) {
            float o = overlap(m, b);
            if (o > best) {
                widest = b;
                best = o;
            }
            if (b.seq < m.seq && m.seq - b.seq <= 3 && (prev == null || b.seq > prev.seq)) {
                prev = b;
            }
            if (b.seq > m.seq && b.seq - m.seq <= 3 && (next == null || b.seq < next.seq)) {
                next = b;
            }
        }
        if (widest == null || prev == null && next == null) {
            return widest;
        }
        int side = LogicalRtl.of(widest.text) ? 1 : 0;
        if (after[side] + before[side] == 0) {
            return widest;
        }
        Glyph byOrder = after[side] >= before[side] ? prev : next;
        return byOrder != null ? byOrder : widest;
    }

    private static int firstFrom(List<Glyph> sorted, float x) {
        int lo = 0;
        int hi = sorted.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted.get(mid).x < x) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static List<Glyph> reorderIndic(List<Glyph> glyphs) {
        List<Glyph> order = new ArrayList<>(glyphs);
        order.sort(Comparator.comparingDouble((Glyph g) -> Math.round(g.baseline / Math.max(1f, g.size * 0.3f)))
                .thenComparingDouble(g -> g.x));
        List<Glyph> rows = new ArrayList<>(order);
        boolean changed = false;
        for (int i = 0; i < rows.size(); i++) {
            Glyph r = rows.get(i);
            if (r != null && reph(r.text) && r.width <= 0.45f * r.size) {
                changed |= moveReph(rows, i);
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            Glyph p = rows.get(i);
            if (p != null && preBase(p.text)) {
                changed |= movePreBase(rows, i);
            }
        }
        if (!changed) {
            return glyphs;
        }
        Map<Glyph, Integer> at = new IdentityHashMap<>();
        for (int i = 0; i < order.size(); i++) {
            at.put(order.get(i), i);
        }
        List<Glyph> out = new ArrayList<>(glyphs.size());
        for (Glyph g : glyphs) {
            Glyph r = rows.get(at.get(g));
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    private static boolean moveReph(List<Glyph> rows, int i) {
        Glyph r = rows.get(i);
        int end = step(rows, i, -1);
        if (end < 0 || !sameRow(rows.get(end), r) || !startsWithLetter(rows.get(end).text, r.text)
                || rows.get(end).right() < r.x - 0.3f * r.size) {
            return false;
        }
        int start = end;
        int before = step(rows, start, -1);
        while (before >= 0 && sameRow(rows.get(before), r) && endsWithVirama(rows.get(before).text)
                && rows.get(start).x - rows.get(before).right() < 0.3f * r.size) {
            start = before;
            before = step(rows, start, -1);
        }
        Glyph main = rows.get(end);
        StringBuilder text = new StringBuilder(r.text);
        float lo = r.x;
        float hi = r.right();
        for (int k = start; k <= end; k++) {
            Glyph g = rows.get(k);
            if (g != null) {
                text.append(g.text);
                lo = Math.min(lo, g.x);
                hi = Math.max(hi, g.right());
                rows.set(k, null);
            }
        }
        rows.set(i, copy(main, text.toString(), lo, hi - lo));
        return true;
    }

    private static boolean movePreBase(List<Glyph> rows, int i) {
        Glyph p = rows.get(i);
        List<Glyph> pre = new ArrayList<>(List.of(p));
        int j = step(rows, i, 1);
        while (j >= 0 && preBase(rows.get(j).text) && sameRow(rows.get(j), p) && rows.get(j).x - pre.getLast().right() < 0.4f * p.size) {
            pre.add(rows.get(j));
            j = step(rows, j, 1);
        }
        if (j < 0 || !sameRow(rows.get(j), p) || rows.get(j).x - pre.getLast().right() > 0.4f * p.size
                || !startsWithLetter(rows.get(j).text, p.text)) {
            return false;
        }
        for (int k = step(rows, i, 1); k >= 0 && k < j; k = step(rows, k, 1)) {
            rows.set(k, null);
        }
        int end = j;
        while (endsWithVirama(rows.get(end).text)) {
            int k = step(rows, end, 1);
            if (k < 0 || !sameRow(rows.get(k), p) || rows.get(k).x - rows.get(end).right() > 0.3f * p.size) {
                break;
            }
            end = k;
        }
        Glyph main = rows.get(j);
        StringBuilder text = new StringBuilder();
        float hi = p.right();
        for (int k = j; k <= end; k++) {
            Glyph g = rows.get(k);
            if (g != null) {
                text.append(g.text);
                hi = Math.max(hi, g.right());
                rows.set(k, null);
            }
        }
        String cluster = text.toString();
        int first = firstConsonant(cluster);
        int stem = stem(cluster, first);
        StringBuilder medials = new StringBuilder();
        StringBuilder vowels = new StringBuilder();
        for (Glyph g : pre) {
            (medial(g.text) ? medials : vowels).append(g.text);
        }
        StringBuilder out = new StringBuilder(cluster.substring(0, first)).append(medials).append(cluster, first, stem)
                .append(vowels);
        int after = step(rows, end, 1);
        if (!vowels.isEmpty() && after >= 0 && sameRow(rows.get(after), p) && composes(pre.getLast().text, rows.get(after).text)
                && rows.get(after).x - hi < 0.3f * p.size) {
            out.append(rows.get(after).text);
            hi = Math.max(hi, rows.get(after).right());
            rows.set(after, null);
        }
        out.append(cluster, stem, cluster.length());
        rows.set(i, copy(main, Normalizer.normalize(out, Normalizer.Form.NFC), p.x, hi - p.x));
        return true;
    }

    private static int firstConsonant(String s) {
        int i = s.isEmpty() ? 0 : Character.charCount(s.codePointAt(0));
        while (i < s.length() && (nukta(s.charAt(i)) || s.charAt(i) == '\u103B')) {
            i++;
        }
        return i;
    }

    private static int stem(String s, int from) {
        int i = from;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (VIRAMA.indexOf(c) >= 0 && i + 1 < s.length() && Character.isLetter(s.codePointAt(i + 1))) {
                i += 1 + Character.charCount(s.codePointAt(i + 1));
            } else if (nukta(c) || c >= '\u103B' && c <= '\u103E' || c == '\u200C' || c == '\u200D' || VIRAMA.indexOf(c) >= 0) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    private static boolean nukta(char c) {
        return c == '\u093C' || c == '\u09BC' || c == '\u0A3C' || c == '\u0ABC' || c == '\u0B3C' || c == '\u0CBC';
    }

    private static int step(List<Glyph> rows, int i, int dir) {
        for (int k = i + dir; k >= 0 && k < rows.size(); k += dir) {
            if (rows.get(k) != null) {
                return k;
            }
        }
        return -1;
    }

    private static boolean sameRow(Glyph a, Glyph b) {
        return Math.abs(a.baseline - b.baseline) <= 0.3f * Math.max(a.size, b.size);
    }

    private static final class LogicalRtl {

        static boolean of(String s) {
            for (int i = 0; i < s.length(); i++) {
                byte d = Character.getDirectionality(s.charAt(i));
                if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                    return true;
                }
            }
            return false;
        }
    }

    static boolean preBase(String text) {
        return text.length() == 1 && PRE_BASE.indexOf(text.charAt(0)) >= 0 || medial(text);
    }

    private static boolean medial(String text) {
        return text.equals("\u103C") || text.equals("\u17D2\u179A");
    }

    static boolean reph(String text) {
        return text.length() == 2 && REPH.contains(text);
    }

    private static boolean startsWithLetter(String text, String like) {
        if (text.isEmpty()) {
            return false;
        }
        int cp = text.codePointAt(0);
        return Character.isLetter(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.of(like.codePointAt(0));
    }

    private static boolean endsWithVirama(String text) {
        return !text.isEmpty() && VIRAMA.indexOf(text.charAt(text.length() - 1)) >= 0;
    }

    private static boolean composes(String preBase, String next) {
        return next.codePointCount(0, next.length()) == 1
                && Normalizer.normalize(preBase + next, Normalizer.Form.NFC).length() == 1;
    }

    static Glyph copy(Glyph g, String text, float x, float width) {
        Glyph c = new Glyph(text, x, width, g.baseline, g.size, g.ascent, g.descent, g.font, g.rgb, g.seq, g.spaceWidth,
                g.bold, g.italic);
        c.underline = g.underline;
        c.strike = g.strike;
        c.highlightRgb = g.highlightRgb;
        c.link = g.link;
        c.footnote = g.footnote;
        c.vertAlign = g.vertAlign;
        c.hscale = g.hscale;
        c.icon = g.icon;
        return c;
    }
}

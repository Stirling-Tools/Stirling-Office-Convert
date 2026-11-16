package stirling.software.officeconvert.layout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics.Rule;
import stirling.software.officeconvert.extract.PageGraphics.VectorMark;

// TeX sets digits, operators and function names in the body font; grow math-font seeds over them.
final class TexMathGrowth {

    private static final String OPERATORS = "+-−=<>≤≥≠±∓×·÷/*:|!′'";
    private static final String BRACKETS = "()[]{}";
    private static final String PUNCTUATION = ",.;";
    private static final Set<String> FUNCTIONS = Set.of("lim", "liminf", "limsup", "sup", "inf", "max", "min", "arg",
            "argmax", "argmin", "det", "dim", "ker", "deg", "gcd", "lcm", "hom", "exp", "log", "ln", "lg", "sin", "cos",
            "tan", "cot", "sec", "csc", "sinh", "cosh", "tanh", "coth", "arcsin", "arccos", "arctan", "Pr", "mod", "tr",
            "Tr", "diag", "rank", "sgn", "erf", "Var", "Cov", "softmax");
    private static final Set<String> PROSE = Set.of("if", "otherwise", "and", "or", "for", "else", "when", "where",
            "with", "then", "s.t.");
    private static final float TIGHT = 0.15f;
    private static final float SPACED = 0.4f;
    private static final float GLUED = 0.05f;
    private static final float QUAD = 0.6f;
    private static final float FAR = 2.5f;
    private static final float SCRIPT = 0.85f;
    private static final int MAX_CANDIDATES = 20_000;
    private static final int MAX_BARS = 2_000;

    private record Bar(Object source, float x, float right, float y) {}

    private TexMathGrowth() {}

    static Set<Glyph> absorb(List<Glyph> glyphs, Set<Glyph> seeds, List<Rule> rules, List<Fill> fills) {
        Set<Glyph> grown = Collections.newSetFromMap(new IdentityHashMap<>());
        if (seeds.isEmpty()) {
            return grown;
        }
        float cell = 0;
        for (Glyph g : seeds) {
            cell = Math.max(cell, g.size);
        }
        cell = Math.max(cell * 2f, 4f);
        Map<Long, List<Glyph>> grid = new HashMap<>();
        int count = 0;
        for (Glyph g : glyphs) {
            if ((seeds.contains(g) || candidate(g)) && count++ < MAX_CANDIDATES) {
                grid.computeIfAbsent(key((int) Math.floor(g.x / cell), (int) Math.floor(g.baseline / cell)),
                        k -> new ArrayList<>()).add(g);
            }
        }
        Map<Glyph, List<Glyph>> words = words(grid);
        List<Bar> bars = bars(rules, fills);
        if (bars.size() > MAX_BARS) {
            bars = List.of();
        }
        Set<Bar> usedBars = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Glyph> members = Collections.newSetFromMap(new IdentityHashMap<>());
        members.addAll(seeds);
        Deque<Glyph> queue = new ArrayDeque<>(seeds);
        while (!queue.isEmpty()) {
            Glyph m = queue.poll();
            int cx = (int) Math.floor(m.x / cell);
            int cy = (int) Math.floor(m.baseline / cell);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (Glyph c : grid.getOrDefault(key(cx + dx, cy + dy), List.of())) {
                        if (members.contains(c)) {
                            continue;
                        }
                        List<Glyph> word = words.getOrDefault(c, List.of(c));
                        if (joins(m, c, word, members) && (word.size() < 2 || !PROSE.contains(text(word))
                                || clear(grid, cell, m, word))) {
                            for (Glyph w : word) {
                                if (members.add(w)) {
                                    grown.add(w);
                                    queue.add(w);
                                }
                            }
                        }
                    }
                }
            }
            for (Bar b : bars) {
                if (usedBars.contains(b)) {
                    continue;
                }
                boolean touched = touches(b, m);
                if (!touched && !(operator(m.text) && besideBar(b, m))) {
                    continue;
                }
                List<Glyph> near = underOrOver(b, grid, cell);
                if (touched ? !spanned(b, m, grid, cell) : !fraction(b, near)) {
                    continue;
                }
                usedBars.add(b);
                near.addAll(beside(b, grid, cell));
                for (Glyph c : near) {
                    List<Glyph> word = words.getOrDefault(c, List.of(c));
                    if (!members.contains(c) && mathLike(word)) {
                        for (Glyph w : word) {
                            if (members.add(w)) {
                                grown.add(w);
                                queue.add(w);
                            }
                        }
                    }
                }
            }
        }
        return grown;
    }

    private static boolean clear(Map<Long, List<Glyph>> grid, float cell, Glyph m, List<Glyph> word) {
        float left = m.right();
        float right = wordLeft(word);
        float y = word.getFirst().baseline;
        int cy = (int) Math.floor(y / cell);
        for (int x = (int) Math.floor(left / cell); x <= (int) Math.floor(right / cell); x++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (Glyph g : grid.getOrDefault(key(x, cy + dy), List.of())) {
                    if (g != m && g.x >= left - 0.01f && g.right() <= right + 0.01f
                            && Math.abs(g.baseline - y) <= 0.3f * g.size) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static List<Glyph> beside(Bar b, Map<Long, List<Glyph>> grid, float cell) {
        List<Glyph> out = new ArrayList<>();
        int cy = (int) Math.floor(b.y() / cell);
        for (int x : new int[] {(int) Math.floor(b.x() / cell) - 1, (int) Math.floor(b.x() / cell),
                (int) Math.floor(b.right() / cell), (int) Math.floor(b.right() / cell) + 1}) {
            for (int y = cy - 1; y <= cy + 1; y++) {
                for (Glyph c : grid.getOrDefault(key(x, y), List.of())) {
                    float size = c.size;
                    boolean before = c.right() <= b.x() + 0.1f * size && b.x() - c.right() <= 0.8f * size;
                    boolean after = c.x >= b.right() - 0.1f * size && c.x - b.right() <= 0.8f * size;
                    boolean axis = Math.abs(c.baseline - 0.25f * size - b.y()) <= 0.5f * size;
                    if ((before || after) && axis && !out.contains(c)) {
                        out.add(c);
                    }
                }
            }
        }
        return out;
    }

    private static final float LONGEST_BAR = 15f;

    static List<Rule> fractionRules(List<Rule> rules, List<Fill> fills, List<VectorMark> marks, float em) {
        List<Rule> out = new ArrayList<>();
        for (Rule r : rules) {
            if (!r.horizontal() || r.length() <= LONGEST_BAR * em && !meetsVertical(r.start(), r.end(), r.pos(), rules, fills)
                    && !arrow(r.start(), r.end(), r.pos(), marks)) {
                out.add(r);
            }
        }
        return out;
    }

    static List<Fill> fractionFills(List<Rule> rules, List<Fill> fills, List<VectorMark> marks, float em) {
        List<Fill> out = new ArrayList<>();
        for (Fill f : fills) {
            float y = (f.top() + f.bottom()) / 2f;
            boolean bar = f.bottom() - f.top() <= 1.5f && f.right() - f.x() > 2f;
            if (!bar || f.right() - f.x() <= LONGEST_BAR * em && !meetsVertical(f.x(), f.right(), y, rules, fills)
                    && !arrow(f.x(), f.right(), y, marks)) {
                out.add(f);
            }
        }
        return out;
    }

    private static boolean arrow(float start, float end, float y, List<VectorMark> marks) {
        for (VectorMark m : marks) {
            boolean atEnd = m.x() <= start + 1.5f && m.right() >= start - 1.5f || m.x() <= end + 1.5f && m.right() >= end - 1.5f;
            if ((m.diagonal() || m.curved()) && atEnd && m.top() <= y + 1.5f && m.bottom() >= y - 1.5f
                    && m.width() < 0.25f * (end - start) + 6f) {
                return true;
            }
        }
        return false;
    }

    // Table rules meet the grid's verticals; fraction bars never touch one.
    private static boolean meetsVertical(float start, float end, float y, List<Rule> rules, List<Fill> fills) {
        for (Rule v : rules) {
            if (!v.horizontal() && v.pos() >= start - 1.5f && v.pos() <= end + 1.5f && y >= v.start() - 1.5f
                    && y <= v.end() + 1.5f) {
                return true;
            }
        }
        for (Fill v : fills) {
            float x = (v.x() + v.right()) / 2f;
            if (v.right() - v.x() <= 1.5f && v.bottom() - v.top() > 2f && x >= start - 1.5f && x <= end + 1.5f
                    && y >= v.top() - 1.5f && y <= v.bottom() + 1.5f) {
                return true;
            }
        }
        return false;
    }

    private static boolean candidate(Glyph g) {
        return g.font != null && g.icon == null && !g.invisible && !g.text.isBlank() && g.math == null;
    }

    private static long key(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    private static boolean joins(Glyph m, Glyph c, List<Glyph> word, Set<Glyph> members) {
        float size = Math.max(m.size, c.size);
        float gap = Math.max(m.x, wordLeft(word)) - Math.min(m.right(), wordRight(word));
        float dy = Math.abs(c.baseline - m.baseline);
        boolean script = Math.min(m.size, c.size) < SCRIPT * size;
        if (word.size() > 1 || Character.isLetter(c.text.codePointAt(0))) {
            return wordJoins(m, word, gap, dy, size, script, members);
        }
        if (dy > (script ? 0.65f : 0.3f) * size) {
            return false;
        }
        boolean spaced = operator(c.text) || operator(m.text);
        return gap <= (spaced ? SPACED : TIGHT) * size;
    }

    private static boolean wordJoins(Glyph m, List<Glyph> word, float gap, float dy, float size, boolean script,
            Set<Glyph> members) {
        String text = text(word);
        boolean sameLine = dy <= (script ? 0.65f : 0.3f) * size;
        if (FUNCTIONS.contains(text)) {
            boolean beside = sameLine && gap <= SPACED * size;
            boolean limit = m.size < SCRIPT * word.getFirst().size && gap < 0 && dy <= 1.3f * size;
            return beside || limit;
        }
        if (!sameLine) {
            return false;
        }
        if (word.size() == 1) {
            return gap <= TIGHT * size || operator(m.text) && gap <= SPACED * size || script && gap <= TIGHT * size;
        }
        if (italicWord(word) && (gap <= TIGHT * size || operator(m.text) && gap <= SPACED * size)) {
            return true;
        }
        boolean level = m.size >= SCRIPT * word.getFirst().size && Math.abs(m.baseline - word.getFirst().baseline) <= 0.1f * size;
        boolean appliedTo = level && m.x >= wordRight(word) && gap <= GLUED * size && (m.text.equals("(") || members.contains(m));
        boolean quadBefore = PROSE.contains(text) && m.right() <= wordLeft(word) && gap >= QUAD * size
                && gap <= FAR * size;
        return appliedTo || quadBefore;
    }

    private static boolean italicWord(List<Glyph> word) {
        for (Glyph g : word) {
            if (!g.italic) {
                return false;
            }
        }
        return word.size() <= 4;
    }

    private static boolean mathLike(List<Glyph> word) {
        if (word.size() > 1) {
            return FUNCTIONS.contains(text(word));
        }
        return true;
    }

    private static boolean operator(String text) {
        return text.length() == 1 && OPERATORS.indexOf(text.charAt(0)) >= 0;
    }

    static boolean bracket(String text) {
        return text.length() == 1 && BRACKETS.indexOf(text.charAt(0)) >= 0;
    }

    static boolean punctuation(String text) {
        return text.length() == 1 && PUNCTUATION.indexOf(text.charAt(0)) >= 0;
    }

    private static String text(List<Glyph> word) {
        StringBuilder sb = new StringBuilder();
        for (Glyph g : word) {
            sb.append(g.text);
        }
        return sb.toString();
    }

    private static float wordLeft(List<Glyph> word) {
        return word.getFirst().x;
    }

    private static float wordRight(List<Glyph> word) {
        return word.getLast().right();
    }

    private static Map<Glyph, List<Glyph>> words(Map<Long, List<Glyph>> grid) {
        List<Glyph> letters = new ArrayList<>();
        for (List<Glyph> cell : grid.values()) {
            for (Glyph g : cell) {
                if (g.text.length() == 1 && Character.isLetter(g.text.charAt(0))) {
                    letters.add(g);
                }
            }
        }
        letters.sort((a, b) -> a.baseline != b.baseline ? Float.compare(a.baseline, b.baseline) : Float.compare(a.x, b.x));
        Map<Glyph, List<Glyph>> out = new IdentityHashMap<>();
        List<Glyph> run = new ArrayList<>();
        for (Glyph g : letters) {
            if (!run.isEmpty()) {
                Glyph last = run.getLast();
                boolean glued = last.font == g.font && Math.abs(last.baseline - g.baseline) < 0.1f * g.size
                        && g.x - last.right() < 0.08f * g.size && g.x >= last.x;
                if (!glued) {
                    close(run, out);
                }
            }
            run.add(g);
        }
        close(run, out);
        return out;
    }

    private static void close(List<Glyph> run, Map<Glyph, List<Glyph>> out) {
        if (run.size() > 1) {
            List<Glyph> word = List.copyOf(run);
            for (Glyph g : word) {
                out.put(g, word);
            }
        }
        run.clear();
    }

    private static List<Bar> bars(List<Rule> rules, List<Fill> fills) {
        List<Bar> out = new ArrayList<>();
        for (Rule r : rules) {
            if (r.horizontal() && r.thickness() <= 1.5f && r.length() >= 2f) {
                out.add(new Bar(r, r.start(), r.end(), r.pos()));
            }
        }
        for (Fill f : fills) {
            if (f.bottom() - f.top() <= 1.5f && f.right() - f.x() >= 2f) {
                out.add(new Bar(f, f.x(), f.right(), (f.top() + f.bottom()) / 2f));
            }
        }
        return out;
    }

    private static boolean touches(Bar b, Glyph m) {
        float size = m.size;
        if (b.right() - b.x() > 25f * size) {
            return false;
        }
        if (!Float.isNaN(m.hang) && m.text.equals("√")) {
            return Math.abs(b.y() - m.baseline) <= 0.3f * size + 1f && b.x() >= m.x && b.x() <= m.right() + 0.5f * size;
        }
        float cx = (m.x + m.right()) / 2f;
        if (cx < b.x() - 0.2f * size || cx > b.right() + 0.2f * size) {
            return false;
        }
        float above = b.y() - (m.baseline + m.descent);
        float below = (m.baseline - 0.75f * size) - b.y();
        return above >= -0.3f * size && above <= 0.6f * size || below >= -0.3f * size && below <= 0.6f * size;
    }

    private static boolean spanned(Bar b, Glyph m, Map<Long, List<Glyph>> grid, float cell) {
        if (m.text.equals("√")) {
            return true;
        }
        boolean over = m.baseline < b.y();
        float left = m.x;
        float right = m.right();
        int x0 = (int) Math.floor(b.x() / cell) - 1;
        int x1 = (int) Math.floor(b.right() / cell) + 1;
        int cy = (int) Math.floor(b.y() / cell);
        for (int x = x0; x <= x1; x++) {
            for (int y = cy - 1; y <= cy + 2; y++) {
                for (Glyph c : grid.getOrDefault(key(x, y), List.of())) {
                    float mid = (c.x + c.right()) / 2f;
                    float reach = over ? b.y() - c.baseline : c.baseline - c.size * 0.75f - b.y();
                    if (mid >= b.x() && mid <= b.right() && reach >= -0.3f * m.size && reach <= 1.5f * m.size) {
                        left = Math.min(left, c.x);
                        right = Math.max(right, c.right());
                    }
                }
            }
        }
        return right - left >= 0.6f * (b.right() - b.x());
    }

    private static boolean besideBar(Bar b, Glyph m) {
        float size = m.size;
        boolean before = m.right() <= b.x() + 0.1f * size && b.x() - m.right() <= 0.8f * size;
        boolean after = m.x >= b.right() - 0.1f * size && m.x - b.right() <= 0.8f * size;
        return (before || after) && Math.abs(m.baseline - 0.25f * size - b.y()) <= 0.5f * size;
    }

    private static boolean fraction(Bar b, List<Glyph> near) {
        float aboveLeft = Float.MAX_VALUE;
        float aboveRight = -Float.MAX_VALUE;
        float belowLeft = Float.MAX_VALUE;
        float belowRight = -Float.MAX_VALUE;
        for (Glyph c : near) {
            if (c.baseline < b.y()) {
                aboveLeft = Math.min(aboveLeft, c.x);
                aboveRight = Math.max(aboveRight, c.right());
            } else {
                belowLeft = Math.min(belowLeft, c.x);
                belowRight = Math.max(belowRight, c.right());
            }
        }
        float widest = Math.max(aboveRight - aboveLeft, belowRight - belowLeft);
        return aboveRight > aboveLeft && belowRight > belowLeft && widest >= 0.6f * (b.right() - b.x());
    }

    private static List<Glyph> underOrOver(Bar b, Map<Long, List<Glyph>> grid, float cell) {
        List<Glyph> out = new ArrayList<>();
        int x0 = (int) Math.floor(b.x() / cell) - 1;
        int x1 = (int) Math.floor(b.right() / cell) + 1;
        int cy = (int) Math.floor(b.y() / cell);
        for (int x = x0; x <= x1; x++) {
            for (int y = cy - 1; y <= cy + 2; y++) {
                for (Glyph c : grid.getOrDefault(key(x, y), List.of())) {
                    if (touches(b, c)) {
                        out.add(c);
                    }
                }
            }
        }
        return out;
    }
}

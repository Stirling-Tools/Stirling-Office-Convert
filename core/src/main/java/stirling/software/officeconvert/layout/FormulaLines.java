package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.extract.PageGraphics.Fill;
import stirling.software.officeconvert.extract.PageGraphics.Rule;

final class FormulaLines {

    record Rebuilt(List<Glyph> glyphs, Set<Object> consumed) {}

    private static final Pattern WEB_MATH = Pattern.compile(".*(mathjax|katex|mjxtex|mjxzero|mjxc-tex).*");
    private static final Pattern LARGE_FONT = Pattern.compile(".*(size[1-4]|-s[1-4]).*");
    private static final String BIG_OPERATORS = "∑∏∐∫∬∭∮⋃⋂⋁⋀⨁⨂⨀";
    private static final String ROOT = "√";
    private static final String OPERATORS = "+-−=∗*/·×<>≤≥≠∈→,:";
    private static final String STACK = "¦";
    private static final int MAX_MATH = 4_000;
    private static final int MAX_BARS = 2_000;
    private static final int MAX_CLUSTER = 800;
    private static final int MAX_DEPTH = 12;
    private static final float MAIN_SHARE = 0.85f;
    private static final float WORD_GAP = 0.15f;
    private static final float ITALIC_GAP = 0.21f;
    private static final float COMMA_GAP = 0.08f;
    private static final float MIN_SQUEEZE = 0.35f;
    private static final float AXIS = 0.25f;
    private static final float STACK_GAP = 0.9f;
    private static final float STACKED_ROWS = 1.45f;
    private static final float SCRIPT_SHARE = 0.6f;
    private static final float OPERAND_GAP = 1.6f;

    private FormulaLines() {}

    static Rebuilt rebuild(List<Glyph> glyphs, List<Rule> rules, List<Fill> fills) {
        Map<FontInfo, Integer> known = new IdentityHashMap<>();
        List<Item> items = new ArrayList<>();
        for (Glyph g : glyphs) {
            int kind = kind(g.font, known);
            if (kind > 0 && !g.text.isEmpty()) {
                items.add(new Item(g, kind == 2));
            }
        }
        if (items.size() < 2 || items.size() > MAX_MATH) {
            return new Rebuilt(glyphs, Set.of());
        }
        float largest = 0;
        for (Item i : items) {
            largest = Math.max(largest, i.size);
        }
        float left = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        for (Item i : items) {
            left = Math.min(left, i.x);
            right = Math.max(right, i.right);
            top = Math.min(top, i.y - 2f * largest);
            bottom = Math.max(bottom, i.y + 2f * largest);
        }
        List<Item> bars = new ArrayList<>();
        for (Rule r : rules) {
            if (r.horizontal() && r.thickness() <= 0.2f * largest + 0.5f && r.length() >= 2f && r.pos() >= top
                    && r.pos() <= bottom && r.end() >= left && r.start() <= right) {
                bars.add(new Item(r, r.start(), r.end(), r.pos(), r.rgb()));
            }
        }
        for (Fill f : fills) {
            float y = (f.top() + f.bottom()) / 2f;
            if (f.bottom() - f.top() <= 0.2f * largest + 0.5f && f.right() - f.x() >= 2f && y >= top && y <= bottom
                    && f.right() >= left && f.x() <= right) {
                bars.add(new Item(f, f.x(), f.right(), y, f.rgb()));
            }
        }
        if (bars.size() <= MAX_BARS) {
            items.addAll(bars);
        }
        List<List<Item>> clusters = joinStacks(clusters(items, largest));
        Set<Glyph> replaced = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> consumed = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Glyph> added = new ArrayList<>();
        for (List<Item> cluster : clusters) {
            if (cluster.size() > MAX_CLUSTER || cluster.stream().filter(i -> i.glyph != null && !i.space()).count() < 2) {
                continue;
            }
            List<MathToken> toks = spacedWords(row(cluster, 0));
            List<Glyph> line = emit(cluster, toks);
            if (line.isEmpty()) {
                continue;
            }
            String math = OmmlWriter.of(toks);
            for (Glyph g : line) {
                g.math = math;
            }
            for (Item i : cluster) {
                if (i.glyph != null) {
                    replaced.add(i.glyph);
                }
            }
            for (MathToken t : toks) {
                if (t.bar() != null) {
                    consumed.add(t.bar());
                }
            }
            added.addAll(line);
        }
        if (replaced.isEmpty()) {
            return new Rebuilt(glyphs, Set.of());
        }
        List<Glyph> out = new ArrayList<>(glyphs.size());
        for (Glyph g : glyphs) {
            if (!replaced.contains(g)) {
                out.add(g);
            }
        }
        out.addAll(added);
        return new Rebuilt(out, consumed);
    }

    private static int kind(FontInfo font, Map<FontInfo, Integer> known) {
        if (font == null) {
            return 0;
        }
        return known.computeIfAbsent(font, f -> {
            String ps = f.postScriptName() == null ? "" : f.postScriptName().toLowerCase(Locale.ROOT);
            if (!WEB_MATH.matcher(ps).matches()) {
                return 0;
            }
            return LARGE_FONT.matcher(ps).matches() ? 2 : 1;
        });
    }

    private static List<List<Item>> clusters(List<Item> items, float largest) {
        int[] parent = new int[items.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble(i -> items.get(i).x));
        float reach = largest * 2f;
        for (int a = 0; a < order.size(); a++) {
            Item p = items.get(order.get(a));
            for (int b = a + 1; b < order.size(); b++) {
                Item q = items.get(order.get(b));
                if (q.x > p.right + reach) {
                    break;
                }
                if (linked(p, q, largest)) {
                    union(parent, order.get(a), order.get(b));
                }
            }
        }
        Map<Integer, List<Item>> byRoot = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            byRoot.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(items.get(i));
        }
        return new ArrayList<>(byRoot.values());
    }

    private static List<List<Item>> joinStacks(List<List<Item>> clusters) {
        List<List<Item>> sorted = new ArrayList<>(clusters);
        sorted.sort(Comparator.comparingDouble(FormulaLines::left));
        List<List<Item>> out = new ArrayList<>();
        List<List<Float>> outLines = new ArrayList<>();
        List<Float> outSizes = new ArrayList<>();
        for (List<Item> c : sorted) {
            float[] main = mainLine(c);
            List<Float> own = plainLines(c, main);
            int host = -1;
            if (own != null) {
                for (int k = out.size() - 1; k >= 0 && host < 0; k--) {
                    List<Float> lines = outLines.get(k);
                    float size = outSizes.get(k);
                    float gap = left(c) - right(out.get(k));
                    if (lines != null && gap >= -size && gap <= 4f * size
                            && sameLines(own, lines, new float[] {0, size})) {
                        host = k;
                    }
                }
            }
            if (host >= 0) {
                out.get(host).addAll(c);
                continue;
            }
            out.add(new ArrayList<>(c));
            outLines.add(own);
            outSizes.add(main[1]);
        }
        return out;
    }

    private static List<Float> plainLines(List<Item> cluster, float[] main) {
        List<Item> full = new ArrayList<>();
        for (Item i : cluster) {
            if (i.glyph != null && !i.space() && !i.structural() && i.size >= MAIN_SHARE * main[1]) {
                full.add(i);
            }
        }
        full.sort(Comparator.comparingDouble((Item i) -> i.y));
        List<Float> lines = new ArrayList<>();
        for (Item i : full) {
            if (lines.isEmpty() || i.y - lines.getLast() > STACK_GAP * main[1]) {
                lines.add(i.y);
            }
        }
        return lines.size() >= 2 ? lines : null;
    }

    private static float left(List<Item> items) {
        float v = Float.MAX_VALUE;
        for (Item i : items) {
            v = Math.min(v, i.x);
        }
        return v;
    }

    private static float right(List<Item> items) {
        float v = -Float.MAX_VALUE;
        for (Item i : items) {
            v = Math.max(v, i.right);
        }
        return v;
    }

    private static final class Item {
        final Glyph glyph;
        final Object bar;
        final boolean large;
        final float x;
        final float right;
        final float y;
        final float size;
        final int rgb;

        Item(Glyph g, boolean large) {
            glyph = g;
            bar = null;
            this.large = large;
            x = g.x;
            right = g.right();
            y = g.baseline;
            size = g.size;
            rgb = g.rgb;
        }

        Item(Object source, float x, float right, float y, int rgb) {
            glyph = null;
            bar = source;
            large = false;
            this.x = x;
            this.right = right;
            this.y = y;
            size = 0;
            this.rgb = rgb;
        }

        float top() {
            return glyph == null ? y : y - 0.75f * size;
        }

        float bottom() {
            return glyph == null ? y : y + 0.25f * size;
        }

        float centreX() {
            return (x + right) / 2f;
        }

        float centreY() {
            return glyph == null ? y : y - 0.3f * size;
        }

        boolean bigOperator() {
            return glyph != null && glyph.text.length() == 1 && BIG_OPERATORS.indexOf(glyph.text.charAt(0)) >= 0;
        }

        boolean root() {
            return glyph != null && glyph.text.equals(ROOT);
        }

        boolean space() {
            return glyph != null && glyph.text.chars().allMatch(c -> Character.isWhitespace(c) || c == 0xA0);
        }

        boolean structural() {
            return large || bigOperator() || root();
        }
    }

    private static boolean linked(Item p, Item q, float largest) {
        if (p.bar != null && q.bar != null) {
            return false;
        }
        if (p.bar != null || q.bar != null) {
            Item bar = p.bar != null ? p : q;
            Item g = p.bar != null ? q : p;
            return barLinked(bar, g) || barBeside(bar, g);
        }
        float size = Math.max(p.size, q.size);
        float gap = Math.max(p.x, q.x) - Math.min(p.right, q.right);
        float dy = Math.abs(p.y - q.y);
        boolean script = Math.min(p.size, q.size) < MAIN_SHARE * size;
        if (p.structural() || q.structural()) {
            return structuralLink(p.structural() ? p : q, p.structural() ? q : p, gap);
        }
        if (gap <= 0.6f * size && dy <= (script ? 0.65f : 0.3f) * size) {
            return true;
        }
        float narrower = Math.min(p.right - p.x, q.right - q.x);
        if (!script && size >= MAIN_SHARE * largest && -gap >= 0.5f * narrower && dy <= STACKED_ROWS * size) {
            return true;
        }
        return gap <= 0.2f * size && script && dy <= 0.9f * size;
    }

    private static boolean structuralLink(Item s, Item o, float gap) {
        float size = s.size;
        float drop = o.y - s.y;
        if (o.structural()) {
            return gap <= 0.6f * size && Math.abs(drop) <= 1.2f * size;
        }
        boolean small = o.size < MAIN_SHARE * size;
        if (s.bigOperator()) {
            if (small) {
                return gap <= 0.6f * size && drop >= -1.4f * size && drop <= 2.0f * size;
            }
            return gap <= OPERAND_GAP * size && Math.abs(drop) <= 0.6f * size;
        }
        return gap <= (small ? 0.6f : 1.0f) * size && Math.abs(drop) <= 1.5f * size;
    }

    private static boolean barLinked(Item bar, Item g) {
        if (g.space() || g.rgb != bar.rgb) {
            return false;
        }
        float size = g.size;
        if (g.root()) {
            return bar.x >= g.x + 0.3f * (g.right - g.x) && bar.x <= g.right + 0.5f * size && bar.y < g.y
                    && bar.y > g.y - 4f * size;
        }
        float cx = g.centreX();
        if (cx < bar.x - 0.2f * size || cx > bar.right + 0.2f * size) {
            return false;
        }
        float above = bar.y - g.bottom();
        float below = g.top() - bar.y;
        return above >= -0.3f * size && above <= 0.6f * size || below >= -0.3f * size && below <= 0.6f * size;
    }

    private static boolean barBeside(Item bar, Item g) {
        if (g.rgb != bar.rgb) {
            return false;
        }
        float size = g.size;
        boolean before = g.right <= bar.x + 0.1f * size && bar.x - g.right <= 0.8f * size;
        boolean after = g.x >= bar.right - 0.1f * size && g.x - bar.right <= 0.8f * size;
        return (before || after) && Math.abs(g.y - AXIS * size - bar.y) <= 0.5f * size;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }

    private static boolean twoDimensional(List<Item> cluster) {
        int glyphs = 0;
        boolean bar = false;
        for (Item i : cluster) {
            if (i.glyph != null) {
                glyphs++;
            } else {
                bar = true;
            }
        }
        if (glyphs < 2) {
            return false;
        }
        if (bar && hasFractionOrRoot(cluster) || farScripts(cluster)
                || cluster.stream().anyMatch(i -> i.large && i.glyph != null)) {
            return true;
        }
        for (Item a : cluster) {
            for (Item b : cluster) {
                if (a == b || a.glyph == null || b.glyph == null || a.space() || b.space()) {
                    continue;
                }
                float overlap = Math.min(a.right, b.right) - Math.max(a.x, b.x);
                float size = Math.max(a.size, b.size);
                if (overlap > 0.2f * Math.min(a.right - a.x, b.right - b.x) && Math.abs(a.y - b.y) > 0.5f * size) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean farScripts(List<Item> cluster) {
        float largest = 0;
        for (Item i : cluster) {
            if (i.glyph != null && !i.space() && !i.structural()) {
                largest = Math.max(largest, i.size);
            }
        }
        for (Item s : cluster) {
            if (s.glyph == null || s.space() || s.structural() || s.size >= MAIN_SHARE * largest) {
                continue;
            }
            float nearest = Float.MAX_VALUE;
            for (Item f : cluster) {
                if (f.glyph != null && !f.space() && !f.structural() && f.size >= MAIN_SHARE * largest) {
                    nearest = Math.min(nearest, Math.abs(f.y - s.y));
                }
            }
            if (nearest != Float.MAX_VALUE && nearest > 0.6f * largest) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFractionOrRoot(List<Item> cluster) {
        for (Item bar : cluster) {
            if (bar.bar == null) {
                continue;
            }
            boolean over = false;
            boolean under = false;
            for (Item g : cluster) {
                if (g.glyph == null || g.space()) {
                    continue;
                }
                if (g.root() && barLinked(bar, g)) {
                    return true;
                }
                if (g.centreX() >= bar.x - 1 && g.centreX() <= bar.right + 1) {
                    over |= g.centreY() < bar.y;
                    under |= g.centreY() > bar.y;
                }
            }
            if (over && under) {
                return true;
            }
        }
        return false;
    }

    private static List<MathToken> row(List<Item> items, int depth) {
        List<MathToken> out = new ArrayList<>();
        if (items.isEmpty()) {
            return out;
        }
        List<List<Item>> columns = columns(items);
        if (depth > MAX_DEPTH) {
            for (Item i : sorted(items)) {
                if (i.glyph != null) {
                    out.add(i.space() ? space(i.size) : glyphTok(i, 0));
                }
            }
            return out;
        }
        float[] main = main(items, columns);
        List<MathToken> cases = cases(items, columns, main, depth);
        if (cases != null) {
            return cases;
        }
        columns = mergeStacks(mergeLimits(columns, main), main);
        float lastRight = Float.NaN;
        boolean afterDelimiter = false;
        for (List<Item> column : columns) {
            float left = Float.MAX_VALUE;
            float right = -Float.MAX_VALUE;
            for (Item i : column) {
                left = Math.min(left, i.x);
                right = Math.max(right, i.right);
            }
            boolean gapBefore = !Float.isNaN(lastRight) && !scripts(column, main)
                    && spaced(out, left - lastRight, main[1]);
            lastRight = right;
            if (column.size() == 1 && column.getFirst().space()) {
                addSpace(out, main[1]);
                continue;
            }
            int level = columnLevel(column, main);
            List<MathToken> part = column(column, main, depth, afterDelimiter);
            if (gapBefore && !part.isEmpty() && !closing(part.getFirst())) {
                addSpace(out, main[1]);
            }
            for (MathToken t : part) {
                if (t.space()) {
                    addSpace(out, main[1]);
                } else {
                    out.add(level == 0 || t.level() != 0 ? t : withLevel(t, level));
                }
            }
            afterDelimiter = column.size() == 1 && column.getFirst().large && opening(column.getFirst().glyph.text);
        }
        return nested(scriptsInOrder(tidy(out)));
    }

    private static boolean opens(MathToken t) {
        return switch (t.kind()) {
            case FRAC_OPEN, ROOT_OPEN, NARY_OPEN, LIM_OPEN, STACK_OPEN -> true;
            default -> false;
        };
    }

    private static boolean closes(MathToken t) {
        return switch (t.kind()) {
            case FRAC_CLOSE, ROOT_CLOSE, NARY_CLOSE, LIM_CLOSE, STACK_CLOSE -> true;
            default -> false;
        };
    }

    private static int compose(int outer, int inner) {
        if (inner == 0) {
            return outer;
        }
        return Integer.signum(inner) == outer ? 2 * outer : -outer;
    }

    private static List<MathToken> nested(List<MathToken> toks) {
        List<MathToken> out = new ArrayList<>(toks);
        int i = 0;
        while (i < out.size()) {
            if (out.get(i).level() == 0) {
                i++;
                continue;
            }
            int end = i;
            float largest = 0;
            float sum = 0;
            int n = 0;
            int side = Integer.signum(out.get(i).level());
            while (end < out.size() && Integer.signum(out.get(end).level()) == side) {
                MathToken t = out.get(end++);
                if (t.source() != null && !t.marker()) {
                    largest = Math.max(largest, t.source().size);
                }
            }
            for (int k = i; k < end; k++) {
                MathToken t = out.get(k);
                if (t.source() != null && !t.marker() && t.source().size >= MAIN_SHARE * largest) {
                    sum += t.source().baseline;
                    n++;
                }
            }
            float baseline = n == 0 ? Float.NaN : sum / n;
            int inside = 0;
            for (int k = i; k < end; k++) {
                MathToken t = out.get(k);
                inside += opens(t) ? 1 : closes(t) ? -1 : 0;
                if (inside > 0 || Float.isNaN(baseline) || t.source() == null || t.marker()
                        || t.source().size >= MAIN_SHARE * largest || Math.abs(t.level()) != 1) {
                    continue;
                }
                float shift = baseline - t.source().baseline;
                int deeper = shift > 0.15f * largest ? 1 : shift < -0.08f * largest ? -1 : 0;
                if (deeper == Integer.signum(t.level())) {
                    out.set(k, t.withLevel(2 * t.level()));
                }
            }
            i = end;
        }
        return out;
    }

    private static List<MathToken> tidy(List<MathToken> toks) {
        List<MathToken> out = new ArrayList<>(toks.size());
        for (int i = 0; i < toks.size(); i++) {
            MathToken t = toks.get(i);
            if (t.space()) {
                boolean dots = !out.isEmpty() && out.getLast().text().equals(".") && i + 1 < toks.size()
                        && toks.get(i + 1).text().equals(".");
                if (out.isEmpty() || dots || opening(out.getLast().text()) || i + 1 >= toks.size()
                        || closing(toks.get(i + 1))) {
                    continue;
                }
            }
            out.add(t);
        }
        List<MathToken> merged = new ArrayList<>(out.size());
        for (int i = 0; i < out.size(); i++) {
            MathToken t = out.get(i);
            if (t.text().equals(".") && t.level() == 0 && i + 2 < out.size() && out.get(i + 1).text().equals(".")
                    && out.get(i + 2).text().equals(".")) {
                merged.add(new MathToken("\u2026", 0, t.source(), null, t.size()));
                i += 2;
                continue;
            }
            merged.add(t);
        }
        while (!merged.isEmpty() && merged.getLast().space()) {
            merged.removeLast();
        }
        return merged;
    }

    private static boolean opening(String text) {
        return text.equals("(") || text.equals("[") || text.equals("{");
    }

    private static boolean closing(MathToken t) {
        return t.level() == 0 && (t.text().equals(")") || t.text().equals("]") || t.text().equals("}") || t.text().equals(","));
    }

    private static List<MathToken> cases(List<Item> items, List<List<Item>> columns, float[] main, int depth) {
        Item brace = null;
        for (List<Item> column : columns) {
            if (column.size() == 1 && column.getFirst().large && column.getFirst().glyph.text.equals("{")) {
                brace = column.getFirst();
                break;
            }
        }
        if (brace == null) {
            return null;
        }
        List<Item> before = new ArrayList<>();
        List<Item> after = new ArrayList<>();
        for (Item i : items) {
            if (i != brace) {
                (i.centreX() < brace.x ? before : after).add(i);
            }
        }
        List<Float> lines = new ArrayList<>();
        List<Item> full = new ArrayList<>();
        for (Item i : after) {
            if (i.glyph != null && !i.space() && !i.structural() && i.size >= MAIN_SHARE * main[1]) {
                full.add(i);
            }
        }
        full.sort(Comparator.comparingDouble((Item i) -> i.y));
        for (Item i : full) {
            if (lines.isEmpty() || i.y - lines.getLast() > STACK_GAP * main[1]) {
                lines.add(i.y);
            }
        }
        if (lines.size() < 2) {
            return null;
        }
        List<List<Item>> rows = new ArrayList<>();
        for (int k = 0; k < lines.size(); k++) {
            rows.add(new ArrayList<>());
        }
        for (Item i : after) {
            float y = i.glyph == null ? i.y + AXIS * main[1] : i.y;
            int best = 0;
            for (int k = 1; k < lines.size(); k++) {
                if (Math.abs(y - lines.get(k)) < Math.abs(y - lines.get(best))) {
                    best = k;
                }
            }
            rows.get(best).add(i);
        }
        List<MathToken> out = new ArrayList<>(row(before, depth + 1));
        if (!out.isEmpty()) {
            out.add(space(main[1]));
        }
        out.add(glyphTok(brace, 0));
        out.add(MathToken.marker(MathToken.Kind.STACK_OPEN, main[1]));
        for (int k = 0; k < rows.size(); k++) {
            if (k > 0) {
                out.add(MathToken.marker(MathToken.Kind.STACK_ROW, main[1]));
                out.add(MathToken.linear(STACK, null, null, main[1]));
            }
            out.addAll(row(rows.get(k), depth + 1));
        }
        out.add(MathToken.marker(MathToken.Kind.STACK_CLOSE, main[1]));
        return out;
    }

    private static boolean spaced(List<MathToken> before, float gap, float size) {
        if (before.isEmpty()) {
            return false;
        }
        MathToken last = before.getLast();
        if (last.level() == 0 && last.text().equals(",")) {
            return gap >= COMMA_GAP * size;
        }
        boolean italicCorrection = last.level() == 0 && last.source() != null && last.source().italic
                && Character.isLetterOrDigit(last.text().codePointAt(0));
        return gap >= (italicCorrection ? ITALIC_GAP : WORD_GAP) * size;
    }

    private static boolean scripts(List<Item> column, float[] main) {
        for (Item i : column) {
            if (i.glyph == null || i.structural() || i.space() || i.size >= MAIN_SHARE * main[1]) {
                return false;
            }
        }
        return !Float.isNaN(main[0]);
    }

    private static List<List<Item>> mergeLimits(List<List<Item>> columns, float[] main) {
        if (Float.isNaN(main[0]) || columns.size() < 2) {
            return columns;
        }
        List<List<Item>> out = new ArrayList<>();
        for (List<Item> column : columns) {
            out.add(new ArrayList<>(column));
        }
        for (int k = 0; k < out.size(); k++) {
            List<Item> c = out.get(k);
            float y = limitRow(c, main);
            if (Float.isNaN(y)) {
                continue;
            }
            for (int n : new int[] {k + 1, k - 1}) {
                if (n < 0 || n >= out.size() || !limitHost(out.get(n), y, main)
                        || gapBetween(c, out.get(n)) > 0.3f * main[1]) {
                    continue;
                }
                out.get(n).addAll(c);
                out.remove(k);
                k--;
                break;
            }
        }
        return out;
    }

    private static List<List<Item>> mergeStacks(List<List<Item>> columns, float[] main) {
        List<List<Item>> out = new ArrayList<>();
        List<Float> lines = null;
        float[] basis = main;
        for (List<Item> column : columns) {
            if (lines != null && !out.isEmpty() && column.stream().allMatch(Item::space)) {
                out.getLast().addAll(column);
                continue;
            }
            boolean delimited = !out.isEmpty() && out.getLast().size() == 1 && out.getLast().getFirst().large
                    && out.getLast().getFirst().glyph != null && opening(out.getLast().getFirst().glyph.text);
            List<Float> own = plainStack(column, delimited ? ownSize(column) : basis);
            boolean joins = lines != null && !out.isEmpty()
                    && (own != null ? sameLines(own, lines, basis) : onLines(column, lines, basis));
            if (joins) {
                out.getLast().addAll(column);
                continue;
            }
            List<Item> stack = new ArrayList<>(column);
            float[] own2 = delimited && own != null ? ownSize(column) : main;
            if (own != null) {
                while (!out.isEmpty() && !closesBefore(out.getLast()) && onLines(out.getLast(), own, own2)) {
                    stack.addAll(0, out.removeLast());
                }
            }
            out.add(stack);
            lines = own;
            basis = own2;
        }
        return out;
    }

    private static boolean closesBefore(List<Item> column) {
        return column.size() == 1 && column.getFirst().glyph != null
                && (column.getFirst().large || opening(column.getFirst().glyph.text));
    }

    private static boolean onLines(List<Item> column, List<Float> lines, float[] main) {
        boolean ink = false;
        for (Item i : column) {
            if (i.glyph == null || i.structural()) {
                return false;
            }
            if (i.space()) {
                continue;
            }
            ink = true;
            boolean near = false;
            for (float y : lines) {
                near |= Math.abs(i.y - y) <= 0.3f * main[1]
                        || i.size < MAIN_SHARE * main[1] && Math.abs(i.y - y) <= 0.6f * main[1];
            }
            if (!near) {
                return false;
            }
        }
        return ink;
    }

    private static List<Float> plainStack(List<Item> column, float[] main) {
        for (Item i : column) {
            if (i.glyph == null || i.structural()) {
                return null;
            }
        }
        List<Float> lines = new ArrayList<>();
        for (Item i : sorted(column).stream().sorted(Comparator.comparingDouble((Item i) -> i.y)).toList()) {
            if (i.space() || i.size < MAIN_SHARE * main[1]) {
                continue;
            }
            if (lines.isEmpty() || i.y - lines.getLast() > STACK_GAP * main[1]) {
                lines.add(i.y);
            }
        }
        return lines.size() >= 2 ? lines : null;
    }

    private static boolean sameLines(List<Float> a, List<Float> b, float[] main) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int k = 0; k < a.size(); k++) {
            if (Math.abs(a.get(k) - b.get(k)) > 0.3f * main[1]) {
                return false;
            }
        }
        return true;
    }

    private static float limitRow(List<Item> column, float[] main) {
        float y = Float.NaN;
        for (Item i : column) {
            if (i.glyph == null || i.structural() || i.size >= MAIN_SHARE * main[1]) {
                return Float.NaN;
            }
            if (i.space()) {
                continue;
            }
            if (Math.abs(i.y - main[0]) <= 0.4f * main[1] || !Float.isNaN(y) && Math.abs(i.y - y) > 0.3f * main[1]) {
                return Float.NaN;
            }
            y = i.y;
        }
        return y;
    }

    private static boolean limitHost(List<Item> column, float y, float[] main) {
        boolean full = false;
        boolean sameRow = false;
        for (Item i : column) {
            if (i.glyph == null || i.space()) {
                continue;
            }
            full |= i.size >= MAIN_SHARE * main[1];
            sameRow |= i.size < MAIN_SHARE * main[1] && Math.abs(i.y - y) <= 0.3f * main[1];
        }
        return full && sameRow;
    }

    private static float gapBetween(List<Item> a, List<Item> b) {
        float aLeft = Float.MAX_VALUE;
        float aRight = -Float.MAX_VALUE;
        float bLeft = Float.MAX_VALUE;
        float bRight = -Float.MAX_VALUE;
        for (Item i : a) {
            aLeft = Math.min(aLeft, i.x);
            aRight = Math.max(aRight, i.right);
        }
        for (Item i : b) {
            bLeft = Math.min(bLeft, i.x);
            bRight = Math.max(bRight, i.right);
        }
        return Math.max(bLeft - aRight, aLeft - bRight);
    }

    private static List<MathToken> scriptsInOrder(List<MathToken> toks) {
        List<MathToken> out = new ArrayList<>(toks.size());
        int i = 0;
        while (i < toks.size()) {
            if (toks.get(i).level() == 0) {
                out.add(toks.get(i++));
                continue;
            }
            int end = i;
            while (end < toks.size() && toks.get(end).level() != 0) {
                end++;
            }
            List<MathToken> run = toks.subList(i, end);
            if (stacked(run)) {
                run.stream().filter(t -> t.level() < 0).forEach(out::add);
                run.stream().filter(t -> t.level() > 0).forEach(out::add);
            } else {
                out.addAll(run);
            }
            i = end;
        }
        return out;
    }

    private static boolean stacked(List<MathToken> run) {
        float subLeft = Float.MAX_VALUE;
        float subRight = -Float.MAX_VALUE;
        float supLeft = Float.MAX_VALUE;
        float supRight = -Float.MAX_VALUE;
        for (MathToken t : run) {
            if (t.source() == null || t.bar() != null) {
                return false;
            }
            if (t.level() < 0) {
                subLeft = Math.min(subLeft, t.source().x);
                subRight = Math.max(subRight, t.source().right());
            } else {
                supLeft = Math.min(supLeft, t.source().x);
                supRight = Math.max(supRight, t.source().right());
            }
        }
        return subLeft < supRight && supLeft < subRight;
    }

    private static List<Item> sorted(List<Item> items) {
        List<Item> s = new ArrayList<>(items);
        s.sort(Comparator.comparingDouble((Item i) -> i.x).thenComparingDouble(i -> i.y));
        return s;
    }

    private static MathToken space(float size) {
        return new MathToken(" ", 0, null, null, size);
    }

    private static void addSpace(List<MathToken> out, float size) {
        if (!out.isEmpty() && !out.getLast().space()) {
            out.add(space(size));
        }
    }

    private static MathToken withLevel(MathToken t, int level) {
        return t.withLevel(level);
    }

    private static float[] main(List<Item> items, List<List<Item>> columns) {
        List<Item> level = new ArrayList<>();
        Item widest = null;
        for (List<Item> column : columns) {
            Item bar = null;
            for (Item i : column) {
                if (i.bar != null && (bar == null || i.right - i.x > bar.right - bar.x)) {
                    bar = i;
                }
            }
            if (bar == null) {
                level.addAll(column);
            } else if (widest == null || bar.right - bar.x > widest.right - widest.x) {
                widest = bar;
            }
        }
        float[] main = mainLine(level);
        if (Float.isNaN(main[0])) {
            float[] all = mainLine(items);
            main[1] = all[1];
            main[0] = widest != null ? widest.y + AXIS * main[1] : all[0];
        }
        return main;
    }

    private static float[] mainLine(List<Item> items) {
        float largest = 0;
        Item tallest = null;
        for (Item i : items) {
            if (i.glyph != null && !i.space() && !i.structural()) {
                largest = Math.max(largest, i.size);
            } else if (i.glyph != null && i.bigOperator() && (tallest == null || i.size > tallest.size)) {
                tallest = i;
            }
        }
        if (tallest != null && largest < MAIN_SHARE * tallest.size) {
            return new float[] {tallest.y, tallest.size};
        }
        if (largest == 0) {
            for (Item i : items) {
                largest = Math.max(largest, i.size);
            }
            return new float[] {Float.NaN, Math.max(largest, 1f)};
        }
        Map<Integer, float[]> counts = new HashMap<>();
        float bucket = Math.max(0.25f * largest, 0.5f);
        for (Item i : items) {
            if (i.glyph != null && !i.space() && !i.structural() && i.size >= MAIN_SHARE * largest) {
                float[] c = counts.computeIfAbsent(Math.round(i.y / bucket), k -> new float[2]);
                c[0]++;
                c[1] += i.y;
            }
        }
        float[] best = null;
        for (float[] c : counts.values()) {
            if (best == null || c[0] > best[0] || c[0] == best[0] && c[1] / c[0] > best[1] / best[0]) {
                best = c;
            }
        }
        return new float[] {best[1] / best[0], largest};
    }

    private static List<List<Item>> columns(List<Item> items) {
        List<Item> sorted = sorted(items);
        int n = sorted.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int a = 0; a < n; a++) {
            Item p = sorted.get(a);
            if (p.space()) {
                continue;
            }
            for (int b = a + 1; b < n; b++) {
                Item q = sorted.get(b);
                if (q.x > p.right + 0.2f * Math.max(p.size, q.size)) {
                    if (p.bar == null) {
                        break;
                    }
                    continue;
                }
                if (!q.space() && sameColumn(p, q)) {
                    union(parent, a, b);
                }
            }
        }
        Map<Integer, List<Item>> byRoot = new HashMap<>();
        List<List<Item>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<Item> column = byRoot.computeIfAbsent(find(parent, i), k -> new ArrayList<>());
            if (column.isEmpty()) {
                out.add(column);
            }
            column.add(sorted.get(i));
        }
        return out;
    }

    private static boolean sameColumn(Item p, Item q) {
        if (p.bar != null && q.root() || q.bar != null && p.root()) {
            return barLinked(p.bar != null ? p : q, p.bar != null ? q : p);
        }
        float overlap = Math.min(p.right, q.right) - Math.max(p.x, q.x);
        float small = Math.min(p.glyph == null ? Float.MAX_VALUE : p.size, q.glyph == null ? Float.MAX_VALUE : q.size);
        if (overlap > 0.15f * (small == Float.MAX_VALUE ? 1f : small)) {
            return true;
        }
        if (p.glyph == null || q.glyph == null || p.structural() || q.structural()) {
            return false;
        }
        float size = Math.max(p.size, q.size);
        return Math.abs(p.size - q.size) < 0.05f * size && Math.abs(p.y - q.y) < 0.05f * size
                && Math.abs(Math.max(p.x, q.x) - Math.min(p.right, q.right)) <= 0.05f * size
                && uprightLetters(p, q);
    }

    private static boolean uprightLetters(Item p, Item q) {
        return !p.glyph.italic && !q.glyph.italic && Character.isLetter(p.glyph.text.codePointAt(0))
                && Character.isLetter(q.glyph.text.codePointAt(0));
    }

    private static int columnLevel(List<Item> column, float[] main) {
        if (Float.isNaN(main[0])) {
            return 0;
        }
        float largest = 0;
        float top = Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        boolean structure = false;
        for (Item i : column) {
            if (i.glyph == null) {
                structure = true;
                continue;
            }
            structure |= i.structural();
            largest = Math.max(largest, i.size);
            top = Math.min(top, i.top());
            bottom = Math.max(bottom, i.bottom());
        }
        if (!structure || largest == 0 || largest >= MAIN_SHARE * main[1]) {
            return 0;
        }
        float axis = main[0] - AXIS * main[1];
        for (Item i : column) {
            if (i.bar != null && Math.abs(i.y - axis) <= 0.35f * main[1] && largest >= SCRIPT_SHARE * main[1]) {
                return 0;
            }
        }
        float centre = (top + bottom) / 2f;
        return centre < main[0] - 0.3f * main[1] ? 1 : centre > main[0] ? -1 : 0;
    }

    private static List<MathToken> column(List<Item> column, float[] main, int depth) {
        return column(column, main, depth, false);
    }

    private static List<MathToken> column(List<Item> column, float[] main, int depth, boolean afterDelimiter) {
        Item widest = null;
        for (Item i : column) {
            if (i.bar != null && (widest == null || i.right - i.x > widest.right - widest.x)) {
                widest = i;
            }
        }
        Item root = null;
        for (Item i : column) {
            if (i.root() && widest != null && barLinked(widest, i)) {
                root = i;
                break;
            }
        }
        if (root != null) {
            return radical(column, root, widest, depth);
        }
        if (widest != null) {
            List<Item> above = new ArrayList<>();
            List<Item> below = new ArrayList<>();
            for (Item i : column) {
                if (i != widest) {
                    (i.centreY() < widest.y ? above : below).add(i);
                }
            }
            if (hasInk(above) && hasInk(below)) {
                List<MathToken> out = new ArrayList<>();
                out.add(MathToken.marker(MathToken.Kind.FRAC_OPEN, main[1]));
                out.addAll(wrapped(row(above, depth + 1)));
                out.add(MathToken.marker(MathToken.Kind.FRAC_MID, main[1]));
                out.add(MathToken.linear("/", widest.bar, null, main[1]));
                out.addAll(wrapped(row(below, depth + 1)));
                out.add(MathToken.marker(MathToken.Kind.FRAC_CLOSE, main[1]));
                return out;
            }
            List<Item> rest = new ArrayList<>(column);
            rest.remove(widest);
            return row(rest, depth + 1);
        }
        Item op = null;
        for (Item i : column) {
            if (i.bigOperator() && (op == null || i.size > op.size)) {
                op = i;
            }
        }
        if (op != null) {
            List<Item> upper = new ArrayList<>();
            List<Item> lower = new ArrayList<>();
            List<Item> rest = new ArrayList<>();
            for (Item i : column) {
                if (i == op) {
                    continue;
                }
                if (i.y < op.y - 0.4f * op.size) {
                    upper.add(i);
                } else if (i.y > op.y + 0.4f * op.size) {
                    lower.add(i);
                } else {
                    rest.add(i);
                }
            }
            if (!upper.isEmpty() || !lower.isEmpty()) {
                List<MathToken> out = new ArrayList<>();
                out.add(MathToken.marker(MathToken.Kind.NARY_OPEN, op.size));
                out.add(glyphTok(op, 0));
                out.add(MathToken.marker(MathToken.Kind.NARY_SUB, op.size));
                for (MathToken t : row(lower, depth + 1)) {
                    out.add(t.space() ? t : withLevel(t, compose(-1, t.level())));
                }
                out.add(MathToken.marker(MathToken.Kind.NARY_SUP, op.size));
                for (MathToken t : row(upper, depth + 1)) {
                    out.add(t.space() ? t : withLevel(t, compose(1, t.level())));
                }
                out.add(MathToken.marker(MathToken.Kind.NARY_CLOSE, op.size));
                out.addAll(row(rest, depth + 1));
                return out;
            }
        }
        List<List<Item>> stacked = stackedRows(column, afterDelimiter ? ownSize(column) : main);
        if (stacked.size() > 1) {
            List<MathToken> out = new ArrayList<>();
            out.add(MathToken.marker(MathToken.Kind.STACK_OPEN, main[1]));
            boolean first = true;
            for (List<Item> r : stacked) {
                if (!first) {
                    out.add(MathToken.marker(MathToken.Kind.STACK_ROW, main[1]));
                    out.add(MathToken.linear(STACK, null, null, main[1]));
                }
                first = false;
                out.addAll(row(r, depth + 1));
            }
            out.add(MathToken.marker(MathToken.Kind.STACK_CLOSE, main[1]));
            return out;
        }
        return plain(column, main);
    }

    private static float[] ownSize(List<Item> column) {
        float size = 0;
        for (Item i : column) {
            if (i.glyph != null && !i.space()) {
                size = Math.max(size, i.size);
            }
        }
        return new float[] {Float.NaN, Math.max(size, 1f)};
    }

    private static List<List<Item>> stackedRows(List<Item> column, float[] main) {
        List<Item> full = new ArrayList<>();
        for (Item i : column) {
            if (i.glyph != null && !i.space() && !i.structural() && i.size >= MAIN_SHARE * main[1]) {
                full.add(i);
            }
        }
        full.sort(Comparator.comparingDouble((Item i) -> i.y));
        List<Float> lines = new ArrayList<>();
        for (Item i : full) {
            if (lines.isEmpty() || i.y - lines.getLast() > STACK_GAP * main[1]) {
                lines.add(i.y);
            }
        }
        if (lines.size() < 2) {
            return List.of(column);
        }
        List<List<Item>> rows = new ArrayList<>();
        for (int k = 0; k < lines.size(); k++) {
            rows.add(new ArrayList<>());
        }
        for (Item i : column) {
            int best = 0;
            for (int k = 1; k < lines.size(); k++) {
                if (Math.abs(i.y - lines.get(k)) < Math.abs(i.y - lines.get(best))) {
                    best = k;
                }
            }
            rows.get(best).add(i);
        }
        return rows;
    }

    private static List<MathToken> radical(List<Item> column, Item root, Item bar, int depth) {
        List<Item> before = new ArrayList<>();
        List<Item> index = new ArrayList<>();
        List<Item> under = new ArrayList<>();
        for (Item i : column) {
            if (i == root || i == bar) {
                continue;
            }
            if (i.centreX() >= bar.x - 1f) {
                under.add(i);
            } else if (i.glyph != null && i.size < MAIN_SHARE * root.size && i.y < root.y - 0.3f * root.size) {
                index.add(i);
            } else {
                before.add(i);
            }
        }
        List<MathToken> out = new ArrayList<>(row(before, depth + 1));
        out.add(MathToken.marker(MathToken.Kind.ROOT_OPEN, root.size));
        for (MathToken t : row(index, depth + 1)) {
            out.add(t.space() ? t : withLevel(t, compose(1, t.level())));
        }
        out.add(MathToken.marker(MathToken.Kind.ROOT_MID, root.size));
        out.add(MathToken.linear(ROOT, bar.bar, root.glyph, root.size));
        out.addAll(wrapped(row(under, depth + 1)));
        out.add(MathToken.marker(MathToken.Kind.ROOT_CLOSE, root.size));
        return out;
    }

    private static boolean hasInk(List<Item> items) {
        for (Item i : items) {
            if (i.glyph != null && !i.space()) {
                return true;
            }
        }
        return false;
    }

    private static List<MathToken> plain(List<Item> column, float[] main) {
        List<Item> full = new ArrayList<>();
        List<Item> small = new ArrayList<>();
        for (Item i : sorted(column)) {
            if (i.glyph != null) {
                (i.space() || i.size >= MAIN_SHARE * main[1] ? full : small).add(i);
            }
        }
        boolean under = false;
        if (!full.isEmpty()) {
            float left = full.getFirst().x;
            float right = full.getLast().right;
            for (Item i : small) {
                under |= i.centreX() > left && i.centreX() < right && i.x < right - 0.15f * main[1];
            }
        }
        List<Item> order = new ArrayList<>(full);
        if (under) {
            order.addAll(small);
        } else {
            order = sorted(column);
        }
        List<MathToken> out = new ArrayList<>();
        if (under) {
            out.add(MathToken.marker(MathToken.Kind.LIM_OPEN, main[1]));
        }
        for (Item i : order) {
            if (under && i == small.getFirst()) {
                out.add(MathToken.marker(MathToken.Kind.LIM_MID, main[1]));
            }
            if (i.glyph != null) {
                out.add(i.space() ? space(i.size) : glyphTok(i, scriptLevel(i, main)));
            }
        }
        if (under) {
            out.add(MathToken.marker(MathToken.Kind.LIM_CLOSE, main[1]));
        }
        return out;
    }

    private static List<MathToken> spacedWords(List<MathToken> toks) {
        List<MathToken> out = new ArrayList<>(toks.size() + 4);
        int i = 0;
        while (i < toks.size()) {
            int end = i;
            while (end < toks.size() && uprightLetter(toks.get(end))) {
                end++;
            }
            if (end - i < 2) {
                out.add(toks.get(i++));
                continue;
            }
            float size = toks.get(i).size();
            if (!out.isEmpty() && separable(out.getLast())) {
                out.add(space(size));
            }
            out.addAll(toks.subList(i, end));
            if (end < toks.size() && separable(toks.get(end))) {
                out.add(space(size));
            }
            i = end;
        }
        return out;
    }

    private static boolean uprightLetter(MathToken t) {
        return t.level() == 0 && t.source() != null && !t.source().italic && t.text().length() == 1
                && Character.isLetter(t.text().charAt(0));
    }

    private static boolean separable(MathToken t) {
        if (t.space() || t.level() != 0 || t.text().isEmpty()) {
            return false;
        }
        char c = t.text().charAt(0);
        return OPERATORS.indexOf(c) < 0 && "()[]{}".indexOf(c) < 0 && !t.text().equals(STACK);
    }

    private static int scriptLevel(Item i, float[] main) {
        if (Float.isNaN(main[0]) || i.size >= MAIN_SHARE * main[1]) {
            return 0;
        }
        float shift = main[0] - i.y;
        return shift > 0.15f * main[1] ? 1 : shift < -0.08f * main[1] ? -1 : 0;
    }

    private static MathToken glyphTok(Item i, int level) {
        return new MathToken(i.glyph.text, level, i.glyph, null, i.size);
    }

    private static List<MathToken> wrapped(List<MathToken> part) {
        if (atomic(part)) {
            return part;
        }
        List<MathToken> out = new ArrayList<>();
        float size = part.isEmpty() ? 0 : part.getFirst().size();
        out.add(MathToken.linear("(", null, null, size));
        out.addAll(part);
        out.add(MathToken.linear(")", null, null, size));
        return out;
    }

    private static boolean atomic(List<MathToken> part) {
        int depth = 0;
        int bases = 0;
        boolean closed = false;
        for (MathToken t : part) {
            if (t.marker()) {
                continue;
            }
            if (t.space()) {
                if (depth == 0) {
                    return false;
                }
                continue;
            }
            if (t.level() != 0) {
                continue;
            }
            for (int k = 0; k < t.text().length(); k++) {
                char c = t.text().charAt(k);
                if (c == '(' || c == '[' || c == '{') {
                    if (closed && depth == 0) {
                        return false;
                    }
                    depth++;
                } else if (c == ')' || c == ']' || c == '}') {
                    depth--;
                    closed |= depth == 0;
                } else if (depth == 0 && OPERATORS.indexOf(c) >= 0) {
                    return false;
                } else if (Character.getType(c) != Character.NON_SPACING_MARK) {
                    if (closed && depth == 0) {
                        return false;
                    }
                    bases++;
                }
            }
        }
        return bases > 0;
    }

    private static List<Glyph> emit(List<Item> cluster, List<MathToken> all) {
        List<MathToken> toks = all.stream().filter(t -> !t.marker()).toList();
        float left = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        Glyph model = null;
        float mainSize = 0;
        for (Item i : cluster) {
            left = Math.min(left, i.x);
            right = Math.max(right, i.right);
            if (i.glyph != null && !i.space() && !i.structural() && i.size > mainSize) {
                mainSize = i.size;
                model = i.glyph;
            }
        }
        if (model == null || toks.isEmpty()) {
            return List.of();
        }
        float baseline = main(cluster, columns(cluster))[0];
        if (Float.isNaN(baseline)) {
            baseline = model.baseline;
        }
        float[] widths = new float[toks.size()];
        float total = 0;
        for (int k = 0; k < toks.size(); k++) {
            MathToken t = toks.get(k);
            float own = t.source() != null ? Math.min(t.source().width, 1.2f * mainSize) : 0;
            widths[k] = own > 0 ? own : (t.space() ? 0.28f : 0.4f) * mainSize;
            total += widths[k];
        }
        float squeeze = total > right - left ? Math.max(MIN_SQUEEZE, (right - left) / total) : 1f;
        long spaces = toks.stream().filter(MathToken::space).count();
        float pad = squeeze == 1f && spaces > 0 ? (right - left - total) / spaces : 0f;
        List<Glyph> out = new ArrayList<>(toks.size());
        float x = left;
        int seq = model.seq;
        for (int k = 0; k < toks.size(); k++) {
            MathToken t = toks.get(k);
            float w = widths[k] * squeeze + (t.space() ? pad : 0f);
            Glyph src = t.source() != null ? t.source() : model;
            float size = t.source() != null && src.size <= 1.2f * mainSize ? src.size : mainSize;
            if (t.level() != 0 && size >= MAIN_SHARE * mainSize) {
                size *= 0.7f;
            }
            float k2 = size / Math.max(src.size, 1e-3f);
            Glyph g = new Glyph(t.text(), x, w, baseline, size, src.ascent * k2, src.descent * k2, t.source() != null ? src.font : model.font, src.rgb,
                    seq++, model.spaceWidth, src.bold, t.source() != null && src.italic);
            g.vertAlign = Integer.signum(t.level());
            g.link = src.link;
            out.add(g);
            x += w;
        }
        return out;
    }
}

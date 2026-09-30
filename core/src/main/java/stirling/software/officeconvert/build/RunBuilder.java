package stirling.software.officeconvert.build;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.layout.BreakUnits;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.LogicalOrder;
import stirling.software.officeconvert.layout.Marker;
import stirling.software.officeconvert.layout.Word;
import stirling.software.officeconvert.model.Inline;
import stirling.software.officeconvert.model.Paragraph.TabStop;
import stirling.software.officeconvert.model.Paragraph;
import stirling.software.officeconvert.model.Picture;
import stirling.software.officeconvert.model.RunStyle;
import stirling.software.officeconvert.model.ScriptWidths;

final class RunBuilder {

    private static final float SCRIPT_SCALE = 0.66f;

    private final boolean dropHyphens;
    private IconPictures icons;

    record NoteMeta(String marker, boolean custom) {}

    private final Map<Integer, NoteMeta> notes = new HashMap<>();

    RunBuilder(boolean dropHyphens) {
        this.dropHyphens = dropHyphens;
    }

    void registerNote(int id, String marker, boolean custom) {
        notes.put(id, new NoteMeta(marker, custom));
    }

    private final class Sink {
        final List<Inline> out;
        final StringBuilder text = new StringBuilder();
        RunStyle style;
        String link;

        Sink(List<Inline> out) {
            this.out = out;
        }

        void append(String s, RunStyle st, String lk) {
            if (style != null && (!style.equals(st) || !Objects.equals(link, lk))) {
                flush();
            }
            style = st;
            link = lk;
            text.append(s);
        }

        void space() {
            if (style != null) {
                text.append(' ');
            }
        }

        void flush() {
            if (style != null && !text.isEmpty()) {
                int anchor = -1;
                String href = link;
                if (href != null && href.startsWith("#page")) {
                    try {
                        anchor = Integer.parseInt(href.substring(5));
                    } catch (NumberFormatException e) {
                        anchor = -1;
                    }
                    href = null;
                }
                out.add(new Inline.Text(text.toString(), style, href, anchor));
            }
            text.setLength(0);
        }

        void add(Inline inline) {
            flush();
            out.add(inline);
        }

        boolean endsWithHyphen() {
            if (!text.isEmpty()) {
                char c = text.charAt(text.length() - 1);
                return c == '-' || c == '­' || c == '‐';
            }
            return false;
        }

        void softHyphen() {
            if (!text.isEmpty()) {
                text.append('\u00AD');
            }
        }

        void dropLastChar() {
            if (!text.isEmpty()) {
                text.setLength(text.length() - 1);
            }
        }

        char lastChar() {
            return text.isEmpty() ? 0 : text.charAt(text.length() - 1);
        }

        int lastCodePoint() {
            return text.isEmpty() ? 0 : text.codePointBefore(text.length());
        }
    }

    private final Set<Integer> emittedNotes = new HashSet<>();

    private Map<String, WidthFix> spacing = Map.of();

    private record WidthFix(float spacing, int scale) {}

    private static final float SCALE_BELOW = 0.88f;

    private static final float SCALE_ABOVE = 1.06f;

    private static final float OTHER_FACE = 0.92f;

    private static float wordSize(Glyph g) {
        return Math.max(1f, Math.round(g.size * 2f) / 2f);
    }

    private static final float ROUNDED_SIZE = 0.004f;

    private static String lookKey(Glyph g) {
        return g.font.family() + "|" + g.bold + "|" + g.italic + "|" + Math.round(g.size * 2f) + "|" + scaleOf(g)
                + (arabicStandIn(g) ? "|ar" : hebrew(g) ? "|he" : modeled(g) != null ? "|" + modeled(g) : "");
    }

    static boolean keepsPunctuationIn(List<Line> lines) {
        boolean eastAsian = false;
        for (Line l : lines) {
            for (Word w : l.words) {
                eastAsian |= w.glyphs.stream().anyMatch(g -> g.text.chars().anyMatch(c -> isCjk((char) c)
                        || Character.UnicodeScript.of(c) == Character.UnicodeScript.HANGUL));
            }
        }
        if (!eastAsian) {
            return false;
        }
        for (int i = 0; i + 1 < lines.size(); i++) {
            if (HANGING.contains(lines.get(i).words.getLast().last().text)) {
                return false;
            }
        }
        return true;
    }

    private static final Set<String> HANGING = Set.of("\u3001", "\u3002", "\uFF0C", "\uFF0E", ",", ".");

    static Character.UnicodeScript modeled(Glyph g) {
        return g.font.substituted() ? ScriptWidths.script(g.text) : null;
    }

    private static void scriptWidths(Map<String, float[]> sums, Word w) {
        Glyph first = w.first();
        Character.UnicodeScript script = modeled(first);
        if (script == null || first.vertAlign != 0) {
            return;
        }
        String key = lookKey(first);
        int n = 0;
        StringBuilder text = new StringBuilder();
        for (; n < w.glyphs.size(); n++) {
            Glyph g = w.glyphs.get(n);
            if (modeled(g) != script || g.vertAlign != 0 || !lookKey(g).equals(key)) {
                break;
            }
            text.append(g.text);
        }
        float em = ScriptWidths.width(text.toString(), script, first.bold) * scaleOf(first) / 100f;
        if (Float.isNaN(em)) {
            return;
        }
        float end = w.right;
        for (int i = n; i < w.glyphs.size(); i++) {
            Glyph g = w.glyphs.get(i);
            if (modeled(g) != null) {
                return;
            }
            end = i == n ? g.x : Math.min(end, g.x);
        }
        float[] acc = look(sums, first);
        acc[0] += end - w.x;
        acc[1] += em * wordSize(first);
        acc[2] += text.length();
        acc[8] += em * first.size;
        acc[9] = ScriptWidths.clustered(script) ? 1 : 0;
    }

    private static final java.util.regex.Pattern NASKH_OR_NASTALIQ =
            java.util.regex.Pattern.compile("(?i)naskh|nasta|amiri|scheherazade|lateef|traditional|harmattan|mirza");

    static boolean hebrew(Glyph g) {
        return g.text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HEBREW);
    }

    static boolean arabicStandIn(Glyph g) {
        if (!g.font.substituted() || !"Arial".equals(g.font.family()) || g.font.mono()) {
            return false;
        }
        String ps = g.font.postScriptName();
        if (ps != null && NASKH_OR_NASTALIQ.matcher(ps).find()) {
            return false;
        }
        return g.text.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.ARABIC);
    }

    private static void arabicWidths(Map<String, float[]> sums, Word w) {
        Map<String, List<Glyph>> groups = new HashMap<>();
        for (Glyph g : w.glyphs) {
            if (g.vertAlign == 0 && arabicStandIn(g)) {
                groups.computeIfAbsent(lookKey(g), k -> new ArrayList<>()).add(g);
            }
        }
        for (List<Glyph> group : groups.values()) {
            StringBuilder logical = new StringBuilder();
            float pdf = 0;
            int chars = 0;
            for (Glyph g : group.reversed()) {
                logical.append(g.text);
                pdf += g.width;
                chars += g.text.length();
            }
            Glyph first = group.getFirst();
            float exact = ArabicWidths.width(logical.toString(), first.bold, first.size) * scaleOf(first) / 100f;
            if (Float.isNaN(exact)) {
                continue;
            }
            float[] acc = look(sums, first);
            acc[0] += pdf;
            acc[1] += exact * wordSize(first) / first.size;
            acc[2] += chars;
            acc[8] += exact;
        }
    }

    private static final float SQUEEZED = 0.06f;

    private static final float MODEL_TOLERANCE = 0.015f;

    private static final float MODEL_NOISE = 0.1f;

    private static final float MODEL_MIN = 0.7f;

    private static final float MODEL_MAX = 1.3f;

    private static final float CJK_SQUEEZED = 0.005f;

    private final Map<String, float[]> modelPool = new HashMap<>();

    private Map<String, WidthFix> widthFixes(List<Line> lines) {
        Map<String, float[]> sums = new HashMap<>();
        float positioned = naturalGap(lines);
        for (Line l : lines) {
            for (int wi = 0; wi < l.words.size(); wi++) {
                Word w = l.words.get(wi);
                Glyph last = w.last();
                boolean spaced = wi + 1 < l.words.size() && l.gaps[wi + 1] == Line.SPACE;
                arabicWidths(sums, w);
                scriptWidths(sums, w);
                Character.UnicodeScript script = modeled(last);
                if (spaced && last.vertAlign == 0 && script != null) {
                    Glyph next = l.words.get(wi + 1).first();
                    float em = ScriptWidths.space(script, last.bold) * scaleOf(last) / 100f;
                    if (ScriptWidths.clustered(script) && modeled(next) == script && lookKey(next).equals(lookKey(last))
                            && !Float.isNaN(em)) {
                        float[] acc = look(sums, last);
                        acc[0] += !Float.isNaN(l.drawnSpace) ? l.drawnSpace : !Float.isNaN(positioned) ? positioned : last.spaceWidth;
                        acc[1] += em * wordSize(last);
                        acc[2] += 1;
                        acc[8] += em * last.size;
                        acc[9] = 1;
                    }
                } else if (spaced && last.vertAlign == 0 && !unmeasured(last)) {
                    float natural = !Float.isNaN(l.drawnSpace) ? l.drawnSpace : !Float.isNaN(positioned) ? positioned : last.spaceWidth;
                    float exact = (arabicStandIn(last) ? ArabicWidths.space(last.bold, last.size)
                            : SubstituteMetrics.width(" ", last.font.family(), last.bold, last.italic, last.size)) * scaleOf(last)
                            / 100f;
                    if (!Float.isNaN(exact)) {
                        float[] acc = look(sums, last);
                        acc[0] += natural;
                        acc[1] += exact * wordSize(last) / last.size;
                        acc[2] += 1;
                        acc[8] += exact;
                    }
                }
                for (int gi = 0; gi < w.glyphs.size(); gi++) {
                    Glyph g = w.glyphs.get(gi);
                    if (g.vertAlign != 0 || g.icon != null || unmeasured(g) || arabicStandIn(g) || modeled(g) != null) {
                        continue;
                    }
                    float exact = substituteWidth(g) * scaleOf(g) / 100f;
                    float word = exact * wordSize(g) / g.size;
                    if (Float.isNaN(word)) {
                        continue;
                    }
                    float[] acc = look(sums, g);
                    acc[8] += exact;
                    acc[7] = Math.max(acc[7], Math.abs(wordSize(g) - g.size) / g.size);
                    acc[0] += g.width;
                    acc[1] += word;
                    acc[2] += g.text.length();
                    acc[6] += isCjk(g.text.charAt(0)) ? g.text.length() : 0;
                    float placed = gi + 1 < w.glyphs.size() ? w.glyphs.get(gi + 1).x - g.x : Float.NaN;
                    if (placed > 0.3f * g.width && placed < 2f * g.width) {
                        acc[3] += placed;
                        acc[4] += g.width;
                    }
                }
            }
        }
        Map<String, WidthFix> out = new HashMap<>();
        for (var e : sums.entrySet()) {
            float[] a = e.getValue();
            if (a[9] > 0) {
                String[] parts = e.getKey().split("\\|");
                float[] pool = modelPool.computeIfAbsent(parts[0] + "|" + parts[1] + "|" + parts[2] + "|" + parts[4] + "|" + parts[5],
                        k -> new float[3]);
                pool[0] += a[0];
                pool[1] += a[1];
                pool[2] += a[2];
                float ratio = pool[0] / pool[1];
                float tolerance = MODEL_TOLERANCE + MODEL_NOISE / (float) Math.sqrt(pool[2]);
                if (pool[2] >= 3 && Math.abs(ratio - 1) >= tolerance && ratio > MODEL_MIN && ratio < MODEL_MAX) {
                    out.put(e.getKey(), new WidthFix(0f, Math.round(100 * ratio)));
                }
                continue;
            }
            boolean placedApart = a[5] == 0 && a[4] > 0 && a[3] / a[4] < 1 - (a[6] * 2 > a[2] ? CJK_SQUEEZED : SQUEEZED);
            boolean otherFace = a[5] == 0 && !placedApart && a[0] < OTHER_FACE * a[8];
            if (a[2] < 3 || a[5] == 0 && !placedApart && !otherFace && a[7] < ROUNDED_SIZE) {
                continue;
            }
            float pdf = placedApart ? a[0] * a[3] / a[4] : a[5] == 0 && !otherFace ? a[8] : a[0];
            if (pdf < SCALE_BELOW * a[1]) {
                out.put(e.getKey(), new WidthFix(0f, Math.max(50, Math.round(100 * pdf / (otherFace ? a[1] : a[8])))));
            } else if (pdf > SCALE_ABOVE * a[1]) {
                out.put(e.getKey(), new WidthFix(0f, Math.min(150, (int) Math.floor(100 * pdf / a[8]))));
            } else {
                float perChar = (pdf - a[1]) / a[2];
                float steps = Math.clamp(perChar, -2f, 4f) * 20f;
                boolean rtl = e.getKey().endsWith("|ar") || e.getKey().endsWith("|he");
                out.put(e.getKey(), new WidthFix((rtl ? Math.round(steps) : (float) Math.floor(steps)) / 20f, 100));
            }
        }
        return out;
    }

    private static float[] look(Map<String, float[]> sums, Glyph g) {
        float[] acc = sums.computeIfAbsent(lookKey(g), k -> new float[10]);
        acc[5] = g.font.substituted() ? 1 : acc[5];
        return acc;
    }

    private static final float SMALL_CAP_SIZE = 0.8f;

    private static float substituteWidth(Glyph g) {
        String text = g.text;
        if (!text.isEmpty() && isCjk(text.charAt(0)) && text.chars().allMatch(c -> isCjk((char) c))) {
            return g.size * text.length();
        }
        if (g.font.smallCaps() && !text.equals(text.toUpperCase(java.util.Locale.ROOT))) {
            return SubstituteMetrics.width(text.toUpperCase(java.util.Locale.ROOT), g.font.family(), g.bold, g.italic,
                    g.size * SMALL_CAP_SIZE);
        }
        return SubstituteMetrics.width(text, g.font.family(), g.bold, g.italic, g.size);
    }

    static boolean unmeasured(Glyph g) {
        if (modeled(g) != null) {
            return false;
        }
        boolean arabic = arabicStandIn(g);
        boolean standIn = g.font.substituted();
        return g.text.codePoints().anyMatch(cp -> switch (Character.UnicodeScript.of(cp)) {
            case LATIN, GREEK, CYRILLIC, COMMON, INHERITED, HAN, HIRAGANA, KATAKANA -> false;
            case HEBREW -> !standIn;
            case ARABIC -> !arabic;
            default -> true;
        });
    }

    private static float naturalGap(List<Line> lines) {
        float best = Float.NaN;
        for (Line l : lines) {
            List<Float> gaps = new ArrayList<>();
            for (int wi = 1; wi < l.words.size(); wi++) {
                if (l.gaps[wi] == Line.SPACE) {
                    gaps.add(l.words.get(wi).x - l.words.get(wi - 1).right);
                }
            }
            if (gaps.size() >= 2) {
                gaps.sort(Float::compare);
                float median = gaps.get(gaps.size() / 2);
                best = Float.isNaN(best) ? median : Math.min(best, median);
            }
        }
        return best > 0 ? best : Float.NaN;
    }

    private RunStyle styled(Glyph g, float hostSize) {
        RunStyle s = styleOf(g, hostSize);
        WidthFix fix = spacing.get(lookKey(g));
        if (fix != null && (fix.spacing() != 0f || fix.scale() != 100) && g.vertAlign == 0 && !unmeasured(g)) {
            int scale = Math.round(s.scale() * fix.scale() / 100f);
            return new RunStyle(s.font(), s.size(), s.bold(), s.italic(), s.underline(), s.strike(), s.rgb(),
                    s.highlight(), s.vertAlign(), s.symbol(), fix.spacing(), scale, s.smallCaps());
        }
        return s;
    }

    void fill(Paragraph p, List<Line> lines, int skipWords, float colLeft, float colRight, float hostSize) {
        fill(p, lines, skipWords, colLeft, colRight, hostSize, new BitSet(), new BitSet());
    }

    void fill(Paragraph p, List<Line> lines, int skipWords, float colLeft, float colRight, float hostSize,
            BitSet hardBreaks, BitSet pageBreaks) {
        fill(p, lines, skipWords, colLeft, colRight, hostSize, hardBreaks, pageBreaks, false);
    }

    void fill(Paragraph p, List<Line> lines, int skipWords, float colLeft, float colRight, float hostSize,
            BitSet hardBreaks, BitSet pageBreaks, boolean markerTab) {
        Sink sink = new Sink(p.inlines);
        spacing = widthFixes(lines);
        p.noHangingPunctuation = keepsPunctuationIn(lines);
        for (int li = 0; li < lines.size(); li++) {
            Line line = lines.get(li);
            int start = li == 0 ? skipWords : 0;
            if (li > 0 && hardBreaks.get(li)) {
                sink.add(new Inline.Break());
            } else if (li > 0 && sink.style != null) {
                Glyph first = line.words.getFirst().first();
                boolean softHyphen = sink.lastChar() == '­';
                if (sink.endsWithHyphen() && (dropHyphens || softHyphen) && Character.isLowerCase(first.text.charAt(0))) {
                    sink.dropLastChar();
                    sink.softHyphen();
                } else if (!sink.endsWithHyphen() && (!(unspaced(sink.lastCodePoint()) && unspaced(first.text.codePointAt(0)))
                        && sink.lastCodePoint() != ETHIOPIC_WORDSPACE || phraseBreak(lines, li))) {
                    sink.space();
                }
            }
            if (li > 0 && pageBreaks.get(li)) {
                sink.add(new Inline.PageBreak());
            }
            if (LogicalOrder.hasRtl(line) || p.bidi && (!LogicalOrder.hasLtr(line) || LogicalOrder.hasArabicDigits(line))) {
                boolean fromRight = start > 0 && Marker.startIndex(line) > 0;
                int from = fromRight ? 0 : start;
                int to = fromRight ? line.words.size() - start : line.words.size();
                appendLogical(sink, line, from, to, hostSize, p.bidi || LogicalOrder.rtlBase(line));
                continue;
            }
            for (int wi = start; wi < line.words.size(); wi++) {
                Word w = line.words.get(wi);
                if (wi > start) {
                    byte gap = line.gaps[wi];
                    if (markerTab && li == 0 && wi == 1) {
                        sink.add(new Inline.Tab(styleOf(w.first(), hostSize)));
                        float pos = Math.max(0, w.x - colLeft);
                        p.tabs.removeIf(t -> Math.abs(t.pos() - pos) < 3f);
                        p.tabs.add(new TabStop(pos, TabStop.Kind.LEFT, (char) 0));
                        p.tabs.sort((a, b) -> Float.compare(a.pos(), b.pos()));
                    } else if (gap == Line.SPACE) {
                        sink.space();
                        if (line.sentenceSpace(wi)) {
                            sink.space();
                        }
                    } else {
                        sink.add(new Inline.Tab(styleOf(w.first(), hostSize)));
                        char leader = gap == Line.LEADER && line.leaders != null ? line.leaders[wi] : 0;
                        addTabStop(p, line, wi, gap == Line.LEADER ? (leader == 0 ? '.' : leader) : 0, colLeft, colRight);
                    }
                }
                if (isFillRun(w.text)) {
                    sink.add(new Inline.Tab(styleOf(w.first(), hostSize)));
                    float pos = Math.max(0, w.right - colLeft);
                    p.tabs.removeIf(t -> Math.abs(t.pos() - pos) < 3f);
                    p.tabs.add(new TabStop(pos, TabStop.Kind.LEFT, '_'));
                    p.tabs.sort((a, b) -> Float.compare(a.pos(), b.pos()));
                    continue;
                }
                for (int gi = 0; gi < w.glyphs.size(); gi++) {
                    Glyph g = w.glyphs.get(gi);
                    if (g.footnote >= 0) {
                        NoteMeta meta = notes.get(g.footnote);
                        if (meta != null && !emittedNotes.contains(g.footnote)) {
                            emittedNotes.add(g.footnote);
                            sink.add(new Inline.FootnoteRef(g.footnote, meta.marker(), meta.custom(), styleOf(g, hostSize)));
                        }
                        if (meta != null) {
                            continue;
                        }
                    }
                    if (g.icon != null && icons != null) {
                        float advance = gi + 1 < w.glyphs.size() ? w.glyphs.get(gi + 1).x - g.x : g.width;
                        Picture pic = icons.picture(g, advance);
                        if (pic != null) {
                            sink.add(new Inline.Image(pic));
                            continue;
                        }
                    }
                    sink.append(g.text, styled(g, hostSize), g.link);
                }
            }
        }
        sink.flush();
    }

    void icons(IconPictures icons) {
        this.icons = icons;
    }

    private void appendLogical(Sink sink, Line line, int fromWord, int toWord, float hostSize, boolean rtlBase) {
        for (LogicalOrder.Token t : LogicalOrder.of(line, fromWord, toWord, rtlBase)) {
            if (t.glyph() == null) {
                if (t.gap() == Line.SPACE) {
                    sink.space();
                } else {
                    sink.add(new Inline.Tab(null));
                }
                continue;
            }
            Glyph g = t.glyph();
            sink.append(t.text(), styled(g, hostSize), g.link);
        }
    }

    private static void addTabStop(Paragraph p, Line line, int wi, char leader, float colLeft, float colRight) {
        int end = wi;
        while (end + 1 < line.words.size() && line.gaps[end + 1] == Line.SPACE) {
            end++;
        }
        Word startWord = line.words.get(wi);
        Word endWord = line.words.get(end);
        boolean rightAligned = leader != 0 || end == line.words.size() - 1 && colRight - endWord.right < 4f;
        float pos = rightAligned ? endWord.right - colLeft : startWord.x - colLeft;
        TabStop.Kind kind = rightAligned ? TabStop.Kind.RIGHT : TabStop.Kind.LEFT;
        for (TabStop t : p.tabs) {
            if (Math.abs(t.pos() - pos) < 3f) {
                return;
            }
        }
        p.tabs.add(new TabStop(Math.max(0, pos), kind, leader));
        p.tabs.sort((a, b) -> Float.compare(a.pos(), b.pos()));
    }

    static boolean isFillRun(String text) {
        if (text.length() < 5) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '_') {
                return false;
            }
        }
        return true;
    }

    static RunStyle styleOf(Glyph g, float hostSize) {
        float size = g.size;
        if (g.vertAlign != 0) {
            size = Math.min(Math.max(hostSize, g.size), g.size / SCRIPT_SCALE);
        }
        size = Math.round(size * 2f) / 2f;
        String font = arabicStandIn(g) ? ArabicWidths.FAMILY : g.font.family();
        boolean symbol = g.font.symbolic();
        if (symbol && !isPrivateUse(g.text)) {
            font = needsSymbolFont(g.text) ? "Segoe UI Symbol" : null;
            symbol = false;
        }
        return new RunStyle(
                font,
                Math.max(1f, size),
                g.bold,
                g.italic,
                g.underline,
                g.strike,
                g.rgb,
                g.highlightRgb,
                g.vertAlign,
                symbol,
                0f,
                scaleOf(g),
                g.font.smallCaps());
    }

    static int scaleOf(Glyph g) {
        int pct = Math.round(g.hscale * 100f);
        return Math.abs(pct - 100) < 3 ? 100 : Math.clamp(pct, 1, 600);
    }

    static boolean isPrivateUse(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0xE000 || c > 0xF8FF) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    static boolean needsSymbolFont(String s) {
        for (int i = 0; i < s.length(); i++) {
            Character.UnicodeBlock b = Character.UnicodeBlock.of(s.charAt(i));
            if (b == Character.UnicodeBlock.DINGBATS
                    || b == Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS
                    || b == Character.UnicodeBlock.GEOMETRIC_SHAPES
                    || b == Character.UnicodeBlock.ARROWS
                    || b == Character.UnicodeBlock.MISCELLANEOUS_SYMBOLS_AND_ARROWS) {
                return true;
            }
        }
        return false;
    }

    private static final int ETHIOPIC_WORDSPACE = 0x1361;

    static boolean phraseBreak(List<Line> lines, int li) {
        Line prev = lines.get(li - 1);
        Word next = lines.get(li).words.getFirst();
        String end = prev.words.getLast().text;
        int last = end.isEmpty() ? 0 : end.codePointBefore(end.length());
        Character.UnicodeScript script = next.text.isEmpty() ? null : Character.UnicodeScript.of(next.text.codePointAt(0));
        if (script == Character.UnicodeScript.TIBETAN) {
            return last >= 0x0F0D && last <= 0x0F11;
        }
        if (script != Character.UnicodeScript.THAI && script != Character.UnicodeScript.LAO
                && script != Character.UnicodeScript.MYANMAR) {
            return false;
        }
        float edge = -Float.MAX_VALUE;
        for (int i = 0; i + 1 < lines.size(); i++) {
            Line other = lines.get(i);
            if (i != li - 1 && other.narrowed == prev.narrowed && (li == 1 || Math.abs(other.x - prev.x) <= 0.5f * prev.size)) {
                edge = Math.max(edge, other.right);
            }
        }
        return prev.right + BreakUnits.syllable(next) + 0.1f * prev.size < edge;
    }

    static boolean unspaced(int cp) {
        if (cp == 0) {
            return false;
        }
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.THAI
                || script == Character.UnicodeScript.LAO || script == Character.UnicodeScript.KHMER
                || script == Character.UnicodeScript.MYANMAR || script == Character.UnicodeScript.TIBETAN
                || cp < 0x10000 && isCjk((char) cp);
    }

    static boolean isCjk(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.HIRAGANA
                || b == Character.UnicodeBlock.KATAKANA
                || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A;
    }
}

package stirling.software.officeconvert.topdf.docx;

import java.util.ArrayList;
import java.util.List;

final class LineBreaker {

    private static final float EPS = 0.01f;

    private static final float SQUEEZE_WORD = 0.34f;

    private static final float SQUEEZE_EM = 0.1f;

    private static final float SQUEEZE_SLACK = 0.08f;

    // A word is measured in pieces and only as far as it could matter, so one long unbroken word stays linear
    private static final int PIECE = 4096;

    private static final int SCAN = 16 * PIECE;

    private final ParaItems pi;

    private final ParaProps pp;

    private final float tabUnit;

    private final float columnWidth;

    private int item;

    private int offset;

    private boolean first = true;

    private final List<TabStop> stops = new ArrayList<>();

    private float shrink;

    private Settings hyphenation;

    private int hyphenRun;

    private static final class Group {
        Line.Slice tab;
        float startX;
        float width;
        float decimal = -1;
        TabStop stop;
        int from;
    }

    LineBreaker(ParaItems pi, ParaProps pp, float tabUnit, float columnWidth) {
        this.pi = pi;
        this.pp = pp;
        this.tabUnit = tabUnit > 1 ? tabUnit : 36;
        this.columnWidth = columnWidth;
        if (pp.tabs != null) {
            for (TabStop t : pp.tabs) {
                if (t.kind() != TabStop.Kind.BAR && t.kind() != TabStop.Kind.CLEAR) {
                    stops.add(t);
                }
            }
        }
    }

    void shrink(float factor) {
        shrink = factor;
    }

    void hyphenate(Settings settings) {
        hyphenation = settings;
    }

    boolean done() {
        return item >= pi.items.size();
    }

    // Only bookmarks are left, so the paragraph mark shares the line of the break before them
    boolean restEmpty() {
        List<Item> items = pi.items;
        for (int i = item; i < items.size(); i++) {
            Item it = items.get(i);
            boolean empty = it.kind == Item.Kind.BOOKMARK || it.kind == Item.Kind.TEXT && it.length() == 0;
            if (!empty) {
                return false;
            }
        }
        return true;
    }

    boolean first() {
        return first;
    }

    int position() {
        return item;
    }

    int offset() {
        return offset;
    }

    void reset(int toItem, int toOffset, boolean isFirst) {
        item = toItem;
        offset = toOffset;
        first = isFirst;
    }

    Line next(float left, float right) {
        Line line = nextLine(left, right);
        hyphenRun = line.hyphen ? hyphenRun + 1 : 0;
        return line;
    }

    private Line nextLine(float left, float right) {
        float indentRight = right;
        Line line = new Line();
        line.left = left;
        line.right = right;
        line.startItem = item;
        line.startOffset = offset;
        boolean wasFirst = first;
        first = false;
        float x = wasFirst && item == pi.labelStart ? left - pi.labelShift() : left;
        Group group = null;
        boolean content = false;
        boolean words = false;
        float spaceW = 0;
        float lastTrail = 0;
        List<Item> items = pi.items;
        while (item < items.size()) {
            Item it = items.get(item);
            switch (it.kind) {
                case BOOKMARK, ANCHOR, NOTE -> {
                    line.zero.add(it);
                    advanceItem();
                    continue;
                }
                case BREAK -> {
                    if (group != null) {
                        x = close(group, line);
                        group = null;
                    }
                    Line.Slice br = new Line.Slice(it, 0, 1, x, 0);
                    line.slices.add(br);
                    advanceItem();
                    String t = it.breakType == null ? "textWrapping" : it.breakType;
                    if (t.equals("separator") || t.equals("continuationSeparator")) {
                        br.w = Math.max(0, Math.min(t.equals("separator") ? 144 : right - left, right - x));
                        x += br.w;
                        content = true;
                        continue;
                    }
                    line.breakType = t;
                    finish(line, x, wasFirst);
                    return line;
                }
                case TAB -> {
                    spaceW = 0;
                    lastTrail = 0;
                    if (group != null) {
                        x = close(group, line);
                        group = null;
                    }
                    TabStop stop = stopAfter(x, wasFirst, it);
                    boolean custom = stop != null && stop.pos() >= 0;
                    if (it.label && custom && stop.kind() != TabStop.Kind.LEFT) {
                        stop = new TabStop(stop.pos(), TabStop.Kind.LEFT, stop.leader());
                    }
                    if (stop == null) {
                        float pos = (float) (Math.floor((x + EPS) / tabUnit) + 1) * tabUnit;
                        stop = new TabStop(pos, TabStop.Kind.LEFT, (char) 0);
                    }
                    // A default stop never lands in the right indent, even after text right-aligned into it
                    float limit = custom ? right : Math.min(right, indentRight);
                    if (stop.pos() > right + EPS && custom && stop.kind() == TabStop.Kind.LEFT
                            && stop.pos() < columnWidth - EPS) {
                        right = columnWidth;
                    } else if (stop.pos() > limit + EPS) {
                        if ((stop.kind() == TabStop.Kind.LEFT || !custom) && content) {
                            finish(line, x, wasFirst);
                            line.breakType = null;
                            return line;
                        }
                        // A right tab set inside the right indent still aligns there; one past the margin stops at it
                        boolean inIndent = custom && stop.kind() == TabStop.Kind.RIGHT && stop.pos() > x + EPS
                                && stop.pos() <= columnWidth + EPS;
                        if (!inIndent) {
                            stop = new TabStop(Math.max(x, right), stop.kind(), stop.leader());
                        }
                    }
                    Line.Slice s = new Line.Slice(it, 0, 1, x, 0);
                    s.tab = stop;
                    line.slices.add(s);
                    content |= !it.label;
                    advanceItem();
                    if (stop.kind() == TabStop.Kind.LEFT) {
                        s.w = Math.max(0, stop.pos() - x);
                        x = Math.max(x, stop.pos());
                    } else {
                        group = new Group();
                        group.tab = s;
                        group.startX = x;
                        group.stop = stop;
                        group.from = line.slices.size();
                        if (custom && stop.kind() == TabStop.Kind.RIGHT) {
                            // Text right-aligned at a stop may run on into the right indent, as far as the margin
                            right = Math.max(right, Math.min(columnWidth, stop.pos() + pp.right()));
                        }
                    }
                    continue;
                }
                default -> {
                }
            }
            int start = it.start + offset;
            int wordEnd = wordEnd(start);
            List<Line.Slice> word = new ArrayList<>();
            List<Item> zeros = new ArrayList<>();
            float w = 0;
            int wi = item;
            int wo = offset;
            int pos = start;
            float reach = reach(x, right);
            while (pos < wordEnd && wi < items.size() && w <= reach) {
                Item wit = items.get(wi);
                if (wit.kind == Item.Kind.TAB || wit.kind == Item.Kind.BREAK) {
                    break;
                }
                if (wit.length() == 0) {
                    zeros.add(wit);
                    wi++;
                    wo = 0;
                    continue;
                }
                int to = piece(wit, wo, Math.min(wit.length(), wordEnd - wit.start));
                if (to > wo) {
                    float sw = wit.width(wo, to);
                    word.add(new Line.Slice(wit, wo, to, 0, sw));
                    w += sw;
                }
                pos = wit.start + to;
                if (to >= wit.length()) {
                    wi++;
                    wo = 0;
                } else {
                    wo = to;
                }
            }
            if (word.isEmpty()) {
                advanceItem();
                continue;
            }
            float trail = trailing(word);
            float join = joinKern(line, word);
            x += join;
            float groupW = group == null ? 0 : group.width;
            float endX = group == null ? x + w - trail : effectiveEnd(group, groupW + w - trail, word);
            float over = endX - right;
            boolean fits = over <= EPS || shrink > 0 && over - shrink * spaceW <= EPS
                    && over <= squeezeLimit(word, w - trail);
            // After nothing but tabs, a word wider than a whole line starts there and breaks at the margin
            boolean huge = !fits && content && !words && group == null && tooWide(word, w - trail, x, right);
            List<Line.Slice> hyphenated = !fits && content && group == null && !huge
                    ? hyphenPrefix(word, x, x - lastTrail, right, spaceW) : null;
            if (hyphenated != null) {
                float pw = 0;
                for (Line.Slice s : hyphenated) {
                    pw += s.w;
                }
                placeWord(line, hyphenated, x, null);
                resumeAfter(hyphenated.get(hyphenated.size() - 1));
                finish(line, x + pw, wasFirst);
                line.hyphen = true;
                line.end += hyphenWidth(hyphenated.get(hyphenated.size() - 1));
                line.breakType = null;
                return line;
            }
            if (!fits && content && !huge) {
                x -= join;
                finish(line, x, wasFirst);
                line.breakType = null;
                if (group != null) {
                    close(group, line);
                }
                return line;
            }
            if (!fits) {
                List<Line.Slice> part = splitPrefix(word, Math.max(1f, right - x));
                float pw = 0;
                for (Line.Slice s : part) {
                    pw += s.w;
                }
                placeWord(line, part, x, group);
                x += pw;
                Line.Slice lastPart = part.get(part.size() - 1);
                int li = indexOf(lastPart.item);
                if (lastPart.to >= lastPart.item.length()) {
                    item = li + 1;
                    offset = 0;
                } else {
                    item = li;
                    offset = lastPart.to;
                }
                if (group != null) {
                    group.width += pw;
                    x = close(group, line);
                }
                skipZero(line);
                // A break right after something too wide for the line still ends that line, as in Word
                String brk = trailingBreak(line, x);
                finish(line, x, wasFirst);
                line.breakType = brk != null ? brk : done() ? "end" : null;
                return line;
            }
            if (join != 0 && !line.slices.isEmpty()) {
                line.slices.get(line.slices.size() - 1).w += join;
                if (group != null) {
                    group.width += join;
                }
            }
            placeWord(line, word, x, group);
            line.zero.addAll(zeros);
            x += w;
            spaceW = Math.max(0, spaceW + join) + (trail > 0 ? Math.max(0, trail + spaceKern(word)) : 0);
            lastTrail = trail;
            if (group != null) {
                group.width += w;
                noteDecimal(group, word, groupW);
            }
            item = wi;
            offset = wo;
            content |= !word.get(0).item.label;
            words |= !word.get(0).item.label;
        }
        if (group != null) {
            x = close(group, line);
        }
        finish(line, x, wasFirst);
        line.breakType = "end";
        return line;
    }

    private boolean tooWide(List<Line.Slice> word, float width, float x, float right) {
        if (width <= right - pp.left() + EPS) {
            return false;
        }
        for (Line.Slice s : word) {
            if (s.item.kind != Item.Kind.TEXT || s.item.shaped) {
                return false;
            }
        }
        Line.Slice first = word.get(0);
        int cp = first.item.text.codePointAt(first.from);
        return right - x >= first.item.width(first.from, first.from + Character.charCount(cp)) - EPS;
    }

    private String trailingBreak(Line line, float x) {
        if (offset != 0 || item >= pi.items.size() || pi.items.get(item).kind != Item.Kind.BREAK) {
            return null;
        }
        Item br = pi.items.get(item);
        String t = br.breakType == null ? "textWrapping" : br.breakType;
        if (t.equals("separator") || t.equals("continuationSeparator")) {
            return null;
        }
        line.slices.add(new Line.Slice(br, 0, 1, x, 0));
        advanceItem();
        return t;
    }

    private void resumeAfter(Line.Slice last) {
        int li = indexOf(last.item);
        if (last.to >= last.item.length()) {
            item = li + 1;
            offset = 0;
        } else {
            item = li;
            offset = last.to;
        }
    }

    private static float hyphenWidth(Line.Slice s) {
        return s.item.look == null ? 0 : s.item.look.style().width("-");
    }

    // Word hyphenates a word that does not fit when the gap after the previous word is wider than the zone
    private List<Line.Slice> hyphenPrefix(List<Line.Slice> word, float x, float textEnd, float right, float spaceW) {
        Settings h = hyphenation;
        if (h == null || right - textEnd <= h.hyphenationZone || h.consecutiveHyphenLimit > 0
                && hyphenRun >= h.consecutiveHyphenLimit) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (Line.Slice s : word) {
            if (s.item.kind != Item.Kind.TEXT || s.item.shaped) {
                return null;
            }
            text.append(s.text());
        }
        int a = 0;
        int b = text.length();
        while (a < b && !Character.isLetter(text.charAt(a))) {
            a++;
        }
        while (b > a && !Character.isLetter(text.charAt(b - 1))) {
            b--;
        }
        String core = text.substring(a, b);
        for (int i = 0; i < core.length(); i++) {
            char c = core.charAt(i);
            if (!Character.isLetter(c) && c != '\'' && c != '\u2019') {
                return null;
            }
        }
        if (core.isEmpty() || h.doNotHyphenateCaps && core.equals(core.toUpperCase(java.util.Locale.ROOT))) {
            return null;
        }
        Hyphenator hy = Hyphenator.forLanguage(word.get(0).item.lang);
        if (hy == null) {
            return null;
        }
        int[] points = hy.points(core);
        for (int k = points.length - 1; k >= 0; k--) {
            List<Line.Slice> prefix = cut(word, a + points[k]);
            float w = 0;
            for (Line.Slice s : prefix) {
                w += s.w;
            }
            float over = x + w + hyphenWidth(prefix.get(prefix.size() - 1)) - right;
            if (over <= EPS || shrink > 0 && over - shrink * spaceW <= EPS && over <= squeezeLimit(prefix, w)) {
                return prefix;
            }
        }
        return null;
    }

    private static List<Line.Slice> cut(List<Line.Slice> word, int chars) {
        List<Line.Slice> out = new ArrayList<>();
        int used = 0;
        for (Line.Slice s : word) {
            int n = s.to - s.from;
            if (used + n <= chars) {
                out.add(new Line.Slice(s.item, s.from, s.to, 0, s.w));
                used += n;
                if (used == chars) {
                    break;
                }
                continue;
            }
            int to = s.from + (chars - used);
            out.add(new Line.Slice(s.item, s.from, to, 0, s.item.width(s.from, to)));
            break;
        }
        return out;
    }

    private static float squeezeLimit(List<Line.Slice> word, float width) {
        Line.Slice first = word.get(0);
        Item it = first.item;
        if (it.kind == Item.Kind.TEXT && first.to > first.from && Breaks.cjk(it.text.charAt(first.from))) {
            return Float.MAX_VALUE;
        }
        float size = it.look == null ? 10 : it.look.size();
        return SQUEEZE_WORD * width + SQUEEZE_EM * size + SQUEEZE_SLACK;
    }

    private static float spaceKern(List<Line.Slice> word) {
        Line.Slice last = word.get(word.size() - 1);
        if (last.item.kind != Item.Kind.TEXT || last.item.shaped || !last.item.look.style().kerning()) {
            return 0;
        }
        String t = last.item.text;
        int j = last.to;
        while (j > last.from && t.charAt(j - 1) == ' ') {
            j--;
        }
        if (j <= last.from || j >= last.to) {
            return 0;
        }
        return pairKern(last.item.look, t.codePointBefore(j), ' ');
    }

    private static float pairKern(Look look, int left, int right) {
        int units = look.face().kerning(left, right);
        return units == 0 ? 0 : look.style().points(units) * look.style().horizontalScale() / 100f;
    }

    private static float joinKern(Line line, List<Line.Slice> word) {
        if (line.slices.isEmpty() || word.isEmpty()) {
            return 0;
        }
        Line.Slice prev = line.slices.get(line.slices.size() - 1);
        Line.Slice next = word.get(0);
        if (prev.item.kind != Item.Kind.TEXT || next.item.kind != Item.Kind.TEXT || prev.to <= prev.from
                || next.to <= next.from || prev.item.shaped || next.item.shaped) {
            return 0;
        }
        Look a = prev.item.look;
        Look b = next.item.look;
        if (!a.style().kerning() || !a.style().equals(b.style())) {
            return 0;
        }
        return pairKern(a, prev.item.text.codePointBefore(prev.to), next.item.text.codePointAt(next.from));
    }

    private void advanceItem() {
        item++;
        offset = 0;
    }

    // True when the next word starts with an inline object, which cannot break across lines
    boolean peekObject() {
        List<Item> items = pi.items;
        for (int wi = item; wi < items.size(); wi++) {
            Item.Kind k = items.get(wi).kind;
            if (k == Item.Kind.TEXT && items.get(wi).length() == 0) {
                continue;
            }
            if (k == Item.Kind.TEXT || k == Item.Kind.TAB || k == Item.Kind.BREAK) {
                return false;
            }
            if (k == Item.Kind.OBJECT) {
                return true;
            }
        }
        return false;
    }

    float peekWord() {
        List<Item> items = pi.items;
        int wi = item;
        int wo = offset;
        while (wi < items.size() && items.get(wi).kind != Item.Kind.TEXT && items.get(wi).kind != Item.Kind.OBJECT
                && items.get(wi).kind != Item.Kind.TAB && items.get(wi).kind != Item.Kind.BREAK) {
            wi++;
            wo = 0;
        }
        if (wi >= items.size() || items.get(wi).kind == Item.Kind.TAB || items.get(wi).kind == Item.Kind.BREAK) {
            return 0;
        }
        int start = items.get(wi).start + wo;
        int wordEnd = wordEnd(start);
        List<Line.Slice> word = new ArrayList<>();
        int pos = start;
        float w = 0;
        float reach = reach(0, columnWidth);
        while (pos < wordEnd && wi < items.size() && w <= reach) {
            Item wit = items.get(wi);
            if (wit.kind == Item.Kind.TAB || wit.kind == Item.Kind.BREAK) {
                break;
            }
            if (wit.length() == 0) {
                wi++;
                wo = 0;
                continue;
            }
            int to = piece(wit, wo, Math.min(wit.length(), wordEnd - wit.start));
            if (to > wo) {
                Line.Slice s = new Line.Slice(wit, wo, to, 0, wit.width(wo, to));
                word.add(s);
                w += s.w;
            }
            pos = wit.start + to;
            if (to >= wit.length()) {
                wi++;
                wo = 0;
            } else {
                wo = to;
            }
        }
        return word.isEmpty() ? 0 : w - trailing(word);
    }

    private void skipZero(Line line) {
        List<Item> items = pi.items;
        while (offset == 0 && item < items.size()) {
            Item.Kind k = items.get(item).kind;
            boolean empty = k == Item.Kind.TEXT && items.get(item).length() == 0;
            if (k != Item.Kind.BOOKMARK && k != Item.Kind.ANCHOR && k != Item.Kind.NOTE && !empty) {
                return;
            }
            line.zero.add(items.get(item));
            advanceItem();
        }
    }

    private float reach(float x, float right) {
        return Math.max(0, right - x) + 2 * Math.max(columnWidth, 72);
    }

    private static int piece(Item it, int from, int to) {
        if (to - from <= PIECE) {
            return to;
        }
        int end = from + PIECE;
        return Character.isLowSurrogate(it.text.charAt(end)) ? end + 1 : end;
    }

    private int indexOf(Item it) {
        List<Item> items = pi.items;
        for (int i = Math.max(0, item); i < items.size(); i++) {
            if (items.get(i) == it) {
                return i;
            }
        }
        return items.indexOf(it);
    }

    private int wordEnd(int start) {
        int n = Math.min(pi.text.length(), start + SCAN);
        int i = start + 1;
        while (i < n && !pi.breakBefore(i)) {
            char c = pi.text.charAt(i);
            if (c == '\t' || c == '\n') {
                break;
            }
            i++;
        }
        return Math.min(i, n);
    }

    private static float trailing(List<Line.Slice> word) {
        float t = 0;
        for (int k = word.size() - 1; k >= 0; k--) {
            Line.Slice s = word.get(k);
            if (s.item.kind != Item.Kind.TEXT) {
                break;
            }
            String txt = s.text();
            int j = txt.length();
            while (j > 0 && txt.charAt(j - 1) == ' ') {
                j--;
            }
            if (j == txt.length()) {
                break;
            }
            t += s.item.width(s.from + j, s.to);
            if (j > 0) {
                break;
            }
        }
        return t;
    }

    private static void placeWord(Line line, List<Line.Slice> word, float x, Group group) {
        float at = x;
        for (Line.Slice s : word) {
            s.x = at;
            at += s.w;
            line.slices.add(s);
        }
    }

    // The end of the character cluster at k: a word too long for the line is cut only between clusters
    private static int next(String text, int k, int limit) {
        int nk = k + Character.charCount(text.codePointAt(k));
        while (nk < limit && Breaks.joined(text, nk)) {
            nk += Character.charCount(text.codePointAt(nk));
        }
        return nk;
    }

    private static List<Line.Slice> splitPrefix(List<Line.Slice> word, float avail) {
        List<Line.Slice> out = new ArrayList<>();
        float used = 0;
        for (Line.Slice s : word) {
            if (used + s.w <= avail + EPS) {
                out.add(s);
                used += s.w;
                continue;
            }
            Item it = s.item;
            int k = s.from;
            float w = 0;
            while (k < s.to) {
                int nk = next(it.text, k, s.to);
                float cw = it.width(s.from, nk);
                if (used + cw > avail + EPS) {
                    break;
                }
                w = cw;
                k = nk;
            }
            if (k == s.from && out.isEmpty()) {
                k = next(it.text, k, s.to);
                w = it.width(s.from, k);
            }
            if (k > s.from) {
                out.add(new Line.Slice(it, s.from, k, 0, w));
            }
            break;
        }
        if (out.isEmpty()) {
            out.add(word.get(0));
        }
        return out;
    }

    private static float effectiveEnd(Group g, float groupWidth, List<Line.Slice> added) {
        float tabW = tabWidth(g, groupWidth, g.decimal >= 0 ? g.decimal : groupWidth);
        return g.startX + tabW + groupWidth;
    }

    private static float tabWidth(Group g, float groupWidth, float decimal) {
        float stop = g.stop.pos();
        return switch (g.stop.kind()) {
            case RIGHT -> Math.max(0, stop - g.startX - groupWidth);
            case CENTER -> Math.max(0, stop - g.startX - groupWidth / 2);
            case DECIMAL -> Math.max(0, stop - g.startX - decimal);
            default -> Math.max(0, stop - g.startX);
        };
    }

    private static void noteDecimal(Group g, List<Line.Slice> word, float before) {
        if (g.decimal >= 0 || g.stop.kind() != TabStop.Kind.DECIMAL) {
            return;
        }
        float acc = before;
        for (Line.Slice s : word) {
            if (s.item.kind == Item.Kind.TEXT) {
                String t = s.text();
                int dot = t.indexOf('.');
                if (dot < 0) {
                    dot = t.indexOf(',');
                }
                if (dot >= 0) {
                    g.decimal = acc + s.item.width(s.from, s.from + dot);
                    return;
                }
            }
            acc += s.w;
        }
    }

    private static float close(Group g, Line line) {
        float decimal = g.decimal >= 0 ? g.decimal : g.width;
        float tw = tabWidth(g, g.width, decimal);
        g.tab.w = tw;
        for (int k = g.from; k < line.slices.size(); k++) {
            line.slices.get(k).x += tw;
        }
        return g.startX + tw + g.width;
    }

    private TabStop stopAfter(float x, boolean firstLine, Item tabItem) {
        if (tabItem.ptab != null) {
            Inline.PTab p = tabItem.ptab;
            float l = "indent".equals(p.relativeTo()) ? pp.left() : 0;
            float r = "indent".equals(p.relativeTo()) ? columnWidth - pp.right() : columnWidth;
            return switch (p.alignment()) {
                case "center" -> new TabStop((l + r) / 2, TabStop.Kind.CENTER, p.leader());
                case "right" -> new TabStop(r, TabStop.Kind.RIGHT, p.leader());
                default -> new TabStop(l, TabStop.Kind.LEFT, p.leader());
            };
        }
        TabStop best = null;
        for (TabStop t : stops) {
            if (t.pos() > x + EPS) {
                best = t;
                break;
            }
        }
        float hang = pp.left();
        if (firstLine && pp.first() < 0 && hang > x + EPS && (best == null || hang < best.pos())) {
            return new TabStop(hang, TabStop.Kind.LEFT, (char) 0);
        }
        return best;
    }

    private void finish(Line line, float x, boolean wasFirst) {
        float end = line.left;
        for (Line.Slice s : line.slices) {
            if (s.item.kind == Item.Kind.TEXT) {
                String t = s.text();
                int j = t.length();
                while (j > 0 && t.charAt(j - 1) == ' ') {
                    j--;
                }
                if (j > 0) {
                    end = Math.max(end, s.x + s.item.width(s.from, s.from + j));
                }
            } else if (s.item.kind != Item.Kind.BREAK) {
                end = Math.max(end, s.x + s.w);
            }
        }
        line.end = end;
        if (!line.slices.isEmpty()) {
            Line.Slice lastSlice = line.slices.get(line.slices.size() - 1);
            if (lastSlice.item.kind == Item.Kind.TEXT && lastSlice.to > lastSlice.from
                    && lastSlice.item.text.charAt(lastSlice.to - 1) == '\u00AD') {
                line.hyphen = true;
                line.end += hyphenWidth(lastSlice);
            }
        }
    }
}

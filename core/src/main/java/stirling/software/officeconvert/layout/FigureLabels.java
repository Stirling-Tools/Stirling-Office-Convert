package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class FigureLabels {

    private static final float SMALL = 0.85f;

    private static final float WORD_SIZE = 1.25f;

    private static final int MAX_CHARS = 24;

    private static final int MAX_WORD_CHARS = 24;

    private static final int MAX_NUMBER_CHARS = 12;

    private static final int TICKS = 4;

    private static final int MAX_TICK_CHARS = 80;

    private static final Pattern NUMBER = Pattern.compile("[-+\\u2212]?[\\d.,]+[%)]?(?:\\s+[-+\\u2212]?[\\d.,]+[%)]?)*");

    private static final float MIN_WIDTH = 60f;

    private static final float MIN_HEIGHT = 40f;

    private static final Pattern CAPTION = Pattern.compile("(?i)^(fig\\.?|figure|table|source|note)\\b.*");

    record Label(Box box, float size, String text) {}

    private FigureLabels() {}

    static Label of(Line l) {
        return new Label(new Box(l.x, l.top, l.right, l.bottom), l.size, l.text().strip());
    }

    static List<Box> grow(List<Box> figures, List<Label> lines, float bodySize) {
        if (figures.isEmpty()) {
            return figures;
        }
        List<Label> small = new ArrayList<>();
        List<Label> words = new ArrayList<>();
        for (Label l : lines) {
            if (isSmall(l, bodySize)) {
                small.add(l);
            } else if (isWord(l, bodySize)) {
                words.add(l);
            }
        }
        if (small.isEmpty() && words.isEmpty()) {
            return figures;
        }
        Boolean[] alone = new Boolean[words.size()];
        List<Box> out = new ArrayList<>();
        for (Box f : figures) {
            if (f.width() < MIN_WIDTH || f.height() < MIN_HEIGHT) {
                out.add(f);
                continue;
            }
            Box grown = f;
            boolean[] usedSmall = new boolean[small.size()];
            boolean[] usedWords = new boolean[words.size()];
            for (boolean more = true; more; ) {
                more = false;
                for (int i = 0; i < small.size(); i++) {
                    Label l = small.get(i);
                    if (!usedSmall[i] && grown.near(l.box(), reach(grown, l))) {
                        grown = grown.union(l.box());
                        usedSmall[i] = more = true;
                    }
                }
                for (int i = 0; i < words.size(); i++) {
                    Label l = words.get(i);
                    if (!usedWords[i] && beside(grown, l)
                            && (alone[i] != null ? alone[i] : (alone[i] = isolated(l, lines, bodySize)))) {
                        grown = grown.union(l.box());
                        usedWords[i] = more = true;
                    }
                }
            }
            out.add(grown);
        }
        return FigureFinder.cluster(out, 1f);
    }

    private static float reach(Box f, Label l) {
        if (NUMBER.matcher(l.text()).matches()) {
            return l.size();
        }
        boolean level = l.box().x() >= f.x() - 1 && l.box().right() <= f.right() + 1;
        return (level ? 1.2f : 0.5f) * l.size();
    }

    private static boolean beside(Box f, Label l) {
        Box b = l.box();
        float gap = b.right() <= f.x() ? f.x() - b.right() : b.x() >= f.right() ? b.x() - f.right() : 0;
        return gap <= 1.5f * l.size() && b.centreY() > f.top() && b.centreY() < f.bottom();
    }

    private static boolean isSmall(Label l, float bodySize) {
        String t = l.text();
        if (t.isEmpty() || l.size() > SMALL * bodySize || CAPTION.matcher(t).matches()) {
            return false;
        }
        if (NUMBER.matcher(t).matches()) {
            return t.length() <= MAX_NUMBER_CHARS || t.length() <= MAX_TICK_CHARS && t.split("\\s+").length >= TICKS;
        }
        return t.length() <= MAX_CHARS;
    }

    private static boolean isWord(Label l, float bodySize) {
        String t = l.text();
        return !t.isEmpty() && l.size() <= WORD_SIZE * bodySize && t.length() <= MAX_WORD_CHARS
                && t.split("\\s+").length <= 3 && !CAPTION.matcher(t).matches();
    }

    private static boolean isolated(Label l, List<Label> lines, float bodySize) {
        Box b = l.box();
        for (Label o : lines) {
            Box ob = o.box();
            if (o == l || isWord(o, bodySize) || isSmall(o, bodySize)) {
                continue;
            }
            boolean overlaps = ob.x() < b.right() && b.x() < ob.right();
            float gap = Math.max(ob.top() - b.bottom(), b.top() - ob.bottom());
            if (overlaps && gap < 1.2f * l.size()) {
                return false;
            }
        }
        return true;
    }
}

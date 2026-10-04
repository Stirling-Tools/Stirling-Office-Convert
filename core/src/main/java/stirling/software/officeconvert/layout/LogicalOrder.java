package stirling.software.officeconvert.layout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import stirling.software.officeconvert.extract.Glyph;

public final class LogicalOrder {

    public record Token(Glyph glyph, String text, byte gap) {}

    private static final String MIRRORS = "()[]{}<>\u00AB\u00BB\u2039\u203A\u2045\u2046\u207D\u207E\u208D\u208E\u2329\u232A"
            + "\u3008\u3009\u300A\u300B\u300C\u300D\u300E\u300F\u3010\u3011\u3014\u3015\u3016\u3017\u3018\u3019\u301A\u301B"
            + "\uFF08\uFF09\uFF3B\uFF3D\uFF5B\uFF5D";

    private static final String PAIRS = "()[]{}\u00AB\u00BB\u2039\u203A";

    private LogicalOrder() {}

    public static boolean hasRtl(Line line) {
        for (Word w : line.words) {
            for (Glyph g : w.glyphs) {
                if (isRtl(g.text)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean hasArabicDigits(Line line) {
        for (Word w : line.words) {
            for (Glyph g : w.glyphs) {
                for (int i = 0; i < g.text.length(); ) {
                    int cp = g.text.codePointAt(i);
                    if (Character.getDirectionality(cp) == Character.DIRECTIONALITY_ARABIC_NUMBER) {
                        return true;
                    }
                    i += Character.charCount(cp);
                }
            }
        }
        return false;
    }

    public static boolean hasLtr(Line line) {
        for (Word w : line.words) {
            for (Glyph g : w.glyphs) {
                if (isLtr(g.text)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean rtlBase(Line line) {
        int rtl = 0;
        int ltr = 0;
        Glyph left = null;
        Glyph right = null;
        for (Word w : line.words) {
            for (Glyph g : w.glyphs) {
                if (isRtl(g.text)) {
                    rtl++;
                } else if (isLtr(g.text)) {
                    ltr++;
                } else {
                    continue;
                }
                left = left == null ? g : left;
                right = g;
            }
        }
        if (left != null && isRtl(left.text) == isRtl(right.text)) {
            return isRtl(left.text);
        }
        return rtl > ltr;
    }

    public static int direction(Line line) {
        Glyph left = null;
        Glyph right = null;
        for (Word w : line.words) {
            for (Glyph g : w.glyphs) {
                if (isRtl(g.text) || isLtr(g.text)) {
                    left = left == null ? g : left;
                    right = g;
                }
            }
        }
        if (left == null || isRtl(left.text) != isRtl(right.text)) {
            return 0;
        }
        return isRtl(left.text) ? 1 : -1;
    }

    public static boolean endsRtl(Line line) {
        for (int wi = line.words.size() - 1; wi >= 0; wi--) {
            List<Glyph> gs = line.words.get(wi).glyphs;
            for (int gi = gs.size() - 1; gi >= 0; gi--) {
                String t = gs.get(gi).text;
                if (isRtl(t) || isLtr(t)) {
                    return isRtl(t);
                }
            }
        }
        return false;
    }

    public static String text(Line line, int fromWord) {
        return text(line, fromWord, rtlBase(line));
    }

    public static String text(Line line, int fromWord, boolean rtlBase) {
        StringBuilder sb = new StringBuilder();
        for (Token t : of(line, fromWord, rtlBase)) {
            if (t.glyph() == null) {
                sb.append(t.gap() == Line.SPACE ? ' ' : '\t');
            } else {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    public static List<Token> of(Line line, int fromWord, boolean rtlBase) {
        return of(line, fromWord, line.words.size(), rtlBase);
    }

    public static List<Token> of(Line line, int fromWord, int toWord, boolean rtlBase) {
        List<Token> seq = new ArrayList<>();
        for (int i = fromWord; i < toWord; i++) {
            if (i > fromWord) {
                seq.add(new Token(null, null, line.gaps[i]));
            }
            for (Glyph g : line.words.get(i).glyphs) {
                seq.add(new Token(g, g.text, (byte) 0));
            }
        }
        boolean[] rtlContext = new boolean[seq.size()];
        if (rtlBase) {
            Collections.reverse(seq);
            Arrays.fill(rtlContext, true);
            for (int[] run : rightToLeftRuns(seq)) {
                Collections.reverse(seq.subList(run[0], run[1] + 1));
                Arrays.fill(rtlContext, run[0], run[1] + 1, false);
            }
        } else {
            for (int[] run : leftToRightRuns(seq)) {
                Collections.reverse(seq.subList(run[0], run[1] + 1));
                Arrays.fill(rtlContext, run[0], run[1] + 1, true);
                for (int[] number : numbers(seq, kinds(seq), run[0], run[1])) {
                    Collections.reverse(seq.subList(number[0], number[1] + 1));
                    Arrays.fill(rtlContext, number[0], number[1] + 1, false);
                }
            }
        }
        return mirrored(seq, rtlContext);
    }

    private static List<int[]> leftToRightRuns(List<Token> seq) {
        List<int[]> out = new ArrayList<>();
        int i = 0;
        while (i < seq.size()) {
            if (seq.get(i).glyph() == null || !isRtl(seq.get(i).text())) {
                i++;
                continue;
            }
            int end = i;
            for (int j = i + 1; j < seq.size(); j++) {
                Token t = seq.get(j);
                if (t.glyph() != null && isRtl(t.text())) {
                    end = j;
                } else if (t.glyph() != null && isLtr(t.text())) {
                    break;
                }
            }
            out.add(new int[] {i, end});
            i = end + 1;
        }
        return out;
    }

    private static List<int[]> rightToLeftRuns(List<Token> seq) {
        char[] k = kinds(seq);
        boolean[] used = new boolean[seq.size()];
        List<int[]> out = new ArrayList<>();
        int i = 0;
        while (i < seq.size()) {
            if (k[i] != 'L') {
                i++;
                continue;
            }
            int end = i;
            for (int j = i + 1; j < seq.size() && k[j] != 'R' && k[j] != 'A'; j++) {
                if (k[j] == 'L') {
                    end = j;
                }
            }
            int start = i;
            while (start > 0 && !used[start - 1] && hyphen(seq.get(start - 1))
                    && (start < 2 || k[start - 2] != 'R' && k[start - 2] != 'A')) {
                start--;
            }
            start = numberBefore(seq, k, used, start);
            end = numberAfter(k, end);
            out.add(new int[] {start, end});
            Arrays.fill(used, start, end + 1, true);
            i = end + 1;
        }
        for (int[] number : numbers(seq, k, 0, seq.size() - 1)) {
            boolean free = true;
            for (int t = number[0]; t <= number[1]; t++) {
                free &= !used[t];
            }
            if (free) {
                out.add(number);
            }
        }
        return out;
    }

    private static int numberBefore(List<Token> seq, char[] k, boolean[] used, int start) {
        int j = start - 1;
        boolean spaced = false;
        boolean glued = false;
        while (j >= 0 && !used[j] && "NLRA".indexOf(k[j]) < 0) {
            spaced |= k[j] == ' ';
            glued |= k[j] != ' ';
            j--;
        }
        if (j < 0 || used[j] || k[j] != 'N' || spaced && glued || glued && arabicNumber(seq.get(j).text())) {
            return start;
        }
        int g = j;
        while (g > 0 && numeric(k[g - 1]) && !used[g - 1]) {
            g--;
        }
        while (k[g] == 'C' || k[g] == 'E') {
            g++;
        }
        return g;
    }

    private static int numberAfter(char[] k, int end) {
        if (end + 1 >= k.length || k[end + 1] != 'N' && k[end + 1] != 'T') {
            return end;
        }
        int e = end + 1;
        while (e + 1 < k.length && numeric(k[e + 1])) {
            e++;
        }
        while (k[e] == 'C' || k[e] == 'E') {
            e--;
        }
        return new String(k, end + 1, e - end).indexOf('N') >= 0 ? e : end;
    }

    private static boolean hyphen(Token t) {
        return t.glyph() != null && t.text().length() == 1 && "-\u2010\u2011\u00AD".indexOf(t.text().charAt(0)) >= 0;
    }

    private static boolean numeric(char kind) {
        return kind == 'N' || kind == 'C' || kind == 'E' || kind == 'T';
    }

    private static char[] kinds(List<Token> seq) {
        char[] k = new char[seq.size()];
        for (int i = 0; i < k.length; i++) {
            Token t = seq.get(i);
            if (t.glyph() == null) {
                k[i] = ' ';
            } else if (isRtl(t.text())) {
                k[i] = arabic(t.text()) ? 'A' : 'R';
            } else if (isLtr(t.text())) {
                k[i] = 'L';
            } else if (isNumber(t.text())) {
                k[i] = 'N';
            } else if (t.text().isBlank()) {
                k[i] = ' ';
            } else {
                k[i] = switch (t.text().length() == 1 ? Character.getDirectionality(t.text().charAt(0)) : -1) {
                    case Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR -> 'C';
                    case Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR -> 'E';
                    case Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR -> 'T';
                    default -> 'O';
                };
            }
        }
        return k;
    }

    private static List<int[]> numbers(List<Token> seq, char[] k, int from, int to) {
        List<int[]> out = new ArrayList<>();
        int i = from;
        while (i <= to) {
            if (k[i] != 'N') {
                i++;
                continue;
            }
            boolean european = !arabicNumber(seq.get(i).text()) && !afterArabic(k, i);
            int start = i;
            int end = i;
            int j = i + 1;
            while (j <= to) {
                if (k[j] == 'N') {
                    end = j;
                    j++;
                } else if ((k[j] == 'C' || k[j] == 'E' && european) && j + 1 <= to && k[j + 1] == 'N') {
                    j++;
                } else {
                    break;
                }
            }
            if (european) {
                while (start > from && k[start - 1] == 'T') {
                    start--;
                }
                while (end < to && k[end + 1] == 'T') {
                    end++;
                }
            }
            if (end > start) {
                out.add(new int[] {start, end});
            }
            i = end + 1;
        }
        return out;
    }

    private static boolean afterArabic(char[] k, int i) {
        for (int j = i - 1; j >= 0; j--) {
            if (k[j] == 'A') {
                return true;
            }
            if (k[j] == 'R' || k[j] == 'L') {
                return false;
            }
        }
        return false;
    }

    private static boolean arabic(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.getDirectionality(s.charAt(i)) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                return true;
            }
        }
        return false;
    }

    private static boolean arabicNumber(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.getDirectionality(s.charAt(i)) == Character.DIRECTIONALITY_ARABIC_NUMBER) {
                return true;
            }
        }
        return false;
    }

    private static List<Token> mirrored(List<Token> seq, boolean[] rtlContext) {
        boolean any = false;
        for (int i = 0; i < seq.size() && !any; i++) {
            any = rtlContext[i] && seq.get(i).glyph() != null && mirrorable(seq.get(i).text());
        }
        if (!any) {
            return seq;
        }
        StringBuilder plain = new StringBuilder();
        StringBuilder flipped = new StringBuilder();
        List<Token> out = new ArrayList<>(seq.size());
        for (int i = 0; i < seq.size(); i++) {
            Token t = seq.get(i);
            String text = t.glyph() == null ? " " : t.text();
            String flip = t.glyph() != null && rtlContext[i] ? mirror(text) : text;
            plain.append(text);
            flipped.append(flip);
            out.add(flip.equals(text) ? t : new Token(t.glyph(), flip, t.gap()));
        }
        return score(flipped) < score(plain) ? out : seq;
    }

    private static boolean mirrorable(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (MIRRORS.indexOf(s.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    static String mirror(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int k = MIRRORS.indexOf(c);
            sb.append(k < 0 ? c : MIRRORS.charAt(k ^ 1));
        }
        return sb.toString();
    }

    private static int score(CharSequence s) {
        Deque<Character> open = new ArrayDeque<>();
        int unmatched = 0;
        char first = 0;
        char last = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c)) {
                first = first == 0 ? c : first;
                last = c;
            }
            int k = PAIRS.indexOf(c);
            if (k < 0) {
                continue;
            }
            if ((k & 1) == 0) {
                open.push(c);
            } else if (!open.isEmpty() && open.peek() == PAIRS.charAt(k - 1)) {
                open.pop();
            } else {
                unmatched++;
            }
        }
        int f = PAIRS.indexOf(first);
        int l = PAIRS.indexOf(last);
        int ends = (f >= 0 && (f & 1) == 1 ? 1 : 0) + (l >= 0 && (l & 1) == 0 ? 1 : 0);
        return 2 * (unmatched + open.size()) + ends;
    }

    public static boolean isRtl(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            byte d = cp < 0x0590 ? Character.DIRECTIONALITY_LEFT_TO_RIGHT : Character.getDirectionality(cp);
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    public static boolean isLtr(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (Character.getDirectionality(cp) == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    public static boolean isNumber(String s) {
        for (int i = 0; i < s.length(); i++) {
            byte d = Character.getDirectionality(s.charAt(i));
            if (d == Character.DIRECTIONALITY_EUROPEAN_NUMBER || d == Character.DIRECTIONALITY_ARABIC_NUMBER) {
                return true;
            }
        }
        return false;
    }
}

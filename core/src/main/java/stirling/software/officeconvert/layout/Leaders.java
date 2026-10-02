package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.List;

import stirling.software.officeconvert.extract.Glyph;

final class Leaders {

    static final float TAB_GAP_EM = 1.4f;

    private Leaders() {}

    static Line classifyLeaders(Line line) {
        Line split = splitGlued(line);
        if (split != line) {
            Line classified = classify(split);
            if (classified != split) {
                return classified;
            }
        }
        return classify(line);
    }

    private static Line splitGlued(Line line) {
        List<Word> out = new ArrayList<>();
        List<Byte> gaps = new ArrayList<>();
        boolean changed = false;
        for (int i = 0; i < line.words.size(); i++) {
            List<Word> parts = splitWord(line.words.get(i));
            changed |= parts.size() > 1;
            for (int k = 0; k < parts.size(); k++) {
                out.add(parts.get(k));
                gaps.add(k == 0 ? line.gaps[i] : Line.SPACE);
            }
        }
        if (!changed) {
            return line;
        }
        byte[] g = new byte[gaps.size()];
        for (int i = 0; i < g.length; i++) {
            g[i] = gaps.get(i);
        }
        Line result = new Line(out, g);
        result.drawnSpace = line.drawnSpace;
        return result;
    }

    private static List<Word> splitWord(Word w) {
        List<Glyph> glyphs = w.glyphs;
        int start = -1;
        int end = -1;
        int chars = 0;
        for (int i = 0; i < glyphs.size(); i++) {
            if (!isLeader(glyphs.get(i).text)) {
                continue;
            }
            int j = i;
            int n = 0;
            while (j < glyphs.size() && isLeader(glyphs.get(j).text)) {
                n += glyphs.get(j).text.length();
                j++;
            }
            if (n >= 4) {
                start = i;
                end = j;
                chars = n;
                break;
            }
            i = j;
        }
        if (chars == 0 || start == 0 && end == glyphs.size()) {
            return List.of(w);
        }
        List<Word> parts = new ArrayList<>(3);
        if (start > 0) {
            parts.add(new Word(glyphs.subList(0, start)));
        }
        parts.add(new Word(glyphs.subList(start, end)));
        if (end < glyphs.size()) {
            parts.add(new Word(glyphs.subList(end, glyphs.size())));
        }
        return parts;
    }

    private static Line classify(Line line) {
        List<Word> words = line.words;
        boolean any = false;
        for (int i = 0; i < words.size(); i++) {
            if (isLeader(words.get(i).text) && words.get(i).text.length() >= 4
                    || isDotRun(words, i)) {
                any = true;
                break;
            }
        }
        List<Word> out = new ArrayList<>();
        List<Byte> gaps = new ArrayList<>();
        List<Character> leaderChars = new ArrayList<>();
        byte pending = Line.SPACE;
        char leaderChar = 0;
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            byte gap = i == 0 ? Line.SPACE : line.gaps[i];
            if (i > 0 && gap == Line.SPACE) {
                Word prev = words.get(i - 1);
                float size = Math.max(prev.size(), w.size());
                if (w.x - prev.right > TAB_GAP_EM * size) {
                    gap = Line.TAB;
                }
            }
            if (any && (isLeader(w.text) && (w.text.length() >= 4 || isDotRun(words, i)))) {
                pending = Line.LEADER;
                leaderChar = w.text.charAt(0);
                continue;
            }
            char lc = 0;
            if (pending == Line.LEADER) {
                gap = Line.LEADER;
                lc = leaderChar;
                pending = Line.SPACE;
            }
            if (out.isEmpty()) {
                gap = Line.SPACE;
            }
            out.add(w);
            gaps.add(gap);
            leaderChars.add(lc);
        }
        if (out.isEmpty() || pending == Line.LEADER) {
            return line;
        }
        byte[] g = new byte[gaps.size()];
        char[] lcs = new char[gaps.size()];
        for (int i = 0; i < g.length; i++) {
            g[i] = gaps.get(i);
            lcs[i] = leaderChars.get(i);
        }
        Line result = new Line(out, g);
        result.leaders = lcs;
        result.drawnSpace = line.drawnSpace;
        return result;
    }

    private static boolean isDotRun(List<Word> words, int i) {
        int n = 0;
        for (int j = Math.max(0, i - 3); j <= Math.min(words.size() - 1, i + 3); j++) {
            if (isLeader(words.get(j).text)) {
                n++;
            }
        }
        return n >= 4 && isLeader(words.get(i).text);
    }

    static boolean isLeader(String word) {
        if (word.isEmpty()) {
            return false;
        }
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (c != '.' && c != '·' && c != '…' && c != '․') {
                return false;
            }
        }
        return true;
    }
}

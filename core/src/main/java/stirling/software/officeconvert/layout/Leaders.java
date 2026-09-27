package stirling.software.officeconvert.layout;

import java.util.ArrayList;
import java.util.List;

final class Leaders {

    static final float TAB_GAP_EM = 1.4f;

    private Leaders() {}

    static Line classifyLeaders(Line line) {
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

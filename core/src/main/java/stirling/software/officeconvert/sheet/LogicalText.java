package stirling.software.officeconvert.sheet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.layout.Line;

final class LogicalText {

    private record Token(Glyph glyph, byte gap) {}

    private LogicalText() {}

    static String of(Line line, int from) {
        List<Token> seq = new ArrayList<>();
        for (int i = from; i < line.words.size(); i++) {
            if (i > from) {
                seq.add(new Token(null, line.gaps[i]));
            }
            for (Glyph g : line.words.get(i).glyphs) {
                seq.add(new Token(g, (byte) 0));
            }
        }
        int rtl = 0;
        int ltr = 0;
        for (Token t : seq) {
            if (t.glyph() != null) {
                rtl += isRtl(t.glyph()) ? 1 : 0;
                ltr += isLtr(t.glyph()) ? 1 : 0;
            }
        }
        if (rtl > 0) {
            if (rtl > ltr) {
                Collections.reverse(seq);
                reverseRuns(seq, true);
            } else {
                reverseRuns(seq, false);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Token t : seq) {
            if (t.glyph() == null) {
                sb.append(t.gap() == Line.SPACE ? ' ' : '\t');
            } else {
                sb.append(t.glyph().text);
            }
        }
        return sb.toString();
    }

    private static void reverseRuns(List<Token> seq, boolean ltrRuns) {
        int i = 0;
        while (i < seq.size()) {
            if (!strong(seq.get(i), ltrRuns)) {
                i++;
                continue;
            }
            int end = i;
            for (int j = i + 1; j < seq.size(); j++) {
                if (strong(seq.get(j), ltrRuns)) {
                    end = j;
                } else if (strong(seq.get(j), !ltrRuns)) {
                    break;
                }
            }
            Collections.reverse(seq.subList(i, end + 1));
            i = end + 1;
        }
    }

    private static boolean strong(Token t, boolean ltr) {
        if (t.glyph() == null) {
            return false;
        }
        return ltr ? isLtr(t.glyph()) || isNumber(t.glyph()) : isRtl(t.glyph());
    }

    static boolean isRtl(Glyph g) {
        for (int i = 0; i < g.text.length(); i++) {
            byte d = Character.getDirectionality(g.text.charAt(i));
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLtr(Glyph g) {
        for (int i = 0; i < g.text.length(); i++) {
            if (Character.getDirectionality(g.text.charAt(i)) == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNumber(Glyph g) {
        for (int i = 0; i < g.text.length(); i++) {
            byte d = Character.getDirectionality(g.text.charAt(i));
            if (d == Character.DIRECTIONALITY_EUROPEAN_NUMBER || d == Character.DIRECTIONALITY_ARABIC_NUMBER) {
                return true;
            }
        }
        return false;
    }
}

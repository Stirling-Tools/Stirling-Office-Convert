package stirling.software.officeconvert.build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import stirling.software.officeconvert.extract.Glyph;
import stirling.software.officeconvert.layout.Line;
import stirling.software.officeconvert.layout.Word;

final class BidiOrder {

    record Token(Glyph glyph, byte gap) {}

    private BidiOrder() {}

    static boolean hasRtl(Line line) {
        for (Word w : line.words) {
            for (Glyph g : w.glyphs) {
                if (isRtl(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean rtlBase(Line line) {
        int rtl = 0;
        int ltr = 0;
        for (Word w : line.words) {
            for (Glyph g : w.glyphs) {
                if (isRtl(g)) {
                    rtl++;
                } else if (isLtr(g)) {
                    ltr++;
                }
            }
        }
        return rtl > ltr;
    }

    static List<Token> logical(Line line) {
        List<Token> seq = new ArrayList<>();
        for (int i = 0; i < line.words.size(); i++) {
            if (i > 0) {
                seq.add(new Token(null, line.gaps[i]));
            }
            for (Glyph g : line.words.get(i).glyphs) {
                seq.add(new Token(g, (byte) 0));
            }
        }
        boolean base = rtlBase(line);
        if (base) {
            Collections.reverse(seq);
            reverseRuns(seq, true);
        } else {
            reverseRuns(seq, false);
        }
        return seq;
    }

    private static void reverseRuns(List<Token> seq, boolean ltrRuns) {
        int i = 0;
        while (i < seq.size()) {
            if (!strongOf(seq.get(i), ltrRuns)) {
                i++;
                continue;
            }
            int end = i;
            int j = i + 1;
            while (j < seq.size()) {
                Token t = seq.get(j);
                if (strongOf(t, ltrRuns)) {
                    end = j;
                } else if (strongOf(t, !ltrRuns)) {
                    break;
                }
                j++;
            }
            Collections.reverse(seq.subList(i, end + 1));
            i = end + 1;
        }
    }

    private static boolean strongOf(Token t, boolean ltr) {
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

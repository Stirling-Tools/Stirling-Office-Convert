package stirling.software.officeconvert.layout;

import java.text.BreakIterator;
import java.util.Locale;

import stirling.software.officeconvert.extract.Glyph;

public final class BreakUnits {

    private static final Locale THAI = Locale.forLanguageTag("th");

    private static final char TSHEG = 0x0F0B;

    private static final char SHAD = 0x0F0D;

    private BreakUnits() {}

    public static boolean unspaced(Line l) {
        return LineTraits.unspaced(l);
    }

    public static float first(Word w) {
        return upTo(w, -1);
    }

    public static float syllable(Word w) {
        return upTo(w, syllable(w.text));
    }

    private static float upTo(Word w, int syllable) {
        int end = syllable;
        for (int i = 0; i < w.text.length() && end < 0; i++) {
            char c = w.text.charAt(i);
            end = c == TSHEG || c == SHAD ? i + 1 : -1;
        }
        if (end < 0) {
            BreakIterator it = BreakIterator.getLineInstance(THAI);
            it.setText(w.text);
            end = it.following(0);
        }
        int chars = 0;
        for (Glyph g : w.glyphs) {
            chars += g.text.length();
            if (end != BreakIterator.DONE && chars >= end) {
                return g.right() - w.x;
            }
        }
        return w.width();
    }

    private static int syllable(String text) {
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            Character.UnicodeScript s = Character.UnicodeScript.of(c);
            if (s != Character.UnicodeScript.MYANMAR && s != Character.UnicodeScript.KHMER) {
                return -1;
            }
            char prev = text.charAt(i - 1);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (Character.isLetter(c) && prev != 0x1039 && prev != 0x17D2 && next != 0x103A && next != 0x1039) {
                return i;
            }
        }
        return -1;
    }
}

package stirling.software.officeconvert.topdf.font;

import java.text.BreakIterator;
import java.util.Locale;

/** Where text may not be split: inside a character cluster, and where a dictionary finds no word end. */
public final class Clusters {

    private Clusters() {}

    // Never inside a character, before a mark, around a joiner, or inside an emoji or flag sequence
    public static boolean joined(CharSequence text, int i) {
        char cur = text.charAt(i);
        if (Character.isLowSurrogate(cur) && Character.isHighSurrogate(text.charAt(i - 1))) {
            return true;
        }
        int cp = Character.codePointAt(text, i);
        int prev = Character.codePointBefore(text, i);
        int type = Character.getType(cp);
        if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK || cp == 0x200D || prev == 0x200D || stacker(prev)) {
            return true;
        }
        if (cp >= 0xFE00 && cp <= 0xFE0F || cp >= 0xE0000 && cp <= 0xE01EF || cp >= 0x1F3FB && cp <= 0x1F3FF) {
            return true;
        }
        if (regional(cp) && regional(prev)) {
            int count = 0;
            for (int k = i; k > 0 && regional(Character.codePointBefore(text, k)); k -= 2) {
                count++;
            }
            return count % 2 == 1;
        }
        return false;
    }

    // A virama or coeng joins the next consonant to its syllable
    private static boolean stacker(int cp) {
        return cp == 0x17D2 || cp == 0x1039 || cp == 0x0DCA || cp >= 0x0900 && cp <= 0x0D7F && (cp & 0x7F) == 0x4D;
    }

    private static boolean regional(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    /** Break opportunities inside runs of Thai, Lao, Khmer and Myanmar, which put no spaces between words. */
    public static boolean[] dictionaryBreaks(String text) {
        int n = text.length();
        boolean[] brk = new boolean[n + 1];
        int i = 0;
        while (i < n) {
            if (!dictionary(text.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < n && dictionary(text.charAt(i))) {
                i++;
            }
            BreakIterator it = BreakIterator.getLineInstance(Locale.of("th"));
            it.setText(text.substring(start, i));
            for (int b = it.first(); b != BreakIterator.DONE; b = it.next()) {
                if (start + b > start && start + b < i) {
                    brk[start + b] = true;
                }
            }
        }
        return brk;
    }

    private static boolean dictionary(char c) {
        return c >= 0x0E00 && c <= 0x0EFF || c >= 0x1000 && c <= 0x109F || c >= 0x1780 && c <= 0x17FF;
    }
}

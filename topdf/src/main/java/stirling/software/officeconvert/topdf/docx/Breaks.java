package stirling.software.officeconvert.topdf.docx;

import java.text.BreakIterator;
import java.util.Locale;

final class Breaks {

    private static final String NO_BREAK_BEFORE = "!),.:;?]}\u00BB\u2019\u201D\u2026\u3001\u3002\u3009\u300B\u300D\u300F"
            + "\u3011\u3015\u3017\u3019\u301F\u30FB\u30FC\u309B\u309C\u309D\u309E\u30FD\u30FE\u3005\uFF01\uFF09\uFF0C"
            + "\uFF0E\uFF1A\uFF1B\uFF1F\uFF3D\uFF5D\uFF60\u3041\u3043\u3045\u3047\u3049\u3063\u3083\u3085\u3087\u308E"
            + "\u30A1\u30A3\u30A5\u30A7\u30A9\u30C3\u30E3\u30E5\u30E7\u30EE\u30F5\u30F6\uFF61\uFF63\uFF64";

    private static final String NO_BREAK_AFTER = "([{\u00AB\u2018\u201C\u3008\u300A\u300C\u300E\u3010\u3014\u3016"
            + "\u3018\u301D\uFF08\uFF3B\uFF5B\uFF5F\uFF62";

    private Breaks() {}

    static boolean[] compute(String text) {
        int n = text.length();
        boolean[] brk = new boolean[n + 1];
        for (int i = 1; i < n; i++) {
            char prev = text.charAt(i - 1);
            char cur = text.charAt(i);
            brk[i] = between(text, i, prev, cur);
        }
        brk[n] = true;
        dictionaryBreaks(text, brk);
        return brk;
    }

    private static boolean between(String text, int i, char prev, char cur) {
        if (cur == ' ' || cur == '\u3000' && !cjk(prev)) {
            return false;
        }
        if (cur == '\t' || cur == '\n' || prev == '\t' || prev == '\n') {
            return true;
        }
        if (prev == ' ' || prev == '\u3000' || prev == '\u200B' || prev == '\u00AD') {
            return cur != '\u00A0' && cur != '\u202F';
        }
        if (prev == '\uFFFC' || cur == '\uFFFC') {
            return true;
        }
        if (prev == '-') {
            // Word breaks after a hyphen attached to what precedes it, also inside URLs and before digits
            char before = i >= 2 ? text.charAt(i - 2) : ' ';
            return before != ' ' && before != ' ' && before != '\t' && before != '\n' && before != '('
                    && (before != '-' || Character.isLetterOrDigit(cur)) && cur != '-' && cur != ' '
                    && cur != ' ' && NO_BREAK_BEFORE.indexOf(cur) < 0;
        }
        if (prev == '\u2010' || prev == '\u2012' || prev == '\u2013' || prev == '\u2014' || prev == '\u2015') {
            return NO_BREAK_BEFORE.indexOf(cur) < 0 && cur != ' ';
        }
        if (cjk(prev) || cjk(cur)) {
            if (NO_BREAK_BEFORE.indexOf(cur) >= 0 || NO_BREAK_AFTER.indexOf(prev) >= 0) {
                return false;
            }
            if (cur == '\u00A0' || prev == '\u00A0') {
                return false;
            }
            return true;
        }
        return false;
    }

    static boolean cjk(char c) {
        return c >= 0x2E80 && c <= 0x9FFF || c >= 0xF900 && c <= 0xFAFF || c >= 0xFF00 && c <= 0xFFEF
                || c >= 0xAC00 && c <= 0xD7AF || c >= 0x3000 && c <= 0x303F || Character.isSurrogate(c);
    }

    private static boolean dictionary(char c) {
        return c >= 0x0E00 && c <= 0x0EFF || c >= 0x1000 && c <= 0x109F || c >= 0x1780 && c <= 0x17FF;
    }

    private static void dictionaryBreaks(String text, boolean[] brk) {
        int n = text.length();
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
            String run = text.substring(start, i);
            BreakIterator it = BreakIterator.getLineInstance(Locale.of("th"));
            it.setText(run);
            for (int b = it.first(); b != BreakIterator.DONE; b = it.next()) {
                if (b > 0 && b < run.length()) {
                    brk[start + b] = true;
                }
            }
        }
    }
}

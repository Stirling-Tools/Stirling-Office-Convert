package stirling.software.officeconvert.extract;

import java.util.List;
import java.util.Map;

final class WordContext {

    private static final float LINE_SLACK = 0.3f;

    private static final float LETTER_GAP = 0.15f;

    private WordContext() {}

    static String choose(List<Glyph> glyphs, int index, String alternative, Map<Glyph, String> alternatives) {
        Glyph g = glyphs.get(index);
        int upper = 0;
        int lower = 0;
        int digits = 0;
        int before = 0;
        int left = 0;
        int right = 0;
        for (int step = -1; step <= 1; step += 2) {
            Glyph near = g;
            for (int i = index + step; i >= 0 && i < glyphs.size(); i += step) {
                Glyph next = glyphs.get(i);
                if (!(step < 0 ? touching(next, near) : touching(near, next))) {
                    break;
                }
                String text = effective(next, alternatives);
                if (text.length() != 1 || !Character.isLetterOrDigit(text.charAt(0))) {
                    break;
                }
                near = next;
                before += step < 0 && Character.isLetter(text.charAt(0)) ? 1 : 0;
                left += step < 0 ? 1 : 0;
                right += step > 0 ? 1 : 0;
                if (!text.equals(next.text) || !alternatives.containsKey(next)) {
                    char c = text.charAt(0);
                    upper += Character.isUpperCase(c) ? 1 : 0;
                    lower += Character.isLowerCase(c) ? 1 : 0;
                    digits += Character.isDigit(c) ? 1 : 0;
                }
            }
        }
        int letters = upper + lower;
        if (g.text.equals(UnmappedGlyphs.STAND_IN)) {
            return right > 0 ? alternative : g.text;
        }
        char mine = g.text.charAt(0);
        char other = alternative.charAt(0);
        if (Character.isDigit(mine) != Character.isDigit(other)) {
            boolean digit = digits > letters;
            if (digits == letters) {
                return g.text;
            }
            return Character.isDigit(mine) == digit ? g.text : alternative;
        }
        if (letters == 0) {
            return mine == 'l' && other == 'I' ? alternative : g.text;
        }
        if (lower > 0 && before == 0) {
            return g.text;
        }
        boolean capitals = lower == 0;
        return Character.isUpperCase(mine) == capitals ? g.text
                : Character.isUpperCase(other) == capitals ? alternative : g.text;
    }

    private static String effective(Glyph g, Map<Glyph, String> alternatives) {
        if (g.text.equals(UnmappedGlyphs.STAND_IN)) {
            String guess = alternatives.get(g);
            return guess == null ? g.text : guess;
        }
        return g.text;
    }

    private static boolean touching(Glyph left, Glyph right) {
        float size = Math.max(Math.max(left.size, right.size), 1e-3f);
        float gap = right.x - left.right();
        return Math.abs(left.baseline - right.baseline) <= LINE_SLACK * size && gap <= LETTER_GAP * size
                && gap >= -LINE_SLACK * size;
    }
}

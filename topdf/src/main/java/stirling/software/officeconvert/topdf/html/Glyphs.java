package stirling.software.officeconvert.topdf.html;

final class Glyphs {

    private Glyphs() {}

    static float estimate(String text, float size) {
        float ems = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            ems += wide(cp) ? 1f : 0.5f;
            i += Character.charCount(cp);
        }
        return ems * size;
    }

    static float word(String word, float size, boolean bold) {
        float widest = 0;
        float run = 0;
        for (int i = 0; i < word.length(); ) {
            int cp = word.codePointAt(i);
            i += Character.charCount(cp);
            if (wide(cp)) {
                widest = Math.max(Math.max(widest, run), 1f);
                run = 0;
                continue;
            }
            run += advance(cp);
        }
        widest = Math.max(widest, run);
        return widest == 0 ? 0 : (widest * (bold ? 1.08f : 1f)) * size + 1f;
    }

    private static float advance(int cp) {
        if (cp >= '0' && cp <= '9') {
            return 0.56f;
        }
        if (cp == 'm' || cp == 'w' || cp == 'M' || cp == 'W') {
            return 0.9f;
        }
        if ("iljtfrI.,:;'!|".indexOf(cp) >= 0) {
            return 0.33f;
        }
        if (Character.isUpperCase(cp)) {
            return 0.7f;
        }
        return Character.isLetter(cp) ? 0.5f : 0.56f;
    }

    static boolean wide(int cp) {
        Character.UnicodeScript s = Character.UnicodeScript.of(cp);
        return s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA
                || s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL
                || cp >= 0x3000 && cp <= 0x303F || cp >= 0xFF00 && cp <= 0xFF60;
    }
}

package stirling.software.officeconvert.topdf.html;

import java.util.HashMap;
import java.util.Map;

import stirling.software.officeconvert.topdf.font.FontFace;
import stirling.software.officeconvert.topdf.font.FontLibrary;

final class Glyphs {

    private static final String SAMPLE = "The quick brown fox jumps over the lazy dog 0123456789";

    private static final ThreadLocal<FontLibrary> FONTS = new ThreadLocal<>();

    private static final ThreadLocal<Map<String, Float>> FACTORS = ThreadLocal.withInitial(HashMap::new);

    private Glyphs() {}

    static void fonts(FontLibrary fonts) {
        FONTS.set(fonts);
        FACTORS.get().clear();
    }

    static void clearFonts() {
        FONTS.remove();
        FACTORS.remove();
    }

    static float factor(String family, boolean bold) {
        FontLibrary fonts = FONTS.get();
        if (fonts == null || family == null) {
            return 1f;
        }
        return FACTORS.get().computeIfAbsent(family + (bold ? "|b" : "|r"), k -> {
            FontFace face = fonts.find(family, bold, false);
            if (face == null || face.unitsPerEm() <= 0) {
                return 1f;
            }
            float actual = 0;
            float guessed = 0;
            for (int i = 0; i < SAMPLE.length(); i++) {
                int cp = SAMPLE.charAt(i);
                actual += face.advance(cp) / (float) face.unitsPerEm();
                guessed += cp == ' ' ? 0.25f : advance(cp) * (bold ? 1.08f : 1f);
            }
            float f = guessed <= 0 ? 1f : actual / guessed;
            return Float.isFinite(f) ? Math.max(0.8f, Math.min(1.35f, f)) : 1f;
        });
    }

    static float estimate(String text, float size, String family, boolean bold) {
        return estimate(text, size) * Math.max(1f, factor(family, bold));
    }

    static float word(String word, float size, boolean bold, String family) {
        return word(word, size, bold) * Math.max(1f, factor(family, bold));
    }

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

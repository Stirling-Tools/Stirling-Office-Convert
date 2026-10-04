package stirling.software.officeconvert.build;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.Glyph;

class RunBuilderScriptCheckTest {

    private static final FontInfo STAND_IN = new FontInfo("Arial", false, false, false, false, false, "ArialMT", true);

    private static final FontInfo EXACT = new FontInfo("Arial", false, false, false, false, false, "ArialMT", false);

    @Test
    void scriptChecksAgreeWithUnicodeForEveryCodePoint() {
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            String text = new String(Character.toChars(cp));
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            for (FontInfo font : new FontInfo[] {STAND_IN, EXACT}) {
                Glyph g = glyph(text, font);
                assertEquals(script == Character.UnicodeScript.HEBREW, RunBuilder.hebrew(g), text);
                if (RunBuilder.modeled(g) == null) {
                    boolean expected = switch (script) {
                        case LATIN, GREEK, CYRILLIC, COMMON, INHERITED, HAN, HIRAGANA, KATAKANA -> false;
                        case HEBREW -> !font.substituted();
                        case ARABIC -> !RunBuilder.arabicStandIn(g);
                        default -> true;
                    };
                    assertEquals(expected, RunBuilder.unmeasured(g), Integer.toHexString(cp));
                }
            }
            if (cp <= Character.MAX_VALUE) {
                Character.UnicodeBlock b = Character.UnicodeBlock.of((char) cp);
                boolean cjk = b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || b == Character.UnicodeBlock.HIRAGANA
                        || b == Character.UnicodeBlock.KATAKANA || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                        || b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                        || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A;
                assertEquals(cjk, RunBuilder.isCjk((char) cp), Integer.toHexString(cp));
            }
        }
    }

    @Test
    void mixedTextFindsTheScriptAfterLatin() {
        assertEquals(true, RunBuilder.hebrew(glyph("abא", EXACT)));
        assertEquals(true, RunBuilder.arabicStandIn(glyph("1ب", STAND_IN)));
        assertEquals(true, RunBuilder.unmeasured(glyph("xก", EXACT)));
        assertEquals(false, RunBuilder.unmeasured(glyph("xé́", EXACT)));
    }

    private static Glyph glyph(String text, FontInfo font) {
        return new Glyph(text, 0, 5, 10, 10, 8, 2, font, 0, 0, 2.5f, false, false);
    }
}

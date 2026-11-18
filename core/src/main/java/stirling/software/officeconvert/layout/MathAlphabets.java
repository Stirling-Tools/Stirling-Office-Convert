package stirling.software.officeconvert.layout;

import java.util.Locale;
import java.util.Map;

import stirling.software.officeconvert.extract.FontInfo;
import stirling.software.officeconvert.extract.FontNames;

// TeX alphabet fonts (msbm, eufm, rsfs, cmsy capitals) extract as plain letters; map them to math alphabets.
final class MathAlphabets {

    private static final Map<Character, Character> DOUBLE_STRUCK = Map.of('C', 'ℂ', 'H', 'ℍ', 'N', 'ℕ',
            'P', 'ℙ', 'Q', 'ℚ', 'R', 'ℝ', 'Z', 'ℤ');
    private static final Map<Character, Character> FRAKTUR = Map.of('C', 'ℭ', 'H', 'ℌ', 'I', 'ℑ', 'R',
            'ℜ', 'Z', 'ℨ');
    private static final Map<Character, Character> SCRIPT = Map.of('B', 'ℬ', 'E', 'ℰ', 'F', 'ℱ', 'H',
            'ℋ', 'I', 'ℐ', 'L', 'ℒ', 'M', 'ℳ', 'R', 'ℛ');

    private MathAlphabets() {}

    static String of(String text, FontInfo font) {
        if (text.equals("µ")) {
            return "μ";
        }
        if (text.length() != 1 || font == null || !FontNames.isTexMath(font.postScriptName())) {
            return text;
        }
        char c = text.charAt(0);
        String ps = FontNames.stripSubset(font.postScriptName()).toLowerCase(Locale.ROOT);
        if (ps.startsWith("msbm") && c >= 'A' && c <= 'Z') {
            return mapped(c, DOUBLE_STRUCK, 0x1D538);
        }
        if (ps.startsWith("eufm") || ps.startsWith("eufb")) {
            return c >= 'A' && c <= 'Z' ? mapped(c, FRAKTUR, 0x1D504)
                    : c >= 'a' && c <= 'z' ? Character.toString(0x1D51E + c - 'a') : text;
        }
        if (ps.startsWith("eusb") && c >= 'A' && c <= 'Z') {
            return Character.toString(0x1D4D0 + c - 'A');
        }
        boolean script = ps.startsWith("rsfs") || ps.startsWith("eusm") || ps.startsWith("cmsy") || ps.startsWith("cmbsy")
                || ps.startsWith("lmmathsymbols");
        if (script && c >= 'A' && c <= 'Z') {
            return mapped(c, SCRIPT, 0x1D49C);
        }
        return text;
    }

    private static String mapped(char c, Map<Character, Character> holes, int base) {
        Character hole = holes.get(c);
        return hole != null ? hole.toString() : Character.toString(base + c - 'A');
    }
}

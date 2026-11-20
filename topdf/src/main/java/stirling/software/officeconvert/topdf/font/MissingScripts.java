package stirling.software.officeconvert.topdf.font;

import java.lang.Character.UnicodeScript;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MissingScripts {

    private static final Map<UnicodeScript, List<String>> LANGUAGES = new EnumMap<>(UnicodeScript.class);

    static {
        LANGUAGES.put(UnicodeScript.ARABIC, List.of("ar", "fa", "ur", "ps", "ku", "sd", "ug"));
        LANGUAGES.put(UnicodeScript.HEBREW, List.of("he", "iw", "yi"));
        LANGUAGES.put(UnicodeScript.SYRIAC, List.of("syr"));
        LANGUAGES.put(UnicodeScript.THAANA, List.of("dv"));
        LANGUAGES.put(UnicodeScript.NKO, List.of("nqo"));
        LANGUAGES.put(UnicodeScript.ETHIOPIC, List.of("am", "ti", "gez"));
        LANGUAGES.put(UnicodeScript.HAN, List.of("zh", "ja"));
        LANGUAGES.put(UnicodeScript.HIRAGANA, List.of("ja"));
        LANGUAGES.put(UnicodeScript.KATAKANA, List.of("ja"));
        LANGUAGES.put(UnicodeScript.BOPOMOFO, List.of("zh"));
        LANGUAGES.put(UnicodeScript.HANGUL, List.of("ko"));
        LANGUAGES.put(UnicodeScript.THAI, List.of("th"));
        LANGUAGES.put(UnicodeScript.LAO, List.of("lo"));
        LANGUAGES.put(UnicodeScript.KHMER, List.of("km"));
        LANGUAGES.put(UnicodeScript.MYANMAR, List.of("my"));
        LANGUAGES.put(UnicodeScript.TIBETAN, List.of("bo", "dz"));
        LANGUAGES.put(UnicodeScript.MONGOLIAN, List.of("mn"));
        LANGUAGES.put(UnicodeScript.GEORGIAN, List.of("ka"));
        LANGUAGES.put(UnicodeScript.ARMENIAN, List.of("hy"));
        LANGUAGES.put(UnicodeScript.GREEK, List.of("el"));
        LANGUAGES.put(UnicodeScript.CYRILLIC, List.of("ru", "uk", "bg", "sr", "mk", "be", "kk", "ky", "mn", "tg"));
        LANGUAGES.put(UnicodeScript.DEVANAGARI, List.of("hi", "mr", "ne", "sa"));
        LANGUAGES.put(UnicodeScript.BENGALI, List.of("bn", "as"));
        LANGUAGES.put(UnicodeScript.GURMUKHI, List.of("pa"));
        LANGUAGES.put(UnicodeScript.GUJARATI, List.of("gu"));
        LANGUAGES.put(UnicodeScript.ORIYA, List.of("or"));
        LANGUAGES.put(UnicodeScript.TAMIL, List.of("ta"));
        LANGUAGES.put(UnicodeScript.TELUGU, List.of("te"));
        LANGUAGES.put(UnicodeScript.KANNADA, List.of("kn"));
        LANGUAGES.put(UnicodeScript.MALAYALAM, List.of("ml"));
        LANGUAGES.put(UnicodeScript.SINHALA, List.of("si"));
        LANGUAGES.put(UnicodeScript.CHEROKEE, List.of("chr"));
        LANGUAGES.put(UnicodeScript.CANADIAN_ABORIGINAL, List.of("iu", "cr"));
        LANGUAGES.put(UnicodeScript.TIFINAGH, List.of("zgh"));
    }

    private MissingScripts() {}

    public static List<String> warnings(Map<UnicodeScript, Long> uncovered, String documentLanguage) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<UnicodeScript, Long> e : uncovered.entrySet()) {
            UnicodeScript script = e.getKey();
            if (script == UnicodeScript.COMMON || script == UnicodeScript.INHERITED
                    || script == UnicodeScript.UNKNOWN) {
                continue;
            }
            long count = e.getValue();
            StringBuilder b = new StringBuilder("Text in the ").append(scriptName(script)).append(" script");
            String language = language(script, documentLanguage);
            if (language != null) {
                b.append(" (").append(language).append(')');
            }
            b.append(" has no installed font: ").append(count)
                    .append(count == 1 ? " character is" : " characters are")
                    .append(" drawn as boxes; install a font that covers it");
            out.add(b.toString());
        }
        return out;
    }

    static String scriptName(UnicodeScript script) {
        String[] words = script.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder b = new StringBuilder();
        for (String w : words) {
            if (!b.isEmpty()) {
                b.append(' ');
            }
            b.append(Character.toUpperCase(w.charAt(0))).append(w, 1, w.length());
        }
        return b.toString();
    }

    static String language(UnicodeScript script, String documentLanguage) {
        List<String> known = LANGUAGES.get(script);
        if (known == null) {
            return null;
        }
        if (documentLanguage != null && !documentLanguage.isBlank()) {
            Locale declared = Locale.forLanguageTag(documentLanguage.strip().replace('_', '-'));
            if (known.contains(declared.getLanguage())) {
                return displayName(declared.getLanguage()) + ", " + declared.toLanguageTag();
            }
        }
        return known.size() == 1 ? displayName(known.get(0)) : null;
    }

    private static String displayName(String language) {
        String name = Locale.forLanguageTag(language).getDisplayLanguage(Locale.ENGLISH);
        return name.isEmpty() ? language : name;
    }
}

package stirling.software.officeconvert.topdf.dml;

import java.util.Locale;

// List numbers in the world's numbering systems, for WordprocessingML number formats and DrawingML autonumbers
public final class Numerals {

    private static final String HEBREW = "\u05D0\u05D1\u05D2\u05D3\u05D4\u05D5\u05D6\u05D7\u05D8\u05D9\u05DB\u05DC"
            + "\u05DE\u05E0\u05E1\u05E2\u05E4\u05E6\u05E7\u05E8\u05E9\u05EA";

    private static final String ABJAD = "\u0627\u0628\u062C\u062F\u0647\u0648\u0632\u062D\u0637\u064A\u0643\u0644"
            + "\u0645\u0646\u0633\u0639\u0641\u0635\u0642\u0631\u0634\u062A\u062B\u062E\u0630\u0636\u0638\u063A";

    private static final String ARABIC = "\u0627\u0628\u062A\u062B\u062C\u062D\u062E\u062F\u0630\u0631\u0632\u0633"
            + "\u0634\u0635\u0636\u0637\u0638\u0639\u063A\u0641\u0642\u0643\u0644\u0645\u0646\u0647\u0648\u064A";

    private static final String THAI = "\u0E01\u0E02\u0E04\u0E07\u0E08\u0E09\u0E0A\u0E0B\u0E0C\u0E0D\u0E0E\u0E0F"
            + "\u0E10\u0E11\u0E12\u0E13\u0E14\u0E15\u0E16\u0E17\u0E18\u0E19\u0E1A\u0E1B\u0E1C\u0E1D\u0E1E\u0E1F"
            + "\u0E20\u0E21\u0E22\u0E23\u0E25\u0E27\u0E28\u0E29\u0E2A\u0E2B\u0E2C\u0E2D\u0E2E";

    private static final String HINDI_VOWELS = "\u0905\u0906\u0907\u0908\u0909\u090A\u090B\u090C\u090F\u0910\u0913"
            + "\u0914";

    private static final String HINDI_CONSONANTS = "\u0915\u0916\u0917\u0918\u0919\u091A\u091B\u091C\u091D\u091E"
            + "\u091F\u0920\u0921\u0922\u0923\u0924\u0925\u0926\u0927\u0928\u092A\u092B\u092C\u092D\u092E\u092F"
            + "\u0930\u0932\u0935\u0936\u0937\u0938\u0939";

    private static final String GANADA = "\uAC00\uB098\uB2E4\uB77C\uB9C8\uBC14\uC0AC\uC544\uC790\uCC28\uCE74\uD0C0"
            + "\uD30C\uD558";

    private static final String CHOSUNG = "\u3131\u3134\u3137\u3139\u3141\u3142\u3145\u3147\u3148\u314A\u314B\u314C"
            + "\u314D\u314E";

    private static final String AIUEO = "\u30A2\u30A4\u30A6\u30A8\u30AA\u30AB\u30AD\u30AF\u30B1\u30B3\u30B5\u30B7"
            + "\u30B9\u30BB\u30BD\u30BF\u30C1\u30C4\u30C6\u30C8\u30CA\u30CB\u30CC\u30CD\u30CE\u30CF\u30D2\u30D5"
            + "\u30D8\u30DB\u30DE\u30DF\u30E0\u30E1\u30E2\u30E4\u30E6\u30E8\u30E9\u30EA\u30EB\u30EC\u30ED\u30EF"
            + "\u30F2\u30F3";

    private static final String IROHA = "\u30A4\u30ED\u30CF\u30CB\u30DB\u30D8\u30C8\u30C1\u30EA\u30CC\u30EB\u30F2"
            + "\u30EF\u30AB\u30E8\u30BF\u30EC\u30BD\u30C4\u30CD\u30CA\u30E9\u30E0\u30A6\u30F0\u30CE\u30AA\u30AF"
            + "\u30E4\u30DE\u30B1\u30D5\u30B3\u30A8\u30C6\u30A2\u30B5\u30AD\u30E6\u30E1\u30DF\u30B7\u30F1\u30D2"
            + "\u30E2\u30BB\u30B9";

    private static final String RUSSIAN = "\u0430\u0431\u0432\u0433\u0434\u0435\u0436\u0437\u0438\u043A\u043B\u043C"
            + "\u043D\u043E\u043F\u0440\u0441\u0442\u0443\u0444\u0445\u0446\u0447\u0448\u0449\u044B\u044D\u044E"
            + "\u044F";

    private static final String STEMS = "\u7532\u4E59\u4E19\u4E01\u620A\u5DF1\u5E9A\u8F9B\u58EC\u7678";

    private static final String BRANCHES = "\u5B50\u4E11\u5BC5\u536F\u8FB0\u5DF3\u5348\u672A\u7533\u9149\u620C\u4EA5";

    private static final String CJK_DIGITS = "\u3007\u4E00\u4E8C\u4E09\u56DB\u4E94\u516D\u4E03\u516B\u4E5D";

    private static final String KOREAN_DIGITS = "\uC601\uC77C\uC774\uC0BC\uC0AC\uC624\uC721\uCE60\uD314\uAD6C";

    private Numerals() {}

    /** The number in a WordprocessingML number format this class knows, else null. */
    public static String format(String fmt, int n) {
        if (fmt == null) {
            return null;
        }
        return switch (fmt) {
            case "hebrew1" -> hebrew(n);
            case "hebrew2" -> letters(n, HEBREW);
            case "arabicAbjad" -> letters(n, ABJAD);
            case "arabicAlpha" -> letters(n, ARABIC);
            case "hindiNumbers" -> digits(n, 0x0966);
            case "hindiVowels" -> letters(n, HINDI_VOWELS);
            case "hindiConsonants" -> letters(n, HINDI_CONSONANTS);
            case "thaiNumbers" -> digits(n, 0x0E50);
            case "thaiLetters" -> letters(n, THAI);
            case "chineseCounting", "chineseCountingThousand", "japaneseCounting", "taiwaneseCounting",
                    "taiwaneseCountingThousand", "chineseLegalSimplified", "japaneseLegal" -> counting(n);
            case "ideographDigital", "japaneseDigitalTenThousand", "taiwaneseDigital" -> spelled(n, CJK_DIGITS);
            case "koreanDigital", "koreanDigital2" -> spelled(n, KOREAN_DIGITS);
            case "ideographTraditional" -> cycle(n, STEMS);
            case "ideographZodiac" -> cycle(n, BRANCHES);
            case "ganada" -> letters(n, GANADA);
            case "chosung" -> letters(n, CHOSUNG);
            case "aiueo", "aiueoFullWidth" -> letters(n, AIUEO);
            case "iroha", "irohaFullWidth" -> letters(n, IROHA);
            case "russianLower" -> letters(n, RUSSIAN);
            case "russianUpper" -> letters(n, RUSSIAN.toUpperCase(Locale.ROOT));
            default -> null;
        };
    }

    /** A DrawingML autonumber scheme in a world numbering system, else null for the caller's own formatter. */
    public static String autoNumber(String scheme, int n) {
        if (scheme == null) {
            return null;
        }
        String[] kinds = {"arabicDb", "circleNum", "ea1Chs", "ea1Cht", "ea1JpnChsDb", "ea1JpnKor", "hebrew2",
            "hindiAlpha1", "hindiAlpha", "hindiNum", "thaiAlpha", "thaiNum", "arabic1", "arabic2"};
        for (String kind : kinds) {
            if (!scheme.startsWith(kind)) {
                continue;
            }
            String number = switch (kind) {
                case "arabicDb" -> fullWidth(n);
                case "circleNum" -> n >= 1 && n <= 20 ? String.valueOf((char) (0x2460 + n - 1)) : Integer.toString(n);
                case "ea1Chs", "ea1Cht", "ea1JpnChsDb", "ea1JpnKor" -> counting(n);
                case "hebrew2" -> letters(n, HEBREW);
                case "hindiAlpha1" -> letters(n, HINDI_CONSONANTS);
                case "hindiAlpha" -> letters(n, HINDI_VOWELS);
                case "hindiNum" -> digits(n, 0x0966);
                case "thaiAlpha" -> letters(n, THAI);
                case "thaiNum" -> digits(n, 0x0E50);
                case "arabic1" -> letters(n, ARABIC);
                default -> letters(n, ABJAD);
            };
            String rest = scheme.substring(kind.length());
            boolean wide = kind.endsWith("Db");
            return switch (rest) {
                case "Period" -> number + (wide ? "\uFF0E" : ".");
                case "Minus" -> number + "-";
                case "ParenR" -> number + ")";
                case "ParenBoth" -> "(" + number + ")";
                default -> number;
            };
        }
        return null;
    }

    public static String digits(int n, int zero) {
        String s = Integer.toString(n);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c >= '0' && c <= '9' ? (char) (zero + c - '0') : c);
        }
        return b.toString();
    }

    private static String fullWidth(int n) {
        return digits(n, 0xFF10);
    }

    // As Word's letter lists: the alphabet, then each letter twice, three times and so on
    static String letters(int n, String alphabet) {
        if (n <= 0) {
            return Integer.toString(n);
        }
        int[] cps = alphabet.codePoints().toArray();
        int letter = cps[(n - 1) % cps.length];
        int repeat = Math.min((n - 1) / cps.length + 1, 50);
        return new String(Character.toChars(letter)).repeat(repeat);
    }

    private static String cycle(int n, String signs) {
        return n <= 0 ? Integer.toString(n) : String.valueOf(signs.charAt((n - 1) % signs.length()));
    }

    private static String spelled(int n, String digits) {
        String s = Integer.toString(n);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c >= '0' && c <= '9' ? digits.charAt(c - '0') : c);
        }
        return b.toString();
    }

    // Hebrew numerals: letters summing to the number, with 15 and 16 written 9+6 and 9+7
    static String hebrew(int n) {
        if (n <= 0 || n >= 10_000) {
            return Integer.toString(n);
        }
        int[] values = {400, 300, 200, 100, 90, 80, 70, 60, 50, 40, 30, 20, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1};
        String letters = "\u05EA\u05E9\u05E8\u05E7\u05E6\u05E4\u05E2\u05E1\u05E0\u05DE\u05DC\u05DB\u05D9\u05D8\u05D7"
                + "\u05D6\u05D5\u05D4\u05D3\u05D2\u05D1\u05D0";
        StringBuilder b = new StringBuilder();
        int x = n;
        while (x > 0) {
            int rest = x % 100;
            if (x < 100 && (rest == 15 || rest == 16)) {
                b.append('\u05D8').append(rest == 15 ? '\u05D5' : '\u05D6');
                break;
            }
            for (int i = 0; i < values.length; i++) {
                if (x >= values[i]) {
                    b.append(letters.charAt(i));
                    x -= values[i];
                    break;
                }
            }
        }
        return b.toString();
    }

    // Chinese and Japanese counting with the signs for ten, hundred and thousand, and zero between them
    static String counting(int n) {
        if (n < 0 || n >= 10_000) {
            return Integer.toString(n);
        }
        if (n == 0) {
            return "\u3007";
        }
        String units = "\u5343\u767E\u5341";
        int[] scale = {1000, 100, 10};
        StringBuilder b = new StringBuilder();
        int x = n;
        boolean gap = false;
        for (int i = 0; i < scale.length; i++) {
            int d = x / scale[i];
            x %= scale[i];
            if (d > 0) {
                if (gap) {
                    b.append('\u96F6');
                }
                if (!(d == 1 && scale[i] == 10 && b.length() == 0)) {
                    b.append(CJK_DIGITS.charAt(d));
                }
                b.append(units.charAt(i));
                gap = false;
            } else if (b.length() > 0) {
                gap = true;
            }
        }
        if (x > 0) {
            if (gap) {
                b.append('\u96F6');
            }
            b.append(CJK_DIGITS.charAt(x));
        }
        return b.toString();
    }
}

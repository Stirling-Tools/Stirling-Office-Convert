package stirling.software.officeconvert.model;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class Scripts {

    public record Fonts(String latin, String eastAsian, String complex, boolean eastAsianHint) {}

    public record Languages(String latin, String eastAsian, String complex) {

        public String primary() {
            return complex != null ? complex : eastAsian != null ? eastAsian : latin;
        }
    }

    private enum Group {
        LATIN,
        EAST_ASIAN,
        COMPLEX,
        OTHER
    }

    private static final String SIMPLIFIED = "\u8FD9\u4EEC\u8BF4\u4E3A\u65F6\u4F1A\u56FD\u4E2A\u6765\u5BF9\u53D1\u7ECF\u8FC7\u8FD8\u8FDB\u4E0E\u4E8E\u5B9E\u73B0\u52A8\u5173\u957F\u95EE\u95E8\u89C1\u4E66\u5B66\u5E94\u4ECE\u7535\u4E1C\u8F66\u9A6C\u9E1F\u9C7C\u8BED\u8BDD\u8BA9\u8BA4\u8BF7\u8BFB\u8C01\u8C03\u5458\u52A1\u4EA7\u4E1A\u6C14\u5F00\u4F53\u5934\u534E\u603B\u533A\u4E49\u529E\u62A5\u573A\u8BE5\u8FB9\u79CD\u6837\u4E13\u6570\u636E\u7F51\u7EBF";

    private static final String TRADITIONAL = "\u9019\u5011\u8AAA\u70BA\u6642\u6703\u570B\u500B\u4F86\u5C0D\u767C\u7D93\u904E\u9084\u9032\u8207\u65BC\u5BE6\u73FE\u52D5\u95DC\u9577\u554F\u9580\u898B\u66F8\u5B78\u61C9\u5F9E\u96FB\u6771\u8ECA\u99AC\u9CE5\u9B5A\u8A9E\u8A71\u8B93\u8A8D\u8ACB\u8B80\u8AB0\u8ABF\u54E1\u52D9\u7522\u696D\u6C23\u958B\u9AD4\u982D\u83EF\u7E3D\u5340\u7FA9\u8FA6\u5831\u5834\u8A72\u908A\u7A2E\u6A23\u5C08\u6578\u64DA\u7DB2\u7DDA";

    private static final Set<String> ARABIC_FONTS = Set.of("Arial", "Times New Roman", "Courier New", "Tahoma", "Segoe UI",
            "Segoe UI Light", "Segoe UI Semibold", "Microsoft Sans Serif", "Arial Unicode MS", "Traditional Arabic",
            "Simplified Arabic", "Simplified Arabic Fixed", "Arabic Typesetting", "Sakkal Majalla", "Aldhabi", "Andalus",
            "Urdu Typesetting", "Dubai", "Microsoft Uighur");

    private static final Set<String> HEBREW_FONTS = Set.of("Arial", "Times New Roman", "Courier New", "Tahoma", "Segoe UI",
            "Segoe UI Light", "Segoe UI Semibold", "Microsoft Sans Serif", "Arial Unicode MS", "David", "Miriam",
            "Miriam Fixed", "Narkisim", "Rod", "FrankRuehl", "Levenim MT", "Gisha", "Aharoni");

    private static final Set<String> INDIC_FONTS = Set.of("Nirmala UI", "Nirmala Text", "Arial Unicode MS", "Mangal", "Aparajita",
            "Kokila", "Utsaah", "Vrinda", "Shonar Bangla", "Raavi", "Shruti", "Kalinga", "Latha", "Vijaya", "Gautami", "Vani",
            "Tunga", "Kartika", "Iskoola Pota");

    private static final Set<String> THAI_FONTS = Set.of("Tahoma", "Leelawadee", "Leelawadee UI", "Microsoft Sans Serif",
            "Angsana New", "AngsanaUPC", "Cordia New", "CordiaUPC", "Browallia New", "BrowalliaUPC", "DilleniaUPC",
            "EucrosiaUPC", "FreesiaUPC", "IrisUPC", "JasmineUPC", "KodchiangUPC", "LilyUPC", "Arial Unicode MS");

    private static final Set<String> CJK_FONTS = Set.of("MS Gothic", "MS PGothic", "MS UI Gothic", "MS Mincho", "MS PMincho",
            "Yu Gothic", "Yu Gothic UI", "Yu Mincho", "Meiryo", "Meiryo UI", "SimSun", "NSimSun", "SimHei", "KaiTi", "FangSong",
            "DengXian", "Microsoft YaHei", "Microsoft JhengHei", "MingLiU", "PMingLiU", "MingLiU_HKSCS", "DFKai-SB",
            "Malgun Gothic", "Batang", "BatangChe", "Gulim", "GulimChe", "Dotum", "DotumChe", "Gungsuh", "Arial Unicode MS");

    private static final Map<Character.UnicodeScript, String> COMPLEX_LANGUAGES = new EnumMap<>(Map.ofEntries(
            Map.entry(Character.UnicodeScript.ARABIC, "ar-SA"),
            Map.entry(Character.UnicodeScript.HEBREW, "he-IL"),
            Map.entry(Character.UnicodeScript.SYRIAC, "syr-SY"),
            Map.entry(Character.UnicodeScript.THAANA, "dv-MV"),
            Map.entry(Character.UnicodeScript.NKO, "nqo"),
            Map.entry(Character.UnicodeScript.DEVANAGARI, "hi-IN"),
            Map.entry(Character.UnicodeScript.BENGALI, "bn-IN"),
            Map.entry(Character.UnicodeScript.GURMUKHI, "pa-IN"),
            Map.entry(Character.UnicodeScript.GUJARATI, "gu-IN"),
            Map.entry(Character.UnicodeScript.ORIYA, "or-IN"),
            Map.entry(Character.UnicodeScript.TAMIL, "ta-IN"),
            Map.entry(Character.UnicodeScript.TELUGU, "te-IN"),
            Map.entry(Character.UnicodeScript.KANNADA, "kn-IN"),
            Map.entry(Character.UnicodeScript.MALAYALAM, "ml-IN"),
            Map.entry(Character.UnicodeScript.SINHALA, "si-LK"),
            Map.entry(Character.UnicodeScript.THAI, "th-TH"),
            Map.entry(Character.UnicodeScript.LAO, "lo-LA"),
            Map.entry(Character.UnicodeScript.KHMER, "km-KH"),
            Map.entry(Character.UnicodeScript.MYANMAR, "my-MM"),
            Map.entry(Character.UnicodeScript.TIBETAN, "bo-CN"),
            Map.entry(Character.UnicodeScript.MONGOLIAN, "mn-Mong-CN")));

    private static final Map<Character.UnicodeScript, String> COMPLEX_FONTS = new EnumMap<>(Map.ofEntries(
            Map.entry(Character.UnicodeScript.SYRIAC, "Segoe UI Historic"),
            Map.entry(Character.UnicodeScript.THAANA, "MV Boli"),
            Map.entry(Character.UnicodeScript.NKO, "Ebrima"),
            Map.entry(Character.UnicodeScript.DEVANAGARI, "Mangal"),
            Map.entry(Character.UnicodeScript.BENGALI, "Vrinda"),
            Map.entry(Character.UnicodeScript.GURMUKHI, "Raavi"),
            Map.entry(Character.UnicodeScript.GUJARATI, "Shruti"),
            Map.entry(Character.UnicodeScript.ORIYA, "Kalinga"),
            Map.entry(Character.UnicodeScript.TAMIL, "Latha"),
            Map.entry(Character.UnicodeScript.TELUGU, "Gautami"),
            Map.entry(Character.UnicodeScript.KANNADA, "Tunga"),
            Map.entry(Character.UnicodeScript.MALAYALAM, "Kartika"),
            Map.entry(Character.UnicodeScript.SINHALA, "Iskoola Pota"),
            Map.entry(Character.UnicodeScript.THAI, "Leelawadee UI"),
            Map.entry(Character.UnicodeScript.LAO, "Lao UI"),
            Map.entry(Character.UnicodeScript.KHMER, "Khmer UI"),
            Map.entry(Character.UnicodeScript.MYANMAR, "Myanmar Text"),
            Map.entry(Character.UnicodeScript.TIBETAN, "Microsoft Himalaya"),
            Map.entry(Character.UnicodeScript.MONGOLIAN, "Mongolian Baiti")));

    private static final Map<Character.UnicodeScript, Set<String>> COMPLEX_KEEP = new EnumMap<>(Map.ofEntries(
            Map.entry(Character.UnicodeScript.ARABIC, ARABIC_FONTS),
            Map.entry(Character.UnicodeScript.HEBREW, HEBREW_FONTS),
            Map.entry(Character.UnicodeScript.THAI, THAI_FONTS),
            Map.entry(Character.UnicodeScript.LAO, Set.of("Lao UI", "DokChampa", "Leelawadee UI")),
            Map.entry(Character.UnicodeScript.KHMER, Set.of("Khmer UI", "DaunPenh", "MoolBoran")),
            Map.entry(Character.UnicodeScript.SYRIAC, Set.of("Estrangelo Edessa", "Segoe UI Historic"))));

    private static final Map<Character.UnicodeScript, String> OTHER_LANGUAGES = new EnumMap<>(Map.ofEntries(
            Map.entry(Character.UnicodeScript.LATIN, "en-US"),
            Map.entry(Character.UnicodeScript.GREEK, "el-GR"),
            Map.entry(Character.UnicodeScript.CYRILLIC, "ru-RU"),
            Map.entry(Character.UnicodeScript.ARMENIAN, "hy-AM"),
            Map.entry(Character.UnicodeScript.GEORGIAN, "ka-GE"),
            Map.entry(Character.UnicodeScript.ETHIOPIC, "am-ET"),
            Map.entry(Character.UnicodeScript.CHEROKEE, "chr-Cher-US"),
            Map.entry(Character.UnicodeScript.CANADIAN_ABORIGINAL, "iu-Cans-CA"),
            Map.entry(Character.UnicodeScript.TIFINAGH, "tzm-Tfng-MA"),
            Map.entry(Character.UnicodeScript.YI, "ii-CN")));

    private static final Map<Character.UnicodeScript, String> OTHER_FONTS = new EnumMap<>(Map.ofEntries(
            Map.entry(Character.UnicodeScript.ARMENIAN, "Sylfaen"),
            Map.entry(Character.UnicodeScript.GEORGIAN, "Sylfaen"),
            Map.entry(Character.UnicodeScript.ETHIOPIC, "Nyala"),
            Map.entry(Character.UnicodeScript.CHEROKEE, "Plantagenet Cherokee"),
            Map.entry(Character.UnicodeScript.CANADIAN_ABORIGINAL, "Euphemia"),
            Map.entry(Character.UnicodeScript.TIFINAGH, "Ebrima"),
            Map.entry(Character.UnicodeScript.VAI, "Ebrima"),
            Map.entry(Character.UnicodeScript.OSMANYA, "Ebrima"),
            Map.entry(Character.UnicodeScript.YI, "Microsoft Yi Baiti"),
            Map.entry(Character.UnicodeScript.TAI_LE, "Microsoft Tai Le"),
            Map.entry(Character.UnicodeScript.NEW_TAI_LUE, "Microsoft New Tai Lue"),
            Map.entry(Character.UnicodeScript.JAVANESE, "Javanese Text")));

    private static final Map<Character.UnicodeScript, Set<String>> OTHER_KEEP = new EnumMap<>(Map.ofEntries(
            Map.entry(Character.UnicodeScript.ARMENIAN, Set.of("Arial", "Sylfaen", "Arial Unicode MS")),
            Map.entry(Character.UnicodeScript.GEORGIAN, Set.of("Sylfaen", "Arial Unicode MS")),
            Map.entry(Character.UnicodeScript.ETHIOPIC, Set.of("Nyala", "Ebrima"))));

    private static final Map<String, String[]> WORDS = new HashMap<>();

    private static final Map<Integer, String> LETTERS = new HashMap<>();

    static {
        words("en-US", "the and of to is in that it for with be are as this by on or not has have all shall any");
        words("de-DE", "der die und das ist nicht mit zu den von sich des auf f\u00FCr im dem ein eine jeder oder hat wird");
        words("fr-FR", "le la les de et des est un une du en que pour dans qui au aux ses sa son pas \u00EAtre ou toute");
        words("es-ES", "el la los las de y que en por del se un una con para su es toda derecho sus");
        words("it-IT", "il di che e la per non sono del della un una ogni ha diritto alla nel gli");
        words("pt-BR", "o a os as de que e do da em um uma para com n\u00E3o dos das tem direito ao");
        words("nl-NL", "de het een en van is niet in op te zijn dat die voor met heeft ieder");
        words("pl-PL", "i w z na si\u0119 nie jest do \u017Ce to jak oraz lub prawo ka\u017Cdy ma");
        words("cs-CZ", "a v se na je \u017Ee s z k o to jsou m\u00E1 pr\u00E1vo ka\u017Ed\u00FD nebo");
        words("tr-TR", "ve bir bu da de i\u00E7in ile her olarak hakk\u0131 veya hi\u00E7 kimse");
        words("vi-VN", "v\u00E0 c\u1EE7a c\u00F3 l\u00E0 \u0111\u01B0\u1EE3c ng\u01B0\u1EDDi kh\u00F4ng c\u00E1c nh\u1EEFng trong cho quy\u1EC1n m\u1ED9t v\u1EDBi");
        words("yo-NG", "n\u00ED \u00E0ti \u00F3 l\u00E1ti t\u00ED k\u00ED s\u00ED ni gbogbo \u00E8n\u00ECy\u00E0n");
        words("hi-IN", "\u0939\u0948 \u0939\u0948\u0902 \u0914\u0930 \u0915\u0947 \u0915\u0940 \u092E\u0947\u0902 \u0915\u093E \u0938\u0947 \u0915\u093F\u0938\u0940");
        words("mr-IN", "\u0906\u0939\u0947 \u0906\u0923\u093F \u0935 \u092F\u093E \u0939\u0947 \u0924\u094D\u092F\u093E \u0906\u0939\u0947\u0924");
        words("ne-NP", "\u091B \u091B\u0928\u094D \u0939\u094B \u0930 \u092E\u093E \u0917\u0930\u094D\u0928 \u0939\u0941\u0928\u0947 \u092A\u0928\u093F \u0924\u0925\u093E");
        letters("vi-VN", "\u01A1\u01B0\u0111\u01A0\u01AF\u0110");
        letters("yo-NG", "\u1E63\u1EB9\u1ECD\u1E62\u1EB8\u1ECC\u0323");
        letters("tr-TR", "\u0131\u011F\u015F\u0130\u011E\u015E");
        letters("pl-PL", "\u0142\u0105\u0119\u017C\u017A\u015B\u0144\u0141\u0104\u0118\u017B\u0179\u015A\u0143");
        letters("cs-CZ", "\u0159\u016F\u011B\u0158\u016E\u011A");
        letters("de-DE", "\u00DF");
        letters("fr-FR", "\u0153\u0152");
        letters("es-ES", "\u00F1\u00D1\u00BF\u00A1");
        letters("uk-UA", "\u0457\u0454\u0491\u0407\u0404\u0490");
        letters("be-BY", "\u045E\u040E");
        letters("sr-Cyrl-RS", "\u0452\u045B\u045F\u0402\u040B\u040F");
        letters("mk-MK", "\u0453\u045C\u0455\u0403\u040C\u0405");
        letters("kk-KZ", "\u04D9\u0493\u049B\u04A3\u04B1\u04BB\u04D8\u0492\u049A\u04A2\u04B0\u04BA");
        letters("mn-MN", "\u04E9\u04AF\u04E8\u04AE");
        letters("ru-RU", "\u044B\u044D\u0451\u042B\u042D\u0401");
        letters("bg-BG", "\u044A\u042A");
        letters("ur-PK", "\u0679\u0688\u0691\u06BA\u06D2\u06C1\u06D3");
        letters("ps-AF", "\u067C\u0681\u0685\u0689\u0693\u0696\u069A\u06AB\u06BC\u06CD\u06D0");
        letters("fa-IR", "\u067E\u0686\u0698\u06AF\u06A9\u06CC\u06F0\u06F1\u06F2\u06F3\u06F4\u06F5\u06F6\u06F7\u06F8\u06F9");
        letters("ar-SA", "\u064A\u0643\u0649\u0629");
        letters("yi-001", "\u05F0\u05F1\u05F2");
        letters("mr-IN", "\u0933");
        letters("ti-ER", "\u12A3\u1250\u1251\u1252\u1253\u1254\u1255\u1256\u1258\u125A\u125B\u125C\u125D\u12B8\u12B9\u12BA\u12BB"
                + "\u12BC\u12BD\u12BE\u12C0\u12C2\u12C3\u12C4\u12C5");
        words("ti-ER", "\u1293\u12ED \u12AB\u1265 \u12A9\u120E\u121D \u12C8\u12ED \u12A6\u1265");
        words("am-ET", "\u1290\u12CD \u12C8\u12ED\u121D \u12A5\u1293 \u12A0\u1208\u12CD \u1235\u1208");
        letters("am-ET", "\u12A0");
    }

    private static final Map<String, Integer> LCIDS = Map.ofEntries(
            Map.entry("en-US", 1033), Map.entry("ar-SA", 1025), Map.entry("fa-IR", 1065), Map.entry("ur-PK", 1056),
            Map.entry("ps-AF", 1123), Map.entry("he-IL", 1037), Map.entry("yi-001", 1085), Map.entry("syr-SY", 1114),
            Map.entry("dv-MV", 1125), Map.entry("hi-IN", 1081), Map.entry("mr-IN", 1102), Map.entry("ne-NP", 1121),
            Map.entry("bn-IN", 1093), Map.entry("pa-IN", 1094), Map.entry("gu-IN", 1095), Map.entry("or-IN", 1096),
            Map.entry("ta-IN", 1097), Map.entry("te-IN", 1098), Map.entry("kn-IN", 1099), Map.entry("ml-IN", 1100),
            Map.entry("si-LK", 1115), Map.entry("th-TH", 1054), Map.entry("lo-LA", 1108), Map.entry("km-KH", 1107),
            Map.entry("my-MM", 1109), Map.entry("bo-CN", 1105), Map.entry("mn-Mong-CN", 2128), Map.entry("ja-JP", 1041),
            Map.entry("ko-KR", 1042), Map.entry("zh-CN", 2052), Map.entry("zh-TW", 1028), Map.entry("el-GR", 1032),
            Map.entry("ru-RU", 1049), Map.entry("uk-UA", 1058), Map.entry("sr-Cyrl-RS", 10266), Map.entry("kk-KZ", 1087),
            Map.entry("be-BY", 1059), Map.entry("mk-MK", 1071), Map.entry("bg-BG", 1026), Map.entry("mn-MN", 1104),
            Map.entry("hy-AM", 1067), Map.entry("ka-GE", 1079), Map.entry("am-ET", 1118), Map.entry("ti-ER", 2163),
            Map.entry("chr-Cher-US", 1116), Map.entry("iu-Cans-CA", 1117), Map.entry("tzm-Tfng-MA", 4191),
            Map.entry("ii-CN", 1144), Map.entry("de-DE", 1031), Map.entry("fr-FR", 1036), Map.entry("es-ES", 3082),
            Map.entry("it-IT", 1040), Map.entry("pt-BR", 1046), Map.entry("nl-NL", 1043), Map.entry("pl-PL", 1045),
            Map.entry("cs-CZ", 1029), Map.entry("tr-TR", 1055), Map.entry("vi-VN", 1066), Map.entry("yo-NG", 1130));

    private Scripts() {}

    private static void words(String tag, String list) {
        for (String w : list.split(" ")) {
            String[] known = WORDS.get(w);
            String[] next = known == null ? new String[] {tag} : java.util.Arrays.copyOf(known, known.length + 1);
            next[next.length - 1] = tag;
            WORDS.put(w, next);
        }
    }

    private static void letters(String tag, String list) {
        list.codePoints().forEach(cp -> LETTERS.put(cp, tag));
    }

    public static final class Profile {

        private final Map<Character.UnicodeScript, Integer> letters = new EnumMap<>(Character.UnicodeScript.class);
        private final Map<String, Integer> votes = new HashMap<>();
        private final Map<String, Integer> shortHits = new HashMap<>();
        private final Map<String, Integer> letterHits = new HashMap<>();
        private int shortWords;
        private int kana;
        private int hangul;
        private int han;
        private int simplified;
        private int traditional;

        public void add(CharSequence text) {
            StringBuilder word = new StringBuilder();
            for (int i = 0; i < text.length(); ) {
                int cp = Character.codePointAt(text, i);
                i += Character.charCount(cp);
                String tag = LETTERS.get(cp);
                if (tag != null) {
                    votes.merge(tag, 1, Integer::sum);
                    letterHits.merge(tag, 1, Integer::sum);
                }
                if (Character.isLetter(cp) || Character.getType(cp) == Character.NON_SPACING_MARK
                        || Character.getType(cp) == Character.COMBINING_SPACING_MARK) {
                    word.appendCodePoint(cp);
                    count(cp);
                } else {
                    vote(word);
                }
            }
            vote(word);
        }

        private void count(int cp) {
            if (!Character.isLetter(cp)) {
                return;
            }
            Character.UnicodeScript s = Character.UnicodeScript.of(cp);
            letters.merge(s, 1, Integer::sum);
            switch (s) {
                case HIRAGANA, KATAKANA -> kana++;
                case HANGUL -> hangul++;
                case HAN -> {
                    han++;
                    simplified += SIMPLIFIED.indexOf(cp) >= 0 ? 1 : 0;
                    traditional += TRADITIONAL.indexOf(cp) >= 0 ? 1 : 0;
                }
                default -> {
                }
            }
        }

        private void vote(StringBuilder word) {
            if (word.isEmpty()) {
                return;
            }
            String[] tags = WORDS.get(word.toString().toLowerCase(Locale.ROOT));
            boolean small = functionWord(word);
            shortWords += small ? 1 : 0;
            if (tags != null) {
                for (String t : tags) {
                    votes.merge(t, 2, Integer::sum);
                    if (small) {
                        shortHits.merge(t, 1, Integer::sum);
                    }
                }
            }
            word.setLength(0);
        }

        private static boolean functionWord(CharSequence word) {
            int n = Character.codePointCount(word, 0, word.length());
            if (n > 3 || Character.UnicodeScript.of(Character.codePointAt(word, 0)) != Character.UnicodeScript.LATIN) {
                return false;
            }
            return word.codePoints().skip(1).noneMatch(Character::isUpperCase);
        }

        public boolean rightToLeft() {
            int rtl = 0;
            int all = 0;
            for (Map.Entry<Character.UnicodeScript, Integer> e : letters.entrySet()) {
                all += e.getValue();
                if (e.getKey() == Character.UnicodeScript.ARABIC || e.getKey() == Character.UnicodeScript.HEBREW
                        || e.getKey() == Character.UnicodeScript.SYRIAC || e.getKey() == Character.UnicodeScript.THAANA
                        || e.getKey() == Character.UnicodeScript.NKO) {
                    rtl += e.getValue();
                }
            }
            return rtl * 2 > all;
        }

        public String eastAsian() {
            if (kana == 0 && hangul == 0 && han == 0) {
                return null;
            }
            if (kana > 0 && kana * 20 >= han + hangul) {
                return "ja-JP";
            }
            if (hangul > kana && hangul * 5 >= han) {
                return "ko-KR";
            }
            if (kana > 0) {
                return "ja-JP";
            }
            return traditional > simplified ? "zh-TW" : "zh-CN";
        }

        public String complex() {
            Character.UnicodeScript s = complexScript();
            return s == null ? null : language(s);
        }

        public String latin() {
            Character.UnicodeScript s = latinScript();
            return s == null ? null : language(s);
        }

        Character.UnicodeScript complexScript() {
            return top(letters, COMPLEX_LANGUAGES.keySet());
        }

        Character.UnicodeScript latinScript() {
            return top(letters, OTHER_LANGUAGES.keySet());
        }

        String language(Character.UnicodeScript s) {
            return switch (s) {
                case LATIN -> latinLanguage();
                case CYRILLIC -> cyrillic();
                case ARABIC -> v("ps-AF") >= 2 && v("ps-AF") >= v("ur-PK") ? "ps-AF" : v("ur-PK") >= 2 ? "ur-PK"
                        : v("fa-IR") > v("ar-SA") ? "fa-IR" : "ar-SA";
                case HEBREW -> v("yi-001") >= 2 ? "yi-001" : "he-IL";
                case DEVANAGARI -> best(java.util.List.of("hi-IN", "mr-IN", "ne-NP"), 2);
                case ETHIOPIC -> v("ti-ER") > v("am-ET") ? "ti-ER" : "am-ET";
                default -> COMPLEX_LANGUAGES.containsKey(s) ? COMPLEX_LANGUAGES.get(s) : OTHER_LANGUAGES.get(s);
            };
        }

        private String latinLanguage() {
            String tag = best(java.util.List.of("en-US", "de-DE", "fr-FR", "es-ES", "it-IT", "pt-BR", "nl-NL", "pl-PL",
                    "cs-CZ", "tr-TR", "vi-VN", "yo-NG"), 4);
            int marked = letterHits.getOrDefault(tag, 0);
            boolean byLetters = marked >= 3 && marked * 100 >= letters.getOrDefault(Character.UnicodeScript.LATIN, 0);
            boolean byWords = shortHits.getOrDefault(tag, 0) * 5 >= shortWords * 2;
            return byLetters || byWords ? tag : "en-US";
        }

        private String cyrillic() {
            for (String tag : new String[] {"mk-MK", "sr-Cyrl-RS", "kk-KZ", "be-BY", "uk-UA"}) {
                if (v(tag) > 0) {
                    return tag;
                }
            }
            if (v("mn-MN") >= 2) {
                return "mn-MN";
            }
            return v("bg-BG") > 0 && v("ru-RU") == 0 ? "bg-BG" : "ru-RU";
        }

        private int v(String tag) {
            return votes.getOrDefault(tag, 0);
        }

        private String best(java.util.List<String> tags, int least) {
            String best = tags.getFirst();
            int n = least - 1;
            for (String t : tags) {
                if (v(t) > n) {
                    best = t;
                    n = v(t);
                }
            }
            return best;
        }
    }

    private static Character.UnicodeScript top(Map<Character.UnicodeScript, Integer> counts, Set<Character.UnicodeScript> among) {
        Character.UnicodeScript best = null;
        int n = 0;
        for (Map.Entry<Character.UnicodeScript, Integer> e : counts.entrySet()) {
            if (among.contains(e.getKey()) && e.getValue() > n) {
                best = e.getKey();
                n = e.getValue();
            }
        }
        return best;
    }

    private record Census(Character.UnicodeScript complex, Character.UnicodeScript other, Character.UnicodeScript latinScript,
            int latin, int eastAsian, int complexCount, int otherCount, int kana, int hangul, int emoji, Profile own) {}

    private static Census census(String text) {
        Map<Character.UnicodeScript, Integer> complex = new EnumMap<>(Character.UnicodeScript.class);
        Map<Character.UnicodeScript, Integer> other = new EnumMap<>(Character.UnicodeScript.class);
        Map<Character.UnicodeScript, Integer> latins = new EnumMap<>(Character.UnicodeScript.class);
        int latin = 0;
        int eastAsian = 0;
        int kana = 0;
        int hangul = 0;
        int emoji = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            Character.UnicodeScript s = Character.UnicodeScript.of(cp);
            if (cp >= 0x1F000 && cp <= 0x1FAFF) {
                emoji++;
                continue;
            }
            switch (group(cp, s)) {
                case LATIN -> {
                    if (Character.isLetter(cp)) {
                        latin++;
                        latins.merge(s, 1, Integer::sum);
                    }
                }
                case EAST_ASIAN -> {
                    eastAsian++;
                    kana += s == Character.UnicodeScript.HIRAGANA || s == Character.UnicodeScript.KATAKANA ? 1 : 0;
                    hangul += s == Character.UnicodeScript.HANGUL ? 1 : 0;
                }
                case COMPLEX -> complex.merge(s, 1, Integer::sum);
                case OTHER -> {
                    if (s != Character.UnicodeScript.COMMON) {
                        other.merge(s, 1, Integer::sum);
                    }
                }
            }
        }
        Character.UnicodeScript topOther = top(other, other.keySet());
        Profile own = new Profile();
        own.add(text);
        Character.UnicodeScript topComplex = top(complex, complex.keySet());
        return new Census(topComplex, topOther, top(latins, latins.keySet()), latin, eastAsian,
                topComplex == null ? 0 : complex.get(topComplex), topOther == null ? 0 : other.get(topOther), kana, hangul, emoji, own);
    }

    private static Group group(int cp, Character.UnicodeScript s) {
        if (cp < 0x0370) {
            return Group.LATIN;
        }
        if (COMPLEX_LANGUAGES.containsKey(s)) {
            return Group.COMPLEX;
        }
        return switch (s) {
            case LATIN, GREEK, CYRILLIC, INHERITED -> Group.LATIN;
            case HAN, HIRAGANA, KATAKANA, HANGUL, BOPOMOFO -> Group.EAST_ASIAN;
            case COMMON -> cp >= 0x3000 && cp <= 0x30FF || cp >= 0xFF00 && cp <= 0xFFEF || cp >= 0x31F0 && cp <= 0x31FF
                    || cp >= 0x3200 && cp <= 0x33FF ? Group.EAST_ASIAN : cp >= 0x2000 && cp < 0x2E80 ? Group.LATIN : Group.OTHER;
            default -> Group.OTHER;
        };
    }

    public static Fonts defaults(String family, Profile profile) {
        if (family == null || profile == null) {
            return new Fonts(family, family, family, false);
        }
        boolean serif = serif(family);
        String eastAsian = profile.eastAsian();
        Character.UnicodeScript complex = profile.complexScript();
        return new Fonts(family, eastAsian == null ? family : eastAsianFont(family, eastAsian, serif),
                complex == null ? family : complexFont(complex, family, serif), false);
    }

    public static Fonts fonts(String family, String text, Profile profile) {
        if (family == null || text == null || text.isEmpty() || western(text)) {
            return new Fonts(family, family, family, false);
        }
        Census c = census(text);
        boolean serif = serif(family);
        String latin = family;
        if (c.otherCount() > 0 && c.otherCount() >= c.latin()) {
            latin = otherFont(c.other(), family);
        } else if (c.emoji() > 0 && c.emoji() >= c.latin() && c.eastAsian() == 0 && c.complex() == null) {
            latin = "Segoe UI Emoji";
        }
        String eastAsian = c.eastAsian() > 0 ? eastAsianFont(family, eastAsian(c, profile), serif)
                : c.emoji() > 0 ? "Segoe UI Emoji" : family;
        String complex = c.complex() != null ? complexFont(c.complex(), family, serif) : family;
        return new Fonts(latin, eastAsian, complex, c.eastAsian() > 0 && c.eastAsian() >= c.latin());
    }

    public static Languages languages(String text, Profile profile) {
        if (text == null || text.isEmpty()) {
            return new Languages(null, null, null);
        }
        if (western(text)) {
            boolean letters = text.chars().anyMatch(Character::isLetter);
            return new Languages(letters && profile != null && profile.latinScript() == Character.UnicodeScript.LATIN
                    ? profile.latin() : null, null, null);
        }
        Census c = census(text);
        Character.UnicodeScript hAnsi = c.otherCount() > 0 && c.otherCount() >= c.latin() ? c.other()
                : c.latin() > 0 ? c.latinScript() : null;
        String latin = null;
        if (hAnsi != null && profile != null && hAnsi == profile.latinScript()) {
            latin = profile.latin();
        } else if (hAnsi != null && c.own().letters.getOrDefault(hAnsi, 0) >= 2) {
            latin = c.own().language(hAnsi);
        }
        String eastAsian = c.eastAsian() > 0 ? eastAsian(c, profile) : null;
        String complex = null;
        if (c.complex() != null) {
            complex = profile != null && c.complex() == profile.complexScript() ? profile.complex() : c.own().language(c.complex());
        }
        return new Languages(latin, eastAsian, complex);
    }

    public static Languages beyond(Languages l, Profile profile) {
        String latin = profile == null || profile.latin() == null ? "en-US" : profile.latin();
        String eastAsian = profile == null ? null : profile.eastAsian();
        String complex = profile == null ? null : profile.complex();
        return new Languages(l.latin() != null && l.latin().equals(latin) ? null : l.latin(),
                l.eastAsian() != null && l.eastAsian().equals(eastAsian) ? null : l.eastAsian(),
                l.complex() != null && l.complex().equals(complex) ? null : l.complex());
    }

    public static String cellFont(String family, String text, Profile profile) {
        if (text == null || western(text)) {
            return family;
        }
        Census c = census(text);
        Fonts f = fonts(family, text, profile);
        int latin = c.latin() + c.otherCount();
        if (c.eastAsian() > 0 && c.eastAsian() >= latin && c.eastAsian() >= c.complexCount()) {
            return f.eastAsian();
        }
        boolean coversLatin = c.complex() == Character.UnicodeScript.ARABIC || c.complex() == Character.UnicodeScript.HEBREW;
        return c.complexCount() > 0 && (coversLatin || c.complexCount() >= latin) ? f.complex() : f.latin();
    }

    public static boolean rightToLeft(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            byte d = Character.getDirectionality(cp);
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                return true;
            }
            if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
                return false;
            }
        }
        return false;
    }

    public static int lcid(String tag) {
        Integer id = tag == null ? null : LCIDS.get(tag);
        return id == null ? -1 : id;
    }

    public static String odfLanguages(Languages l) {
        StringBuilder sb = new StringBuilder();
        odfLanguage(sb, l.latin(), "fo:language", "fo:script", "fo:country");
        odfLanguage(sb, l.eastAsian(), "style:language-asian", "style:script-asian", "style:country-asian");
        odfLanguage(sb, l.complex(), "style:language-complex", "style:script-complex", "style:country-complex");
        return sb.toString();
    }

    private static void odfLanguage(StringBuilder sb, String tag, String language, String script, String country) {
        if (tag == null) {
            return;
        }
        String[] parts = tag.split("-");
        sb.append(' ').append(language).append("=\"").append(parts[0]).append('"');
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].length() == 4 || parts[i].length() == 2 && Character.isLetter(parts[i].charAt(0))
                    || parts[i].length() == 3 && Character.isDigit(parts[i].charAt(0))) {
                sb.append(' ').append(parts[i].length() == 4 ? script : country).append("=\"").append(parts[i]).append('"');
            }
        }
    }

    private static String eastAsian(Census c, Profile profile) {
        if (c.kana() > 0) {
            return "ja-JP";
        }
        if (c.hangul() > 0) {
            return "ko-KR";
        }
        String doc = profile == null ? null : profile.eastAsian();
        return doc != null ? doc : "zh-CN";
    }

    private static String eastAsianFont(String family, String language, boolean serif) {
        if (CJK_FONTS.contains(family)) {
            return family;
        }
        return switch (language) {
            case "ja-JP" -> serif ? "MS Mincho" : "MS Gothic";
            case "ko-KR" -> serif ? "Batang" : "Malgun Gothic";
            case "zh-TW" -> serif ? "PMingLiU" : "Microsoft JhengHei";
            default -> serif ? "SimSun" : "Microsoft YaHei";
        };
    }

    private static String complexFont(Character.UnicodeScript script, String family, boolean serif) {
        if (script == Character.UnicodeScript.ARABIC || script == Character.UnicodeScript.HEBREW) {
            if (COMPLEX_KEEP.get(script).contains(family)) {
                return family;
            }
            return family.equals("Courier New") ? family : serif ? "Times New Roman" : "Arial";
        }
        Set<String> keep = COMPLEX_KEEP.getOrDefault(script, INDIC_FONTS);
        if (keep.contains(family)) {
            return family;
        }
        return COMPLEX_FONTS.getOrDefault(script, family);
    }

    private static String otherFont(Character.UnicodeScript script, String family) {
        Set<String> keep = OTHER_KEEP.get(script);
        if (keep != null && keep.contains(family)) {
            return family;
        }
        return OTHER_FONTS.getOrDefault(script, family);
    }

    private static boolean serif(String family) {
        String k = family.toLowerCase(Locale.ROOT);
        return k.contains("times") || k.contains("roman") || k.contains("serif") && !k.contains("sans") || k.contains("georgia")
                || k.contains("cambria") || k.contains("garamond") || k.contains("book") || k.contains("palatino")
                || k.contains("century") || k.contains("mincho") || k.contains("song") || k.contains("ming")
                || k.contains("batang") || k.contains("constantia") || k.equals("sylfaen");
    }

    private static boolean western(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x0370) {
                return false;
            }
        }
        return true;
    }
}

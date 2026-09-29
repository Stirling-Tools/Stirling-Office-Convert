package stirling.software.officeconvert.topdf.font;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class Substitutes {

    static final List<String> SANS = List.of("Arial", "Liberation Sans", "Arimo", "Helvetica", "Carlito", "Calibri",
            "DejaVu Sans", "Noto Sans", "Segoe UI", "Open Sans", "Roboto", "Verdana", "Tahoma", "FreeSans");

    static final List<String> SERIF = List.of("Times New Roman", "Liberation Serif", "Tinos", "Times", "Cambria",
            "Caladea", "DejaVu Serif", "Noto Serif", "Georgia", "FreeSerif");

    static final List<String> MONO = List.of("Courier New", "Liberation Mono", "Cousine", "Courier", "Consolas",
            "DejaVu Sans Mono", "Noto Sans Mono", "FreeMono");

    private static final List<String> NARROW = List.of("Liberation Sans Narrow", "Arial Narrow", "Nimbus Sans Narrow",
            "DejaVu Sans Condensed");

    private static final List<String> JAPANESE_SANS = List.of("Yu Gothic", "Meiryo", "MS Gothic", "MS PGothic",
            "Noto Sans JP", "Noto Sans CJK JP", "IPAexGothic", "IPAGothic", "TakaoGothic", "VL Gothic",
            "Droid Sans Fallback");

    private static final List<String> JAPANESE_SERIF = List.of("Yu Mincho", "MS Mincho", "MS PMincho",
            "Noto Serif JP", "Noto Serif CJK JP", "IPAexMincho", "IPAMincho", "TakaoMincho");

    private static final List<String> CHINESE_SANS = List.of("Microsoft YaHei", "DengXian", "SimHei",
            "Noto Sans SC", "Noto Sans CJK SC", "Source Han Sans SC", "WenQuanYi Zen Hei", "WenQuanYi Micro Hei",
            "Droid Sans Fallback");

    private static final List<String> CHINESE_SERIF = List.of("SimSun", "NSimSun", "Noto Serif SC",
            "Noto Serif CJK SC", "AR PL UMing CN", "AR PL SungtiL GB");

    private static final List<String> TAIWAN = List.of("Microsoft JhengHei", "PMingLiU", "MingLiU", "Noto Sans TC",
            "Noto Sans CJK TC", "AR PL UMing TW", "WenQuanYi Zen Hei");

    private static final List<String> KOREAN = List.of("Malgun Gothic", "Gulim", "Dotum", "Batang", "Noto Sans KR",
            "Noto Sans CJK KR", "NanumGothic", "UnDotum", "Baekmuk Gulim");

    private static final List<String> SYMBOLS = List.of("Segoe UI Symbol", "Cambria Math", "DejaVu Sans",
            "Noto Sans Symbols", "Noto Sans Symbols 2", "Noto Sans Math", "Symbola", "Arial Unicode MS",
            "FreeSerif");

    private static final List<String> EMOJI = List.of("Segoe UI Emoji", "Noto Emoji", "Symbola", "Segoe UI Symbol",
            "DejaVu Sans");

    private static final Map<String, List<String>> TABLE = new HashMap<>();

    static {
        put(List.of("Carlito", "Calibri"), "Calibri", "Calibri Light");
        put(List.of("Caladea", "Cambria"), "Cambria");
        put(List.of("Liberation Sans", "Arimo", "Arial", "Helvetica", "Nimbus Sans", "Nimbus Sans L", "FreeSans"),
                "Arial", "Helvetica", "Helvetica Neue", "Arial MT", "Arial Unicode MS", "MS Sans Serif",
                "Microsoft Sans Serif", "Swiss", "Arial Black");
        put(List.of("Liberation Serif", "Tinos", "Times New Roman", "Times", "Nimbus Roman", "Nimbus Roman No9 L",
                "FreeSerif"), "Times New Roman", "Times", "Times Roman", "TimesNewRoman", "MS Serif", "Roman");
        put(List.of("Liberation Mono", "Cousine", "Courier New", "Courier", "Nimbus Mono PS", "Nimbus Mono L",
                "FreeMono"), "Courier New", "Courier", "Consolas", "Lucida Console", "Menlo", "Monaco",
                "Andale Mono", "Courier 10 Pitch");
        put(List.of("Liberation Sans Narrow", "Arial Narrow", "Nimbus Sans Narrow", "Liberation Sans", "Arimo",
                "Arial"), "Arial Narrow");
        put(List.of("Arial", "Liberation Sans", "Arimo", "Calibri", "Carlito", "Segoe UI"), "Aptos", "Aptos Display",
                "Aptos Light", "Aptos SemiBold", "Aptos ExtraBold", "Aptos Black");
        put(List.of("Arial Narrow", "Liberation Sans Narrow"), "Aptos Narrow");
        put(List.of("Cambria", "Caladea", "Georgia", "Gelasio", "Liberation Serif"), "Aptos Serif");
        put(List.of("Consolas", "Liberation Mono", "Cousine", "Courier New"), "Aptos Mono");
        put(List.of("Selawik", "Open Sans", "Noto Sans", "DejaVu Sans", "Liberation Sans", "Arial"), "Segoe UI",
                "Segoe UI Light", "Segoe UI Semilight", "Segoe UI Semibold", "Segoe UI Black");
        put(List.of("DejaVu Sans", "Bitstream Vera Sans", "Liberation Sans", "Arial"), "Verdana");
        put(List.of("Wine Tahoma", "DejaVu Sans Condensed", "DejaVu Sans", "Liberation Sans", "Arial"), "Tahoma");
        put(List.of("Gelasio", "Liberation Serif", "DejaVu Serif", "Times New Roman"), "Georgia");
        put(List.of("EB Garamond", "Garamond", "Cormorant Garamond", "Liberation Serif", "Times New Roman"),
                "Garamond", "Adobe Garamond Pro", "Garamond Premier Pro");
        put(List.of("URW Gothic", "URW Gothic L", "TeX Gyre Adventor", "Century Gothic", "Liberation Sans"),
                "Century Gothic", "ITC Avant Garde Gothic", "Avant Garde");
        put(List.of("TeX Gyre Schola", "Century Schoolbook L", "C059", "Liberation Serif"), "Century Schoolbook",
                "Century");
        put(List.of("TeX Gyre Pagella", "URW Palladio L", "P052", "Palatino", "Liberation Serif"), "Palatino Linotype",
                "Book Antiqua", "Palatino");
        put(List.of("TeX Gyre Bonum", "URW Bookman", "URW Bookman L", "Liberation Serif"), "Bookman Old Style",
                "Bookman");
        put(List.of("Liberation Sans", "Arial", "DejaVu Sans"), "Trebuchet MS", "Franklin Gothic Book",
                "Gill Sans MT", "Candara", "Corbel", "Lucida Sans Unicode", "Lucida Sans", "Futura", "Gill Sans");
        put(List.of("Liberation Serif", "Times New Roman", "DejaVu Serif"), "Constantia",
                "Baskerville Old Face", "Bodoni MT", "Perpetua", "Rockwell");
        put(List.of("Liberation Mono", "Courier New", "DejaVu Sans Mono"), "Lucida Sans Typewriter", "OCR A Extended");
        put(List.of("Cambria Math", "STIX Two Math", "Latin Modern Math", "DejaVu Math TeX Gyre", "Noto Sans Math",
                "Cambria", "Caladea"), "Cambria Math");
        put(List.of("Symbol", "Standard Symbols PS", "Standard Symbols L", "OpenSymbol", "Symbola", "DejaVu Sans"),
                "Symbol");
        put(List.of("Wingdings", "OpenSymbol", "Symbola", "Noto Sans Symbols 2", "DejaVu Sans"), "Wingdings",
                "Wingdings 2", "Wingdings 3", "Webdings");
        put(JAPANESE_SANS, "MS Gothic", "MS PGothic", "MS UI Gothic", "Yu Gothic", "Yu Gothic UI", "Meiryo",
                "Meiryo UI", "ＭＳ ゴシック", "ＭＳ Ｐゴシック", "游ゴシック", "メイリオ");
        put(JAPANESE_SERIF, "MS Mincho", "MS PMincho", "Yu Mincho", "ＭＳ 明朝", "ＭＳ Ｐ明朝", "游明朝");
        put(CHINESE_SANS, "Microsoft YaHei", "Microsoft YaHei UI", "DengXian", "SimHei", "微软雅黑", "等线", "黑体");
        put(CHINESE_SERIF, "SimSun", "NSimSun", "SimSun-ExtB", "FangSong", "KaiTi", "宋体", "新宋体", "仿宋", "楷体");
        put(TAIWAN, "PMingLiU", "MingLiU", "Microsoft JhengHei", "新細明體", "細明體", "微軟正黑體");
        put(KOREAN, "Malgun Gothic", "Gulim", "GulimChe", "Dotum", "DotumChe", "Batang", "BatangChe", "Gungsuh",
                "맑은 고딕", "굴림", "돋움", "바탕", "궁서");
    }

    private Substitutes() {}

    private static void put(List<String> substitutes, String... families) {
        for (String f : families) {
            TABLE.put(FontLibrary.normalize(f), substitutes);
        }
    }

    static List<String> table(String family) {
        return TABLE.getOrDefault(FontLibrary.normalize(family), List.of());
    }

    /** True for a Chinese, Japanese or Korean family name, in English or in its own script. */
    static boolean eastAsian(String family) {
        if (family == null) {
            return false;
        }
        String f = FontLibrary.normalize(family);
        List<String> chain = TABLE.get(f);
        if (chain == JAPANESE_SANS || chain == JAPANESE_SERIF || chain == CHINESE_SANS || chain == CHINESE_SERIF
                || chain == TAIWAN || chain == KOREAN) {
            return true;
        }
        String english = FontNames.english(family);
        return english != null || containsCjk(f);
    }

    // The installed East Asian faces closest to a missing one: its own language and style first, then Latin serif
    private static List<String> eastAsianChain(String f) {
        boolean serif = containsAny(f, "mincho", "明朝", "song", "sun", "宋", "ming", "明", "batang", "바탕", "gungsuh",
                "궁서", "kai", "楷", "fang", "仿");
        List<String> chain = new ArrayList<>();
        if (containsAny(f, "batang", "gulim", "dotum", "gungsuh", "malgun", "nanum", "바탕", "굴림", "돋움", "궁서", "고딕")) {
            chain.addAll(KOREAN);
            chain.addAll(JAPANESE_SANS);
        } else if (containsAny(f, "mingliu", "jhenghei", "dfkai", "明體", "正黑", "標楷")) {
            chain.addAll(TAIWAN);
            chain.addAll(CHINESE_SANS);
            chain.addAll(JAPANESE_SANS);
        } else if (containsAny(f, "simsun", "simhei", "yahei", "dengxian", "kaiti", "fangsong", "宋", "黑", "楷", "仿",
                "等线")) {
            chain.addAll(serif ? CHINESE_SERIF : CHINESE_SANS);
            chain.addAll(serif ? CHINESE_SANS : CHINESE_SERIF);
            chain.addAll(TAIWAN);
            chain.addAll(JAPANESE_SANS);
        } else {
            chain.addAll(serif ? JAPANESE_SERIF : JAPANESE_SANS);
            chain.addAll(serif ? JAPANESE_SANS : JAPANESE_SERIF);
            chain.addAll(CHINESE_SANS);
        }
        chain.addAll(KOREAN);
        if (serif) {
            chain.addAll(SERIF);
        }
        return chain;
    }

    static List<String> generic(String family) {
        String f = FontLibrary.normalize(family);
        List<String> chain = new ArrayList<>();
        boolean serif = !f.contains("sans") && !f.contains("gothic") && containsAny(f, "serif", "times", "roman",
                "garamond", "georgia", "antiqua", "palatino", "cambria", "minion", "baskerville", "bodoni", "caslon",
                "didot", "schoolbook", "book", "century", "constantia", "perpetua", "caladea", "tinos", "gelasio",
                "corsiva", "rockwell", "calisto", "californian", "goudy", "bell mt", "centaur", "footlight",
                "high tower", "lucida bright", "lucida fax", "elephant", "bernard", "modern no", "sylfaen", "cooper",
                "sitka", "charter", "utopia", "lora", "merriweather", "playfair", "crimson", "slab", "trajan", "sabon",
                "plantin", "janson", "cheltenham", "clarendon", "bembo", "jenson", "old style", "oldstyle", "imprint",
                "castellar", "algerian", "engravers", "felix titling", "colonna", "wide latin", "cambo", "domine");
        boolean mono = containsAny(f.replace("monotype", ""), "mono", "courier", "consol", "code", "typewriter",
                "fixed", "terminal");
        if (mono) {
            chain.addAll(MONO);
        } else if (eastAsian(family) || containsAny(f, "mincho", "ms gothic", "ms pgothic", "ms ui gothic",
                "yu gothic", "ud gothic", "biz ud", "hiragino", "meiryo", "malgun", "nanum", "batang", "gulim", "dotum",
                "gungsuh", "songti", "simsun", "simhei", "heiti", "yahei", "jhenghei", "mingliu", "dengxian",
                "fangsong", "kaiti", "source han", " cjk", "wenquanyi", "kozuka", "ipagothic", "ipamincho", "ipaex")) {
            String e = FontNames.english(family);
            chain.addAll(eastAsianChain(e == null ? f : FontLibrary.normalize(e) + " " + f));
        } else if (containsAny(f, "narrow", "condensed", "cond", "compressed")) {
            chain.addAll(serif ? List.of("DejaVu Serif Condensed") : NARROW);
        } else if (serif) {
            chain.addAll(SERIF);
        }
        chain.addAll(SANS);
        chain.addAll(SERIF);
        return chain;
    }

    static List<String> script(int codePoint) {
        Character.UnicodeScript script;
        try {
            script = Character.UnicodeScript.of(codePoint);
        } catch (IllegalArgumentException e) {
            return SYMBOLS;
        }
        List<String> out = new ArrayList<>();
        switch (script) {
            case HAN, BOPOMOFO -> {
                out.addAll(CHINESE_SANS);
                out.addAll(CHINESE_SERIF);
                out.addAll(JAPANESE_SANS);
                out.addAll(TAIWAN);
                out.addAll(KOREAN);
            }
            case HIRAGANA, KATAKANA -> {
                out.addAll(JAPANESE_SANS);
                out.addAll(JAPANESE_SERIF);
                out.addAll(CHINESE_SANS);
            }
            case HANGUL -> {
                out.addAll(KOREAN);
                out.addAll(JAPANESE_SANS);
            }
            // DejaVu before Noto: Noto Sans Arabic lines are 2.1 em tall against 1.15 for Arial's Arabic
            case ARABIC -> out.addAll(List.of("Arial", "Times New Roman", "Segoe UI", "Tahoma", "DejaVu Sans",
                    "Noto Sans Arabic", "Noto Naskh Arabic", "FreeSerif"));
            case HEBREW -> out.addAll(List.of("Arial", "Times New Roman", "Segoe UI", "David", "Noto Sans Hebrew",
                    "DejaVu Sans", "FreeSans"));
            case THAI -> out.addAll(List.of("Leelawadee UI", "Tahoma", "Noto Sans Thai", "Loma", "Garuda",
                    "FreeSerif"));
            case DEVANAGARI, BENGALI, GURMUKHI, GUJARATI, ORIYA, TAMIL, TELUGU, KANNADA, MALAYALAM, SINHALA -> {
                out.add("Nirmala UI");
                out.add("Mangal");
            }
            case COMMON, INHERITED, UNKNOWN -> {
                if (emoji(codePoint)) {
                    out.addAll(EMOJI);
                }
                out.addAll(SANS);
                out.addAll(SYMBOLS);
            }
            default -> {
            }
        }
        String name = script.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        out.add("Noto Sans " + name);
        out.add("Noto Serif " + name);
        out.addAll(List.of("Segoe UI", "Segoe UI Historic", "Arial Unicode MS", "DejaVu Sans", "FreeSans",
                "FreeSerif"));
        out.addAll(SYMBOLS);
        return out;
    }

    private static boolean emoji(int cp) {
        return cp >= 0x1F000 && cp <= 0x1FAFF || cp >= 0x2600 && cp <= 0x27BF;
    }

    private static boolean containsAny(String s, String... parts) {
        for (String p : parts) {
            if (s.contains(p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsCjk(String s) {
        return s.codePoints().anyMatch(cp -> {
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            return sc == Character.UnicodeScript.HAN || sc == Character.UnicodeScript.HIRAGANA
                    || sc == Character.UnicodeScript.KATAKANA || sc == Character.UnicodeScript.HANGUL;
        });
    }
}

package stirling.software.officeconvert.topdf.font;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class Substitutes {

    static final List<String> SANS = List.of("Arial", "Liberation Sans", "Arimo", "Helvetica", "Nimbus Sans",
            "TeX Gyre Heros", "Carlito", "Calibri", "DejaVu Sans", "Noto Sans", "Segoe UI", "Selawik", "Open Sans",
            "Roboto", "Verdana", "Tahoma", "FreeSans");

    static final List<String> SERIF = List.of("Times New Roman", "Liberation Serif", "Tinos", "Times", "Nimbus Roman",
            "TeX Gyre Termes", "Cambria", "Caladea", "DejaVu Serif", "Noto Serif", "Georgia", "Gelasio", "FreeSerif");

    static final List<String> MONO = List.of("Courier New", "Liberation Mono", "Cousine", "Courier", "Nimbus Mono PS",
            "TeX Gyre Cursor", "Consolas", "DejaVu Sans Mono", "Noto Sans Mono", "FreeMono");

    private static final List<String> NARROW = List.of("Liberation Sans Narrow", "Arial Narrow", "Nimbus Sans Narrow",
            "Roboto Condensed", "DejaVu Sans Condensed");

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

    private static final List<String> TAIWAN_SERIF = List.of("PMingLiU", "MingLiU", "Noto Serif TC",
            "Noto Serif CJK TC", "AR PL UMing TW", "Microsoft JhengHei", "Noto Sans TC", "WenQuanYi Zen Hei");

    private static final List<String> KOREAN = List.of("Malgun Gothic", "Gulim", "Dotum", "Batang", "Noto Sans KR",
            "Noto Sans CJK KR", "NanumGothic", "UnDotum", "Baekmuk Gulim");

    private static final List<String> KOREAN_SERIF = List.of("Batang", "Gungsuh", "Noto Serif KR", "Noto Serif CJK KR",
            "NanumMyeongjo", "UnBatang", "Baekmuk Batang", "Malgun Gothic", "Noto Sans KR", "Noto Sans CJK KR");

    private static final List<String> HANGUL_CELLS = List.of("WenQuanYi Zen Hei", "WenQuanYi Micro Hei");

    private static final List<String> SYMBOLS = List.of("Segoe UI Symbol", "Cambria Math", "DejaVu Sans",
            "Noto Sans Symbols", "Noto Sans Symbols 2", "Noto Sans Math", "Symbola", "Arial Unicode MS",
            "FreeSerif");

    private static final List<String> EMOJI = List.of("Segoe UI Emoji", "Noto Emoji", "Symbola", "Segoe UI Symbol",
            "DejaVu Sans");

    private static final Map<String, List<String>> TABLE = new HashMap<>();

    static {
        put(List.of("Carlito", "Calibri", "Source Sans 3", "Liberation Sans"), "Calibri");
        put(List.of("Carlito", "Calibri", "Source Sans 3", "Liberation Sans"), "Calibri Light");
        put(List.of("Caladea", "Cambria", "Source Serif 4", "Gelasio", "Liberation Serif"), "Cambria");
        put(List.of("Liberation Sans", "Arimo", "Arial", "Helvetica", "Nimbus Sans", "TeX Gyre Heros", "Nimbus Sans L",
                "FreeSans"), "Arial", "Helvetica", "Helvetica Neue", "Arial MT", "Arial Unicode MS", "MS Sans Serif",
                "Microsoft Sans Serif", "Swiss", "Arial Mäori", "Liberation Sans", "Nimbus Sans", "Arimo");
        put(List.of("Archivo Black", "Liberation Sans", "Arimo", "Arial", "Helvetica", "Nimbus Sans"), "Arial Black");
        put(List.of("Liberation Serif", "Tinos", "Times New Roman", "Times", "Nimbus Roman", "TeX Gyre Termes",
                "Nimbus Roman No9 L", "FreeSerif"), "Times New Roman", "Times", "Times Roman", "TimesNewRoman",
                "MS Serif", "Roman", "Liberation Serif", "Nimbus Roman", "Tinos");
        put(List.of("Liberation Mono", "Cousine", "Courier New", "Courier", "Nimbus Mono PS", "TeX Gyre Cursor",
                "Nimbus Mono L", "FreeMono"), "Courier New", "Courier", "Courier 10 Pitch", "Andale Mono", "Menlo",
                "Monaco", "SF Mono", "Liberation Mono", "Nimbus Mono PS", "Cousine");
        put(List.of("Inconsolata", "Source Code Pro", "Fira Mono", "DejaVu Sans Mono", "Liberation Mono", "Cousine",
                "Courier New"), "Consolas");
        put(List.of("DejaVu Sans Mono", "Bitstream Vera Sans Mono", "Liberation Mono", "Courier New"),
                "Lucida Console", "Lucida Sans Typewriter");
        put(List.of("Source Code Pro", "Fira Mono", "DejaVu Sans Mono", "Consolas", "Liberation Mono", "Courier New"),
                "Cascadia Code", "Cascadia Mono");
        put(List.of("Liberation Sans Narrow", "Arial Narrow", "Nimbus Sans Narrow", "Roboto Condensed",
                "Liberation Sans", "Arimo", "Arial"), "Arial Narrow", "Helvetica Narrow", "Liberation Sans Narrow",
                "Nimbus Sans Narrow");
        put(List.of("Source Sans 3", "Carlito", "Calibri", "Selawik", "Segoe UI", "Liberation Sans", "Arimo", "Arial"),
                "Aptos", "Aptos Display", "Aptos Light", "Aptos SemiBold", "Aptos ExtraBold", "Aptos Black");
        put(List.of("Roboto Condensed", "Liberation Sans Narrow", "Arial Narrow", "Nimbus Sans Narrow", "Carlito",
                "Calibri"), "Aptos Narrow");
        put(List.of("Caladea", "Cambria", "Source Serif 4", "Gelasio", "Georgia", "Liberation Serif"), "Aptos Serif");
        put(List.of("Source Code Pro", "Consolas", "Inconsolata", "Liberation Mono", "Cousine", "Courier New"),
                "Aptos Mono");
        put(List.of("Selawik", "Open Sans", "Noto Sans", "DejaVu Sans", "Liberation Sans", "Arial"), "Segoe UI",
                "Segoe UI Semibold", "Segoe UI Bold", "Segoe UI Black", "Segoe UI Historic", "Selawik");
        put(List.of("Selawik Light", "Selawik", "Open Sans", "Noto Sans", "Liberation Sans", "Arial"),
                "Segoe UI Light");
        put(List.of("Selawik Semilight", "Selawik", "Open Sans", "Noto Sans", "Liberation Sans", "Arial"),
                "Segoe UI Semilight");
        put(List.of("DejaVu Sans", "Bitstream Vera Sans", "Noto Sans", "Open Sans", "Liberation Sans", "Arial"),
                "Verdana", "MS Reference Sans Serif");
        put(List.of("Wine Tahoma", "DejaVu Sans Condensed", "DejaVu Sans", "Selawik", "Liberation Sans", "Arial"),
                "Tahoma");
        put(List.of("Gelasio", "Source Serif 4", "Liberation Serif", "DejaVu Serif", "Times New Roman"), "Georgia",
                "Georgia Pro", "Gelasio");
        put(List.of("EB Garamond", "Garamond", "Cormorant Garamond", "Liberation Serif", "Times New Roman"),
                "Garamond", "Adobe Garamond Pro", "Garamond Premier Pro", "ITC Garamond", "Stempel Garamond",
                "EB Garamond");
        put(List.of("URW Gothic", "TeX Gyre Adventor", "URW Gothic L", "Century Gothic", "Liberation Sans"),
                "Century Gothic", "ITC Avant Garde Gothic", "ITC Avant Garde", "Avant Garde", "Avant Garde Gothic",
                "Futura", "URW Gothic");
        put(List.of("C059", "TeX Gyre Schola", "Century Schoolbook L", "Liberation Serif"), "Century Schoolbook",
                "Century", "New Century Schoolbook", "Century Schoolbook L", "C059");
        put(List.of("P052", "TeX Gyre Pagella", "URW Palladio L", "Palatino", "Palatino Linotype", "Book Antiqua",
                "Liberation Serif"), "Palatino Linotype", "Book Antiqua", "Palatino", "URW Palladio L", "P052");
        put(List.of("URW Bookman", "TeX Gyre Bonum", "URW Bookman L", "Bookman Old Style", "Liberation Serif"),
                "Bookman Old Style", "Bookman", "ITC Bookman", "URW Bookman");
        put(List.of("Z003", "TeX Gyre Chorus", "URW Chancery L", "Monotype Corsiva", "Liberation Serif"),
                "Monotype Corsiva", "Zapf Chancery", "ITC Zapf Chancery", "Z003");
        put(List.of("Comic Relief", "Comic Neue", "Comic Sans MS", "Liberation Sans", "Arial"), "Comic Sans MS",
                "Comic Sans", "Segoe Print", "Ink Free", "Comic Neue");
        put(List.of("Fira Sans", "Source Sans 3", "Selawik", "DejaVu Sans", "Liberation Sans", "Arial"),
                "Trebuchet MS", "Bahnschrift");
        put(List.of("Source Sans 3", "Roboto", "Lato", "Liberation Sans", "Arial"), "Franklin Gothic",
                "Franklin Gothic Book", "Franklin Gothic Medium", "Franklin Gothic Demi", "Franklin Gothic Heavy",
                "Libre Franklin", "Tw Cen MT", "Source Sans 3", "Source Sans Pro");
        put(List.of("Roboto Condensed", "Liberation Sans Narrow", "Arial Narrow", "Nimbus Sans Narrow",
                "DejaVu Sans Condensed"), "Franklin Gothic Demi Cond", "Franklin Gothic Medium Cond",
                "Gill Sans MT Condensed", "Tw Cen MT Condensed", "Impact", "Haettenschweiler", "Roboto Condensed");
        put(List.of("Lato", "Carlito", "Source Sans 3", "Liberation Sans", "Arial"), "Gill Sans MT", "Gill Sans",
                "Gill Sans Nova", "Lato");
        put(List.of("Source Sans 3", "Carlito", "Lato", "Liberation Sans", "Arial"), "Candara");
        put(List.of("Carlito", "Source Sans 3", "Lato", "Liberation Sans", "Arial"), "Corbel", "Corbel Light");
        put(List.of("Open Sans", "DejaVu Sans", "Noto Sans", "Liberation Sans", "Arial"), "Lucida Sans",
                "Lucida Sans Unicode", "Open Sans");
        put(List.of("Source Serif 4", "Gelasio", "Caladea", "Liberation Serif", "Times New Roman"), "Constantia",
                "Lucida Bright", "Sitka Text", "Sitka Small", "Sitka Heading", "Sylfaen", "Source Serif 4",
                "Source Serif Pro");
        put(List.of("EB Garamond", "Liberation Serif", "Times New Roman", "DejaVu Serif"), "Baskerville Old Face",
                "Baskerville", "Perpetua", "Goudy Old Style", "Californian FB", "Calisto MT", "Centaur");
        put(List.of("Liberation Serif", "Times New Roman", "DejaVu Serif"), "Bodoni MT");
        put(List.of("C059", "DejaVu Serif", "Liberation Serif", "Times New Roman"), "Rockwell");
        put(List.of("Roboto", "Open Sans", "Noto Sans", "Liberation Sans", "Arial"), "Roboto", "Segoe UI Variable");
        put(List.of("Liberation Mono", "Courier New", "DejaVu Sans Mono"), "OCR A Extended");
        put(List.of("D050000L", "Noto Sans Symbols 2", "DejaVu Sans"), "ZapfDingbats", "Zapf Dingbats",
                "ITC Zapf Dingbats", "Dingbats");
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
        put(TAIWAN, "Microsoft JhengHei", "微軟正黑體");
        put(TAIWAN_SERIF, "PMingLiU", "MingLiU", "MingLiU-ExtB", "PMingLiU-ExtB", "新細明體", "細明體");
        put(KOREAN, "Malgun Gothic", "Gulim", "GulimChe", "Dotum", "DotumChe", "맑은 고딕", "굴림", "돋움");
        put(KOREAN_SERIF, "Batang", "BatangChe", "Gungsuh", "GungsuhChe", "바탕", "궁서");
    }

    // Measured against the Office fonts' own advances: every Latin glyph within 0.3 % of an em
    private static final Map<String, Set<String>> CLONES = new HashMap<>();

    static {
        clones(List.of("Carlito"), "Calibri");
        clones(List.of("Liberation Sans", "Arimo", "Nimbus Sans", "Nimbus Sans L", "TeX Gyre Heros"), "Arial",
                "Helvetica", "Arial MT", "Microsoft Sans Serif");
        clones(List.of("Arial"), "Helvetica", "Microsoft Sans Serif");
        clones(List.of("Liberation Sans Narrow", "Nimbus Sans Narrow"), "Arial Narrow", "Helvetica Narrow");
        clones(List.of("Arial Narrow"), "Helvetica Narrow");
        clones(List.of("Liberation Serif", "Tinos", "Nimbus Roman", "Nimbus Roman No9 L", "TeX Gyre Termes"),
                "Times New Roman", "Times", "Times Roman", "TimesNewRoman");
        clones(List.of("Times New Roman"), "Times", "Times Roman");
        clones(List.of("Liberation Mono", "Cousine", "Nimbus Mono PS", "Nimbus Mono L", "TeX Gyre Cursor", "FreeMono"),
                "Courier New", "Courier");
        clones(List.of("Courier New"), "Courier");
        clones(List.of("Gelasio"), "Georgia");
        clones(List.of("Selawik"), "Segoe UI");
        clones(List.of("Selawik Light"), "Segoe UI Light");
        clones(List.of("Selawik Semilight"), "Segoe UI Semilight");
        clones(List.of("URW Gothic", "URW Gothic L"), "Century Gothic");
        clones(List.of("URW Gothic", "URW Gothic L", "TeX Gyre Adventor"), "ITC Avant Garde Gothic", "ITC Avant Garde",
                "Avant Garde", "Avant Garde Gothic");
        clones(List.of("P052", "URW Palladio L"), "Book Antiqua");
        clones(List.of("P052", "URW Palladio L", "TeX Gyre Pagella"), "Palatino");
        clones(List.of("URW Bookman", "URW Bookman L", "TeX Gyre Bonum"), "Bookman Old Style", "Bookman",
                "ITC Bookman");
        clones(List.of("C059", "Century Schoolbook L", "TeX Gyre Schola"), "Century Schoolbook", "Century",
                "New Century Schoolbook");
        clones(List.of("DejaVu Sans Mono", "Bitstream Vera Sans Mono"), "Lucida Console", "Lucida Sans Typewriter");
        clones(List.of("Z003", "URW Chancery L", "TeX Gyre Chorus"), "Zapf Chancery", "ITC Zapf Chancery");
        clones(List.of("Comic Relief"), "Comic Sans MS");
        clones(List.of("Archivo Black"), "Arial Black");
        clones(List.of("Liberation Sans", "Arimo", "Arial"), "Arial Mäori");
        clones(List.of("Source Sans 3"), "Source Sans Pro");
    }

    private Substitutes() {}

    private static void put(List<String> substitutes, String... families) {
        for (String f : families) {
            TABLE.put(FontLibrary.normalize(f), substitutes);
        }
    }

    private static void clones(List<String> substitutes, String... families) {
        for (String f : families) {
            Set<String> set = CLONES.computeIfAbsent(FontLibrary.normalize(f), k -> new HashSet<>());
            for (String s : substitutes) {
                set.add(FontLibrary.normalize(s));
            }
        }
    }

    /** True when face {@code f} is a metric clone of {@code family} in the style it was asked for. */
    static boolean metricClone(String family, FontFace f) {
        Set<String> set = family == null ? null : CLONES.get(FontLibrary.normalize(family));
        if (set == null || f.syntheticBold() || f.syntheticItalic()) {
            return false;
        }
        FontEntry e = f.program().entry();
        return set.contains(FontLibrary.normalize(e.family())) || set.contains(FontLibrary.normalize(e.fullName()));
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
                || chain == TAIWAN || chain == TAIWAN_SERIF || chain == KOREAN || chain == KOREAN_SERIF) {
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
            chain.addAll(serif ? KOREAN_SERIF : KOREAN);
            chain.addAll(serif ? KOREAN : KOREAN_SERIF);
        } else if (containsAny(f, "mingliu", "jhenghei", "dfkai", "明體", "正黑", "標楷")) {
            chain.addAll(serif ? TAIWAN_SERIF : TAIWAN);
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
                out.addAll(HANGUL_CELLS);
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

    // Word draws these from Segoe UI Emoji too: the star, circle, squares, watch, hourglass and clock symbols
    private static boolean emoji(int cp) {
        return cp >= 0x1F000 && cp <= 0x1FAFF || cp >= 0x2600 && cp <= 0x27BF || cp == 0x2B50 || cp == 0x2B55
                || cp == 0x2B1B || cp == 0x2B1C || cp == 0x231A || cp == 0x231B || cp >= 0x23E9 && cp <= 0x23F3;
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

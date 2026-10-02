package stirling.software.officeconvert.extract;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class FontNames {

    private static final Map<String, String> ALIASES = new HashMap<>();

    private static final Set<String> SYMBOL_FAMILIES =
            Set.of("Symbol", "Wingdings", "Wingdings 2", "Wingdings 3", "Webdings", "MT Extra");

    static {
        alias("Arial", "arial", "arialmt", "helvetica", "helv", "helveticaneue", "arimo",
                "liberationsans", "nimbussans", "nimbussansl", "texgyreheros", "freesans");
        alias("Arial Narrow", "arialnarrow", "helveticanarrow", "nimbussansnarrow");
        alias("Arial Black", "arialblack");
        alias("Arial Unicode MS", "arialunicodems");
        alias("Times New Roman", "times", "timesroman", "timesnewroman", "timesnewromanps",
                "tinos", "liberationserif", "nimbusroman", "nimbusromno9l", "texgyretermes",
                "freeserif", "timesten");
        alias("Courier New", "courier", "couriernew", "cousine", "liberationmono", "nimbusmono",
                "nimbusmonol", "nimbusmonops", "freemono", "texgyrecursor");
        alias("Cambria Math", "cambriamath");
        alias("Calibri", "calibri", "carlito");
        alias("Cambria", "cambria", "caladea");
        alias("Trebuchet MS", "trebuchetms", "trebuchet");
        alias("Segoe UI", "segoeui");
        alias("Segoe UI Light", "segoeuilight");
        alias("Segoe UI Semibold", "segoeuisemibold");
        alias("Segoe UI Symbol", "segoeuisymbol", "zapfdingbats", "dingbats");
        alias("Book Antiqua", "bookantiqua");
        alias("Palatino Linotype", "palatinolinotype", "palatino", "texgyrepagella", "urwpalladio");
        alias("Bookman Old Style", "bookmanoldstyle", "bookman");
        alias("Century Gothic", "centurygothic");
        alias("Century Schoolbook", "centuryschoolbook", "newcenturyschlbk");
        alias("Comic Sans MS", "comicsansms");
        alias("Lucida Console", "lucidaconsole");
        alias("Lucida Sans Unicode", "lucidasansunicode");
        alias("Franklin Gothic Medium", "franklingothicmedium");
        alias("MS Gothic", "msgothic");
        alias("MS Mincho", "msmincho");
        alias("MS PGothic", "mspgothic");
        alias("Microsoft YaHei", "microsoftyahei");
        alias("Microsoft JhengHei", "microsoftjhenghei");
        alias("Malgun Gothic", "malgungothic");
        alias("Yu Gothic", "yugothic");
        alias("Yu Mincho", "yumincho");
        alias("SimSun", "simsun");
        alias("SimHei", "simhei");
        alias("MingLiU", "mingliu");
        alias("Meiryo", "meiryo");
        for (String plain :
                new String[] {
                    "Candara", "Consolas", "Constantia", "Corbel",
                    "Georgia", "Verdana", "Tahoma", "Garamond", "Impact", "Symbol", "Wingdings",
                    "Webdings", "Aptos", "Gadugi", "Ebrima", "Nirmala UI", "Sylfaen", "Batang",
                    "Gulim", "Dotum", "Gungsuh", "Mangal", "Aharoni", "David", "Miriam",
                    "Traditional Arabic", "Simplified Arabic", "Arabic Typesetting", "Leelawadee"
                }) {
            alias(plain, key(plain));
        }
    }

    private FontNames() {}

    private static void alias(String family, String... keys) {
        for (String k : keys) {
            ALIASES.put(k, family);
        }
    }

    private static String key(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    public static String stripSubset(String name) {
        if (name != null && name.length() > 7 && name.charAt(6) == '+') {
            for (int i = 0; i < 6; i++) {
                char c = name.charAt(i);
                if (c < 'A' || c > 'Z') {
                    return name;
                }
            }
            return name.substring(7);
        }
        return name;
    }

    static String basePart(String ps) {
        int cut = -1;
        for (int i = 1; i < ps.length(); i++) {
            char c = ps.charAt(i);
            if (c == ',' || c == '-' || c == '_') {
                cut = i;
                break;
            }
        }
        return cut > 0 ? ps.substring(0, cut) : ps;
    }

    static String stylePart(String ps) {
        String base = basePart(ps);
        String rest = ps.length() > base.length() ? ps.substring(base.length() + 1) : "";
        return rest.toLowerCase(Locale.ROOT);
    }

    public static String family(String candidate, String postScriptName) {
        String fromCandidate = candidate == null ? null : resolve(candidate);
        if (fromCandidate != null) {
            return fromCandidate;
        }
        if (postScriptName == null || postScriptName.isBlank()) {
            return null;
        }
        String ps = stripSubset(postScriptName.strip());
        String tex = texFamily(ps);
        if (tex != null) {
            return tex;
        }
        String resolved = resolve(ps);
        if (resolved != null) {
            return resolved;
        }
        String base = trimPsSuffix(basePart(ps));
        String aliased = ALIASES.get(key(base));
        if (aliased != null) {
            return aliased;
        }
        String stripped = stripStyleWords(base);
        aliased = ALIASES.get(key(stripped));
        return aliased != null ? aliased : splitCamel(stripped);
    }

    private static String resolve(String name) {
        String k = key(name);
        if (k.isEmpty()) {
            return null;
        }
        String direct = ALIASES.get(k);
        if (direct != null) {
            return direct;
        }
        String trimmed = key(trimPsSuffix(name));
        return ALIASES.get(trimmed);
    }

    private static String trimPsSuffix(String base) {
        String[] suffixes = {"PSMT", "PS", "MT"};
        for (String s : suffixes) {
            if (base.length() > s.length() + 2 && base.endsWith(s)) {
                return base.substring(0, base.length() - s.length());
            }
        }
        return base;
    }

    private static String stripStyleWords(String base) {
        String[] words = {
            "BoldItalic", "BoldOblique", "Bold", "Italic", "Oblique", "Regular", "Roman", "Book"
        };
        for (String w : words) {
            if (base.length() > w.length() + 2 && base.endsWith(w)) {
                return base.substring(0, base.length() - w.length());
            }
        }
        return base;
    }

    static String splitCamel(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                char prev = s.charAt(i - 1);
                boolean nextLower = i + 1 < s.length() && Character.isLowerCase(s.charAt(i + 1));
                if (Character.isLowerCase(prev) || (Character.isUpperCase(prev) && nextLower)) {
                    sb.append(' ');
                }
            }
            sb.append(c);
        }
        return sb.toString().strip();
    }

    private static String texFamily(String ps) {
        String lower = ps.toLowerCase(Locale.ROOT);
        if (lower.matches("(cm|lm|sf|ec|tc)(tt|mono|vtt|itt|sltt)[a-z0-9]*")
                || lower.startsWith("lmmono")) {
            return "Courier New";
        }
        if (lower.matches("(cm|sf|ec)(ss|sssbx|ssi|ssbx|ssdc)[0-9]*") || lower.startsWith("lmsans")) {
            return "Arial";
        }
        if (lower.matches("(cmsy|cmex|msbm|msam|cmmib|stmary|rsfs|eufm|wasy)[0-9]*")) {
            return "Cambria Math";
        }
        if (lower.matches("(cm|sf|ec|tc)[a-z]{1,4}[0-9]{1,4}") || lower.startsWith("lmroman")) {
            return "Times New Roman";
        }
        return null;
    }

    private static final Set<String> OFFICE = Set.of(
            "Arial", "Arial Narrow", "Arial Black", "Arial Unicode MS", "Times New Roman", "Courier New",
            "Calibri", "Calibri Light", "Cambria", "Cambria Math", "Candara", "Consolas", "Constantia", "Corbel",
            "Georgia", "Verdana", "Tahoma", "Trebuchet MS", "Segoe UI", "Segoe UI Light", "Segoe UI Semibold",
            "Segoe UI Symbol", "Garamond", "Book Antiqua", "Palatino Linotype", "Bookman Old Style",
            "Century Gothic", "Century Schoolbook", "Comic Sans MS", "Impact", "Lucida Console",
            "Lucida Sans Unicode", "Franklin Gothic Medium", "Gill Sans MT", "Symbol", "Wingdings", "Webdings",
            "Aptos", "MS Gothic", "MS Mincho", "MS PGothic", "SimSun", "SimHei", "Microsoft YaHei",
            "Microsoft JhengHei", "Malgun Gothic", "Yu Gothic", "Yu Mincho", "Meiryo", "MingLiU", "Gadugi",
            "Ebrima", "Nirmala UI", "Sylfaen", "Batang", "Gulim", "Dotum", "Gungsuh", "Mangal", "Aharoni",
            "David", "Miriam", "Traditional Arabic", "Simplified Arabic", "Arabic Typesetting", "Leelawadee",
            "Bahnschrift", "Ink Free", "Sitka Text", "Lucida Sans", "Century", "Baskerville Old Face");

    public static boolean isOfficeFont(String family) {
        return family != null && OFFICE.contains(family);
    }

    private static final java.util.regex.Pattern ICON_FONT = java.util.regex.Pattern.compile(
            "(?i).*(fontawesome|font-awesome|fa-(solid|regular|brands|light|thin|duotone)|materialicons|material-?symbols"
                    + "|materialdesignicons|glyphicons|icomoon|fontello|octicons|ionicons|feather|remixicon|bootstrap-?icons"
                    + "|lineawesome|line-awesome|simple-?line-?icons|themify|typicons|entypo|foundation-?icons|academicons"
                    + "|devicons?|weathericons|fontisto|boxicons|phosphor|tabler-?icons|lucide|codicon|dashicons).*");

    public static boolean isIconFont(String postScriptName) {
        return postScriptName != null && ICON_FONT.matcher(stripSubset(postScriptName)).matches();
    }

    private static final java.util.regex.Pattern SMALL_CAPS =
            java.util.regex.Pattern.compile("(?i:.*(smallcaps|small-caps|csc\\d|caps\\d).*)|.*-[A-Za-z]*SC");

    public static boolean isSmallCaps(String postScriptName) {
        return postScriptName != null && SMALL_CAPS.matcher(stripSubset(postScriptName)).matches();
    }

    public static boolean isTexFont(String postScriptName) {
        return postScriptName != null && texFamily(stripSubset(postScriptName)) != null;
    }

    public static boolean isSymbolFamily(String family) {
        return family != null && SYMBOL_FAMILIES.contains(family);
    }

    static boolean styleIsBold(String style) {
        return style.startsWith("medi") && !style.startsWith("medium")
                || style.contains("bold")
                || style.contains("black")
                || style.contains("heavy")
                || style.contains("semibold")
                || style.contains("demi") && !style.contains("demilight")
                || style.equals("bd")
                || style.startsWith("bd")
                || style.contains("extrabold")
                || style.contains("ultrabold");
    }

    static boolean styleIsItalic(String style) {
        return style.contains("ital")
                || style.contains("italic")
                || style.contains("oblique")
                || style.equals("it")
                || style.endsWith("it")
                || style.contains("slanted")
                || style.contains("kursiv");
    }

    public static boolean looksSerif(String family) {
        String k = key(family);
        return k.contains("times") || k.contains("roman") || k.contains("serif") && !k.contains("sans")
                || k.contains("georgia") || k.contains("cambria") || k.contains("garamond")
                || k.contains("book") || k.contains("palatino") || k.contains("century")
                || k.contains("minion") || k.contains("baskerville") || k.contains("caslon")
                || k.contains("mincho") || k.contains("song") || k.contains("constantia");
    }

    public static boolean looksMono(String family) {
        String k = key(family);
        return k.contains("mono") || k.contains("courier") || k.contains("consol")
                || k.contains("code") || k.contains("typewriter") || k.contains("lucidaconsole");
    }
}

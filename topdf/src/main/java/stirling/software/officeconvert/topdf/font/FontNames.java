package stirling.software.officeconvert.topdf.font;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Family names as documents write them: PostScript names and style words glued to the family. */
final class FontNames {

    record Alias(String family, boolean bold, boolean italic) {}

    private static final Set<String> BOLD = Set.of("bold", "bd", "semibold", "demibold", "demi", "heavy", "black",
            "grassetto", "fett", "gras", "negrita", "negrito", "vet", "pogrubiony", "fet", "lihavoitu", "félkövér",
            "tučné", "полужирный");

    private static final Set<String> ITALIC = Set.of("italic", "it", "oblique", "grassettocorsivo", "kursiv",
            "italique", "cursiva", "corsivo", "itálico", "cursief", "kursywa", "kursivoitu", "dőlt", "kurzíva",
            "курсив");

    private static final Set<String> PLAIN = Set.of("regular", "normal", "standard", "normale", "обычный");

    private static final Set<String> PLAIN_STYLE = Set.of("regular", "roman", "normal", "book", "plain", "medium",
            "standard");

    // Style words that name a family of their own ("Calibri-Light" is Calibri Light, not Calibri)
    private static final Set<String> NAMED = Set.of("light", "semilight", "extralight", "ultralight", "thin",
            "hairline", "display", "narrow", "condensed", "cond", "semicondensed");

    // Office writes East Asian families under their local names; the metrics tables use the English ones
    private static final Map<String, String> LOCAL = localNames("ＭＳ 明朝", "MS Mincho", "ＭＳ Ｐ明朝", "MS PMincho",
            "ＭＳ ゴシック", "MS Gothic", "ＭＳ Ｐゴシック", "MS PGothic", "ＭＳ ＵＩ ゴシック", "MS UI Gothic", "游ゴシック",
            "Yu Gothic", "游ゴシック Light", "Yu Gothic Light", "游ゴシック Medium", "Yu Gothic Medium", "游明朝",
            "Yu Mincho", "メイリオ", "Meiryo", "宋体", "SimSun", "新宋体", "NSimSun", "黑体", "SimHei", "楷体", "KaiTi",
            "楷体_GB2312", "KaiTi", "仿宋", "FangSong", "仿宋_GB2312", "FangSong", "微软雅黑", "Microsoft YaHei", "等线",
            "DengXian", "等线 Light", "DengXian Light", "新細明體", "PMingLiU", "細明體", "MingLiU", "微軟正黑體",
            "Microsoft JhengHei", "標楷體", "DFKai-SB", "맑은 고딕", "Malgun Gothic", "바탕", "Batang", "바탕체",
            "BatangChe", "굴림", "Gulim", "굴림체", "GulimChe", "돋움", "Dotum", "돋움체", "DotumChe", "궁서", "Gungsuh",
            "游ゴシック体", "YuGothic", "游明朝体", "YuMincho", "ヒラギノ角ゴ Pro W3", "Hiragino Kaku Gothic Pro",
            "ヒラギノ角ゴ ProN W3", "Hiragino Kaku Gothic ProN", "ヒラギノ明朝 Pro W3", "Hiragino Mincho Pro",
            "ヒラギノ明朝 ProN W3", "Hiragino Mincho ProN", "微软雅黑 Light", "Microsoft YaHei Light", "华文细黑",
            "STXihei", "华文黑体", "STHeiti", "华文宋体", "STSong", "华文楷体", "STKaiti", "华文仿宋", "STFangsong",
            "苹方-简", "PingFang SC", "蘋方-繁", "PingFang TC", "나눔고딕", "NanumGothic", "나눔명조", "NanumMyeongjo");

    private FontNames() {}

    private static Map<String, String> localNames(String... pairs) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(FontLibrary.normalize(pairs[i]), pairs[i + 1]);
        }
        return Map.copyOf(m);
    }

    /** The English name of an East Asian family written under its local name, else null. */
    static String english(String name) {
        return name == null ? null : LOCAL.get(FontLibrary.normalize(name));
    }

    static Alias alias(String name) {
        if (name == null) {
            return null;
        }
        String local = english(name);
        if (local != null) {
            return new Alias(local, false, false);
        }
        String n = name.strip();
        boolean bold = false;
        boolean italic = false;
        int dash = n.indexOf('-');
        StringBuilder named = new StringBuilder();
        if (n.indexOf(' ') < 0 && dash > 0) {
            String[] style = spaced(stripVendor(n.substring(dash + 1))).split("\s+");
            boolean known = true;
            for (String w : style) {
                String l = w.toLowerCase(Locale.ROOT);
                if (BOLD.contains(l) || l.equals("bolditalic") || l.equals("boldoblique")) {
                    bold = true;
                } else if (NAMED.contains(l)) {
                    named.append(' ').append(w);
                } else if (!ITALIC.contains(l) && !PLAIN_STYLE.contains(l)) {
                    known = false;
                }
                italic |= ITALIC.contains(l) || l.equals("bolditalic") || l.equals("boldoblique");
            }
            if (known) {
                n = n.substring(0, dash);
            } else {
                bold = false;
                italic = false;
                named.setLength(0);
            }
        }
        if (n.indexOf(' ') < 0) {
            n = spaced(stripVendor(n));
        }
        n += named;
        String[] words = n.split("\s+");
        int keep = words.length;
        while (keep > 1) {
            String w = words[keep - 1].toLowerCase(Locale.ROOT);
            if (BOLD.contains(w)) {
                bold = true;
            } else if (ITALIC.contains(w)) {
                italic = true;
                bold |= w.equals("grassettocorsivo");
            } else if (!PLAIN.contains(w)) {
                break;
            }
            keep--;
        }
        String family = String.join(" ", Arrays.copyOf(words, keep));
        if (family.isEmpty() || FontLibrary.normalize(family).equals(FontLibrary.normalize(name))) {
            return null;
        }
        return new Alias(family, bold, italic);
    }

    private static String stripVendor(String n) {
        for (String suffix : new String[] {"PSMT", "MT", "PS"}) {
            if (n.length() > suffix.length() + 2 && n.endsWith(suffix)
                    && Character.isLowerCase(n.charAt(n.length() - suffix.length() - 1))) {
                return n.substring(0, n.length() - suffix.length());
            }
        }
        return n;
    }

    // "TimesNewRoman" -> "Times New Roman", "MSGothic" -> "MS Gothic", "BoldItalic" -> "Bold Italic"
    private static String spaced(String n) {
        StringBuilder b = new StringBuilder(n.length() + 4);
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                char p = n.charAt(i - 1);
                boolean nextLower = i + 1 < n.length() && Character.isLowerCase(n.charAt(i + 1));
                if (Character.isLowerCase(p) || Character.isUpperCase(p) && nextLower) {
                    b.append(' ');
                }
            }
            b.append(c);
        }
        return b.toString();
    }
}

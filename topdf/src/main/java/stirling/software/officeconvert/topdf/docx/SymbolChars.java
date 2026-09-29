package stirling.software.officeconvert.topdf.docx;

import java.util.Locale;
import java.util.function.IntPredicate;

import stirling.software.officeconvert.topdf.font.FontFace;

// Word's Symbol and Wingdings codes, for when those fonts are missing and a Unicode face draws them instead
final class SymbolChars {

    private static final String SYMBOL =
            "\u0000\u0021\u2200\u0023\u2203\u0025\u0026\u220B\u0028\u0029\u2217\u002B\u002C\u2212\u002E\u002F"
            + "\u0030\u0031\u0032\u0033\u0034\u0035\u0036\u0037\u0038\u0039\u003A\u003B\u003C\u003D\u003E\u003F"
            + "\u2245\u0391\u0392\u03A7\u0394\u0395\u03A6\u0393\u0397\u0399\u03D1\u039A\u039B\u039C\u039D\u039F"
            + "\u03A0\u0398\u03A1\u03A3\u03A4\u03A5\u03C2\u03A9\u039E\u03A8\u0396\u005B\u2234\u005D\u22A5\u005F"
            + "\u203E\u03B1\u03B2\u03C7\u03B4\u03B5\u03C6\u03B3\u03B7\u03B9\u03D5\u03BA\u03BB\u03BC\u03BD\u03BF"
            + "\u03C0\u03B8\u03C1\u03C3\u03C4\u03C5\u03D6\u03C9\u03BE\u03C8\u03B6\u007B\u007C\u007D\u223C\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u20AC\u03D2\u2032\u2264\u2044\u221E\u0192\u2663\u2666\u2665\u2660\u2194\u2190\u2191\u2192\u2193"
            + "\u00B0\u00B1\u2033\u2265\u00D7\u221D\u2202\u2022\u00F7\u2260\u2261\u2248\u2026\u23D0\u23AF\u21B5"
            + "\u2135\u2111\u211C\u2118\u2297\u2295\u2205\u2229\u222A\u2283\u2287\u2284\u2282\u2286\u2208\u2209"
            + "\u2220\u2207\u00AE\u00A9\u2122\u220F\u221A\u22C5\u00AC\u2227\u2228\u21D4\u21D0\u21D1\u21D2\u21D3"
            + "\u25CA\u2329\u00AE\u00A9\u2122\u2211\u239B\u239C\u239D\u23A1\u23A2\u23A3\u23A7\u23A8\u23A9\u23AA"
            + "\u0000\u232A\u222B\u2320\u23AE\u2321\u239E\u239F\u23A0\u23A4\u23A5\u23A6\u23AB\u23AC\u23AD\u0000";

    private static final String WINGDINGS =
            "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u260E\u0000\u2709\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u261C\u261E\u0000\u0000\u0000\u263A\u0000\u2639\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u25CF\u274D\u25A0\u25A1"
            + "\u0000\u2751\u2752\u0000\u29EB\u25C6\u2756\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u00B7\u2022"
            + "\u25AA\u25CB\u0000\u0000\u25C9\u25CE\u0000\u25AA\u25FB\u0000\u0000\u2605\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u27A2\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u2717\u2713\u2612\u2611\u0000";

    private static final String WINGDINGS_2 =
            "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u2717"
            + "\u2713\u0000\u2611\u2612\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u2610\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000";

    static final int BULLET = 0x2022;

    private SymbolChars() {}

    static String remap(String text, FontFace face, boolean label, IntPredicate drawable) {
        if (!face.substituted() || face.symbolEncoded()) {
            return text;
        }
        return remap(text, face.requestedFamily(), label, face::covers, drawable);
    }

    static String remap(String text, String family, boolean label, IntPredicate covers, IntPredicate drawable) {
        String table = table(family);
        if (table == null) {
            return text;
        }
        StringBuilder out = null;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int n = Character.charCount(cp);
            int mapped = map(table, cp, covers);
            if (mapped > 0 && label && !drawable.test(mapped)) {
                mapped = BULLET;
            }
            if (mapped <= 0 && label && code(cp, covers) > 0x20) {
                mapped = BULLET;
            }
            if (mapped > 0 && mapped != cp && out == null) {
                out = new StringBuilder(text.length());
                out.append(text, 0, i);
            }
            if (out != null) {
                out.appendCodePoint(mapped > 0 ? mapped : cp);
            }
            i += n;
        }
        return out == null ? text : out.toString();
    }

    private static int map(String table, int cp, IntPredicate covers) {
        int code = code(cp, covers);
        if (code < 0x20 || code > 0xFF) {
            return -1;
        }
        char c = table.charAt(code - 0x20);
        return c == 0 ? -1 : c;
    }

    private static int code(int cp, IntPredicate covers) {
        if (cp >= 0xF020 && cp <= 0xF0FF && !covers.test(cp)) {
            return cp - 0xF000;
        }
        return cp >= 0x20 && cp <= 0xFF ? cp : -1;
    }

    // Win ascent and descent in ems of the Windows fonts, which set the line height of a list label even when missing
    static float[] vertical(String family) {
        if (family == null) {
            return null;
        }
        return switch (family.strip().toLowerCase(Locale.ROOT)) {
            case "symbol" -> new float[] {2059f / 2048, 450f / 2048};
            case "wingdings" -> new float[] {1841f / 2048, 432f / 2048};
            case "wingdings 2" -> new float[] {1727f / 2048, 432f / 2048};
            case "wingdings 3" -> new float[] {1900f / 2048, 432f / 2048};
            default -> null;
        };
    }

    private static String table(String family) {
        if (family == null) {
            return null;
        }
        return switch (family.strip().toLowerCase(Locale.ROOT)) {
            case "symbol" -> SYMBOL;
            case "wingdings" -> WINGDINGS;
            case "wingdings 2" -> WINGDINGS_2;
            default -> null;
        };
    }
}

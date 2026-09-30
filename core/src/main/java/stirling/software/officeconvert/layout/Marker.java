package stirling.software.officeconvert.layout;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record Marker(Kind kind, String text, int value, String prefix, String suffix) {

    public enum Kind {
        BULLET,
        DECIMAL,
        LOWER_LETTER,
        UPPER_LETTER,
        LOWER_ROMAN,
        UPPER_ROMAN,
        HEBREW,
        ARABIC_ABJAD,
        ARABIC_ALPHA,
        CHINESE,
        FULL_WIDTH,
        GANADA
    }

    private static final String ABJAD = "\u0627\u0628\u062C\u062F\u0647\u0648\u0632\u062D\u0637\u064A\u0643\u0644\u0645\u0646"
            + "\u0633\u0639\u0641\u0635\u0642\u0631\u0634\u062A\u062B\u062E\u0630\u0636\u0638\u063A";

    private static final String ALPHA = "\u0627\u0628\u062A\u062B\u062C\u062D\u062E\u062F\u0630\u0631\u0632\u0633\u0634\u0635"
            + "\u0636\u0637\u0638\u0639\u063A\u0641\u0642\u0643\u0644\u0645\u0646\u0647\u0648\u064A";

    private static final String HEBREW_LETTERS = "\u05D0\u05D1\u05D2\u05D3\u05D4\u05D5\u05D6\u05D7\u05D8\u05D9\u05DB\u05DC"
            + "\u05DE\u05E0\u05E1\u05E2\u05E4\u05E6\u05E7\u05E8\u05E9\u05EA";

    private static final int[] HEBREW_VALUES = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 200, 300, 400};

    private static final String CHINESE_DIGITS = "\u3007\u4E00\u4E8C\u4E09\u56DB\u4E94\u516D\u4E03\u516B\u4E5D";

    private static final String GANADA_LETTERS = "\uAC00\uB098\uB2E4\uB77C\uB9C8\uBC14\uC0AC\uC544\uC790\uCC28\uCE74\uD0C0\uD30C\uD558";

    private static final char FULL_WIDTH_CLOSE = 0xFF09;

    private static final String OPENERS = "([\uFF08";

    private static final String CLOSERS = ".)]:\uFF0E\u3001\uFF09";

    private static final String BULLETS = "•●◦▪■□‣⁃∙○◆◇►▶➢➤✓✔❖–\u2014-*·§➔→⇒✗☐☑✦★☆❑◘♦";

    private static final Pattern NUMBERED =
            Pattern.compile("^([(\\[]?)([0-9]{1,3}|[a-zA-Z]|[ivxlcdmIVXLCDM]{1,6})([.)\\]:]?)$");

    public boolean isBullet() {
        return kind == Kind.BULLET;
    }

    public String wordFormat() {
        return switch (kind) {
            case BULLET -> "bullet";
            case DECIMAL -> "decimal";
            case LOWER_LETTER -> "lowerLetter";
            case UPPER_LETTER -> "upperLetter";
            case LOWER_ROMAN -> "lowerRoman";
            case UPPER_ROMAN -> "upperRoman";
            case HEBREW -> "hebrew1";
            case ARABIC_ABJAD -> "arabicAbjad";
            case ARABIC_ALPHA -> "arabicAlpha";
            case CHINESE -> "chineseCounting";
            case FULL_WIDTH -> "decimalFullWidth";
            case GANADA -> "ganada";
        };
    }

    public static int startIndex(Line line) {
        return line.words.size() >= 2 && LogicalOrder.rtlBase(line) ? line.words.size() - 1 : 0;
    }

    public static int nextIndex(Line line) {
        int s = startIndex(line);
        return s == 0 ? 1 : s - 1;
    }

    public static Marker leading(Line line) {
        if (line.words.size() < 2) {
            return null;
        }
        int s = startIndex(line);
        Word w = line.words.get(s);
        if (s == 0) {
            return parse(w.text, w.first().font);
        }
        StringBuilder text = new StringBuilder();
        for (LogicalOrder.Token t : LogicalOrder.of(line, s, true)) {
            if (t.glyph() != null) {
                text.append(t.text());
            }
        }
        return parse(text.toString(), w.first().font);
    }

    public static float gapAfter(Line line) {
        int s = startIndex(line);
        int n = nextIndex(line);
        return s == 0 ? line.words.get(n).x - line.words.get(s).right : line.words.get(s).x - line.words.get(n).right;
    }

    public static boolean tabAfter(Line line) {
        int s = startIndex(line);
        return line.gaps[s == 0 ? 1 : s] != Line.SPACE;
    }

    public static Marker parse(String word, stirling.software.officeconvert.extract.FontInfo font) {
        if ("o".equals(word) && font != null && (font.mono() || font.symbolic())) {
            return new Marker(Kind.BULLET, word, 0, "", "");
        }
        return parse(word);
    }

    public static Marker parse(String word) {
        if (word == null || word.isEmpty() || word.length() > 8) {
            return null;
        }
        if (word.length() <= 2) {
            boolean allBullets = true;
            for (int i = 0; i < word.length(); i++) {
                char c = word.charAt(i);
                if (BULLETS.indexOf(c) < 0 && (c < 0xF020 || c > 0xF0FF)) {
                    allBullets = false;
                }
            }
            if (allBullets) {
                return new Marker(Kind.BULLET, word, 0, "", "");
            }
        }
        Marker world = world(word);
        if (world != null) {
            return world;
        }
        Matcher m = NUMBERED.matcher(word);
        if (!m.matches()) {
            return null;
        }
        String prefix = m.group(1);
        String body = m.group(2);
        String suffix = m.group(3);
        if (suffix.isEmpty()) {
            return null;
        }
        if (prefix.equals("(") && !suffix.equals(")") || prefix.equals("[") && !suffix.equals("]")) {
            return null;
        }
        if (Character.isDigit(body.charAt(0))) {
            int v = Integer.parseInt(body);
            return v == 0 ? null : new Marker(Kind.DECIMAL, word, v, prefix, suffix);
        }
        if (suffix.equals(":")) {
            return null;
        }
        boolean lower = Character.isLowerCase(body.charAt(0));
        if (body.length() == 1 && !"ivxlcdmIVXLCDM".contains(body)) {
            int v = Character.toLowerCase(body.charAt(0)) - 'a' + 1;
            return new Marker(lower ? Kind.LOWER_LETTER : Kind.UPPER_LETTER, word, v, prefix, suffix);
        }
        int roman = roman(body.toLowerCase(Locale.ROOT));
        if (roman > 0) {
            return new Marker(lower ? Kind.LOWER_ROMAN : Kind.UPPER_ROMAN, word, roman, prefix, suffix);
        }
        if (body.length() == 1) {
            int v = Character.toLowerCase(body.charAt(0)) - 'a' + 1;
            return new Marker(lower ? Kind.LOWER_LETTER : Kind.UPPER_LETTER, word, v, prefix, suffix);
        }
        return null;
    }

    private static Marker world(String word) {
        int start = OPENERS.indexOf(word.charAt(0)) >= 0 ? 1 : 0;
        boolean closed = word.length() > start + 1 && CLOSERS.indexOf(word.charAt(word.length() - 1)) >= 0;
        if (!closed) {
            return null;
        }
        String prefix = word.substring(0, start);
        String body = word.substring(start, word.length() - 1);
        String suffix = word.substring(word.length() - 1);
        if (!prefix.isEmpty() && suffix.charAt(0) != ')' && suffix.charAt(0) != FULL_WIDTH_CLOSE) {
            return null;
        }
        Marker found = numeral(Kind.FULL_WIDTH, fullWidth(body), word, prefix, suffix);
        found = found != null ? found : numeral(Kind.CHINESE, chinese(body), word, prefix, suffix);
        found = found != null ? found : numeral(Kind.HEBREW, hebrew(body), word, prefix, suffix);
        found = found != null ? found : numeral(Kind.GANADA, letter(GANADA_LETTERS, body), word, prefix, suffix);
        if (found == null) {
            int abjad = letter(ABJAD, body);
            int alpha = letter(ALPHA, body);
            found = numeral(alpha < abjad ? Kind.ARABIC_ALPHA : Kind.ARABIC_ABJAD, Math.min(abjad, alpha), word, prefix, suffix);
        }
        return found;
    }

    private static Marker numeral(Kind kind, int value, String word, String prefix, String suffix) {
        return value > 0 ? new Marker(kind, word, value, prefix, suffix) : null;
    }

    private static int letter(String alphabet, String body) {
        return body.length() == 1 ? alphabet.indexOf(body.charAt(0)) + 1 : 0;
    }

    private static int fullWidth(String body) {
        if (body.isEmpty() || body.length() > 3 || !body.chars().allMatch(c -> c >= 0xFF10 && c <= 0xFF19)) {
            return 0;
        }
        return Integer.parseInt(Normalizer.normalize(body, Normalizer.Form.NFKC));
    }

    public static int arabicValue(String body, Kind kind) {
        return letter(kind == Kind.ARABIC_ALPHA ? ALPHA : ABJAD, body);
    }

    private static int chinese(String body) {
        if (body.isEmpty() || body.length() > 3) {
            return 0;
        }
        int value = 0;
        int digit = -1;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            int d = CHINESE_DIGITS.indexOf(c);
            if (c == '\u5341') {
                value += 10 * (digit < 0 ? 1 : digit);
                digit = -1;
            } else if (d > 0 && digit < 0) {
                digit = d;
            } else {
                return 0;
            }
        }
        return value + Math.max(0, digit);
    }

    private static int hebrew(String body) {
        if (body.isEmpty() || body.length() > 3) {
            return 0;
        }
        int value = 0;
        int place = 3;
        for (int i = 0; i < body.length(); i++) {
            int k = HEBREW_LETTERS.indexOf(body.charAt(i));
            if (k < 0 || k / 9 >= place) {
                return 0;
            }
            place = k / 9;
            value += HEBREW_VALUES[k];
        }
        return value;
    }

    static int roman(String s) {
        int total = 0;
        int prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (s.charAt(i)) {
                case 'i' -> 1;
                case 'v' -> 5;
                case 'x' -> 10;
                case 'l' -> 50;
                case 'c' -> 100;
                case 'd' -> 500;
                case 'm' -> 1000;
                default -> -1;
            };
            if (v < 0) {
                return 0;
            }
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return toRoman(total).equals(s) ? total : 0;
    }

    static String toRoman(int n) {
        int[] vals = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] syms = {"m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vals.length && n > 0; i++) {
            while (n >= vals[i]) {
                sb.append(syms[i]);
                n -= vals[i];
            }
        }
        return sb.toString();
    }
}

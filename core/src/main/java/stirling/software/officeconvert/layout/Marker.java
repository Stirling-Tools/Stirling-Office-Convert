package stirling.software.officeconvert.layout;

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
        UPPER_ROMAN
    }

    private static final String BULLETS = "•●◦▪■□‣⁃∙○◆◇►▶➢➤✓✔❖–\u2014-*·§➔→⇒✗☐☑✦★☆❑◘♦";

    private static final Pattern NUMBERED =
            Pattern.compile("^([(\\[]?)([0-9]{1,3}|[a-zA-Z]|[ivxlcdmIVXLCDM]{1,6})([.)\\]:]?)$");

    public boolean isBullet() {
        return kind == Kind.BULLET;
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

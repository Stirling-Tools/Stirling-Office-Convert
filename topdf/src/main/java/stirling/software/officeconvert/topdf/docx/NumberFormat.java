package stirling.software.officeconvert.topdf.docx;

import java.util.Locale;

import stirling.software.officeconvert.topdf.dml.Numerals;

final class NumberFormat {

    private static final String[] ONES = {"", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};

    private static final String[] TENS = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty",
        "ninety"};

    private static final String[] ORDINAL_ONES = {"", "first", "second", "third", "fourth", "fifth", "sixth",
        "seventh", "eighth", "ninth", "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth",
        "sixteenth", "seventeenth", "eighteenth", "nineteenth"};

    private NumberFormat() {}

    static String format(int n, String fmt) {
        if (fmt == null) {
            return Integer.toString(n);
        }
        return switch (fmt) {
            case "none" -> "";
            case "bullet" -> "";
            case "decimalZero" -> n >= 0 && n < 10 ? "0" + n : Integer.toString(n);
            case "upperRoman" -> roman(n).toUpperCase(Locale.ROOT);
            case "lowerRoman" -> roman(n);
            case "upperLetter" -> letter(n).toUpperCase(Locale.ROOT);
            case "lowerLetter" -> letter(n);
            case "ordinal" -> n + suffix(n);
            case "cardinalText" -> capitalize(cardinal(n));
            case "ordinalText" -> capitalize(ordinal(n));
            case "hex" -> Integer.toHexString(n).toUpperCase(Locale.ROOT);
            case "chicago" -> chicago(n);
            case "decimalEnclosedCircle", "decimalEnclosedCircleChinese" -> n >= 1 && n <= 20
                    ? String.valueOf((char) (0x2460 + n - 1)) : Integer.toString(n);
            case "decimalEnclosedParen" -> n >= 1 && n <= 20 ? String.valueOf((char) (0x2474 + n - 1))
                    : "(" + n + ")";
            case "decimalEnclosedFullstop" -> n >= 1 && n <= 20 ? String.valueOf((char) (0x2488 + n - 1)) : n + ".";
            case "decimalFullWidth", "decimalFullWidth2" -> fullWidth(Integer.toString(n));
            case "numberInDash" -> "- " + n + " -";
            default -> {
                String world = Numerals.format(fmt, n);
                yield world != null ? world : Integer.toString(n);
            }
        };
    }

    static String roman(int n) {
        if (n <= 0 || n >= 4000) {
            return Integer.toString(n);
        }
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i"};
        StringBuilder sb = new StringBuilder();
        int x = n;
        for (int i = 0; i < v.length; i++) {
            while (x >= v[i]) {
                sb.append(s[i]);
                x -= v[i];
            }
        }
        return sb.toString();
    }

    static String letter(int n) {
        if (n <= 0) {
            return Integer.toString(n);
        }
        int idx = (n - 1) % 26;
        int repeat = (n - 1) / 26 + 1;
        return String.valueOf((char) ('a' + idx)).repeat(Math.min(repeat, 50));
    }

    private static String suffix(int n) {
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) {
            return "th";
        }
        return switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    static String cardinal(int n) {
        if (n == 0) {
            return "zero";
        }
        if (n < 0 || n >= 1_000_000) {
            return Integer.toString(n);
        }
        StringBuilder sb = new StringBuilder();
        if (n >= 1000) {
            sb.append(cardinal(n / 1000)).append(" thousand");
            n %= 1000;
            if (n > 0) {
                sb.append(' ');
            }
        }
        if (n >= 100) {
            sb.append(ONES[n / 100]).append(" hundred");
            n %= 100;
            if (n > 0) {
                sb.append(" and ");
            }
        }
        if (n >= 20) {
            sb.append(TENS[n / 10]);
            if (n % 10 > 0) {
                sb.append('-').append(ONES[n % 10]);
            }
        } else if (n > 0) {
            sb.append(ONES[n]);
        }
        return sb.toString();
    }

    static String ordinal(int n) {
        if (n <= 0 || n >= 1_000_000) {
            return Integer.toString(n);
        }
        if (n < 20) {
            return ORDINAL_ONES[n];
        }
        if (n < 100 && n % 10 == 0) {
            String t = TENS[n / 10];
            return t.substring(0, t.length() - 1) + "ieth";
        }
        if (n < 100) {
            return TENS[n / 10] + "-" + ORDINAL_ONES[n % 10];
        }
        int rest = n % 100;
        String head = cardinal(n - rest);
        return rest == 0 ? head + "th" : head + " " + ordinal(rest);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String chicago(int n) {
        String[] marks = {"*", "\u2020", "\u2021", "\u00A7"};
        if (n <= 0) {
            return Integer.toString(n);
        }
        int idx = (n - 1) % 4;
        int repeat = (n - 1) / 4 + 1;
        return marks[idx].repeat(Math.min(repeat, 20));
    }

    private static String fullWidth(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(c >= '0' && c <= '9' ? (char) (0xFF10 + c - '0') : c);
        }
        return sb.toString();
    }
}

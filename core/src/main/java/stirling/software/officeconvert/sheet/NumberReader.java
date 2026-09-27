package stirling.software.officeconvert.sheet;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import stirling.software.officeconvert.sheet.Conventions.DecimalMark;

final class NumberReader {

    private static final int MAX_LENGTH = 40;

    private static final int MAX_DIGITS = 15;

    private static final int MAX_PLAIN_DIGITS = 11;

    private static final Pattern PREFIX = Pattern.compile(
            "^(US\\$|A\\$|C\\$|NZ\\$|HK\\$|S\\$|R\\$|[$£€¥₹₩₽₺₪₫₱₦฿]|(?:USD|EUR|GBP|CHF|JPY|CAD|AUD|NZD|SEK|NOK|DKK|PLN"
                    + "|CZK|HUF|INR|CNY|ZAR|BRL|MXN|TRY|Rs\\.?) )( ?)");
    private static final Pattern SUFFIX = Pattern.compile(
            "( ?)([$£€¥₹₽₺₪]|USD|EUR|GBP|CHF|JPY|CAD|AUD|SEK|NOK|DKK|PLN|CZK|HUF|kr\\.?|zł|Kč|Ft|lei)$");
    private static final Pattern CHARS = Pattern.compile("[0-9][0-9.,' ’]*|[.,][0-9]+");
    private static final Pattern INTEGER = Pattern.compile("[0-9]+");
    private static final Pattern US_GROUPED = Pattern.compile("[0-9]{1,3}(,[0-9]{3})+(\\.[0-9]+)?");
    private static final Pattern EU_GROUPED = Pattern.compile("[0-9]{1,3}(\\.[0-9]{3})+(,[0-9]+)?");
    private static final Pattern SPACE_GROUPED = Pattern.compile("[0-9]{1,3}( [0-9]{3})+([.,][0-9]+)?");
    private static final Pattern APOSTROPHE_GROUPED = Pattern.compile("[0-9]{1,3}(['’][0-9]{3})+(\\.[0-9]+)?");
    private static final Pattern INDIAN_GROUPED = Pattern.compile("[0-9]{1,2}(,[0-9]{2})+,[0-9]{3}(\\.[0-9]+)?");
    private static final Pattern POINT_DECIMAL = Pattern.compile("[0-9]*\\.[0-9]+");
    private static final Pattern COMMA_DECIMAL = Pattern.compile("[0-9]*,[0-9]+");

    record Digits(String canonical, int decimals, boolean grouped, DecimalMark evidence) {}

    private record Marks(String digits, boolean negative, boolean parentheses, boolean plus, String prefix,
            String suffix, boolean percent) {}

    private NumberReader() {}

    static CellValue read(String text, DecimalMark mark) {
        String s = normalise(text);
        if (s.isEmpty() || s.length() > MAX_LENGTH) {
            return null;
        }
        Marks m = marks(s);
        if (m == null) {
            return null;
        }
        Digits d = digits(m.digits(), mark);
        if (d == null || isCode(d)) {
            return null;
        }
        double value;
        try {
            value = new BigDecimal(d.canonical().startsWith(".") ? "0" + d.canonical() : d.canonical()).doubleValue();
        } catch (NumberFormatException e) {
            return null;
        }
        if (m.negative() || m.parentheses()) {
            value = -value;
        }
        if (m.percent()) {
            value /= 100d;
        }
        if (value == 0) {
            value = 0;
        }
        return CellValue.number(text.strip(), value, format(m, d));
    }

    static DecimalMark evidence(String text) {
        String s = normalise(text);
        if (s.isEmpty() || s.length() > MAX_LENGTH) {
            return DecimalMark.UNKNOWN;
        }
        Marks m = marks(s);
        Digits d = m == null ? null : digits(m.digits(), DecimalMark.UNKNOWN);
        return d == null ? DecimalMark.UNKNOWN : d.evidence();
    }

    static String normalise(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00A0' || c == '\u202F' || c == '\u2009' || c == '\u2007' || c == '\u2002' || c == '\u2003') {
                sb.append(' ');
            } else if (c == '−' || c == '–' || c == '‒') {
                sb.append('-');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().strip();
    }

    private static Marks marks(String s) {
        boolean parentheses = false;
        boolean negative = false;
        boolean plus = false;
        if (s.length() > 2 && s.startsWith("(") && s.endsWith(")")) {
            parentheses = true;
            s = s.substring(1, s.length() - 1).strip();
        }
        if (s.startsWith("-") || s.startsWith("+")) {
            negative = s.charAt(0) == '-';
            plus = !negative;
            s = s.substring(1).strip();
        }
        String prefix = "";
        Matcher pm = PREFIX.matcher(s);
        if (pm.find()) {
            prefix = pm.group(1) + pm.group(2);
            s = s.substring(pm.end());
            if (!negative && !plus && s.startsWith("-")) {
                negative = true;
                s = s.substring(1).strip();
            }
        }
        boolean percent = false;
        String suffix = "";
        if (s.endsWith("%")) {
            percent = true;
            s = s.substring(0, s.length() - 1).strip();
        } else if (prefix.isEmpty()) {
            Matcher sm = SUFFIX.matcher(s);
            if (sm.find()) {
                suffix = sm.group(1) + sm.group(2);
                s = s.substring(0, sm.start());
            }
        }
        if (!parentheses && s.length() > 2 && s.startsWith("(") && s.endsWith(")")) {
            parentheses = true;
            s = s.substring(1, s.length() - 1).strip();
        }
        if (!negative && !plus && !parentheses && s.length() > 1 && s.endsWith("-")) {
            String rest = s.substring(0, s.length() - 1);
            if (rest.indexOf('.') < 0 && rest.indexOf(',') < 0) {
                return null;
            }
            negative = true;
            s = rest;
        }
        if ((negative || parentheses) && plus || plus && s.indexOf(' ') >= 0) {
            return null;
        }
        return s.isEmpty() || !CHARS.matcher(s).matches() ? null
                : new Marks(s, negative, parentheses, plus, prefix, suffix, percent);
    }

    static Digits digits(String s, DecimalMark mark) {
        if (INTEGER.matcher(s).matches()) {
            return new Digits(s, 0, false, DecimalMark.UNKNOWN);
        }
        if (US_GROUPED.matcher(s).matches()) {
            boolean point = s.indexOf('.') >= 0;
            if (!point && s.indexOf(',') == s.lastIndexOf(',')) {
                if (s.startsWith("0,")) {
                    return decimal(s, ',', DecimalMark.COMMA);
                }
                return mark == DecimalMark.COMMA ? decimal(s, ',', DecimalMark.UNKNOWN)
                        : grouped(s.replace(",", ""), 0, DecimalMark.UNKNOWN);
            }
            return grouped(s.replace(",", ""), decimalsAfter(s, '.'), DecimalMark.POINT);
        }
        if (EU_GROUPED.matcher(s).matches()) {
            boolean comma = s.indexOf(',') >= 0;
            if (!comma && s.indexOf('.') == s.lastIndexOf('.')) {
                return mark == DecimalMark.COMMA && !s.startsWith("0.") ? grouped(s.replace(".", ""), 0, DecimalMark.UNKNOWN)
                        : decimal(s, '.', s.startsWith("0.") ? DecimalMark.POINT : DecimalMark.UNKNOWN);
            }
            return grouped(s.replace(".", "").replace(',', '.'), decimalsAfter(s, ','), DecimalMark.COMMA);
        }
        if (SPACE_GROUPED.matcher(s).matches()) {
            int comma = s.indexOf(',');
            int point = s.indexOf('.');
            DecimalMark proves = comma >= 0 ? DecimalMark.COMMA : point >= 0 ? DecimalMark.POINT : DecimalMark.UNKNOWN;
            if (proves != DecimalMark.UNKNOWN && mark != DecimalMark.UNKNOWN && proves != mark) {
                return null;
            }
            return grouped(s.replace(" ", "").replace(',', '.'), comma >= 0 ? decimalsAfter(s, ',') : decimalsAfter(s, '.'),
                    proves);
        }
        if (APOSTROPHE_GROUPED.matcher(s).matches()) {
            return grouped(s.replace("'", "").replace("’", ""), decimalsAfter(s, '.'), DecimalMark.POINT);
        }
        if (INDIAN_GROUPED.matcher(s).matches()) {
            return grouped(s.replace(",", ""), decimalsAfter(s, '.'), DecimalMark.POINT);
        }
        if (POINT_DECIMAL.matcher(s).matches()) {
            return mark == DecimalMark.COMMA ? null : decimal(s, '.', DecimalMark.POINT);
        }
        if (COMMA_DECIMAL.matcher(s).matches()) {
            return mark == DecimalMark.POINT ? null : decimal(s, ',', DecimalMark.COMMA);
        }
        return null;
    }

    private static Digits grouped(String canonical, int decimals, DecimalMark evidence) {
        return new Digits(canonical, decimals, true, evidence);
    }

    private static Digits decimal(String s, char mark, DecimalMark evidence) {
        return new Digits(s.replace(mark, '.'), decimalsAfter(s, mark), false, evidence);
    }

    private static int decimalsAfter(String s, char mark) {
        int i = s.lastIndexOf(mark);
        return i < 0 ? 0 : s.length() - i - 1;
    }

    private static boolean isCode(Digits d) {
        String c = d.canonical();
        int dot = c.indexOf('.');
        String whole = dot < 0 ? c : c.substring(0, dot);
        if (whole.length() > 1 && whole.startsWith("0")) {
            return true;
        }
        int digits = c.length() - (dot < 0 ? 0 : 1);
        return digits > MAX_DIGITS || !d.grouped() && dot < 0 && digits > MAX_PLAIN_DIGITS;
    }

    private static NumberFormat format(Marks m, Digits d) {
        boolean plain = !d.grouped() && d.decimals() == 0 && !m.percent() && m.prefix().isEmpty() && m.suffix().isEmpty()
                && !m.parentheses() && !m.plus();
        if (plain) {
            return NumberFormat.GENERAL;
        }
        if (m.percent()) {
            return NumberFormat.percent(d.decimals(), d.grouped(), m.parentheses(), m.plus());
        }
        return NumberFormat.number(d.decimals(), d.grouped(), m.prefix(), m.suffix(), m.parentheses(), m.plus());
    }
}

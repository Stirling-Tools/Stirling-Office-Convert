package stirling.software.officeconvert.table;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record NumericValue(double value, String format) {

    private static final String GENERAL = "General";

    private static final int MAX_LENGTH = 40;

    private static final int MAX_DIGITS = 15;

    private static final Pattern PREFIX_CURRENCY =
            Pattern.compile("^(US\\$|A\\$|C\\$|NZ\\$|R\\$|HK\\$|[$£€¥₹₩₽₺₪])\\s?");
    private static final Pattern SUFFIX_CURRENCY =
            Pattern.compile("\\s?([€$£¥₹₽₺₪]|EUR|USD|GBP|CHF|JPY|kr|zł|Kč)$");
    private static final Pattern US_GROUPED = Pattern.compile("^\\d{1,3}(,\\d{3})+(\\.\\d+)?$");
    private static final Pattern EU_GROUPED = Pattern.compile("^\\d{1,3}(\\.\\d{3})+(,\\d+)?$");
    private static final Pattern SPACE_GROUPED =
            Pattern.compile("^\\d{1,3}([ \\u00A0\\u202F]\\d{3})+([.,]\\d+)?$");
    private static final Pattern PLAIN = Pattern.compile("^\\d+(\\.\\d+)?$|^\\.\\d+$");
    private static final Pattern EU_DECIMAL = Pattern.compile("^\\d+,\\d{1,2}$");

    private record Marked(
            String digits,
            boolean negative,
            boolean parenthesised,
            String prefix,
            String suffix,
            boolean percent) {}

    private record Digits(String canonical, boolean grouped) {

        int decimals() {
            int dot = canonical.indexOf('.');
            return dot < 0 ? 0 : canonical.length() - dot - 1;
        }
    }

    public static Optional<NumericValue> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String s = text.strip();
        if (s.isEmpty() || s.length() > MAX_LENGTH || s.indexOf('\n') >= 0) {
            return Optional.empty();
        }
        Marked marked = stripMarkers(s);
        Digits digits = marked.digits().isEmpty() ? null : readDigits(marked.digits());
        if (digits == null || isCode(digits.canonical())) {
            return Optional.empty();
        }
        String canonical = digits.canonical();
        double value;
        try {
            value =
                    new BigDecimal(canonical.startsWith(".") ? "0" + canonical : canonical)
                            .doubleValue();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (marked.negative() || marked.parenthesised()) {
            value = -value;
        }
        if (marked.percent()) {
            value = value / 100d;
        }
        return Optional.of(new NumericValue(value, format(marked, digits)));
    }

    static boolean looksNumeric(String text) {
        String s = text.strip();
        return switch (s) {
            case "-", "--", "n/a", "N/A", "n.a.", "nil" -> true;
            default -> isDashPlaceholder(s) || parse(s).isPresent();
        };
    }

    private static Marked stripMarkers(String s) {
        boolean parenthesised = s.length() > 2 && s.startsWith("(") && s.endsWith(")");
        if (parenthesised) {
            s = s.substring(1, s.length() - 1).strip();
        }
        boolean negative = startsWithMinus(s);
        if (negative) {
            s = s.substring(1).strip();
        }
        String prefix = "";
        Matcher pm = PREFIX_CURRENCY.matcher(s);
        if (pm.find()) {
            prefix = pm.group(1);
            s = s.substring(pm.end());
            if (!negative && startsWithMinus(s)) {
                negative = true;
                s = s.substring(1).strip();
            }
        }
        String suffix = "";
        Matcher sm = SUFFIX_CURRENCY.matcher(s);
        if (prefix.isEmpty() && sm.find()) {
            String currency = sm.group(1);
            suffix = sm.group().startsWith(currency) ? currency : " " + currency;
            s = s.substring(0, sm.start());
        }
        boolean percent = s.endsWith("%");
        if (percent) {
            s = s.substring(0, s.length() - 1).strip();
        }
        if (s.startsWith("+") && (percent || s.contains("."))) {
            s = s.substring(1);
        }
        return new Marked(s, negative, parenthesised, prefix, suffix, percent);
    }

    private static Digits readDigits(String s) {
        if (US_GROUPED.matcher(s).matches()) {
            return new Digits(s.replace(",", ""), true);
        }
        if (EU_GROUPED.matcher(s).matches()
                && (s.contains(",") || s.indexOf('.') != s.lastIndexOf('.'))) {
            return new Digits(s.replace(".", "").replace(',', '.'), true);
        }
        if (SPACE_GROUPED.matcher(s).matches()) {
            return new Digits(s.replaceAll("[ \\u00A0\\u202F]", "").replace(',', '.'), true);
        }
        if (PLAIN.matcher(s).matches()) {
            return new Digits(s, false);
        }
        if (EU_DECIMAL.matcher(s).matches()) {
            return new Digits(s.replace(',', '.'), false);
        }
        return null;
    }

    private static boolean isCode(String canonical) {
        int dot = canonical.indexOf('.');
        String integerPart = dot < 0 ? canonical : canonical.substring(0, dot);
        return (integerPart.length() > 1 && integerPart.startsWith("0"))
                || canonical.replace(".", "").length() > MAX_DIGITS;
    }

    private static boolean isDashPlaceholder(String s) {
        return s.length() == 1 && (s.charAt(0) == 0x2013 || s.charAt(0) == 0x2014);
    }

    private static boolean startsWithMinus(String s) {
        if (s.length() < 2) {
            return false;
        }
        char c = s.charAt(0);
        return c == '-' || c == 0x2212 || c == 0x2013;
    }

    private static String format(Marked marked, Digits digits) {
        int decimals = digits.decimals();
        if (!digits.grouped()
                && decimals == 0
                && !marked.percent()
                && marked.prefix().isEmpty()
                && marked.suffix().isEmpty()
                && !marked.parenthesised()) {
            return GENERAL;
        }
        StringBuilder base = new StringBuilder(digits.grouped() ? "#,##0" : "0");
        if (decimals > 0) {
            base.append('.').append("0".repeat(decimals));
        }
        if (marked.percent()) {
            base.append('%');
        }
        String positive = quote(marked.prefix()) + base + quote(marked.suffix());
        return marked.parenthesised() ? positive + ";(" + positive + ")" : positive;
    }

    private static String quote(String literal) {
        return literal.isEmpty() ? "" : "\"" + literal + "\"";
    }
}

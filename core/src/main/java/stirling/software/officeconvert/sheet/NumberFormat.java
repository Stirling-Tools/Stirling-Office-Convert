package stirling.software.officeconvert.sheet;

import java.util.List;

public record NumberFormat(
        Kind kind,
        int decimals,
        boolean grouping,
        String prefix,
        String suffix,
        boolean parentheses,
        boolean plus,
        List<DatePart> date) {

    public enum Kind {
        GENERAL,
        NUMBER,
        PERCENT,
        DATE
    }

    public record DatePart(Field field, int width, String literal) {

        public static DatePart text(String literal) {
            return new DatePart(Field.TEXT, 0, literal);
        }

        public static DatePart of(Field field, int width) {
            return new DatePart(field, width, "");
        }
    }

    public enum Field {
        YEAR,
        MONTH,
        DAY,
        HOUR,
        MINUTE,
        SECOND,
        AM_PM,
        TEXT
    }

    public static final NumberFormat GENERAL = new NumberFormat(Kind.GENERAL, 0, false, "", "", false, false, List.of());

    public NumberFormat {
        prefix = prefix == null ? "" : prefix;
        suffix = suffix == null ? "" : suffix;
        date = date == null ? List.of() : List.copyOf(date);
    }

    public static NumberFormat number(int decimals, boolean grouping, String prefix, String suffix, boolean parentheses,
            boolean plus) {
        return new NumberFormat(Kind.NUMBER, decimals, grouping, prefix, suffix, parentheses, plus, List.of());
    }

    public static NumberFormat percent(int decimals, boolean grouping, boolean parentheses, boolean plus) {
        return new NumberFormat(Kind.PERCENT, decimals, grouping, "", "", parentheses, plus, List.of());
    }

    public static NumberFormat date(List<DatePart> parts) {
        return new NumberFormat(Kind.DATE, 0, false, "", "", false, false, parts);
    }

    public boolean hasDay() {
        return date.stream().anyMatch(p -> p.field() == Field.YEAR || p.field() == Field.MONTH || p.field() == Field.DAY);
    }

    public boolean hasTime() {
        return date.stream().anyMatch(p -> p.field() == Field.HOUR);
    }

    public boolean monthNames() {
        return date.stream().anyMatch(p -> p.field() == Field.MONTH && p.width() >= 3);
    }

    public String excelCode() {
        return switch (kind) {
            case GENERAL -> "General";
            case NUMBER, PERCENT -> numberCode();
            case DATE -> dateCode();
        };
    }

    private String numberCode() {
        StringBuilder body = new StringBuilder(grouping ? "#,##0" : "0");
        if (decimals > 0) {
            body.append('.').append("0".repeat(decimals));
        }
        if (kind == Kind.PERCENT) {
            body.append('%');
        }
        String positive = quoted(prefix) + body + quoted(suffix);
        if (parentheses) {
            return positive + ";(" + positive + ")";
        }
        if (plus) {
            return "+" + positive + ";-" + positive + ";" + positive;
        }
        return positive;
    }

    private String dateCode() {
        boolean ampm = date.stream().anyMatch(p -> p.field() == Field.AM_PM);
        StringBuilder sb = new StringBuilder(monthNames() || ampm ? "[$-409]" : "");
        for (DatePart p : date) {
            switch (p.field()) {
                case YEAR -> sb.append(p.width() <= 2 ? "yy" : "yyyy");
                case MONTH -> sb.append("m".repeat(Math.max(1, Math.min(4, p.width()))));
                case DAY -> sb.append(p.width() <= 1 ? "d" : "dd");
                case HOUR -> sb.append(p.width() <= 1 || ampm ? "h" : "hh");
                case MINUTE -> sb.append("mm");
                case SECOND -> sb.append("ss");
                case AM_PM -> sb.append("AM/PM");
                case TEXT -> literal(sb, p.literal());
            }
        }
        return sb.toString();
    }

    private static void literal(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == ',' || c == '-' || c == ':') {
                sb.append(c);
            } else {
                sb.append('\\').append(c);
            }
        }
    }

    private static String quoted(String literal) {
        return literal.isEmpty() ? "" : "\"" + literal.replace("\"", "") + "\"";
    }
}

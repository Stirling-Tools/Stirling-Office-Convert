package stirling.software.officeconvert.topdf.odf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.w3c.dom.Element;

/** ODF data styles (number, percentage, currency, date, time, boolean and text styles, with their conditional maps)
 * written as Excel number format codes. */
final class NumberFormats {

    private final Styles styles;

    NumberFormats(Styles styles) {
        this.styles = styles;
    }

    /** The format code for a data style, or null for General. */
    String code(String dataStyle) {
        Element s = styles.dataStyle(dataStyle);
        if (s == null) {
            return null;
        }
        String main = section(s);
        List<String[]> maps = new ArrayList<>();
        int n = 0;
        for (Element m : Dom.kids(s, Ns.STYLE, "map")) {
            if (n++ > 3) {
                break;
            }
            Element target = styles.dataStyle(Dom.attr(m, Ns.STYLE, "apply-style-name"));
            if (target == null || target == s) {
                continue;
            }
            maps.add(new String[] {condition(Dom.attr(m, Ns.STYLE, "condition", "")), section(target)});
        }
        if (maps.isEmpty()) {
            return main.isEmpty() ? null : main;
        }
        String positive = null;
        String negative = null;
        String zero = null;
        List<String> custom = new ArrayList<>();
        for (String[] m : maps) {
            switch (m[0]) {
                case ">0", ">=0" -> positive = m[1];
                case "<0" -> negative = m[1];
                case "=0" -> zero = m[1];
                default -> custom.add("[" + m[0] + "]" + m[1]);
            }
        }
        if (!custom.isEmpty()) {
            custom.add(main);
            return String.join(";", custom.subList(0, Math.min(3, custom.size())));
        }
        if (positive != null && negative == null && zero == null) {
            return positive + ";" + main;
        }
        if (positive != null && negative != null) {
            return positive + ";" + negative + ";" + (zero != null ? zero : main);
        }
        if (positive != null) {
            return positive + ";" + main + ";" + zero;
        }
        return main;
    }

    private static String condition(String c) {
        String t = c.replace("value()", "").replace(" ", "");
        return t.isEmpty() ? ">=0" : t;
    }

    private static String section(Element s) {
        StringBuilder b = new StringBuilder();
        String color = colorOf(s);
        if (color != null) {
            b.append('[').append(color).append(']');
        }
        String kind = Dom.local(s);
        boolean duration = kind.equals("time-style") && "false".equals(Dom.attr(s, Ns.NUMBER, "truncate-on-overflow"));
        boolean hours = false;
        for (Element k : Dom.kids(s)) {
            if (!Ns.NUMBER.equals(k.getNamespaceURI())) {
                continue;
            }
            boolean longForm = "long".equals(Dom.attr(k, Ns.NUMBER, "style"));
            switch (Dom.local(k)) {
                case "number" -> b.append(number(k));
                case "scientific-number" -> b.append(scientific(k));
                case "fraction" -> b.append(fraction(k));
                case "text" -> b.append(literal(k.getTextContent()));
                case "text-content" -> b.append('@');
                case "fill-character" -> {
                    String f = k.getTextContent();
                    if (f != null && !f.isEmpty()) {
                        b.append('*').append(f.charAt(0));
                    }
                }
                case "currency-symbol" -> b.append(literal(k.getTextContent()));
                case "day" -> b.append(longForm ? "dd" : "d");
                case "day-of-week" -> b.append(longForm ? "dddd" : "ddd");
                case "month" -> {
                    boolean textual = "true".equals(Dom.attr(k, Ns.NUMBER, "textual"));
                    b.append(textual ? (longForm ? "mmmm" : "mmm") : (longForm ? "mm" : "m"));
                }
                case "year" -> b.append(longForm ? "yyyy" : "yy");
                case "era" -> b.append(longForm ? "gggg" : "g");
                case "quarter" -> b.append(longForm ? "\"Q\"q" : "q");
                case "hours" -> {
                    String h = longForm ? "hh" : "h";
                    b.append(duration && !hours ? "[" + h + "]" : h);
                    hours = true;
                }
                case "minutes" -> b.append(longForm ? "mm" : "m");
                case "seconds" -> {
                    b.append(longForm ? "ss" : "s");
                    int dp = Dom.integer(k, Ns.NUMBER, "decimal-places", 0);
                    if (dp > 0) {
                        b.append('.').append("0".repeat(Math.min(dp, 9)));
                    }
                }
                case "am-pm" -> b.append("AM/PM");
                case "boolean" -> b.append("General");
                default -> {
                }
            }
        }
        if (kind.equals("percentage-style") && b.indexOf("%") < 0) {
            b.append('%');
        }
        if (kind.equals("boolean-style")) {
            return "\"TRUE\";\"TRUE\";\"FALSE\"";
        }
        return b.toString();
    }

    private static String colorOf(Element s) {
        Element tp = Dom.kid(s, Ns.STYLE, "text-properties");
        String hex = Colors.fill(Dom.attr(tp, Ns.FO, "color"));
        if (hex == null) {
            return null;
        }
        return switch (hex) {
            case "FF0000" -> "Red";
            case "0000FF" -> "Blue";
            case "00FF00" -> "Green";
            case "000000" -> "Black";
            case "FFFFFF" -> "White";
            case "FFFF00" -> "Yellow";
            case "FF00FF" -> "Magenta";
            case "00FFFF" -> "Cyan";
            default -> null;
        };
    }

    private static String number(Element k) {
        int dp = Math.max(0, Math.min(30, Dom.integer(k, Ns.NUMBER, "decimal-places", 0)));
        int minDp = Math.max(0, Math.min(dp, Dom.integer(k, Ns.NUMBER, "min-decimal-places", dp)));
        int minInt = Math.max(0, Math.min(30, Dom.integer(k, Ns.NUMBER, "min-integer-digits", 1)));
        boolean grouping = "true".equals(Dom.attr(k, Ns.NUMBER, "grouping"));
        StringBuilder b = new StringBuilder();
        String integer = "0".repeat(minInt);
        if (grouping) {
            String padded = "#".repeat(Math.max(0, 4 - integer.length())) + integer;
            StringBuilder g = new StringBuilder(padded);
            g.insert(g.length() - 3, ',');
            b.append(g);
        } else {
            b.append(integer.isEmpty() ? "#" : integer);
        }
        List<Element> embedded = Dom.kids(k, Ns.NUMBER, "embedded-text");
        if (!embedded.isEmpty()) {
            StringBuilder withText = new StringBuilder();
            String digits = b.toString();
            int len = digits.length();
            for (int i = 0; i < len; i++) {
                int fromRight = len - i;
                for (Element e : embedded) {
                    if (Dom.integer(e, Ns.NUMBER, "position", -1) == fromRight) {
                        withText.append(literal(e.getTextContent()));
                    }
                }
                withText.append(digits.charAt(i));
            }
            b = withText;
        }
        if (dp > 0) {
            b.append('.').append("0".repeat(minDp)).append("#".repeat(dp - minDp));
        }
        double factor = 1;
        try {
            factor = Double.parseDouble(Dom.attr(k, Ns.NUMBER, "display-factor", "1"));
        } catch (NumberFormatException e) {
            factor = 1;
        }
        while (factor >= 999 && b.length() < 64) {
            b.append(',');
            factor /= 1000;
        }
        return b.toString();
    }

    private static String scientific(Element k) {
        int dp = Math.max(0, Math.min(30, Dom.integer(k, Ns.NUMBER, "decimal-places", 0)));
        int minInt = Math.max(1, Math.min(30, Dom.integer(k, Ns.NUMBER, "min-integer-digits", 1)));
        int exp = Math.max(1, Math.min(5, Dom.integer(k, Ns.NUMBER, "min-exponent-digits", 2)));
        return "0".repeat(minInt) + (dp > 0 ? "." + "0".repeat(dp) : "") + "E+" + "0".repeat(exp);
    }

    private static String fraction(Element k) {
        int minInt = Dom.integer(k, Ns.NUMBER, "min-integer-digits", 0);
        int num = Math.max(1, Math.min(5, Dom.integer(k, Ns.NUMBER, "min-numerator-digits", 1)));
        String denValue = Dom.attr(k, Ns.NUMBER, "denominator-value");
        int den = Math.max(1, Math.min(5, Dom.integer(k, Ns.NUMBER, "min-denominator-digits", 1)));
        String lead = minInt > 0 ? "# " : "";
        return lead + "?".repeat(num) + "/" + (denValue != null ? denValue : "?".repeat(den));
    }

    static String literal(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.chars().allMatch(c -> " -/:.,()$".indexOf(c) >= 0)) {
            return text;
        }
        return "\"" + text.replace("\"", "\\\"") + "\"";
    }

    static boolean isDate(String code) {
        if (code == null) {
            return false;
        }
        String c = code.toLowerCase(Locale.ROOT).replaceAll("\"[^\"]*\"", "");
        return c.contains("y") || c.contains("d") || c.contains("h") || c.contains("s") && !c.contains("e+");
    }
}

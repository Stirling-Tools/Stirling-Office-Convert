package stirling.software.officeconvert.topdf.docx;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Excel number formats for chart labels: numbers, percentages, literal text and dates from cached serial values
final class ChartFormat {

    private ChartFormat() {}

    static String format(double v, String code, boolean date1904) {
        if (!Double.isFinite(v)) {
            return "";
        }
        List<String> sections = sections(code == null || code.isBlank() ? "General" : code);
        String sec = sections.get(0);
        boolean abs = false;
        if (v < 0 && sections.size() > 1 && !sections.get(1).isEmpty()) {
            sec = sections.get(1);
            abs = true;
        } else if (v == 0 && sections.size() > 2 && !sections.get(2).isEmpty()) {
            sec = sections.get(2);
        }
        sec = brackets(sec);
        if (sec.isBlank() || sec.equalsIgnoreCase("General")) {
            return general(v);
        }
        if (isDate(sec)) {
            return date(v, sec, date1904);
        }
        return number(abs ? -v : v, sec);
    }

    private static List<String> sections(String code) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == '\\' && !quoted && i + 1 < code.length()) {
                sb.append(c).append(code.charAt(++i));
                continue;
            } else if (c == ';' && !quoted) {
                out.add(sb.toString());
                sb.setLength(0);
                continue;
            }
            sb.append(c);
        }
        out.add(sb.toString());
        return out;
    }

    // Drops colours and conditions, keeps the symbol of a [$sym-locale] currency
    private static String brackets(String sec) {
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < sec.length(); i++) {
            char c = sec.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            }
            if (c == '[' && !quoted) {
                int end = sec.indexOf(']', i);
                if (end < 0) {
                    break;
                }
                String in = sec.substring(i + 1, end);
                if (in.startsWith("$")) {
                    int dash = in.indexOf('-');
                    String sym = dash < 0 ? in.substring(1) : in.substring(1, dash);
                    if (!sym.isEmpty()) {
                        sb.append('"').append(sym).append('"');
                    }
                } else if (in.matches("(?i)h+|m+|s+")) {
                    sb.append(in);
                }
                i = end;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String unquoted(String sec) {
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < sec.length(); i++) {
            char c = sec.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == '\\' || c == '_' || c == '*') {
                i++;
            } else if (!quoted) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static boolean isDate(String sec) {
        String s = unquoted(sec).toLowerCase(Locale.ROOT);
        if (s.contains("0") || s.contains("#") || s.contains("?")) {
            return false;
        }
        for (char c : s.toCharArray()) {
            if (c == 'y' || c == 'm' || c == 'd' || c == 'h' || c == 's') {
                return true;
            }
        }
        return false;
    }

    private static String general(double v) {
        if (Math.abs(v) < 1e-12) {
            return "0";
        }
        BigDecimal d = new BigDecimal(v).round(new MathContext(10, RoundingMode.HALF_UP)).stripTrailingZeros();
        return d.abs().compareTo(BigDecimal.valueOf(1e11)) >= 0 ? String.format(Locale.ROOT, "%.2E", v)
                : d.toPlainString();
    }

    private static String literal(String part) {
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (quoted) {
                sb.append(c);
            } else if (c == '\\' && i + 1 < part.length()) {
                sb.append(part.charAt(++i));
            } else if (c == '_' && i + 1 < part.length()) {
                sb.append(' ');
                i++;
            } else if (c == '*' && i + 1 < part.length()) {
                i++;
            } else if (c != '@') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean placeholder(char c) {
        return c == '0' || c == '#' || c == '?';
    }

    private static String number(double v, String sec) {
        int first = -1;
        int last = -1;
        boolean quoted = false;
        for (int i = 0; i < sec.length(); i++) {
            char c = sec.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (c == '\\' || c == '_' || c == '*')) {
                i++;
            } else if (!quoted && placeholder(c)) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (first < 0) {
            return literal(sec);
        }
        String prefix = literal(sec.substring(0, first));
        String body = sec.substring(first, last + 1);
        String suffix = literal(sec.substring(last + 1));
        boolean percent = (prefix + suffix).contains("%");
        if (percent) {
            v *= 100;
        }
        while (suffix.startsWith(",") || body.endsWith(",")) {
            if (suffix.startsWith(",")) {
                suffix = suffix.substring(1);
            } else {
                body = body.substring(0, body.length() - 1);
            }
            v /= 1000;
        }
        String digits = body.replaceAll("[^0#?.,]", "").replace('?', '#');
        boolean exponent = body.toUpperCase(Locale.ROOT).contains("E+") || body.toUpperCase(Locale.ROOT).contains("E-");
        String out;
        try {
            if (exponent) {
                String mantissa = body.substring(0, body.toUpperCase(Locale.ROOT).indexOf('E')).replaceAll("[^0#.]", "");
                String exp = body.substring(body.toUpperCase(Locale.ROOT).indexOf('E') + 2).replaceAll("[^0#]", "");
                out = decimal(excelDigits(mantissa) + "E" + (exp.isEmpty() ? "0" : exp)).format(v);
                if (!out.contains("E-")) {
                    out = out.replace("E", "E+");
                }
            } else {
                out = decimal(excelDigits(digits)).format(v);
            }
        } catch (IllegalArgumentException e) {
            out = general(v);
        }
        if (out.startsWith("-") && !prefix.isEmpty()) {
            return "-" + prefix + out.substring(1) + suffix;
        }
        return prefix + out + suffix;
    }

    // Excel rounds halves away from zero
    private static DecimalFormat decimal(String pattern) {
        DecimalFormat f = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT));
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f;
    }

    // Excel shows a digit for every placeholder right of its first 0, which DecimalFormat rejects as "0#"
    private static String excelDigits(String body) {
        int dot = body.indexOf('.');
        String whole = dot < 0 ? body : body.substring(0, dot);
        int zero = whole.indexOf('0');
        if (zero >= 0) {
            whole = whole.substring(0, zero) + whole.substring(zero).replace('#', '0');
        }
        if (whole.isEmpty()) {
            whole = "#";
        }
        String frac = dot < 0 ? "" : body.substring(dot + 1).replace(",", "");
        return frac.isEmpty() ? whole : whole + "." + frac;
    }

    private record Token(char kind, int count, String text) {}

    private static String date(double v, String sec, boolean date1904) {
        long days = (long) Math.floor(v);
        double frac = v - days;
        long secs = Math.round(frac * 86400);
        if (secs >= 86400) {
            days++;
            secs -= 86400;
        }
        LocalDate d;
        boolean leapBug = !date1904 && days == 60;
        if (date1904) {
            d = LocalDate.of(1904, 1, 1).plusDays(days);
        } else if (days >= 61) {
            d = LocalDate.of(1899, 12, 30).plusDays(days);
        } else if (days >= 1) {
            d = LocalDate.of(1899, 12, 31).plusDays(days);
        } else {
            d = LocalDate.of(1900, 1, 1);
        }
        int year = leapBug ? 1900 : d.getYear();
        int month = leapBug ? 2 : d.getMonthValue();
        int day = leapBug ? 29 : d.getDayOfMonth();
        int hour = (int) (secs / 3600);
        int minute = (int) (secs / 60 % 60);
        int second = (int) (secs % 60);
        List<Token> tokens = new ArrayList<>();
        boolean ampm = false;
        for (int i = 0; i < sec.length(); i++) {
            char c = sec.charAt(i);
            char lc = Character.toLowerCase(c);
            if (c == '"') {
                int end = sec.indexOf('"', i + 1);
                end = end < 0 ? sec.length() : end;
                tokens.add(new Token('"', 0, sec.substring(i + 1, end)));
                i = end;
            } else if (c == '\\' && i + 1 < sec.length()) {
                tokens.add(new Token('"', 0, String.valueOf(sec.charAt(++i))));
            } else if (c == '_' && i + 1 < sec.length()) {
                tokens.add(new Token('"', 0, " "));
                i++;
            } else if (c == '*' && i + 1 < sec.length()) {
                i++;
            } else if (sec.regionMatches(true, i, "AM/PM", 0, 5)) {
                tokens.add(new Token('p', 5, null));
                ampm = true;
                i += 4;
            } else if (sec.regionMatches(true, i, "A/P", 0, 3)) {
                tokens.add(new Token('p', 3, null));
                ampm = true;
                i += 2;
            } else if (lc == 'y' || lc == 'm' || lc == 'd' || lc == 'h' || lc == 's' || lc == 'e') {
                int j = i;
                while (j < sec.length() && Character.toLowerCase(sec.charAt(j)) == lc) {
                    j++;
                }
                tokens.add(new Token(lc == 'e' ? 'y' : lc, lc == 'e' ? 4 : j - i, null));
                i = j - 1;
            } else if (c == '.' && i + 1 < sec.length() && sec.charAt(i + 1) == '0') {
                while (i + 1 < sec.length() && sec.charAt(i + 1) == '0') {
                    i++;
                }
            } else {
                tokens.add(new Token('"', 0, String.valueOf(c)));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < tokens.size(); k++) {
            Token t = tokens.get(k);
            switch (t.kind()) {
                case 'y' -> sb.append(t.count() <= 2 ? String.format(Locale.ROOT, "%02d", year % 100)
                        : Integer.toString(year));
                case 'm' -> {
                    if (minutes(tokens, k)) {
                        sb.append(t.count() >= 2 ? String.format(Locale.ROOT, "%02d", minute) : minute);
                    } else {
                        java.time.Month m = java.time.Month.of(month);
                        switch (Math.min(t.count(), 5)) {
                            case 1 -> sb.append(month);
                            case 2 -> sb.append(String.format(Locale.ROOT, "%02d", month));
                            case 3 -> sb.append(m.getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
                            case 4 -> sb.append(m.getDisplayName(TextStyle.FULL, Locale.ENGLISH));
                            default -> sb.append(m.getDisplayName(TextStyle.NARROW, Locale.ENGLISH));
                        }
                    }
                }
                case 'd' -> {
                    switch (Math.min(t.count(), 4)) {
                        case 1 -> sb.append(day);
                        case 2 -> sb.append(String.format(Locale.ROOT, "%02d", day));
                        case 3 -> sb.append(leapBug ? "Wed" : d.getDayOfWeek().getDisplayName(TextStyle.SHORT,
                                Locale.ENGLISH));
                        default -> sb.append(leapBug ? "Wednesday" : d.getDayOfWeek().getDisplayName(TextStyle.FULL,
                                Locale.ENGLISH));
                    }
                }
                case 'h' -> {
                    int hh = ampm ? (hour % 12 == 0 ? 12 : hour % 12) : hour;
                    sb.append(t.count() >= 2 ? String.format(Locale.ROOT, "%02d", hh) : hh);
                }
                case 's' -> sb.append(t.count() >= 2 ? String.format(Locale.ROOT, "%02d", second) : second);
                case 'p' -> sb.append(t.count() == 5 ? (hour < 12 ? "AM" : "PM") : (hour < 12 ? "A" : "P"));
                default -> sb.append(t.text());
            }
        }
        return sb.toString();
    }

    // An m right after hours or right before seconds means minutes
    private static boolean minutes(List<Token> tokens, int k) {
        if (tokens.get(k).count() > 2) {
            return false;
        }
        for (int i = k - 1; i >= 0; i--) {
            char c = tokens.get(i).kind();
            if (c != '"') {
                if (c == 'h') {
                    return true;
                }
                break;
            }
        }
        for (int i = k + 1; i < tokens.size(); i++) {
            char c = tokens.get(i).kind();
            if (c != '"') {
                return c == 's';
            }
        }
        return false;
    }
}

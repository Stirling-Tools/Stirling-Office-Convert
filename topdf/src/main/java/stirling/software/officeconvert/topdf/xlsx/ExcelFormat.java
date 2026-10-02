package stirling.software.officeconvert.topdf.xlsx;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DateFormatSymbols;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Excel's number and date format codes, applied to the cached value the way Excel displays it
final class ExcelFormat {

    private enum Kind {
        LITERAL,
        DIGIT,
        POINT,
        COMMA,
        PERCENT,
        EXPONENT,
        DATE,
        ELAPSED,
        GENERAL
    }

    private record Token(Kind kind, String text) {}

    private record Parsed(List<Token> tokens, Locale locale, boolean dated, boolean fraction) {}

    private static final Map<String, Parsed> PARSED = new ConcurrentHashMap<>();

    private static final Map<Integer, Locale> LCIDS = Map.ofEntries(Map.entry(0x409, Locale.US),
            Map.entry(0x809, Locale.UK), Map.entry(0x407, Locale.GERMANY), Map.entry(0x807, Locale.of("de", "CH")),
            Map.entry(0xC07, Locale.of("de", "AT")), Map.entry(0x40C, Locale.FRANCE),
            Map.entry(0x80C, Locale.of("fr", "BE")), Map.entry(0xC0C, Locale.CANADA_FRENCH),
            Map.entry(0x100C, Locale.of("fr", "CH")), Map.entry(0x410, Locale.ITALY),
            Map.entry(0x40A, Locale.of("es", "ES")), Map.entry(0xC0A, Locale.of("es", "ES")),
            Map.entry(0x80A, Locale.of("es", "MX")), Map.entry(0x416, Locale.of("pt", "BR")),
            Map.entry(0x816, Locale.of("pt", "PT")), Map.entry(0x413, Locale.of("nl", "NL")),
            Map.entry(0x813, Locale.of("nl", "BE")), Map.entry(0x406, Locale.of("da", "DK")),
            Map.entry(0x41D, Locale.of("sv", "SE")), Map.entry(0x414, Locale.of("nb", "NO")),
            Map.entry(0x40B, Locale.of("fi", "FI")), Map.entry(0x415, Locale.of("pl", "PL")),
            Map.entry(0x405, Locale.of("cs", "CZ")), Map.entry(0x41B, Locale.of("sk", "SK")),
            Map.entry(0x40E, Locale.of("hu", "HU")), Map.entry(0x418, Locale.of("ro", "RO")),
            Map.entry(0x419, Locale.of("ru", "RU")), Map.entry(0x422, Locale.of("uk", "UA")),
            Map.entry(0x41F, Locale.of("tr", "TR")), Map.entry(0x408, Locale.of("el", "GR")),
            Map.entry(0x411, Locale.JAPAN), Map.entry(0x412, Locale.KOREA), Map.entry(0x804, Locale.CHINA),
            Map.entry(0x404, Locale.TAIWAN), Map.entry(0x40D, Locale.of("he", "IL")),
            Map.entry(0x421, Locale.of("id", "ID")), Map.entry(0x42A, Locale.of("vi", "VN")));

    private static final String SYSTEM_LONG_DATE = "dddd, mmmm d, yyyy";

    private static final String SYSTEM_TIME = "h:mm:ss AM/PM";

    private ExcelFormat() {}

    static String builtin(int index, String format) {
        return SystemDates.US.builtin(index, format);
    }

    static boolean isDate(String section) {
        return parsed(section).dated();
    }

    private static Parsed parsed(String section) {
        Parsed known = PARSED.get(section);
        if (known == null) {
            Locale[] locale = {Locale.US};
            List<Token> tokens = List.copyOf(tokens(section, locale));
            known = new Parsed(tokens, locale[0], dated(tokens), fraction(tokens));
            if (PARSED.size() >= 1024) {
                PARSED.clear();
            }
            PARSED.put(section, known);
        }
        return known;
    }

    // Date letters count only when no digit placeholder makes it a number format ("0.0 EUR" is a number)
    private static boolean dated(List<Token> tokens) {
        boolean letters = false;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind == Kind.DATE || t.kind == Kind.ELAPSED) {
                letters = true;
            } else if (t.kind == Kind.DIGIT && !fractionalSeconds(tokens, i)) {
                return false;
            }
        }
        return letters;
    }

    private static boolean fractionalSeconds(List<Token> tokens, int i) {
        int k = i;
        while (k > 0 && tokens.get(k - 1).kind == Kind.DIGIT && tokens.get(k - 1).text.equals("0")) {
            k--;
        }
        return tokens.get(i).text.equals("0") && k >= 2 && tokens.get(k - 1).kind == Kind.POINT
                && isSeconds(tokens.get(k - 2));
    }

    // Null when the section needs something this does not do (fractions); the caller then falls back
    static String format(double value, String section, boolean minus, boolean date1904) {
        Parsed p = parsed(section);
        if (!p.dated() && p.fraction()) {
            return null;
        }
        if (p.dated()) {
            return date(value, p.tokens(), p.locale(), date1904);
        }
        return number(value, p.tokens(), minus);
    }

    private static boolean fraction(List<Token> tokens) {
        for (int i = 1; i + 1 < tokens.size(); i++) {
            if (tokens.get(i).kind == Kind.LITERAL && tokens.get(i).text.equals("/")
                    && tokens.get(i - 1).kind == Kind.DIGIT
                    && (tokens.get(i + 1).kind == Kind.DIGIT || Character.isDigit(tokens.get(i + 1).text.charAt(0)))) {
                return true;
            }
        }
        return false;
    }

    private static List<Token> tokens(String s, Locale[] locale) {
        List<Token> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            char lower = Character.toLowerCase(c);
            if (c == '"') {
                int end = s.indexOf('"', i + 1);
                out.add(new Token(Kind.LITERAL, s.substring(i + 1, end < 0 ? n : end)));
                i = end < 0 ? n : end + 1;
            } else if (c == '\\' && i + 1 < n) {
                out.add(new Token(Kind.LITERAL, String.valueOf(s.charAt(i + 1))));
                i += 2;
            } else if ((c == '_' || c == '*') && i + 1 < n) {
                char x = s.charAt(i + 1);
                char base = c == '_' ? FormatCode.SPACE_BASE : FormatCode.FILL_BASE;
                out.add(new Token(Kind.LITERAL, String.valueOf(x < 0x100 ? (char) (base + x) : base)));
                i += 2;
            } else if (c == '[') {
                int end = s.indexOf(']', i);
                if (end < 0) {
                    i = n;
                    continue;
                }
                bracket(s.substring(i + 1, end), out, locale);
                i = end + 1;
            } else if (c == '0' || c == '#' || c == '?') {
                out.add(new Token(Kind.DIGIT, String.valueOf(c)));
                i++;
            } else if (c == '.') {
                out.add(new Token(Kind.POINT, "."));
                i++;
            } else if (c == ',') {
                out.add(new Token(Kind.COMMA, ","));
                i++;
            } else if (c == '%') {
                out.add(new Token(Kind.PERCENT, "%"));
                i++;
            } else if ((c == 'E' || c == 'e') && i + 1 < n && (s.charAt(i + 1) == '+' || s.charAt(i + 1) == '-')) {
                out.add(new Token(Kind.EXPONENT, s.substring(i, i + 2)));
                i += 2;
            } else if (s.regionMatches(true, i, "General", 0, 7)) {
                out.add(new Token(Kind.GENERAL, "General"));
                i += 7;
            } else if (s.regionMatches(true, i, "AM/PM", 0, 5)) {
                out.add(new Token(Kind.DATE, s.substring(i, i + 5)));
                i += 5;
            } else if (s.regionMatches(true, i, "A/P", 0, 3)) {
                out.add(new Token(Kind.DATE, s.substring(i, i + 3)));
                i += 3;
            } else if ("ymdhse".indexOf(lower) >= 0) {
                int j = i + 1;
                while (j < n && Character.toLowerCase(s.charAt(j)) == lower) {
                    j++;
                }
                out.add(new Token(Kind.DATE, s.substring(i, j)));
                i = j;
            } else {
                out.add(new Token(Kind.LITERAL, String.valueOf(c)));
                i++;
            }
        }
        return out;
    }

    private static final Pattern NUMERALS = Pattern.compile("\\[\\$[^\\]-]*-([0-9A-Fa-f]{7,8})\\]");

    // The zero of each numeral system a locale tag's top byte names (02 Arabic-Indic, 04 Devanagari, 0D Thai, ...)
    private static final int[] ZEROS = {0, 0, 0x0660, 0x06F0, 0x0966, 0x09E6, 0x0A66, 0x0AE6, 0x0B66, 0x0BE6, 0x0C66,
        0x0CE6, 0x0D66, 0x0E50, 0x0ED0, 0x0F20, 0x1040, 0, 0x17E0, 0x1810};

    // Excel shows the digits of a value in the numeral system its format's locale tag asks for
    static String nativeDigits(String section, String text) {
        if (text == null || section.indexOf('[') < 0) {
            return text;
        }
        Matcher m = NUMERALS.matcher(section);
        if (!m.find()) {
            return text;
        }
        int system = (int) (Long.parseLong(m.group(1), 16) >>> 24);
        int zero = system < ZEROS.length ? ZEROS[system] : 0;
        if (zero == 0) {
            return text;
        }
        boolean arabic = zero == 0x0660 || zero == 0x06F0;
        StringBuilder b = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean between = i > 0 && i + 1 < text.length() && Character.isDigit(text.charAt(i - 1))
                    && text.charAt(i + 1) >= '0' && text.charAt(i + 1) <= '9';
            if (c >= '0' && c <= '9') {
                b.append((char) (zero + c - '0'));
            } else if (arabic && between && c == '.') {
                b.append('\u066B');
            } else if (arabic && between && c == ',') {
                b.append('\u066C');
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static void bracket(String tag, List<Token> out, Locale[] locale) {
        String t = tag.trim();
        if (t.isEmpty()) {
            return;
        }
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.matches("h+|m+|s+")) {
            out.add(new Token(Kind.ELAPSED, lower));
            return;
        }
        if (t.charAt(0) != '$') {
            return;
        }
        int dash = t.indexOf('-');
        String symbol = t.substring(1, dash < 0 ? t.length() : dash);
        if (!symbol.isEmpty()) {
            out.add(new Token(Kind.LITERAL, symbol));
        }
        if (dash >= 0) {
            try {
                int lcid = (int) (Long.parseLong(t.substring(dash + 1).trim(), 16) & 0xFFFF);
                if (lcid == 0xF800 || lcid == 0xF400) {
                    out.add(new Token(Kind.LITERAL, lcid == 0xF800 ? "\u0000L" : "\u0000T"));
                    return;
                }
                locale[0] = LCIDS.getOrDefault(lcid, locale[0]);
            } catch (NumberFormatException ignored) {
                return;
            }
        }
    }

    private static String number(double value, List<Token> tokens, boolean minus) {
        boolean negative = value < 0;
        double v = Math.abs(value);
        int intEnd = tokens.size();
        int point = -1;
        int exponent = -1;
        for (int i = 0; i < tokens.size(); i++) {
            Kind k = tokens.get(i).kind;
            if (k == Kind.POINT && point < 0 && exponent < 0) {
                point = i;
                intEnd = Math.min(intEnd, i);
            } else if (k == Kind.EXPONENT && exponent < 0) {
                exponent = i;
                intEnd = Math.min(intEnd, i);
            } else if (k == Kind.PERCENT) {
                v *= 100;
            }
        }
        boolean grouping = false;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).kind != Kind.COMMA) {
                continue;
            }
            int j = i + 1;
            while (j < tokens.size() && tokens.get(j).kind == Kind.COMMA) {
                j++;
            }
            boolean digitNext = j < tokens.size() && tokens.get(j).kind == Kind.DIGIT;
            boolean digitBefore = false;
            for (int k = i - 1; k >= 0 && !digitBefore; k--) {
                digitBefore = tokens.get(k).kind == Kind.DIGIT;
            }
            if (digitNext && i < intEnd && digitBefore) {
                grouping = true;
            } else if (!digitNext && digitBefore) {
                v /= 1000;
            }
        }
        int decimals = 0;
        int intDigits = 0;
        int expDigits = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).kind != Kind.DIGIT) {
                continue;
            }
            if (exponent >= 0 && i > exponent) {
                expDigits++;
            } else if (point >= 0 && i > point) {
                decimals++;
            } else {
                intDigits++;
            }
        }
        int exp = 0;
        if (exponent >= 0 && v != 0) {
            int step = Math.max(1, intDigits);
            exp = (int) Math.floor(Math.log10(v));
            exp = Math.floorDiv(exp, step) * step;
            v = v / Math.pow(10, exp);
            BigDecimal m = round(v, decimals);
            if (m.compareTo(BigDecimal.TEN.pow(step)) >= 0) {
                exp += step;
                v = v / Math.pow(10, step);
            }
        }
        String plain = plain(v, decimals);
        int dot = plain.indexOf('.');
        String whole = dot < 0 ? plain : plain.substring(0, dot);
        String frac = dot < 0 ? "" : plain.substring(dot + 1);
        if (whole.equals("0")) {
            whole = "";
        }
        StringBuilder out = new StringBuilder();
        if (negative && minus && hasPlaceholders(tokens)) {
            out.append('-');
        }
        String integer = integer(tokens, intEnd, whole, grouping);
        String fraction = decimals(tokens, point, exponent, frac);
        String exponentText = exponent < 0 ? "" : exponent(tokens, exponent, exp, expDigits);
        boolean integerDone = false;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            boolean inInteger = i < intEnd;
            switch (t.kind) {
                case DIGIT -> {
                    if (inInteger && !integerDone) {
                        out.append(integer);
                        integerDone = true;
                    }
                }
                case POINT -> {
                    if (i == point) {
                        if (!integerDone) {
                            out.append(integer);
                            integerDone = true;
                        }
                        out.append('.').append(fraction);
                    } else if (!(point >= 0 && i > point && (exponent < 0 || i < exponent))) {
                        out.append('.');
                    }
                }
                case EXPONENT -> {
                    if (i == exponent) {
                        if (!integerDone) {
                            out.append(integer);
                            integerDone = true;
                        }
                        out.append(exponentText);
                    }
                }
                case PERCENT -> {
                    if (!(point >= 0 && i > point && (exponent < 0 || i < exponent))) {
                        out.append('%');
                    }
                }
                case DATE, ELAPSED -> {
                    if (!(point >= 0 && i > point && (exponent < 0 || i < exponent))) {
                        out.append(t.text);
                    }
                }
                case GENERAL -> out.append(ValueFormatter.general(value < 0 && minus ? value : Math.abs(value), 11));
                case LITERAL -> {
                    boolean between = inInteger && integerDone && hasDigitAfter(tokens, i, intEnd);
                    if (!between && !(point >= 0 && i > point && (exponent < 0 || i < exponent))
                            && !(exponent >= 0 && i > exponent && hasDigitAfter(tokens, i, tokens.size()))) {
                        out.append(t.text);
                    }
                }
                default -> {
                }
            }
        }
        return out.toString();
    }

    private static boolean hasPlaceholders(List<Token> tokens) {
        for (Token t : tokens) {
            if (t.kind == Kind.DIGIT) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDigitAfter(List<Token> tokens, int i, int end) {
        for (int k = i + 1; k < end; k++) {
            if (tokens.get(k).kind == Kind.DIGIT) {
                return true;
            }
        }
        return false;
    }

    static String plain(double v, int decimals) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            String whole = Long.toString((long) v);
            return decimals == 0 ? whole : whole + "." + "0".repeat(decimals);
        }
        return round(v, decimals).toPlainString();
    }

    // A shortest repr of at most 15 digits is what the exact value rounds to at 15 digits, without BigInteger powers
    static BigDecimal round(double v, int decimals) {
        BigDecimal shortest = BigDecimal.valueOf(v);
        if (shortest.precision() <= 15) {
            return shortest.setScale(decimals, RoundingMode.HALF_UP);
        }
        BigDecimal bd = clearOfHalf(shortest, v) ? shortest.round(FIFTEEN) : new BigDecimal(v).round(FIFTEEN);
        return bd.setScale(decimals, RoundingMode.HALF_UP);
    }

    private static final MathContext FIFTEEN = new MathContext(15, RoundingMode.HALF_EVEN);

    private static final long[] TENS = {1, 10, 100, 1_000, 10_000};

    private static boolean clearOfHalf(BigDecimal shortest, double v) {
        int p = shortest.precision();
        if (p < 16 || p > 18 || !(Math.abs(v) >= Double.MIN_NORMAL)) {
            return false;
        }
        long unit = TENS[p - 15];
        long tail = Math.abs(shortest.unscaledValue().longValue()) % unit;
        return Math.abs(2 * tail - unit) > 4 * TENS[p - 16];
    }

    // Digits fill the placeholders from the right; the leftmost placeholder takes any extra digits
    private static String integer(List<Token> tokens, int intEnd, String digits, boolean grouping) {
        List<Integer> places = new ArrayList<>();
        int firstDigit = -1;
        for (int i = 0; i < intEnd; i++) {
            if (tokens.get(i).kind == Kind.DIGIT) {
                places.add(i);
                if (firstDigit < 0) {
                    firstDigit = i;
                }
            }
        }
        if (places.isEmpty()) {
            return digits;
        }
        int zeros = 0;
        int optional = 0;
        boolean zeroSeen = false;
        for (int p : places) {
            char c = tokens.get(p).text.charAt(0);
            zeroSeen |= c == '0';
            if (zeroSeen && c != '?') {
                zeros++;
            } else if (c == '?') {
                optional++;
            }
        }
        String d = digits;
        while (d.length() < zeros) {
            d = "0" + d;
        }
        if (grouping && !d.isEmpty()) {
            StringBuilder g = new StringBuilder();
            for (int i = 0; i < d.length(); i++) {
                if (i > 0 && (d.length() - i) % 3 == 0) {
                    g.append(',');
                }
                g.append(d.charAt(i));
            }
            d = g.toString();
        }
        StringBuilder pad = new StringBuilder();
        int shown = digits.length() > zeros ? digits.length() : zeros;
        for (int i = shown; i < zeros + optional; i++) {
            pad.append((char) (FormatCode.SPACE_BASE + '0'));
        }
        boolean literals = false;
        for (int i = firstDigit; i < intEnd; i++) {
            if (tokens.get(i).kind == Kind.LITERAL && hasDigitAfter(tokens, i, intEnd)) {
                literals = true;
            }
        }
        if (!literals || grouping) {
            return pad + d;
        }
        StringBuilder out = new StringBuilder();
        int di = digits.length();
        for (int k = intEnd - 1; k >= firstDigit; k--) {
            Token t = tokens.get(k);
            if (t.kind == Kind.LITERAL) {
                out.insert(0, t.text);
                continue;
            }
            if (t.kind != Kind.DIGIT) {
                continue;
            }
            if (k == firstDigit) {
                if (di > 0) {
                    out.insert(0, digits, 0, di);
                    di = 0;
                } else {
                    out.insert(0, placeholder(t.text.charAt(0)));
                }
            } else if (di > 0) {
                out.insert(0, digits.charAt(--di));
            } else {
                out.insert(0, placeholder(t.text.charAt(0)));
            }
        }
        return out.toString();
    }

    private static String placeholder(char c) {
        return c == '0' ? "0" : c == '?' ? String.valueOf((char) (FormatCode.SPACE_BASE + '0')) : "";
    }

    private static String decimals(List<Token> tokens, int point, int exponent, String frac) {
        if (point < 0) {
            return "";
        }
        int end = exponent >= 0 ? exponent : tokens.size();
        List<Integer> places = new ArrayList<>();
        for (int i = point + 1; i < end; i++) {
            if (tokens.get(i).kind == Kind.DIGIT) {
                places.add(i);
            }
        }
        int keep = places.size();
        while (keep > 0) {
            char c = tokens.get(places.get(keep - 1)).text.charAt(0);
            if (c == '0' || frac.charAt(keep - 1) != '0') {
                break;
            }
            keep--;
        }
        StringBuilder out = new StringBuilder();
        int di = 0;
        for (int i = point + 1; i < end; i++) {
            Token t = tokens.get(i);
            if (t.kind == Kind.DIGIT) {
                if (di < keep) {
                    out.append(frac.charAt(di));
                } else if (t.text.charAt(0) == '?') {
                    out.append((char) (FormatCode.SPACE_BASE + '0'));
                }
                di++;
            } else if (t.kind == Kind.LITERAL || t.kind == Kind.DATE || t.kind == Kind.ELAPSED) {
                out.append(t.text);
            } else if (t.kind == Kind.PERCENT) {
                out.append('%');
            }
        }
        return out.toString();
    }

    private static String exponent(List<Token> tokens, int at, int exp, int digits) {
        String sign = exp < 0 ? "-" : tokens.get(at).text.charAt(1) == '+' ? "+" : "";
        String n = Integer.toString(Math.abs(exp));
        StringBuilder out = new StringBuilder(tokens.get(at).text.substring(0, 1)).append(sign);
        for (int i = n.length(); i < digits; i++) {
            out.append('0');
        }
        out.append(n);
        for (int i = at + 1; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.kind == Kind.LITERAL && hasDigitAfter(tokens, i, tokens.size())) {
                out.append(t.text);
            }
        }
        return out.toString();
    }

    private static String date(double value, List<Token> tokens, Locale locale, boolean date1904) {
        if (value < 0 || value > 2_958_465.99999) {
            return null;
        }
        List<Token> ts = tokens;
        for (Token t : tokens) {
            if (t.kind == Kind.LITERAL && (t.text.equals("\u0000L") || t.text.equals("\u0000T"))) {
                ts = tokens(t.text.equals("\u0000L") ? SYSTEM_LONG_DATE : SYSTEM_TIME, new Locale[1]);
                break;
            }
        }
        int fracDigits = 0;
        boolean ampm = false;
        for (int i = 0; i < ts.size(); i++) {
            Token t = ts.get(i);
            if (t.kind == Kind.DATE && (t.text.equalsIgnoreCase("am/pm") || t.text.equalsIgnoreCase("a/p"))) {
                ampm = true;
            }
            if (t.kind == Kind.POINT && i > 0 && isSeconds(ts.get(i - 1))) {
                int k = i + 1;
                while (k < ts.size() && ts.get(k).kind == Kind.DIGIT && ts.get(k).text.equals("0")) {
                    k++;
                }
                fracDigits = Math.max(fracDigits, k - i - 1);
            }
        }
        long scale = (long) Math.pow(10, Math.min(3, fracDigits));
        long ticks = Math.round(value * 86_400L * scale);
        long day = Math.floorDiv(ticks, 86_400L * scale);
        long inDay = ticks - day * 86_400L * scale;
        long seconds = inDay / scale;
        long fraction = inDay % scale;
        int hour = (int) (seconds / 3600);
        int minute = (int) (seconds / 60 % 60);
        int second = (int) (seconds % 60);
        int[] ymd = ymd(day, date1904);
        int weekday = (int) Math.floorMod(day + (date1904 ? 5 : 6), 7L);
        DateFormatSymbols names = DateFormatSymbols.getInstance(locale);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < ts.size(); i++) {
            Token t = ts.get(i);
            switch (t.kind) {
                case DATE -> out.append(dateToken(t.text, ts, i, ymd, weekday, hour, minute, second, ampm, names));
                case ELAPSED -> {
                    long total = t.text.charAt(0) == 'h' ? ticks / scale / 3600 : t.text.charAt(0) == 'm'
                            ? ticks / scale / 60 : ticks / scale;
                    out.append(pad(total, t.text.length()));
                }
                case POINT -> {
                    if (i > 0 && isSeconds(ts.get(i - 1)) && fracDigits > 0) {
                        String f = pad(fraction, (int) Math.log10(scale));
                        int k = i + 1;
                        int count = 0;
                        while (k < ts.size() && ts.get(k).kind == Kind.DIGIT && ts.get(k).text.equals("0")) {
                            k++;
                            count++;
                        }
                        out.append('.').append(f, 0, Math.min(count, f.length()));
                        i = k - 1;
                    } else {
                        out.append('.');
                    }
                }
                case DIGIT -> out.append(t.text.equals("0") ? "0" : "");
                case LITERAL -> out.append(t.text);
                case COMMA -> out.append(',');
                case PERCENT -> out.append('%');
                default -> {
                }
            }
        }
        return out.toString();
    }

    private static boolean isSeconds(Token t) {
        return (t.kind == Kind.DATE || t.kind == Kind.ELAPSED) && Character.toLowerCase(t.text.charAt(0)) == 's';
    }

    private static String dateToken(String t, List<Token> ts, int i, int[] ymd, int weekday, int hour, int minute,
            int second, boolean ampm, DateFormatSymbols names) {
        if (t.equalsIgnoreCase("am/pm")) {
            String s = hour < 12 ? "AM" : "PM";
            return Character.isLowerCase(t.charAt(0)) ? s.toLowerCase(Locale.ROOT) : s;
        }
        if (t.equalsIgnoreCase("a/p")) {
            String s = hour < 12 ? "A" : "P";
            return Character.isLowerCase(t.charAt(0)) ? s.toLowerCase(Locale.ROOT) : s;
        }
        int n = t.length();
        return switch (Character.toLowerCase(t.charAt(0))) {
            case 'y' -> n <= 2 ? pad(ymd[0] % 100, 2) : Integer.toString(ymd[0]);
            case 'e' -> Integer.toString(ymd[0]);
            case 'd' -> n == 1 ? Integer.toString(ymd[2]) : n == 2 ? pad(ymd[2], 2)
                    : n == 3 ? names.getShortWeekdays()[weekday + 1] : names.getWeekdays()[weekday + 1];
            case 'h' -> {
                int h = ampm ? (hour % 12 == 0 ? 12 : hour % 12) : hour;
                yield n == 1 ? Integer.toString(h) : pad(h, 2);
            }
            case 's' -> n == 1 ? Integer.toString(second) : pad(second, 2);
            case 'm' -> {
                if (n <= 2 && minutes(ts, i)) {
                    yield n == 1 ? Integer.toString(minute) : pad(minute, 2);
                }
                int m = ymd[1];
                if (n == 1) {
                    yield Integer.toString(m);
                }
                if (n == 2) {
                    yield pad(m, 2);
                }
                String full = m >= 1 && m <= 12 ? names.getMonths()[m - 1] : "";
                if (n == 3) {
                    yield (m >= 1 && m <= 12 ? names.getShortMonths()[m - 1] : "").replace(".", "");
                }
                yield n == 5 && !full.isEmpty() ? full.substring(0, 1) : full;
            }
            default -> t;
        };
    }

    private static boolean minutes(List<Token> ts, int i) {
        for (int k = i - 1; k >= 0; k--) {
            Token t = ts.get(k);
            if (t.kind == Kind.DATE || t.kind == Kind.ELAPSED) {
                if (Character.toLowerCase(t.text.charAt(0)) == 'h') {
                    return true;
                }
                break;
            }
        }
        for (int k = i + 1; k < ts.size(); k++) {
            Token t = ts.get(k);
            if (t.kind == Kind.DATE || t.kind == Kind.ELAPSED) {
                return Character.toLowerCase(t.text.charAt(0)) == 's';
            }
        }
        return false;
    }

    private static int[] ymd(long day, boolean date1904) {
        if (date1904) {
            LocalDate d = LocalDate.of(1904, 1, 1).plusDays(day);
            return new int[] {d.getYear(), d.getMonthValue(), d.getDayOfMonth()};
        }
        if (day == 0) {
            return new int[] {1900, 1, 0};
        }
        if (day == 60) {
            return new int[] {1900, 2, 29};
        }
        LocalDate d = LocalDate.of(1899, 12, day < 60 ? 31 : 30).plusDays(day);
        return new int[] {d.getYear(), d.getMonthValue(), d.getDayOfMonth()};
    }

    private static String pad(long v, int width) {
        String s = Long.toString(v);
        StringBuilder b = new StringBuilder();
        for (int i = s.length(); i < width; i++) {
            b.append('0');
        }
        return b.append(s).toString();
    }
}

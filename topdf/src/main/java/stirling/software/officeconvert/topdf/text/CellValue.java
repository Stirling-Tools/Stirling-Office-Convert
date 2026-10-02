package stirling.software.officeconvert.topdf.text;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record CellValue(String text, boolean number) {

    private static final Pattern NUMBER = Pattern.compile(
            "[+-]?(?:(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?");

    private static final Pattern DATE = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})(?:T([01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(?:\\.\\d+)?)?");

    private static final double EXACT_INTEGERS = 9007199254740992.0;

    private static final MathContext DIGITS = new MathContext(15, RoundingMode.HALF_UP);

    static CellValue of(String field) {
        String t = trimSpaces(field);
        if (!t.isEmpty() && t.length() <= 400 && NUMBER.matcher(t).matches()) {
            double v = Double.parseDouble(t.replace(",", ""));
            if (Double.isFinite(v)) {
                return new CellValue(general(v), true);
            }
        }
        if (field.length() >= 10 && field.length() <= 40 && isoDate(field)) {
            return new CellValue(field, true);
        }
        return new CellValue(field, false);
    }

    static String general(double v) {
        if (v == 0) {
            return "0";
        }
        double a = Math.abs(v);
        if (a < EXACT_INTEGERS && v == Math.rint(v)) {
            return Long.toString((long) v);
        }
        BigDecimal r = new BigDecimal(v).round(DIGITS);
        int exponent = r.precision() - r.scale() - 1;
        if (a >= 1e15 || exponent < -9) {
            return scientific(r, exponent);
        }
        return r.stripTrailingZeros().toPlainString();
    }

    private static String scientific(BigDecimal r, int exponent) {
        String mantissa = r.movePointLeft(exponent).stripTrailingZeros().toPlainString();
        String digits = Integer.toString(Math.abs(exponent));
        String padded = exponent >= 0 ? "0".repeat(Math.max(0, 3 - digits.length())) + digits
                : "0".repeat(Math.max(0, 2 - digits.length())) + digits;
        return mantissa + (exponent >= 0 ? "E+" : "E-") + padded;
    }

    private static boolean isoDate(String s) {
        Matcher m = DATE.matcher(s);
        if (!m.matches()) {
            return false;
        }
        try {
            int year = Integer.parseInt(m.group(1));
            LocalDate.of(year, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            return year > 0;
        } catch (DateTimeException e) {
            return false;
        }
    }

    private static String trimSpaces(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && s.charAt(a) == ' ') {
            a++;
        }
        while (b > a && s.charAt(b - 1) == ' ') {
            b--;
        }
        return s.substring(a, b);
    }
}

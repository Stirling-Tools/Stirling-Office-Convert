package stirling.software.officeconvert.sheet;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import stirling.software.officeconvert.sheet.Conventions.DateOrder;
import stirling.software.officeconvert.sheet.NumberFormat.DatePart;
import stirling.software.officeconvert.sheet.NumberFormat.Field;

final class DateReader {

    private static final String MONTH =
            "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?"
                    + "|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";
    private static final String TIME = "(?:[ T]([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?(?: ?([ap])\\.?m\\.?)?)?";
    private static final Pattern ISO =
            Pattern.compile("([0-9]{4})([-/.])([0-9]{1,2})\\2([0-9]{1,2})" + TIME, Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC =
            Pattern.compile("([0-9]{1,2})([-/.])([0-9]{1,2})\\2([0-9]{4}|[0-9]{2})" + TIME, Pattern.CASE_INSENSITIVE);
    private static final Pattern DAY_NAMED =
            Pattern.compile("([0-9]{1,2})(\\.?)([ -])" + MONTH + "(\\.?)\\3([0-9]{4}|[0-9]{2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_DAY =
            Pattern.compile(MONTH + "(\\.?) ([0-9]{1,2})(,?) ([0-9]{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_YEAR = Pattern.compile(MONTH + "(\\.?)([ -])([0-9]{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOCK =
            Pattern.compile("([0-9]{1,2}):([0-9]{2})(?::([0-9]{2}))?(?: ?([ap])\\.?m\\.?)?", Pattern.CASE_INSENSITIVE);

    private static final String[] MONTHS = {
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november",
        "december"
    };

    private static final LocalDate EPOCH = LocalDate.of(1899, 12, 30);

    private static final LocalDate FIRST = LocalDate.of(1900, 3, 1);

    private DateReader() {}

    static CellValue read(String text, DateOrder order) {
        String s = NumberReader.normalise(text);
        if (s.length() < 4 || s.length() > 30 || !Character.isLetterOrDigit(s.charAt(0))) {
            return null;
        }
        Matcher m;
        if ((m = ISO.matcher(s)).matches()) {
            List<DatePart> parts = new ArrayList<>(List.of(DatePart.of(Field.YEAR, 4), DatePart.text(m.group(2)),
                    DatePart.of(Field.MONTH, m.group(3).length()), DatePart.text(m.group(2)),
                    DatePart.of(Field.DAY, m.group(4).length())));
            return dated(text, num(m.group(1)), num(m.group(3)), num(m.group(4)), parts, m, 5);
        }
        if ((m = NUMERIC.matcher(s)).matches()) {
            return numeric(text, m, order);
        }
        if ((m = DAY_NAMED.matcher(s)).matches()) {
            int month = month(m.group(4));
            List<DatePart> parts = new ArrayList<>();
            parts.add(DatePart.of(Field.DAY, m.group(1).length()));
            parts.add(DatePart.text(m.group(2) + m.group(3)));
            parts.add(DatePart.of(Field.MONTH, nameWidth(m.group(4))));
            parts.add(DatePart.text(m.group(5) + m.group(3)));
            parts.add(DatePart.of(Field.YEAR, m.group(6).length()));
            return dated(text, year(m.group(6)), month, num(m.group(1)), parts, null, 0);
        }
        if ((m = NAMED_DAY.matcher(s)).matches()) {
            List<DatePart> parts = List.of(DatePart.of(Field.MONTH, nameWidth(m.group(1))), DatePart.text(m.group(2) + " "),
                    DatePart.of(Field.DAY, m.group(3).length()), DatePart.text(m.group(4) + " "), DatePart.of(Field.YEAR, 4));
            return dated(text, num(m.group(5)), month(m.group(1)), num(m.group(3)), parts, null, 0);
        }
        if ((m = NAMED_YEAR.matcher(s)).matches()) {
            List<DatePart> parts = List.of(DatePart.of(Field.MONTH, nameWidth(m.group(1))),
                    DatePart.text(m.group(2) + m.group(3)), DatePart.of(Field.YEAR, 4));
            return dated(text, num(m.group(4)), month(m.group(1)), 1, parts, null, 0);
        }
        if ((m = CLOCK.matcher(s)).matches()) {
            List<DatePart> parts = new ArrayList<>();
            double time = time(m, 1, parts);
            return Double.isNaN(time) ? null : CellValue.date(text.strip(), time, NumberFormat.date(parts));
        }
        return null;
    }

    static DateOrder evidence(String text) {
        Matcher m = NUMERIC.matcher(NumberReader.normalise(text));
        if (!m.matches()) {
            return DateOrder.UNKNOWN;
        }
        int a = num(m.group(1));
        int b = num(m.group(3));
        if (a > 12 && a <= 31 && b >= 1 && b <= 12) {
            return DateOrder.DMY;
        }
        if (b > 12 && b <= 31 && a >= 1 && a <= 12) {
            return DateOrder.MDY;
        }
        return DateOrder.UNKNOWN;
    }

    private static CellValue numeric(String text, Matcher m, DateOrder order) {
        int a = num(m.group(1));
        int b = num(m.group(3));
        DateOrder proven = evidence(text);
        if (proven == DateOrder.UNKNOWN && a != b) {
            proven = order != DateOrder.UNKNOWN ? order : m.group(2).equals(".") ? DateOrder.DMY : DateOrder.UNKNOWN;
            if (proven == DateOrder.UNKNOWN) {
                return null;
            }
        } else if (proven == DateOrder.UNKNOWN) {
            proven = order == DateOrder.MDY ? DateOrder.MDY : DateOrder.DMY;
        }
        boolean dmy = proven == DateOrder.DMY;
        String sep = m.group(2);
        List<DatePart> parts = new ArrayList<>();
        parts.add(DatePart.of(dmy ? Field.DAY : Field.MONTH, m.group(1).length()));
        parts.add(DatePart.text(sep));
        parts.add(DatePart.of(dmy ? Field.MONTH : Field.DAY, m.group(3).length()));
        parts.add(DatePart.text(sep));
        parts.add(DatePart.of(Field.YEAR, m.group(4).length()));
        return dated(text, year(m.group(4)), dmy ? b : a, dmy ? a : b, parts, m, 5);
    }

    private static CellValue dated(String text, int year, int month, int day, List<DatePart> parts, Matcher m,
            int timeGroup) {
        LocalDate date;
        try {
            date = LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            return null;
        }
        if (date.isBefore(FIRST)) {
            return null;
        }
        double serial = ChronoUnit.DAYS.between(EPOCH, date);
        List<DatePart> all = new ArrayList<>(parts);
        if (m != null && m.group(timeGroup) != null) {
            all.add(DatePart.text(" "));
            double time = time(m, timeGroup, all);
            if (Double.isNaN(time)) {
                return null;
            }
            serial += time;
        }
        return CellValue.date(text.strip(), serial, NumberFormat.date(all));
    }

    private static double time(Matcher m, int g, List<DatePart> parts) {
        int hour = num(m.group(g));
        int minute = num(m.group(g + 1));
        int second = m.group(g + 2) == null ? 0 : num(m.group(g + 2));
        String ampm = m.group(g + 3);
        if (ampm != null) {
            if (hour < 1 || hour > 12) {
                return Double.NaN;
            }
            hour = hour % 12 + (ampm.equalsIgnoreCase("p") ? 12 : 0);
        }
        if (hour > 23 || minute > 59 || second > 59) {
            return Double.NaN;
        }
        parts.add(DatePart.of(Field.HOUR, m.group(g).length()));
        parts.add(DatePart.text(":"));
        parts.add(DatePart.of(Field.MINUTE, 2));
        if (m.group(g + 2) != null) {
            parts.add(DatePart.text(":"));
            parts.add(DatePart.of(Field.SECOND, 2));
        }
        if (ampm != null) {
            parts.add(DatePart.text(" "));
            parts.add(DatePart.of(Field.AM_PM, 2));
        }
        return (hour * 3600 + minute * 60 + second) / 86400d;
    }

    private static int month(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i].startsWith(n.substring(0, 3))) {
                return i + 1;
            }
        }
        return 0;
    }

    private static int nameWidth(String name) {
        int m = month(name);
        return m > 0 && MONTHS[m - 1].equalsIgnoreCase(name) && name.length() > 3 ? 4 : 3;
    }

    private static int year(String s) {
        int y = num(s);
        return s.length() == 2 ? (y < 30 ? 2000 + y : 1900 + y) : y;
    }

    private static int num(String s) {
        return Integer.parseInt(s);
    }
}

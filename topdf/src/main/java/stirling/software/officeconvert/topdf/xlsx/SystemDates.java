package stirling.software.officeconvert.topdf.xlsx;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

// Built-in format 14 is the system's short date, so Excel shows it in the order of the machine's regional settings
record SystemDates(String shortDate, boolean hour24) {

    static final String US_SHORT_DATE = "m/d/yyyy";

    static final SystemDates US = new SystemDates(US_SHORT_DATE, false);

    static SystemDates of(Locale locale) {
        if (locale == null) {
            return US;
        }
        try {
            String date = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null,
                    IsoChronology.INSTANCE, locale);
            String time = DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, FormatStyle.SHORT,
                    IsoChronology.INSTANCE, locale);
            return new SystemDates(excel(date), time.indexOf('H') >= 0 || time.indexOf('k') >= 0);
        } catch (RuntimeException e) {
            return US;
        }
    }

    static SystemDates host() {
        return of(Locale.getDefault(Locale.Category.FORMAT));
    }

    // Excel always shows four year digits for the short date, whatever the locale's pattern says
    static String excel(String pattern) {
        StringBuilder out = new StringBuilder();
        boolean year = false;
        boolean month = false;
        boolean day = false;
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            int j = i;
            while (j < pattern.length() && pattern.charAt(j) == c) {
                j++;
            }
            int n = j - i;
            if (c == 'y' || c == 'u') {
                out.append("yyyy");
                year = true;
            } else if ((c == 'M' || c == 'L') && n <= 2) {
                out.append(n == 2 ? "mm" : "m");
                month = true;
            } else if (c == 'd' && n <= 2) {
                out.append(n == 2 ? "dd" : "d");
                day = true;
            } else if (Character.isLetterOrDigit(c) || c == '\'' || c == '"' || c == '\\') {
                return US_SHORT_DATE;
            } else {
                out.append(pattern, i, j);
            }
            i = j;
        }
        return year && month && day ? out.toString().trim() : US_SHORT_DATE;
    }

    String shortDateTime() {
        return shortDate + (hour24 ? " hh:mm" : " h:mm");
    }

    String builtin(int index, String format) {
        if (index == 14 && "m/d/yy".equals(format)) {
            return shortDate;
        }
        if (index == 22 && "m/d/yy h:mm".equals(format)) {
            return shortDateTime();
        }
        return format;
    }

    String date(LocalDateTime now) {
        double serial = ChronoUnit.DAYS.between(EPOCH, now.toLocalDate());
        String s = ExcelFormat.format(serial, shortDate, true, false);
        return s == null ? now.format(DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US)) : s;
    }

    String time(LocalDateTime now) {
        return now.format(DateTimeFormatter.ofPattern(hour24 ? "HH:mm" : "h:mm a", Locale.US));
    }

    private static final LocalDate EPOCH = LocalDate.of(1899, 12, 30);
}

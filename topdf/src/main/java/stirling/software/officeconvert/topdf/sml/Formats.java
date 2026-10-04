package stirling.software.officeconvert.topdf.sml;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** SpreadsheetML 2003 number format names and date values, as Excel reads them. */
final class Formats {

    private Formats() {}

    /** The format code a named or custom format stands for; null for General. */
    static String code(String format) {
        if (format == null) {
            return null;
        }
        return switch (format.trim().toLowerCase(Locale.ROOT)) {
            case "", "general", "general number" -> null;
            case "currency" -> "\"$\"#,##0.00_);[Red]\\(\"$\"#,##0.00\\)";
            case "euro currency" -> "[$€-2]\\ #,##0.00";
            case "fixed" -> "0.00";
            case "standard" -> "#,##0.00";
            case "percent" -> "0.00%";
            case "scientific" -> "0.00E+00";
            case "short date" -> "m/d/yyyy";
            case "medium date" -> "d-mmm-yy";
            case "long date" -> "dddd, mmmm d, yyyy";
            case "short time" -> "h:mm";
            case "medium time" -> "h:mm AM/PM";
            case "long time" -> "h:mm:ss";
            case "general date" -> "m/d/yyyy h:mm";
            case "yes/no" -> "\"Yes\";\"Yes\";\"No\"";
            case "true/false" -> "\"True\";\"True\";\"False\"";
            case "on/off" -> "\"On\";\"On\";\"Off\"";
            default -> format;
        };
    }

    /** An ISO date and time as Excel's serial number; NaN when it is not one. */
    static double serial(String value, boolean date1904) {
        if (value == null) {
            return Double.NaN;
        }
        String v = value.trim();
        LocalDateTime t;
        try {
            if (v.length() >= 19) {
                t = LocalDateTime.parse(v.length() > 19 ? v.substring(0, Math.min(v.length(), 23)) : v);
            } else if (v.length() == 10) {
                t = LocalDate.parse(v).atStartOfDay();
            } else {
                return Double.NaN;
            }
        } catch (RuntimeException e) {
            return Double.NaN;
        }
        LocalDateTime base = date1904 ? LocalDateTime.of(1904, 1, 1, 0, 0) : LocalDateTime.of(1899, 12, 30, 0, 0);
        double days = ChronoUnit.MILLIS.between(base, t) / 86_400_000.0;
        if (!date1904 && t.isBefore(LocalDateTime.of(1900, 3, 1, 0, 0))) {
            days -= 1;
        }
        return days;
    }
}

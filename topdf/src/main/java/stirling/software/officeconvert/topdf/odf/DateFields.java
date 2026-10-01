package stirling.software.officeconvert.topdf.odf;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Element;

final class DateFields {

    static final String META = "urn:oasis:names:tc:opendocument:xmlns:meta:1.0";

    static final String DC = "http://purl.org/dc/elements/1.1/";

    private static final Set<String> FIELDS = Set.of("date", "time", "creation-date", "creation-time",
            "modification-date", "modification-time", "print-date", "print-time");

    private static final Pattern DURATION = Pattern.compile("-?P(?:\\d+D)?T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)(?:\\.\\d+)?S)?");

    private DateFields() {}

    static boolean is(String local) {
        return FIELDS.contains(local);
    }

    static String text(Element field, Element meta, Styles styles) {
        String local = Dom.local(field);
        boolean time = local.endsWith("time");
        LocalDateTime value = parse(Dom.attr(field, Ns.TEXT, time ? "time-value" : "date-value"));
        if (value == null) {
            value = parse(metaValue(meta, local));
        }
        if (value == null) {
            return null;
        }
        Element style = styles.dataStyle(Dom.attr(field, Ns.STYLE, "data-style-name"));
        if (style == null) {
            return time ? String.format(Locale.ROOT, "%02d:%02d:%02d", value.getHour(), value.getMinute(),
                    value.getSecond())
                    : String.format(Locale.ROOT, "%04d-%02d-%02d", value.getYear(), value.getMonthValue(),
                            value.getDayOfMonth());
        }
        return format(style, value);
    }

    private static String metaValue(Element meta, String local) {
        if (meta == null) {
            return null;
        }
        String created = Dom.text(Dom.kid(meta, META, "creation-date"));
        String modified = Dom.text(Dom.kid(meta, DC, "date"));
        String printed = Dom.text(Dom.kid(meta, META, "print-date"));
        if (local.startsWith("creation")) {
            return created;
        }
        if (local.startsWith("print")) {
            return printed;
        }
        return modified != null && !modified.isBlank() ? modified : created;
    }

    static LocalDateTime parse(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String s = v.trim();
        Matcher d = DURATION.matcher(s);
        if (d.matches()) {
            return LocalDateTime.of(1899, 12, 30, number(d.group(1)) % 24, number(d.group(2)) % 60,
                    number(d.group(3)) % 60);
        }
        try {
            if (s.length() == 10) {
                return LocalDateTime.parse(s + "T00:00:00");
            }
            if (s.endsWith("Z") || s.matches(".*[+-]\\d\\d:\\d\\d$")) {
                return OffsetDateTime.parse(s).toLocalDateTime();
            }
            return LocalDateTime.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int number(String v) {
        return v == null ? 0 : Integer.parseInt(v.length() > 6 ? v.substring(0, 6) : v);
    }

    private static String format(Element style, LocalDateTime t) {
        Locale locale = locale(style);
        StringBuilder b = new StringBuilder();
        boolean ampm = Dom.kid(style, Ns.NUMBER, "am-pm") != null;
        for (Element k : Dom.kids(style)) {
            if (!Ns.NUMBER.equals(k.getNamespaceURI())) {
                continue;
            }
            boolean longForm = "long".equals(Dom.attr(k, Ns.NUMBER, "style"));
            switch (Dom.local(k)) {
                case "day" -> b.append(pad(t.getDayOfMonth(), longForm));
                case "month" -> {
                    if ("true".equals(Dom.attr(k, Ns.NUMBER, "textual"))) {
                        b.append(t.getMonth().getDisplayName(longForm ? TextStyle.FULL : TextStyle.SHORT, locale));
                    } else {
                        b.append(pad(t.getMonthValue(), longForm));
                    }
                }
                case "year" -> b.append(longForm ? String.valueOf(t.getYear()) : pad(t.getYear() % 100, true));
                case "day-of-week" -> b.append(t.getDayOfWeek()
                        .getDisplayName(longForm ? TextStyle.FULL : TextStyle.SHORT, locale));
                case "hours" -> {
                    int h = ampm ? (t.getHour() + 11) % 12 + 1 : t.getHour();
                    b.append(pad(h, longForm));
                }
                case "minutes" -> b.append(pad(t.getMinute(), longForm));
                case "seconds" -> b.append(pad(t.getSecond(), longForm));
                case "am-pm" -> b.append(t.getHour() < 12 ? "AM" : "PM");
                case "text" -> b.append(k.getTextContent());
                default -> {
                }
            }
        }
        return b.toString();
    }

    private static Locale locale(Element style) {
        String language = Dom.attr(style, Ns.NUMBER, "language");
        if (language == null) {
            return Locale.ENGLISH;
        }
        String country = Dom.attr(style, Ns.NUMBER, "country", "");
        return Locale.of(language, country);
    }

    private static String pad(int v, boolean two) {
        return two && v < 10 ? "0" + v : String.valueOf(v);
    }
}

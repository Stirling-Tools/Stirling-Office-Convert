package stirling.software.officeconvert.topdf.field;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

public final class DatePicture {

    public static final int MAX_PICTURE = 256;

    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July",
        "August", "September", "October", "November", "December"};

    private static final String[] DAYS = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
        "Sunday"};

    private DatePicture() {}

    public static boolean dated(String kind) {
        return switch (kind) {
            case "DATE", "TIME", "CREATEDATE", "SAVEDATE", "PRINTDATE" -> true;
            default -> false;
        };
    }

    public static String text(String kind, List<String> args, int[] t) {
        if (!valid(t)) {
            return null;
        }
        String picture = switchValue(args, "\\@");
        if (picture == null) {
            picture = switch (kind) {
                case "DATE" -> "M/d/yyyy";
                case "TIME" -> "h:mm AM/PM";
                default -> "M/d/yyyy h:mm:ss AM/PM";
            };
        }
        return format(picture.length() > MAX_PICTURE ? picture.substring(0, MAX_PICTURE) : picture, t);
    }

    public static boolean valid(int[] t) {
        return t != null && t.length >= 5 && t[0] >= 1 && t[1] >= 1 && t[1] <= 12 && t[2] >= 1 && t[2] <= 31;
    }

    public static String switchValue(List<String> args, String name) {
        String value = null;
        for (int i = 1; i + 1 < args.size(); i++) {
            if (args.get(i).equals(name)) {
                value = args.get(i + 1);
            }
        }
        return value;
    }

    public static String format(String p, int[] t) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == '\'') {
                int end = p.indexOf('\'', i + 1);
                end = end < 0 ? p.length() : end;
                out.append(p, i + 1, end);
                i = end + 1;
                continue;
            }
            String rest = p.substring(i).toLowerCase(Locale.ROOT);
            if (rest.startsWith("am/pm") || rest.startsWith("a/p")) {
                boolean full = rest.startsWith("am/pm");
                String mark = t[3] < 12 ? (full ? "AM" : "A") : (full ? "PM" : "P");
                out.append(Character.isLowerCase(c) ? mark.toLowerCase(Locale.ROOT) : mark);
                i += full ? 5 : 3;
                continue;
            }
            int n = 1;
            while (i + n < p.length() && p.charAt(i + n) == c) {
                n++;
            }
            switch (c) {
                case 'y', 'Y' -> out.append(n <= 2 ? two(t[0] % 100) : Integer.toString(t[0]));
                case 'M' -> out.append(n >= 4 ? MONTHS[t[1] - 1] : n == 3 ? MONTHS[t[1] - 1].substring(0, 3)
                        : n == 2 ? two(t[1]) : Integer.toString(t[1]));
                case 'd', 'D' -> {
                    String day = DAYS[dayOfWeek(t[0], t[1], t[2])];
                    out.append(n >= 4 ? day : n == 3 ? day.substring(0, 3) : n == 2 ? two(t[2])
                            : Integer.toString(t[2]));
                }
                case 'H' -> out.append(n >= 2 ? two(t[3]) : Integer.toString(t[3]));
                case 'h' -> {
                    int h = t[3] % 12 == 0 ? 12 : t[3] % 12;
                    out.append(n >= 2 ? two(h) : Integer.toString(h));
                }
                case 'm' -> out.append(n >= 2 ? two(t[4]) : Integer.toString(t[4]));
                case 's', 'S' -> {
                    int s = t.length > 5 ? t[5] : 0;
                    out.append(n >= 2 ? two(s) : Integer.toString(s));
                }
                default -> out.append(String.valueOf(c).repeat(n));
            }
            i += n;
        }
        return out.toString();
    }

    private static String two(int v) {
        return v < 10 ? "0" + v : Integer.toString(v);
    }

    private static int dayOfWeek(int y, int m, int d) {
        try {
            return LocalDate.of(y, m, d).getDayOfWeek().getValue() - 1;
        } catch (DateTimeException e) {
            return 0;
        }
    }
}

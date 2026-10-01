package stirling.software.officeconvert.topdf.field;

import java.util.List;
import java.util.Locale;

public final class FieldCase {

    private FieldCase() {}

    public static String apply(String text, List<String> args) {
        String out = text;
        for (int i = 1; i + 1 < args.size(); i++) {
            if (!args.get(i).equals("\\*")) {
                continue;
            }
            out = switch (args.get(i + 1).toLowerCase(Locale.ROOT)) {
                case "upper" -> out.toUpperCase(Locale.ROOT);
                case "lower" -> out.toLowerCase(Locale.ROOT);
                case "firstcap" -> out.isEmpty() ? out : out.substring(0, 1).toUpperCase(Locale.ROOT) + out.substring(1);
                case "caps" -> caps(out);
                default -> out;
            };
        }
        return out;
    }

    private static String caps(String s) {
        StringBuilder b = new StringBuilder(s.length());
        boolean start = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(start ? Character.toUpperCase(c) : c);
            start = Character.isWhitespace(c);
        }
        return b.toString();
    }
}

package stirling.software.officeconvert.sheet;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class SheetNames {

    private static final int MAX = 31;

    private final Set<String> used = new HashSet<>();

    String unique(String wanted) {
        String base = clean(wanted);
        String name = base;
        for (int n = 2; !used.add(name.toLowerCase(Locale.ROOT)); n++) {
            String suffix = " (" + n + ")";
            name = base.substring(0, Math.min(base.length(), MAX - suffix.length())) + suffix;
        }
        return name;
    }

    private static String clean(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < MAX; i++) {
            char c = s.charAt(i);
            sb.append("[]:*?/\\".indexOf(c) >= 0 || c < 0x20 ? '_' : c);
        }
        String t = sb.toString().strip();
        while (t.startsWith("'")) {
            t = t.substring(1);
        }
        while (t.endsWith("'")) {
            t = t.substring(0, t.length() - 1);
        }
        return t.isEmpty() || t.equalsIgnoreCase("history") ? "Sheet" : t;
    }
}

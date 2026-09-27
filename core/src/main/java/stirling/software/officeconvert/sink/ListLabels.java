package stirling.software.officeconvert.sink;

import java.util.HashMap;
import java.util.Map;

import stirling.software.officeconvert.model.Numbering;

public final class ListLabels {

    private final Numbering numbering;
    private final Map<Integer, int[]> counters = new HashMap<>();

    private static final int UNSTARTED = Integer.MIN_VALUE;

    public ListLabels(Numbering numbering) {
        this.numbering = numbering;
    }

    public String next(int numId, int level) {
        if (numId < 1 || numId > numbering.instances.size() || level < 0 || level > 8) {
            return "";
        }
        Numbering.Instance inst = numbering.instances.get(numId - 1);
        int[] count = counters.computeIfAbsent(numId, k -> unstarted());
        count[level] = count[level] == UNSTARTED ? Math.max(0, inst.starts[level]) : count[level] + 1;
        for (int l = level + 1; l < 9; l++) {
            count[l] = UNSTARTED;
        }
        Numbering.Level def = inst.definition.levels[level];
        if (def != null && "bullet".equals(def.format())) {
            return def.text();
        }
        String text = def != null ? def.text() : "%" + (level + 1) + ".";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%' && i + 1 < text.length() && text.charAt(i + 1) >= '1' && text.charAt(i + 1) <= '9') {
                int l = text.charAt(++i) - '1';
                Numbering.Level ld = inst.definition.levels[l];
                int value = count[l] == UNSTARTED ? Math.max(0, inst.starts[l]) : count[l];
                sb.append(format(ld == null ? "decimal" : ld.format(), value));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int[] unstarted() {
        int[] c = new int[9];
        java.util.Arrays.fill(c, UNSTARTED);
        return c;
    }

    public static String format(String format, int n) {
        return switch (format) {
            case "lowerLetter" -> letters(n, 'a');
            case "upperLetter" -> letters(n, 'A');
            case "lowerRoman" -> roman(n).toLowerCase(java.util.Locale.ROOT);
            case "upperRoman" -> roman(n);
            case "none" -> "";
            default -> Integer.toString(n);
        };
    }

    private static String letters(int n, char base) {
        if (n <= 0) {
            return Integer.toString(n);
        }
        char c = (char) (base + (n - 1) % 26);
        return String.valueOf(c).repeat((n - 1) / 26 + 1);
    }

    private static String roman(int n) {
        if (n <= 0 || n >= 4000) {
            return Integer.toString(n);
        }
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            while (n >= v[i]) {
                sb.append(s[i]);
                n -= v[i];
            }
        }
        return sb.toString();
    }
}

package stirling.software.officeconvert.topdf.xlsx;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class FormatCode {

    static final char SPACE_BASE = '';

    static final char FILL_BASE = '';

    private static final Color[] NAMED = {
        Color.BLACK, Color.BLUE, Color.CYAN, Color.GREEN, Color.MAGENTA, Color.RED, Color.WHITE, Color.YELLOW
    };

    private static final String[] NAMES = {"black", "blue", "cyan", "green", "magenta", "red", "white", "yellow"};

    private FormatCode() {}

    private static final Map<String, List<String>> SECTIONS = new ConcurrentHashMap<>();

    static List<String> sections(String format) {
        List<String> known = SECTIONS.get(format);
        if (known == null) {
            known = List.copyOf(split(format));
            if (SECTIONS.size() >= 1024) {
                SECTIONS.clear();
            }
            SECTIONS.put(format, known);
        }
        return known;
    }

    private static List<String> split(String format) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean bracket = false;
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (quoted) {
                cur.append(c);
                if (c == '"') {
                    quoted = false;
                }
                continue;
            }
            if (c == '\\' && i + 1 < format.length()) {
                cur.append(c).append(format.charAt(++i));
                continue;
            }
            if (c == '"') {
                quoted = true;
            } else if (c == '[') {
                bracket = true;
            } else if (c == ']') {
                bracket = false;
            } else if (c == ';' && !bracket) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    static String numberSection(String format, double value) {
        return sections(format).get(numberSectionIndex(format, value));
    }

    static int numberSectionIndex(String format, double value) {
        List<String> s = sections(format);
        List<String> numeric = s.size() > 3 ? s.subList(0, 3) : s;
        boolean conditions = false;
        for (String part : numeric) {
            if (condition(part) != null) {
                conditions = true;
                break;
            }
        }
        if (conditions) {
            for (int i = 0; i < numeric.size(); i++) {
                String cond = condition(numeric.get(i));
                if (cond == null || test(cond, value)) {
                    return i;
                }
            }
            return numeric.size() - 1;
        }
        if (numeric.size() == 1) {
            return 0;
        }
        if (numeric.size() == 2) {
            return value < 0 ? 1 : 0;
        }
        return value > 0 ? 0 : value < 0 ? 1 : 2;
    }

    static String textSection(String format) {
        List<String> s = sections(format);
        if (s.size() >= 4) {
            return s.get(3);
        }
        for (String part : s) {
            if (hasUnquoted(part, '@')) {
                return part;
            }
        }
        return null;
    }

    static Color color(String section) {
        int i = 0;
        while (i < section.length()) {
            char c = section.charAt(i);
            if (c == '"') {
                int end = section.indexOf('"', i + 1);
                i = end < 0 ? section.length() : end + 1;
                continue;
            }
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '[') {
                int end = section.indexOf(']', i);
                if (end < 0) {
                    return null;
                }
                String tag = section.substring(i + 1, end).trim().toLowerCase(Locale.ROOT);
                for (int k = 0; k < NAMES.length; k++) {
                    if (tag.equals(NAMES[k])) {
                        return NAMED[k];
                    }
                }
                if (tag.startsWith("color")) {
                    try {
                        int n = Integer.parseInt(tag.substring(5).trim());
                        return n >= 1 && n <= 56 ? PALETTE[n - 1] : null;
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
                i = end + 1;
                continue;
            }
            i++;
        }
        return null;
    }

    static String withMarkers(String format) {
        StringBuilder b = new StringBuilder(format.length());
        boolean quoted = false;
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (quoted) {
                b.append(c);
                if (c == '"') {
                    quoted = false;
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
                b.append(c);
            } else if (c == '\\' && i + 1 < format.length()) {
                b.append(c).append(format.charAt(++i));
            } else if ((c == '_' || c == '*') && i + 1 < format.length()) {
                char x = format.charAt(++i);
                char base = c == '_' ? SPACE_BASE : FILL_BASE;
                b.append(x < 0x100 ? (char) (base + x) : base);
            } else if (c == '[') {
                int end = format.indexOf(']', i);
                if (end < 0) {
                    b.append(c);
                } else {
                    b.append(format, i, end + 1);
                    i = end;
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    static String applyText(String section, String text) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < section.length(); i++) {
            char c = section.charAt(i);
            if (c == '"') {
                int end = section.indexOf('"', i + 1);
                if (end < 0) {
                    b.append(section, i + 1, section.length());
                    break;
                }
                b.append(section, i + 1, end);
                i = end;
            } else if (c == '\\' && i + 1 < section.length()) {
                b.append(section.charAt(++i));
            } else if (c == '@') {
                b.append(text);
            } else if ((c == '_' || c == '*') && i + 1 < section.length()) {
                char x = section.charAt(++i);
                char base = c == '_' ? SPACE_BASE : FILL_BASE;
                b.append(x < 0x100 ? (char) (base + x) : base);
            } else if (c == '[') {
                int end = section.indexOf(']', i);
                i = end < 0 ? section.length() : end;
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    static boolean isSpacer(char c) {
        return c >= SPACE_BASE && c < SPACE_BASE + 0x100;
    }

    static boolean isFill(char c) {
        return c >= FILL_BASE && c < FILL_BASE + 0x100;
    }

    static char marked(char c) {
        return (char) (isSpacer(c) ? c - SPACE_BASE : c - FILL_BASE);
    }

    static boolean isGeneral(String format) {
        String f = format == null ? "" : format.trim();
        return f.isEmpty() || f.equalsIgnoreCase("General");
    }

    private static boolean hasUnquoted(String s, char target) {
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == '\\') {
                i++;
            } else if (!quoted && c == target) {
                return true;
            }
        }
        return false;
    }

    private static String condition(String section) {
        int i = 0;
        while ((i = section.indexOf('[', i)) >= 0) {
            int end = section.indexOf(']', i);
            if (end < 0) {
                return null;
            }
            String tag = section.substring(i + 1, end).trim();
            if (!tag.isEmpty() && "<>=".indexOf(tag.charAt(0)) >= 0) {
                return tag;
            }
            i = end + 1;
        }
        return null;
    }

    private static boolean test(String cond, double v) {
        String op;
        if (cond.startsWith("<=") || cond.startsWith(">=") || cond.startsWith("<>")) {
            op = cond.substring(0, 2);
        } else {
            op = cond.substring(0, 1);
        }
        double x;
        try {
            x = Double.parseDouble(cond.substring(op.length()).trim());
        } catch (NumberFormatException e) {
            return false;
        }
        return switch (op) {
            case "<" -> v < x;
            case ">" -> v > x;
            case "=" -> v == x;
            case "<=" -> v <= x;
            case ">=" -> v >= x;
            default -> v != x;
        };
    }

    private static final Color[] PALETTE = {
        new Color(0x000000), new Color(0xFFFFFF), new Color(0xFF0000), new Color(0x00FF00), new Color(0x0000FF),
        new Color(0xFFFF00), new Color(0xFF00FF), new Color(0x00FFFF), new Color(0x800000), new Color(0x008000),
        new Color(0x000080), new Color(0x808000), new Color(0x800080), new Color(0x008080), new Color(0xC0C0C0),
        new Color(0x808080), new Color(0x9999FF), new Color(0x993366), new Color(0xFFFFCC), new Color(0xCCFFFF),
        new Color(0x660066), new Color(0xFF8080), new Color(0x0066CC), new Color(0xCCCCFF), new Color(0x000080),
        new Color(0xFF00FF), new Color(0xFFFF00), new Color(0x00FFFF), new Color(0x800080), new Color(0x800000),
        new Color(0x008080), new Color(0x0000FF), new Color(0x00CCFF), new Color(0xCCFFFF), new Color(0xCCFFCC),
        new Color(0xFFFF99), new Color(0x99CCFF), new Color(0xFF99CC), new Color(0xCC99FF), new Color(0xFFCC99),
        new Color(0x3366FF), new Color(0x33CCCC), new Color(0x99CC00), new Color(0xFFCC00), new Color(0xFF9900),
        new Color(0xFF6600), new Color(0x666699), new Color(0x969696), new Color(0x003366), new Color(0x339966),
        new Color(0x003300), new Color(0x333300), new Color(0x993300), new Color(0x993366), new Color(0x333399),
        new Color(0x333333)
    };
}

package stirling.software.officeconvert.topdf.odf;

import java.util.Locale;
import java.util.Map;

final class Colors {

    private static final Map<String, String> NAMED = Map.ofEntries(Map.entry("black", "000000"),
            Map.entry("white", "FFFFFF"), Map.entry("red", "FF0000"), Map.entry("green", "008000"),
            Map.entry("blue", "0000FF"), Map.entry("yellow", "FFFF00"), Map.entry("gray", "808080"),
            Map.entry("grey", "808080"), Map.entry("silver", "C0C0C0"), Map.entry("maroon", "800000"),
            Map.entry("navy", "000080"), Map.entry("olive", "808000"), Map.entry("purple", "800080"),
            Map.entry("teal", "008080"), Map.entry("aqua", "00FFFF"), Map.entry("fuchsia", "FF00FF"),
            Map.entry("lime", "00FF00"), Map.entry("orange", "FFA500"));

    private Colors() {}

    static String hex(String v, String fallback) {
        if (v == null) {
            return fallback;
        }
        String s = v.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
            if (s.length() == 3) {
                s = "" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
            }
            if (s.length() == 6 && s.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                return s.toUpperCase(Locale.ROOT);
            }
            return fallback;
        }
        String named = named(s);
        return named == null ? fallback : named;
    }

    static String named(String v) {
        return NAMED.get(v.toLowerCase(Locale.ROOT));
    }

    /** A colour, or null for transparent, none or anything unreadable. */
    static String fill(String v) {
        if (v == null || v.isBlank() || "transparent".equalsIgnoreCase(v.trim()) || "none".equalsIgnoreCase(v.trim())) {
            return null;
        }
        return hex(v, null);
    }
}

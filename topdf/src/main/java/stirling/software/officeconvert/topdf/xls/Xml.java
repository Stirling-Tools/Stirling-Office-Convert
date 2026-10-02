package stirling.software.officeconvert.topdf.xls;

public final class Xml {

    public static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    public static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    public static final String PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships";

    public static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

    private Xml() {}

    // Attribute values and plain element text: characters XML cannot hold are left out
    public static String attr(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String r = switch (c) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '"' -> "&quot;";
                case '\t' -> "&#9;";
                case '\n' -> "&#10;";
                case '\r' -> "&#13;";
                default -> legal(s, i) ? null : "";
            };
            if (r != null && b == null) {
                b = new StringBuilder(s.length() + 16).append(s, 0, i);
            }
            if (b != null) {
                if (r == null) {
                    b.append(c);
                } else {
                    b.append(r);
                }
            }
        }
        return b == null ? s : b.toString();
    }

    // Cell text as SpreadsheetML writes it: other controls as _xHHHH_ and a literal _xHHHH_ with its underscore escaped
    public static String text(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') {
                b.append("&amp;");
            } else if (c == '<') {
                b.append("&lt;");
            } else if (c == '>') {
                b.append("&gt;");
            } else if (c == '\t' || c == '\n') {
                b.append(c);
            } else if (c == '\r') {
                b.append("&#13;");
            } else if (c == '_' && escaped(s, i)) {
                b.append("_x005F_");
            } else if (c < 0x20 || c == 0xFFFE || c == 0xFFFF) {
                b.append(String.format("_x%04X_", (int) c));
            } else if (legal(s, i)) {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static boolean escaped(String s, int i) {
        if (i + 6 >= s.length() || s.charAt(i + 1) != 'x' || s.charAt(i + 6) != '_') {
            return false;
        }
        for (int k = i + 2; k < i + 6; k++) {
            if (Character.digit(s.charAt(k), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean legal(String s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) {
            return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
        }
        if (Character.isLowSurrogate(c)) {
            return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
        }
        return c >= 0x20 && c != 0xFFFE && c != 0xFFFF || c == '\t' || c == '\n' || c == '\r';
    }
}

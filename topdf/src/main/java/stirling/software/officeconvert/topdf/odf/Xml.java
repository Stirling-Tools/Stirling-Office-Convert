package stirling.software.officeconvert.topdf.odf;

final class Xml {

    static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    static final String PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture";
    static final String WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    static final String WPS = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape";
    static final String S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    static final String P = "http://schemas.openxmlformats.org/presentationml/2006/main";
    static final String REL_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
    static final String CT = "application/vnd.openxmlformats-officedocument.";

    private Xml() {}

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String rep = switch (c) {
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '&' -> "&amp;";
                case '"' -> "&quot;";
                default -> valid(s, i) ? null : "";
            };
            if (rep != null && out == null) {
                out = new StringBuilder(s.length() + 16).append(s, 0, i);
            }
            if (out != null) {
                if (rep != null) {
                    out.append(rep);
                } else {
                    out.append(c);
                }
            }
        }
        return out == null ? s : out.toString();
    }

    private static boolean valid(String s, int i) {
        char c = s.charAt(i);
        if (c == '\t' || c == '\n' || c == '\r') {
            return true;
        }
        if (c < 0x20 || c == 0xFFFE || c == 0xFFFF) {
            return false;
        }
        if (Character.isHighSurrogate(c)) {
            return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
        }
        if (Character.isLowSurrogate(c)) {
            return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
        }
        return true;
    }
}

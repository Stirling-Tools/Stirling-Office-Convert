package stirling.software.officeconvert.topdf.doc;

final class Xml {

    static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

    static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    static final String PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships";

    static final String WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";

    static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";

    static final String PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture";

    static final String WPS = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape";

    static final String WPG = "http://schemas.microsoft.com/office/word/2010/wordprocessingGroup";

    static final String NAMESPACES = " xmlns:w=\"" + W + "\" xmlns:r=\"" + R + "\" xmlns:wp=\"" + WP + "\" xmlns:a=\""
            + A + "\" xmlns:pic=\"" + PIC + "\" xmlns:wps=\"" + WPS + "\" xmlns:wpg=\"" + WPG + "\"";

    private Xml() {}

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        text(b, s);
        return b.toString();
    }

    static void text(StringBuilder b, CharSequence s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> {
                    if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                        b.append(c).append(s.charAt(++i));
                    } else if (legal(c)) {
                        b.append(c);
                    }
                }
            }
        }
    }

    static boolean legal(char c) {
        return c >= 0x20 && c < 0xD800 || c >= 0xE000 && c <= 0xFFFD || c == '\t' || c == '\n' || c == '\r';
    }

    static String hex(int rgb) {
        return String.format("%06X", rgb & 0xFFFFFF);
    }
}

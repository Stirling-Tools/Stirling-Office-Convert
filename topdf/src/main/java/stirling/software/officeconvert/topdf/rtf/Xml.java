package stirling.software.officeconvert.topdf.rtf;

final class Xml {

    static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

    private Xml() {}

    static String attr(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                default -> {
                    if (legal(s, i)) {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }

    static void text(CharSequence s, StringBuilder b) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                default -> {
                    if (legal(s, i)) {
                        b.append(c);
                    }
                }
            }
        }
    }

    private static boolean legal(CharSequence s, int i) {
        char c = s.charAt(i);
        if (Character.isHighSurrogate(c)) {
            return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
        }
        if (Character.isLowSurrogate(c)) {
            return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
        }
        return c >= 0x20 && c != 0xFFFE && c != 0xFFFF || c == '\t';
    }
}

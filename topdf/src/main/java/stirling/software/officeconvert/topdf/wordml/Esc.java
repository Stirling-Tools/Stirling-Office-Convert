package stirling.software.officeconvert.topdf.wordml;

final class Esc {

    static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

    private Esc() {}

    static String attr(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        text(b, s, 0, s.length());
        return b.toString();
    }

    static void text(StringBuilder b, CharSequence s, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\t', '\n', '\r' -> b.append("&#").append((int) c).append(';');
                default -> {
                    if (Character.isHighSurrogate(c) && i + 1 < end && Character.isLowSurrogate(s.charAt(i + 1))) {
                        b.append(c).append(s.charAt(++i));
                    } else if (c >= 0x20 && c < 0xD800 || c >= 0xE000 && c <= 0xFFFD) {
                        b.append(c);
                    }
                }
            }
        }
    }
}

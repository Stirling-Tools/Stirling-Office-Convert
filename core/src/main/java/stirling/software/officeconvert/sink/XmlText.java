package stirling.software.officeconvert.sink;

public final class XmlText {

    private XmlText() {}

    public static void escape(StringBuilder sb, String s) {
        escape(sb, s, false);
    }

    public static void escapeKeepingTabs(StringBuilder sb, String s) {
        escape(sb, s, true);
    }

    private static void escape(StringBuilder sb, String s, boolean keepTabs) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if (c >= 0x20 && c != 0xFFFE && c != 0xFFFF || keepTabs && c == '\t') {
                        if (Character.isHighSurrogate(c)) {
                            if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                                sb.append(c).append(s.charAt(++i));
                            }
                        } else if (!Character.isLowSurrogate(c)) {
                            sb.append(c);
                        }
                    }
                }
            }
        }
    }
}

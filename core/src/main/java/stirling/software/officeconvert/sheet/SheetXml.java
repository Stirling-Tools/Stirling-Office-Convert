package stirling.software.officeconvert.sheet;

import java.math.BigDecimal;

public final class SheetXml {

    private SheetXml() {}

    public static void escape(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\n' -> sb.append('\n');
                case '\t' -> sb.append('\t');
                case '\r' -> { }
                default -> {
                    if (Character.isHighSurrogate(c)) {
                        if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                            sb.append(c).append(s.charAt(++i));
                        }
                    } else if (c >= 0x20 && !Character.isLowSurrogate(c) && c != 0xFFFE && c != 0xFFFF) {
                        sb.append(c);
                    }
                }
            }
        }
    }

    public static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        escape(sb, s);
        return sb.toString();
    }

    public static String number(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        String s = Double.toString(v);
        return s.indexOf('E') >= 0 ? new BigDecimal(s).toPlainString() : s;
    }
}

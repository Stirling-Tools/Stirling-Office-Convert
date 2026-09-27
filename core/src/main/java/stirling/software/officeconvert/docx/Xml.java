package stirling.software.officeconvert.docx;

final class Xml {

    private Xml() {}

    static void text(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if (c >= 0x20 && c != 0xFFFE && c != 0xFFFF || c == '\t') {
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

    static void runText(StringBuilder sb, String s) {
        int from = 0;
        for (int i = s.indexOf('\u00AD'); i >= 0; i = s.indexOf('\u00AD', from)) {
            text(sb, s.substring(from, i));
            sb.append("</w:t><w:softHyphen/><w:t xml:space=\"preserve\">");
            from = i + 1;
        }
        text(sb, s.substring(from));
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        text(sb, s);
        return sb.toString();
    }

    static int twips(float pt) {
        return Math.round(pt * 20f);
    }

    static int halfPoints(float pt) {
        return Math.min(3276, Math.max(2, Math.round(pt * 2f)));
    }

    private static final long MAX_EMU = 2L * 1584 * 12700;

    static long emu(float pt) {
        return Math.min(MAX_EMU, Math.max(1L, Math.round(pt * 12700.0)));
    }

    static long emuOffsetDown(float pt) {
        return Math.max(0, emuOffset(pt));
    }

    static long emuOffset(float pt) {
        return Math.max(-MAX_EMU, Math.min(MAX_EMU, Math.round(pt * 12700.0)));
    }

    static int eighths(float pt) {
        return Math.max(2, Math.min(96, Math.round(pt * 8f)));
    }

    static String hex(int rgb) {
        return String.format("%06X", rgb & 0xFFFFFF);
    }
}

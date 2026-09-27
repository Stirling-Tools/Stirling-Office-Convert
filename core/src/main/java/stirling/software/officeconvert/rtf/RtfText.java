package stirling.software.officeconvert.rtf;

final class RtfText {

    private RtfText() {}

    static void text(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '{' -> sb.append("\\{");
                case '}' -> sb.append("\\}");
                case '\t' -> sb.append("\\tab ");
                case '\n' -> sb.append("\\line ");
                case '\u00AD' -> sb.append("\\-");
                case '\u00A0' -> sb.append("\\~");
                default -> {
                    if (c >= 0x20 && c < 0x7F) {
                        sb.append(c);
                    } else if (c >= 0x80 && c != 0xFFFE && c != 0xFFFF) {
                        if (Character.isHighSurrogate(c) && (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1)))) {
                            continue;
                        }
                        if (Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(s.charAt(i - 1)))) {
                            continue;
                        }
                        sb.append("\\u").append((int) (short) c).append('?');
                    }
                }
            }
        }
    }

    static String text(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        text(sb, s);
        return sb.toString();
    }

    static int twips(float pt) {
        if (!Float.isFinite(pt)) {
            return 0;
        }
        return Math.round(Math.max(-31680f, Math.min(31680f, pt)) * 20f);
    }

    static int halfPoints(float pt) {
        return Math.min(3276, Math.max(2, Math.round(pt * 2f)));
    }

    static long emu(float pt) {
        return Math.round(pt * 12700.0);
    }

    static int bgr(int rgb) {
        return (rgb & 0xFF) << 16 | rgb & 0xFF00 | rgb >> 16 & 0xFF;
    }
}

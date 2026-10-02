package stirling.software.officeconvert.topdf.lotus;

import java.nio.charset.Charset;

final class LotusText {

    private static final String LICS =
            "\u0300\u0301\u0302\u0308\u0303\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
            + "\u0300\u0301\u0302\u0308\u0303\u0131\u0331\u25B2\u25BC\u0000\u00A0\u2190\u0000\u0000\u0000\u0000"
            + "\u0192\u00A1\u00A2\u00A3\u201C\u00A5\u20A7\u00A7\u00A4\u00A9\u00AA\u00AB\u0394\u03C0\u2265\u00F7"
            + "\u00B0\u00B1\u00B2\u00B3\u201E\u00B5\u00B6\u00B7\u2122\u00B9\u00BA\u00BB\u00BC\u00BD\u2264\u00BF"
            + "\u00C0\u00C1\u00C2\u00C3\u00C4\u00C5\u00C6\u00C7\u00C8\u00C9\u00CA\u00CB\u00CC\u00CD\u00CE\u00CF"
            + "\u00D0\u00D1\u00D2\u00D3\u00D4\u00D5\u00D6\u0152\u00D8\u00D9\u00DA\u00DB\u00DC\u0178\u00DE\u00DF"
            + "\u00E0\u00E1\u00E2\u00E3\u00E4\u00E5\u00E6\u00E7\u00E8\u00E9\u00EA\u00EB\u00EC\u00ED\u00EE\u00EF"
            + "\u00F0\u00F1\u00F2\u00F3\u00F4\u00F5\u00F6\u0153\u00F8\u00F9\u00FA\u00FB\u00FC\u00FF\u00FE\u0000";

    private static final Charset GROUP_1 = Charset.forName("IBM850");

    private static final Charset UNICODE = Charset.forName("UTF-16BE");

    private LotusText() {}

    static String lics(byte[] d, int from, int stop) {
        StringBuilder b = new StringBuilder(stop - from);
        for (int i = from; i < stop; i++) {
            int c = d[i] & 0xFF;
            char ch = c < 0x80 ? (char) c : LICS.charAt(c - 0x80);
            if (ch != 0) {
                b.append(ch);
            }
        }
        return b.toString();
    }

    static String lmbcs(byte[] d, int from, int stop, Runnable lost) {
        StringBuilder b = new StringBuilder(stop - from);
        int i = from;
        while (i < stop) {
            int c = d[i] & 0xFF;
            if (c >= 0x20 && c < 0x80) {
                b.append((char) c);
                i++;
            } else if (c >= 0x80) {
                b.append(new String(d, i, 1, GROUP_1));
                i++;
            } else if (c == 0x14 && i + 2 < stop) {
                b.append(new String(d, i + 1, 2, UNICODE));
                i += 3;
            } else if (c >= 0x10 && c <= 0x13) {
                i = doubleByte(d, i, stop, b, lost);
            } else if (c == 0x0F && i + 1 < stop) {
                int next = d[i + 1] & 0xFF;
                if (next >= 0x20) {
                    b.append((char) (next < 0x80 ? next - 0x20 : next));
                }
                i += 2;
            } else if (group(c) != null && i + 1 < stop) {
                b.append(new String(d, i + 1, 1, group(c)));
                i += 2;
            } else {
                if (c == '\t' || c == '\n') {
                    b.append((char) c);
                }
                i++;
            }
        }
        return b.toString();
    }

    private static int doubleByte(byte[] d, int i, int stop, StringBuilder b, Runnable lost) {
        int c = d[i] & 0xFF;
        boolean single = i + 2 < stop && (d[i + 1] & 0xFF) == c;
        int length = single ? 1 : 2;
        int at = single ? i + 2 : i + 1;
        if (at + length > stop) {
            lost.run();
            return stop;
        }
        Charset cs = group(c);
        String s = cs == null ? "" : new String(d, at, length, cs);
        if (s.isEmpty() || s.indexOf('\uFFFD') >= 0) {
            lost.run();
        } else {
            b.append(s);
        }
        return at + length;
    }

    private static Charset group(int g) {
        String name = switch (g) {
            case 0x01 -> "IBM850";
            case 0x02 -> "x-IBM851";
            case 0x03 -> "windows-1255";
            case 0x04 -> "windows-1256";
            case 0x05 -> "windows-1251";
            case 0x06 -> "IBM852";
            case 0x08 -> "windows-1254";
            case 0x0B -> "x-IBM874";
            case 0x10 -> "MS932";
            case 0x11 -> "MS949";
            case 0x12 -> "MS950";
            case 0x13 -> "GBK";
            default -> null;
        };
        try {
            return name != null && Charset.isSupported(name) ? Charset.forName(name) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}

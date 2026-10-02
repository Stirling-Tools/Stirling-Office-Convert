package stirling.software.officeconvert.topdf.rtf;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

final class CodePages {

    static final Charset WINDOWS_1252 = forName("windows-1252", StandardCharsets.ISO_8859_1);

    private CodePages() {}

    static Charset forCharset(int charset) {
        return switch (charset) {
            case 0 -> WINDOWS_1252;
            case 77 -> forCodePage(10000);
            case 78 -> forCodePage(10001);
            case 79 -> forCodePage(10003);
            case 80 -> forCodePage(10008);
            case 81 -> forCodePage(10002);
            case 83 -> forCodePage(10005);
            case 84 -> forCodePage(10004);
            case 85 -> forCodePage(10006);
            case 86 -> forCodePage(10081);
            case 88 -> forCodePage(10029);
            case 89 -> forCodePage(10007);
            case 128 -> forCodePage(932);
            case 129 -> forCodePage(949);
            case 130 -> forCodePage(1361);
            case 134 -> forCodePage(936);
            case 136 -> forCodePage(950);
            case 161 -> forCodePage(1253);
            case 162 -> forCodePage(1254);
            case 163 -> forCodePage(1258);
            case 177 -> forCodePage(1255);
            case 178, 179, 180, 181 -> forCodePage(1256);
            case 186 -> forCodePage(1257);
            case 204 -> forCodePage(1251);
            case 222 -> forCodePage(874);
            case 238 -> forCodePage(1250);
            case 254 -> forCodePage(437);
            case 255 -> forCodePage(850);
            default -> null;
        };
    }

    static Charset forCodePage(int cp) {
        String name = switch (cp) {
            case 437 -> "IBM437";
            case 708 -> "ISO-8859-6";
            case 819 -> "ISO-8859-1";
            case 850 -> "IBM850";
            case 852 -> "IBM852";
            case 860 -> "IBM860";
            case 862 -> "IBM862";
            case 863 -> "IBM863";
            case 864 -> "IBM864";
            case 865 -> "IBM865";
            case 866 -> "IBM866";
            case 874 -> "x-windows-874";
            case 932 -> "windows-31j";
            case 936 -> "GBK";
            case 949 -> "x-windows-949";
            case 950 -> "x-windows-950";
            case 1200 -> "UTF-16LE";
            case 1250, 1251, 1252, 1253, 1254, 1255, 1256, 1257, 1258 -> "windows-" + cp;
            case 1361 -> "x-Johab";
            case 10000 -> "x-MacRoman";
            case 10001 -> "x-MacJapanese";
            case 10002 -> "x-MacChineseTrad";
            case 10003 -> "x-MacKorean";
            case 10004 -> "x-MacArabic";
            case 10005 -> "x-MacHebrew";
            case 10006 -> "x-MacGreek";
            case 10007 -> "x-MacCyrillic";
            case 10008 -> "x-MacChineseSimp";
            case 10029 -> "x-MacCentralEurope";
            case 10081 -> "x-MacTurkish";
            case 20127 -> "US-ASCII";
            case 20866 -> "KOI8-R";
            case 21866 -> "KOI8-U";
            case 28591, 28592, 28593, 28594, 28595, 28596, 28597, 28598, 28599 -> "ISO-8859-" + (cp - 28590);
            case 28605 -> "ISO-8859-15";
            case 54936 -> "GB18030";
            case 65001 -> "UTF-8";
            default -> null;
        };
        return name == null ? null : forName(name, null);
    }

    static boolean doubleByte(Charset cs) {
        String n = cs.name();
        return n.equals("windows-31j") || n.equals("GBK") || n.equals("x-windows-949") || n.equals("x-windows-950")
                || n.equals("Big5") || n.equals("x-Johab") || n.equals("GB18030") || n.equals("Shift_JIS");
    }

    static boolean pairs(Charset cs, byte[] bytes, int length) {
        String n = cs.name();
        boolean paired = false;
        int i = 0;
        while (i < length) {
            int b = bytes[i] & 0xFF;
            int unit = unit(n, bytes, i, length);
            if (unit == 0) {
                return false;
            }
            paired |= b >= 0x80 && unit > 1;
            i += unit;
        }
        return paired;
    }

    static int wholeLength(Charset cs, byte[] bytes, int length) {
        if (cs == null || !doubleByte(cs)) {
            return length;
        }
        String n = cs.name();
        int i = 0;
        while (i < length) {
            int b = bytes[i] & 0xFF;
            int width = b < 0x80 || single(n, b) || !lead(n, b) ? 1 : four(n, bytes, i, length) ? 4 : 2;
            if (i + width > length) {
                return i;
            }
            i += width;
        }
        return length;
    }

    private static int unit(String cs, byte[] bytes, int i, int length) {
        int b = bytes[i] & 0xFF;
        if (b < 0x80 || single(cs, b)) {
            return 1;
        }
        if (!lead(cs, b) || i + 1 >= length) {
            return 0;
        }
        if (four(cs, bytes, i, length)) {
            return i + 3 < length && lead(cs, bytes[i + 2] & 0xFF) && digit(bytes[i + 3] & 0xFF) ? 4 : 0;
        }
        return trail(cs, bytes[i + 1] & 0xFF) ? 2 : 0;
    }

    private static boolean single(String cs, int b) {
        return switch (cs) {
            case "windows-31j", "Shift_JIS" -> b >= 0xA1 && b <= 0xDF;
            case "GBK", "GB18030" -> b == 0x80;
            default -> false;
        };
    }

    private static boolean four(String cs, byte[] bytes, int i, int length) {
        return cs.equals("GB18030") && i + 1 < length && digit(bytes[i + 1] & 0xFF);
    }

    private static boolean digit(int b) {
        return b >= 0x30 && b <= 0x39;
    }

    private static boolean lead(String cs, int b) {
        return switch (cs) {
            case "windows-31j", "Shift_JIS" -> b >= 0x81 && b <= 0x9F || b >= 0xE0 && b <= 0xFC;
            case "x-Johab" -> b >= 0x84 && b <= 0xD3 || b >= 0xD8 && b <= 0xDE || b >= 0xE0 && b <= 0xF9;
            default -> b >= 0x81 && b <= 0xFE;
        };
    }

    private static boolean trail(String cs, int b) {
        return switch (cs) {
            case "windows-31j", "Shift_JIS" -> b >= 0x40 && b <= 0xFC && b != 0x7F;
            case "x-windows-949" -> b >= 0x41 && b <= 0x5A || b >= 0x61 && b <= 0x7A || b >= 0x81 && b <= 0xFE;
            case "x-windows-950", "Big5" -> b >= 0x40 && b <= 0x7E || b >= 0xA1 && b <= 0xFE;
            case "x-Johab" -> b >= 0x31 && b <= 0x7E || b >= 0x81 && b <= 0xFE;
            default -> b >= 0x40 && b <= 0xFE && b != 0x7F;
        };
    }

    private static Charset forName(String name, Charset fallback) {
        try {
            return Charset.isSupported(name) ? Charset.forName(name) : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}

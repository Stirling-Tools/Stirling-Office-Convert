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

    private static Charset forName(String name, Charset fallback) {
        try {
            return Charset.isSupported(name) ? Charset.forName(name) : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}

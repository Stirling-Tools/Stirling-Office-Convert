package stirling.software.officeconvert.topdf.doc6;

import java.nio.charset.Charset;
import java.util.List;

/** The code page a Word 6 document's 8-bit text is in: Macintosh for a Mac file, else the Windows code page of its
 * language, or of the first font with a non-Western character set. */
final class CodePages {

    private CodePages() {}

    static Charset of(Fib6 fib, List<Integer> fontCharsets) {
        if (fib.chse == 256 || fib.envr == 1) {
            return charset("x-MacRoman");
        }
        int cp = byLanguage(fib.lid);
        if (cp == 1252) {
            for (int chs : fontCharsets) {
                int c = byCharset(chs);
                if (c != 0) {
                    cp = c;
                    break;
                }
            }
        }
        return charset(cp == 1252 ? "windows-1252" : cp >= 1250 && cp <= 1258 ? "windows-" + cp
                : cp == 874 ? "x-windows-874" : cp == 932 ? "MS932" : cp == 936 ? "GBK" : cp == 949 ? "MS949"
                        : cp == 950 ? "Big5" : "windows-1252");
    }

    private static int byLanguage(int lid) {
        int primary = lid & 0x3FF;
        return switch (primary) {
            case 0x05, 0x0E, 0x15, 0x1B, 0x18, 0x24, 0x1C -> 1250;
            case 0x1A -> lid == 0x0C1A ? 1251 : 1250;
            case 0x19, 0x22, 0x23, 0x02, 0x2F -> 1251;
            case 0x08 -> 1253;
            case 0x1F -> 1254;
            case 0x0D -> 1255;
            case 0x01 -> 1256;
            case 0x25, 0x26, 0x27 -> 1257;
            case 0x2A -> 1258;
            case 0x1E -> 874;
            case 0x11 -> 932;
            case 0x12 -> 949;
            case 0x04 -> lid == 0x0804 || lid == 0x1004 ? 936 : 950;
            default -> 1252;
        };
    }

    private static int byCharset(int chs) {
        return switch (chs) {
            case 238 -> 1250;
            case 204 -> 1251;
            case 161 -> 1253;
            case 162 -> 1254;
            case 177 -> 1255;
            case 178 -> 1256;
            case 186 -> 1257;
            case 163 -> 1258;
            case 222 -> 874;
            case 128 -> 932;
            case 134 -> 936;
            case 129 -> 949;
            case 136 -> 950;
            default -> 0;
        };
    }

    private static Charset charset(String name) {
        try {
            return Charset.forName(name);
        } catch (RuntimeException e) {
            return Charset.forName("windows-1252");
        }
    }
}

package stirling.software.officeconvert.topdf.biff5;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** 8-bit strings in the workbook's code page (the CODEPAGE record), as BIFF5 stores all text. */
final class Text {

    private Charset charset = Charset.forName("windows-1252");

    void codePage(int cp) {
        String name = switch (cp) {
            case 367 -> "US-ASCII";
            case 1200 -> "UTF-16LE";
            case 0x8000, 10000 -> "x-MacRoman";
            case 0x8001 -> "windows-1252";
            case 932 -> "MS932";
            case 936 -> "GBK";
            case 949 -> "MS949";
            case 950 -> "Big5";
            default -> cp >= 1250 && cp <= 1258 ? "windows-" + cp : cp > 0 ? "Cp" + cp : null;
        };
        if (name == null) {
            return;
        }
        try {
            charset = Charset.forName(name);
        } catch (RuntimeException e) {
            charset = Charset.forName("windows-1252");
        }
    }

    String decode(byte[] bytes) {
        return charset == StandardCharsets.UTF_16LE ? new String(bytes, StandardCharsets.UTF_16LE)
                : new String(bytes, charset);
    }

    /** A string of {@code length} bytes at {@code at} in the current record. */
    String read(Stream s, int at, int length) {
        return decode(s.bytes(at, length));
    }
}

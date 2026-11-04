package stirling.software.officeconvert.topdf.rtf;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

final class Picture {

    static final int MAX_BYTES = 64 << 20;
    private static final byte[] HEX_DIGITS = hexDigits();

    String type;
    int picw;
    int pich;
    int goalw;
    int goalh;
    int scalex = 100;
    int scaley = 100;
    int cropl;
    int cropr;
    int cropt;
    int cropb;
    int mapMode;
    int bitsPixel = 1;
    int widthBytes;

    final ByteArrayOutputStream data = new ByteArrayOutputStream();

    private int nibble = -1;

    private byte[] decoded;

    boolean truncated;

    boolean word(String word, int param) {
        switch (word) {
            case "pngblip" -> type = "png";
            case "jpegblip" -> type = "jpeg";
            case "emfblip" -> type = "emf";
            case "wmetafile" -> {
                type = "wmf";
                mapMode = param;
            }
            case "dibitmap" -> type = "dib";
            case "macpict" -> type = "pict";
            case "wbitmap" -> type = "wbitmap";
            case "wbmbitspixel" -> bitsPixel = param;
            case "wbmwidthbytes" -> widthBytes = param;
            case "pmmetafile" -> type = "unsupported";
            case "picw" -> picw = param;
            case "pich" -> pich = param;
            case "picwgoal" -> goalw = param;
            case "pichgoal" -> goalh = param;
            case "picscalex" -> scalex = param;
            case "picscaley" -> scaley = param;
            case "piccropl" -> cropl = param;
            case "piccropr" -> cropr = param;
            case "piccropt" -> cropt = param;
            case "piccropb" -> cropb = param;
            default -> {
                return false;
            }
        }
        return true;
    }

    void hex(int c) {
        int v = Character.digit(c, 16);
        if (v < 0) {
            return;
        }
        if (nibble < 0) {
            nibble = v;
            return;
        }
        if (data.size() < MAX_BYTES) {
            data.write(nibble << 4 | v);
        } else {
            truncated = true;
        }
        nibble = -1;
    }

    void hex(byte[] text, int offset, int length) {
        if (length == 0) {
            return;
        }
        if (decoded == null) {
            decoded = new byte[8192];
        }
        int count = 0;
        int remaining = MAX_BYTES - data.size();
        for (int i = offset; i < offset + length; i++) {
            int value = HEX_DIGITS[text[i] & 0xFF];
            if (value < 0) {
                continue;
            }
            if (nibble < 0) {
                nibble = value;
                continue;
            }
            if (remaining > 0) {
                decoded[count++] = (byte) (nibble << 4 | value);
                remaining--;
                if (count == decoded.length) {
                    data.write(decoded, 0, count);
                    count = 0;
                }
            } else {
                truncated = true;
            }
            nibble = -1;
        }
        data.write(decoded, 0, count);
    }

    private static byte[] hexDigits() {
        byte[] digits = new byte[256];
        Arrays.fill(digits, (byte) -1);
        String lower = "0123456789abcdef";
        String upper = "0123456789ABCDEF";
        for (int i = 0; i < 16; i++) {
            digits[lower.charAt(i)] = (byte) i;
            digits[upper.charAt(i)] = (byte) i;
        }
        return digits;
    }

    long room() {
        return Math.max(0, MAX_BYTES - data.size());
    }
}

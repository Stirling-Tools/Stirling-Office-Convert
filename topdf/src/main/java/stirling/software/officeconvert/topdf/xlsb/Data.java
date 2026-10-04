package stirling.software.officeconvert.topdf.xlsb;

import java.nio.charset.StandardCharsets;

/** Little-endian reads over one record's data; reading past its end yields zeros and empty strings, and marks it. */
final class Data {

    private final byte[] b;

    private final int end;

    private int pos;

    private boolean overrun;

    Data(byte[] b, int size) {
        this.b = b;
        this.end = size;
    }

    int remaining() {
        return Math.max(0, end - pos);
    }

    boolean overrun() {
        return overrun;
    }

    void skip(int n) {
        if (n < 0 || n > remaining()) {
            overrun = true;
            pos = end;
            return;
        }
        pos += n;
    }

    int u8() {
        if (pos + 1 > end) {
            overrun = true;
            pos = end;
            return 0;
        }
        return b[pos++] & 0xFF;
    }

    int u16() {
        if (pos + 2 > end) {
            overrun = true;
            pos = end;
            return 0;
        }
        int v = (b[pos] & 0xFF) | (b[pos + 1] & 0xFF) << 8;
        pos += 2;
        return v;
    }

    int i16() {
        return (short) u16();
    }

    int i32() {
        if (pos + 4 > end) {
            overrun = true;
            pos = end;
            return 0;
        }
        int v = (b[pos] & 0xFF) | (b[pos + 1] & 0xFF) << 8 | (b[pos + 2] & 0xFF) << 16 | (b[pos + 3] & 0xFF) << 24;
        pos += 4;
        return v;
    }

    long u32() {
        return i32() & 0xFFFFFFFFL;
    }

    double f64() {
        long lo = u32();
        long hi = u32();
        return Double.longBitsToDouble(hi << 32 | lo);
    }

    String string() {
        long n = u32();
        if (n == 0xFFFFFFFFL || n == 0) {
            return "";
        }
        if (n * 2 > remaining()) {
            overrun = true;
            pos = end;
            return "";
        }
        String s = new String(b, pos, (int) n * 2, StandardCharsets.UTF_16LE);
        pos += (int) n * 2;
        return s;
    }

    Color color() {
        int flags = u8();
        int index = u8();
        int tint = i16();
        int r = u8();
        int g = u8();
        int bl = u8();
        u8();
        return new Color(flags >> 1 & 0x7F, index, tint, r << 16 | g << 8 | bl);
    }

    /** A BrtColor: kind 0 automatic, 1 indexed, 2 RGB, 3 theme. */
    record Color(int kind, int index, int tint, int rgb) {

        String attributes() {
            String t = tint == 0 ? "" : " tint=\"" + (tint < 0 ? tint / 32768.0 : tint / 32767.0) + "\"";
            return switch (kind) {
                case 0 -> " auto=\"1\"";
                case 1 -> " indexed=\"" + index + "\"" + t;
                case 2 -> String.format(" rgb=\"FF%06X\"", rgb) + t;
                case 3 -> " theme=\"" + index + "\"" + t;
                default -> null;
            };
        }

        String element(String name) {
            String a = attributes();
            return a == null ? "" : "<" + name + a + "/>";
        }
    }
}

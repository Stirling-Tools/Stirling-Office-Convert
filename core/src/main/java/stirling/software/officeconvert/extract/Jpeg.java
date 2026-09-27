package stirling.software.officeconvert.extract;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class Jpeg {

    public record Frame(int width, int height, int components) {}

    private static final int SOI = 0xD8;
    private static final int EOI = 0xD9;
    private static final int SOS = 0xDA;

    private Jpeg() {}

    public static Frame frame(InputStream in) throws IOException {
        if (in.read() != 0xFF || in.read() != SOI) {
            return null;
        }
        while (true) {
            int m = marker(in);
            if (m < 0 || m == SOS || m == EOI) {
                return null;
            }
            if (standalone(m)) {
                continue;
            }
            int length = u16(in);
            if (length < 2) {
                return null;
            }
            if (isFrame(m)) {
                if (length < 8) {
                    return null;
                }
                in.read();
                int height = u16(in);
                int width = u16(in);
                int components = in.read();
                return width < 0 || height < 0 || components < 0 ? null : new Frame(width, height, components);
            }
            in.skipNBytes(length - 2);
        }
    }

    public static Frame frame(byte[] jpeg) {
        try {
            return frame(new java.io.ByteArrayInputStream(jpeg));
        } catch (IOException e) {
            return null;
        }
    }

    public static byte[] clean(byte[] jpeg) {
        if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != SOI) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(jpeg.length);
        out.write(0xFF);
        out.write(SOI);
        int i = 2;
        while (i < jpeg.length) {
            while (i < jpeg.length && (jpeg[i] & 0xFF) != 0xFF) {
                i++;
            }
            while (i < jpeg.length && (jpeg[i] & 0xFF) == 0xFF) {
                i++;
            }
            if (i >= jpeg.length) {
                break;
            }
            int m = jpeg[i++] & 0xFF;
            if (m == EOI) {
                break;
            }
            if (standalone(m)) {
                out.write(0xFF);
                out.write(m);
                continue;
            }
            if (i + 2 > jpeg.length) {
                return null;
            }
            int length = (jpeg[i] & 0xFF) << 8 | jpeg[i + 1] & 0xFF;
            if (length < 2 || i + length > jpeg.length) {
                return null;
            }
            if (kept(m, jpeg, i + 2, length - 2)) {
                out.write(0xFF);
                out.write(m);
                out.write(jpeg, i, length);
            }
            i += length;
            if (m == SOS) {
                int start = i;
                while (i + 1 < jpeg.length) {
                    int b = jpeg[i + 1] & 0xFF;
                    if ((jpeg[i] & 0xFF) == 0xFF && b != 0 && (b < 0xD0 || b > 0xD7) && b != 0xFF) {
                        break;
                    }
                    i++;
                }
                if (i + 1 >= jpeg.length) {
                    i = jpeg.length;
                }
                out.write(jpeg, start, i - start);
            }
        }
        out.write(0xFF);
        out.write(EOI);
        return out.toByteArray();
    }

    private static boolean kept(int m, byte[] b, int at, int length) {
        return switch (m) {
            case 0xE0 -> starts(b, at, length, "JFIF\0") || starts(b, at, length, "JFXX\0");
            case 0xE2 -> starts(b, at, length, "ICC_PROFILE\0");
            case 0xEE -> starts(b, at, length, "Adobe");
            case 0xDB, 0xC4, 0xDD, 0xCC, 0xDC, SOS -> true;
            default -> isFrame(m);
        };
    }

    private static boolean starts(byte[] b, int at, int length, String tag) {
        byte[] t = tag.getBytes(StandardCharsets.ISO_8859_1);
        if (length < t.length) {
            return false;
        }
        for (int k = 0; k < t.length; k++) {
            if (b[at + k] != t[k]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFrame(int m) {
        return m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC;
    }

    private static boolean standalone(int m) {
        return m == 0x01 || m >= 0xD0 && m <= 0xD7;
    }

    private static int marker(InputStream in) throws IOException {
        int b = in.read();
        while (b >= 0 && b != 0xFF) {
            b = in.read();
        }
        while (b == 0xFF) {
            b = in.read();
        }
        return b;
    }

    private static int u16(InputStream in) throws IOException {
        int hi = in.read();
        int lo = in.read();
        return hi < 0 || lo < 0 ? -1 : hi << 8 | lo;
    }
}

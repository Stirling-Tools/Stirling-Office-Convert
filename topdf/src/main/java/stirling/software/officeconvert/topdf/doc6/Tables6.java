package stirling.software.officeconvert.topdf.doc6;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The Word 6 font table and style sheet rewritten in Word 97's layout (16-bit names, wider style base), their
 * property modifiers translated. */
final class Tables6 {

    private static final int MAX_FONTS = 4096;

    private static final int MAX_STYLES = 4096;

    private Tables6() {}

    /** The fonts' character sets, in table order, for guessing the text's code page. */
    static List<Integer> charsets(Fib6 fib) {
        List<Integer> out = new ArrayList<>();
        for (int[] f : fonts(fib)) {
            out.add(f[1]);
        }
        return out;
    }

    private static List<int[]> fonts(Fib6 fib) {
        List<int[]> out = new ArrayList<>();
        if (!fib.present(15)) {
            return out;
        }
        int at = fib.fc(15) + 2;
        int end = fib.fc(15) + fib.lcb(15);
        while (at < end && out.size() < MAX_FONTS) {
            int size = fib.u8(at) + 1;
            if (size < 7 || at + size > end) {
                break;
            }
            out.add(new int[] {at, fib.u8(at + 4), size});
            at += size;
        }
        return out;
    }

    static byte[] fontTable(Fib6 fib, Charset charset) {
        List<int[]> fonts = fonts(fib);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        le16(out, fonts.size());
        le16(out, 0);
        for (int[] f : fonts) {
            int at = f[0];
            int nameEnd = at + 6;
            while (nameEnd < at + f[2] && fib.main[nameEnd] != 0) {
                nameEnd++;
            }
            String name = new String(fib.main, at + 6, nameEnd - (at + 6), f[1] == 2 ? StandardCharsets.ISO_8859_1
                    : charset);
            byte[] utf16 = (name + "\u0000").getBytes(StandardCharsets.UTF_16LE);
            int size = 6 + 10 + 24 + utf16.length;
            out.write(size - 1);
            out.write(fib.u8(at + 1));
            out.write(fib.u8(at + 2));
            out.write(fib.u8(at + 3));
            out.write(fib.u8(at + 4));
            out.write(0);
            out.writeBytes(new byte[34]);
            out.writeBytes(utf16);
        }
        return out.toByteArray();
    }

    static byte[] styleSheet(Fib6 fib, Charset charset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!fib.present(1)) {
            return null;
        }
        byte[] m = fib.main;
        int start = fib.fc(1);
        int end = start + fib.lcb(1);
        int cbStshi = fib.u16(start);
        int stshi = start + 2;
        int cstd = Math.min(MAX_STYLES, fib.u16(stshi));
        int base = fib.u16(stshi + 2);
        if (base < 4 || base > 64) {
            return null;
        }
        le16(out, 18);
        le16(out, cstd);
        le16(out, 10);
        le16(out, fib.u16(stshi + 4));
        le16(out, fib.u16(stshi + 6));
        le16(out, fib.u16(stshi + 8));
        le16(out, fib.u16(stshi + 10));
        int ftc = cbStshi >= 14 ? fib.u16(stshi + 12) : 0;
        le16(out, ftc);
        le16(out, ftc);
        le16(out, ftc);
        int at = stshi + cbStshi;
        for (int i = 0; i < cstd; i++) {
            if (at + 2 > end) {
                le16(out, 0);
                continue;
            }
            int cb = fib.u16(at);
            int std = at + 2;
            at = std + cb;
            if (cb == 0 || at > end) {
                le16(out, 0);
                continue;
            }
            byte[] converted = style(m, std, cb, base, charset);
            le16(out, converted.length);
            out.writeBytes(converted);
        }
        return out.toByteArray();
    }

    private static byte[] style(byte[] m, int std, int cb, int base, Charset charset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int w0 = u16(m, std);
        int w1 = u16(m, std + 2);
        int w2 = u16(m, std + 4);
        int sgc = w1 & 0x0F;
        int cupx = w2 & 0x0F;
        le16(out, w0);
        le16(out, w1);
        le16(out, w2);
        le16(out, 0);
        le16(out, 0);
        int p = std + base;
        int len = p < std + cb ? m[p] & 0xFF : 0;
        String name = p + 1 + len <= std + cb ? new String(m, p + 1, len, charset) : "";
        le16(out, name.length());
        out.writeBytes(name.getBytes(StandardCharsets.UTF_16LE));
        le16(out, 0);
        p += len + 2;
        if (((p - std) & 1) != 0) {
            p++;
        }
        for (int u = 0; u < cupx && p + 2 <= std + cb; u++) {
            int size = u16(m, p);
            int data = p + 2;
            if (data + size > std + cb) {
                break;
            }
            byte[] upx;
            if (sgc == 1 && u == 0 && size >= 2) {
                byte[] g = Sprms6.translate(m, data + 2, data + size);
                upx = new byte[2 + g.length];
                upx[0] = m[data];
                upx[1] = m[data + 1];
                System.arraycopy(g, 0, upx, 2, g.length);
            } else {
                upx = Sprms6.translate(m, data, data + size);
            }
            le16(out, upx.length);
            out.writeBytes(upx);
            if ((upx.length & 1) != 0) {
                out.write(0);
            }
            p = data + size;
            if ((size & 1) != 0) {
                p++;
            }
        }
        return out.toByteArray();
    }

    static int u16(byte[] m, int at) {
        return at >= 0 && at + 1 < m.length ? (m[at] & 0xFF) | (m[at + 1] & 0xFF) << 8 : 0;
    }

    static void le16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write(v >> 8 & 0xFF);
    }

    static void le32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write(v >> 8 & 0xFF);
        out.write(v >> 16 & 0xFF);
        out.write(v >> 24 & 0xFF);
    }
}

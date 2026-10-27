package stirling.software.officeconvert.lineart;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

final class Jbig2Generic {

    static final byte[] AT = {3, -1, -3, -1, 2, -2, -2, -2};

    private static final int TYPICAL_CONTEXT = 0x9B25;

    private Jbig2Generic() {}

    static byte[] encode(Bitmap bitmap) {
        int w = bitmap.width();
        int h = bitmap.height();
        ByteArrayOutputStream s = new ByteArrayOutputStream(bitmap.packed().length / 16 + 64);
        pageInformation(s, w, h);
        region(s, 1, bitmap.packed(), bitmap.stride(), w, h, 0, 0);
        return s.toByteArray();
    }

    static void pageInformation(ByteArrayOutputStream s, int w, int h) {
        header(s, 0, 48, 19, -1);
        int32(s, w);
        int32(s, h);
        int32(s, 0);
        int32(s, 0);
        s.write(0);
        s.write(0);
        s.write(0);
    }

    static void region(ByteArrayOutputStream s, int number, byte[] bits, int stride, int w, int h, int x, int y) {
        MqEncoder coder = new MqEncoder(bits.length / 8);
        generic(coder, new MqEncoder.Contexts(1 << 16), bits, stride, w, h, true);
        byte[] data = coder.finish();
        header(s, number, 38, 17 + 1 + AT.length + data.length, -1);
        regionInformation(s, w, h, x, y);
        s.write(0x08);
        s.writeBytes(AT);
        s.writeBytes(data);
    }

    static void regionInformation(ByteArrayOutputStream s, int w, int h, int x, int y) {
        int32(s, w);
        int32(s, h);
        int32(s, x);
        int32(s, y);
        s.write(0);
    }

    static void header(ByteArrayOutputStream s, int number, int type, int length, int referred) {
        int32(s, number);
        s.write(type);
        if (referred < 0) {
            s.write(0);
        } else {
            s.write(0x20);
            s.write(referred);
        }
        s.write(1);
        int32(s, length);
    }

    static void int32(ByteArrayOutputStream s, int v) {
        s.write(v >>> 24);
        s.write(v >>> 16);
        s.write(v >>> 8);
        s.write(v);
    }

    static void generic(MqEncoder coder, MqEncoder.Contexts gb, byte[] bits, int stride, int w, int h, boolean typical) {
        boolean previousTypical = false;
        for (int y = 0; y < h; y++) {
            LineArt.checkpoint();
            int row = y * stride;
            if (typical) {
                boolean same = y == 0 ? emptyRow(bits, row, stride) : sameRow(bits, row, row - stride, stride);
                coder.encode(gb, TYPICAL_CONTEXT, same != previousTypical ? 1 : 0);
                previousTypical = same;
                if (same) {
                    continue;
                }
            }
            int above = y >= 1 ? row - stride : -1;
            int above2 = y >= 2 ? row - 2 * stride : -1;
            int line1 = pixel(bits, above, 0, w) << 3 | pixel(bits, above, 1, w) << 2 | pixel(bits, above, 2, w) << 1
                    | pixel(bits, above, 3, w);
            int line2 = pixel(bits, above2, 0, w) << 2 | pixel(bits, above2, 1, w) << 1 | pixel(bits, above2, 2, w);
            int line0 = 0;
            for (int x = 0; x < w; x++) {
                int bit = (bits[row + (x >>> 3)] >>> (7 - (x & 7))) & 1;
                coder.encode(gb, line0 | line1 << 4 | line2 << 11, bit);
                line0 = (line0 << 1 | bit) & 0xF;
                line1 = (line1 << 1 | pixel(bits, above, x + 4, w)) & 0x7F;
                line2 = (line2 << 1 | pixel(bits, above2, x + 3, w)) & 0x1F;
            }
        }
    }

    private static int pixel(byte[] bits, int row, int x, int w) {
        if (row < 0 || x >= w) {
            return 0;
        }
        return (bits[row + (x >>> 3)] >>> (7 - (x & 7))) & 1;
    }

    private static boolean emptyRow(byte[] bits, int row, int stride) {
        for (int i = 0; i < stride; i++) {
            if (bits[row + i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameRow(byte[] bits, int row, int previous, int stride) {
        return Arrays.equals(bits, row, row + stride, bits, previous, previous + stride);
    }
}

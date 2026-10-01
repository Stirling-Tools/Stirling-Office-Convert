package stirling.software.officeconvert.topdf.rtf;

final class WindowsBitmap {

    static final long MAX_PIXELS = 32_000_000L;

    private WindowsBitmap() {}

    static byte[] bmp(byte[] bits, int width, int height, int bitsPixel, int widthBytes) {
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS
                || bitsPixel != 1 && bitsPixel != 24 && bitsPixel != 32) {
            return null;
        }
        int stride = widthBytes > 0 ? widthBytes : ((width * bitsPixel + 15) / 16) * 2;
        if ((long) stride * 8 < (long) width * bitsPixel || (long) stride * height > bits.length) {
            return null;
        }
        int out = ((width * bitsPixel + 31) / 32) * 4;
        int palette = bitsPixel == 1 ? 8 : 0;
        int offset = 14 + 40 + palette;
        long total = offset + (long) out * height;
        if (total > Integer.MAX_VALUE) {
            return null;
        }
        byte[] b = new byte[(int) total];
        b[0] = 'B';
        b[1] = 'M';
        put32(b, 2, b.length);
        put32(b, 10, offset);
        put32(b, 14, 40);
        put32(b, 18, width);
        put32(b, 22, height);
        b[26] = 1;
        b[28] = (byte) bitsPixel;
        put32(b, 34, out * height);
        if (bitsPixel == 1) {
            put32(b, 46, 2);
            b[58] = (byte) 0xFF;
            b[59] = (byte) 0xFF;
            b[60] = (byte) 0xFF;
        }
        int copy = Math.min(stride, out);
        for (int y = 0; y < height; y++) {
            System.arraycopy(bits, y * stride, b, offset + (height - 1 - y) * out, copy);
        }
        return b;
    }

    private static void put32(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
        b[at + 2] = (byte) (v >> 16);
        b[at + 3] = (byte) (v >> 24);
    }
}

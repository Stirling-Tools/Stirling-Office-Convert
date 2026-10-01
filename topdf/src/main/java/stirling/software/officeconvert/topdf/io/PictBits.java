package stirling.software.officeconvert.topdf.io;

import java.awt.Color;
import java.awt.image.BufferedImage;

final class PictBits {

    record Image(BufferedImage image, int srcLeft, int srcTop, int srcRight, int srcBottom, int dstLeft, int dstTop,
            int dstRight, int dstBottom, boolean opaque) {}

    private PictBits() {}

    private record Map(int rb, int top, int left, int width, int height, boolean pixmap, int packType, int pixelSize,
            int cmpCount, int[] palette) {}

    static Image read(PictReader in, int opcode, Color fg, Color bg) {
        boolean direct = opcode == 0x9A || opcode == 0x9B;
        boolean region = opcode == 0x91 || opcode == 0x99 || opcode == 0x9B;
        if (direct) {
            in.skip(4);
        }
        Map map = map(in, direct, !direct);
        int sTop = in.s16();
        int sLeft = in.s16();
        int sBottom = in.s16();
        int sRight = in.s16();
        int dTop = in.s16();
        int dLeft = in.s16();
        int dBottom = in.s16();
        int dRight = in.s16();
        int mode = in.u16();
        if (region) {
            int size = in.u16();
            in.skip(Math.max(0, size - 2));
        }
        boolean opaque = map.pixmap() || mode == 0 || mode == 8;
        BufferedImage image = pixels(in, map, fg, bg, opaque);
        return new Image(image, sLeft - map.left(), sTop - map.top(), sRight - map.left(), sBottom - map.top(), dLeft,
                dTop, dRight, dBottom, opaque);
    }

    static BufferedImage pattern(PictReader in, Color fg, Color bg) {
        return pixels(in, map(in, false, true), fg, bg, true);
    }

    private static Map map(PictReader in, boolean direct, boolean table) {
        int rowBytes = in.u16();
        boolean pixmap = direct || (rowBytes & 0x8000) != 0;
        int rb = rowBytes & 0x3FFF;
        int top = in.s16();
        int left = in.s16();
        int bottom = in.s16();
        int right = in.s16();
        int packType = 0;
        int pixelSize = 1;
        int cmpCount = 1;
        if (pixmap) {
            in.skip(2);
            packType = in.u16();
            in.skip(14);
            pixelSize = in.u16();
            cmpCount = in.u16();
            in.skip(14);
        }
        if (packType == 0 && pixelSize == 32) {
            packType = 4;
        } else if (packType == 0 && pixelSize == 16) {
            packType = 3;
        }
        int[] palette = pixmap && table ? colorTable(in) : null;
        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0 || (long) width * height > PictureDecoder.DECODE_PIXELS || rb <= 0
                || rb > Pict.MAX_ROW_BYTES || cmpCount < 1 || cmpCount > 4) {
            throw new IllegalStateException("bitmap out of range");
        }
        if (pixelSize != 32 && (long) rb * 8 < (long) width * pixelSize) {
            throw new IllegalStateException("bitmap rows too short");
        }
        return new Map(rb, top, left, width, height, pixmap, packType, pixelSize, cmpCount, palette);
    }

    private static BufferedImage pixels(PictReader in, Map m, Color fg, Color bg, boolean opaque) {
        int width = m.width();
        BufferedImage image = new BufferedImage(width, m.height(),
                opaque ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        byte[] row = new byte[Math.max(m.rb(), m.pixelSize() == 32 ? width * Math.max(3, m.cmpCount()) : m.rb())];
        for (int y = 0; y < m.height(); y++) {
            int len = rowData(in, row, m.rb(), m.packType(), m.pixelSize(), m.cmpCount(), width);
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, pixel(row, len, x, width, m.pixelSize(), m.packType(), m.cmpCount(), m.palette(),
                        m.pixmap(), fg, bg, opaque));
            }
        }
        return image;
    }

    private static int[] colorTable(PictReader in) {
        in.skip(4);
        int flags = in.u16();
        int size = in.u16() + 1;
        if (size > 256) {
            throw new IllegalStateException("colour table too large");
        }
        int[] palette = new int[256];
        for (int i = 0; i < size; i++) {
            int value = in.u16();
            int r = in.u16() >> 8;
            int g = in.u16() >> 8;
            int b = in.u16() >> 8;
            int index = (flags & 0x8000) != 0 ? i : value & 0xFF;
            palette[index] = r << 16 | g << 8 | b;
        }
        return palette;
    }

    private static int rowData(PictReader in, byte[] row, int rb, int packType, int pixelSize, int cmpCount,
            int width) {
        if (pixelSize == 32 && packType == 2) {
            int n = width * 3;
            in.read(row, 0, n);
            return n;
        }
        if (rb < 8 || packType == 1) {
            in.read(row, 0, rb);
            return rb;
        }
        int count = rb > 250 ? in.u16() : in.u8();
        int end = in.position() + count;
        int expect = pixelSize == 32 ? width * cmpCount : rb;
        int n = pixelSize == 16 && packType == 3 ? unpackWords(in, end, row, expect) : unpack(in, end, row, expect);
        in.seek(end);
        return n;
    }

    private static int unpack(PictReader in, int end, byte[] out, int max) {
        int n = 0;
        while (in.position() < end && n < max) {
            int c = (byte) in.u8();
            if (c >= 0) {
                for (int i = 0; i <= c && n < max && in.position() < end; i++) {
                    out[n++] = (byte) in.u8();
                }
            } else if (c != -128) {
                byte b = (byte) in.u8();
                for (int i = 0; i < 1 - c && n < max; i++) {
                    out[n++] = b;
                }
            }
        }
        return n;
    }

    private static int unpackWords(PictReader in, int end, byte[] out, int max) {
        int n = 0;
        while (in.position() < end && n + 1 < max) {
            int c = (byte) in.u8();
            if (c >= 0) {
                for (int i = 0; i <= c && n + 1 < max && in.position() + 1 < end; i++) {
                    out[n++] = (byte) in.u8();
                    out[n++] = (byte) in.u8();
                }
            } else if (c != -128) {
                byte hi = (byte) in.u8();
                byte lo = (byte) in.u8();
                for (int i = 0; i < 1 - c && n + 1 < max; i++) {
                    out[n++] = hi;
                    out[n++] = lo;
                }
            }
        }
        return n;
    }

    private static int pixel(byte[] row, int len, int x, int width, int pixelSize, int packType, int cmpCount,
            int[] palette, boolean pixmap, Color fg, Color bg, boolean opaque) {
        switch (pixelSize) {
            case 32 -> {
                if (packType == 4) {
                    int skip = cmpCount >= 4 ? width : 0;
                    return at(row, len, skip + x) << 16 | at(row, len, skip + width + x) << 8
                            | at(row, len, skip + 2 * width + x);
                }
                if (packType == 2) {
                    return at(row, len, 3 * x) << 16 | at(row, len, 3 * x + 1) << 8 | at(row, len, 3 * x + 2);
                }
                return at(row, len, 4 * x + 1) << 16 | at(row, len, 4 * x + 2) << 8 | at(row, len, 4 * x + 3);
            }
            case 16 -> {
                int v = at(row, len, 2 * x) << 8 | at(row, len, 2 * x + 1);
                return expand5(v >> 10) << 16 | expand5(v >> 5) << 8 | expand5(v);
            }
            case 1, 2, 4, 8 -> {
                int perByte = 8 / pixelSize;
                int b = at(row, len, x / perByte);
                int shift = 8 - pixelSize * (x % perByte + 1);
                int index = b >> shift & (1 << pixelSize) - 1;
                if (pixmap && palette != null) {
                    return palette[index];
                }
                Color c = index == 0 ? bg : fg;
                return index == 0 && !opaque ? 0 : 0xFF000000 | c.getRGB();
            }
            default -> {
                return 0;
            }
        }
    }

    private static int expand5(int v) {
        int c = v & 0x1F;
        return c << 3 | c >> 2;
    }

    private static int at(byte[] row, int len, int i) {
        return i < len ? row[i] & 0xFF : 0;
    }
}

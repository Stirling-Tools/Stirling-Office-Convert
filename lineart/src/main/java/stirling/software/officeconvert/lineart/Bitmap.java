package stirling.software.officeconvert.lineart;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.Arrays;

public final class Bitmap {

    private final int width;
    private final int height;
    private final int stride;
    private final byte[] bits;
    private final int inkRgb;

    Bitmap(int width, int height, byte[] bits, int inkRgb) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("A bitmap needs a positive size, was " + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.stride = (width + 7) >>> 3;
        if (bits.length != (long) stride * height) {
            throw new IllegalArgumentException("Expected " + (long) stride * height + " bytes, got " + bits.length);
        }
        this.bits = bits;
        this.inkRgb = inkRgb & 0xFFFFFF;
    }

    static Bitmap blank(int width, int height) {
        return new Bitmap(width, height, new byte[((width + 7) >>> 3) * height], 0);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int stride() {
        return stride;
    }

    public boolean ink(int x, int y) {
        return (bits[y * stride + (x >>> 3)] & (0x80 >>> (x & 7))) != 0;
    }

    public int inkRgb() {
        return inkRgb;
    }

    public long inkCount() {
        long n = 0;
        int tail = width & 7;
        int last = tail == 0 ? 0xFF : 0xFF00 >>> tail & 0xFF;
        for (int y = 0; y < height; y++) {
            int row = y * stride;
            for (int i = 0; i < stride; i++) {
                int b = bits[row + i] & 0xFF;
                n += Integer.bitCount(i == stride - 1 ? b & last : b);
            }
        }
        return n;
    }

    public byte[] rows() {
        return bits.clone();
    }

    byte[] packed() {
        return bits;
    }

    Bitmap withInk(int rgb) {
        return new Bitmap(width, height, bits, rgb);
    }

    public BufferedImage toImage() {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < bits.length; i++) {
            data[i] = (byte) ~bits[i];
        }
        return image;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Bitmap other) || other.width != width || other.height != height) {
            return false;
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (ink(x, y) != other.ink(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * width + height) + Arrays.hashCode(bits);
    }
}

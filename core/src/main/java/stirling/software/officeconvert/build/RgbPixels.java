package stirling.software.officeconvert.build;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;

public final class RgbPixels {

    private static final ColorModel RGB = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), false,
            false, Transparency.OPAQUE, DataBuffer.TYPE_BYTE);

    private RgbPixels() {}

    public static BufferedImage forJpeg(BufferedImage img) {
        return img.getType() == BufferedImage.TYPE_BYTE_GRAY ? img : onWhite(img);
    }

    public static BufferedImage onWhite(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        byte[] px = new byte[Math.multiplyExact(Math.multiplyExact(w, h), 3)];
        WritableRaster src = img.getRaster();
        int type = img.getType();
        boolean whole = src.getParent() == null && src.getSampleModelTranslateX() == 0
                && src.getSampleModelTranslateY() == 0;
        if (whole && src.getDataBuffer() instanceof DataBufferInt ints && ints.getNumBanks() == 1
                && src.getSampleModel() instanceof SinglePixelPackedSampleModel sm
                && (type == BufferedImage.TYPE_INT_RGB || type == BufferedImage.TYPE_INT_ARGB
                        || type == BufferedImage.TYPE_INT_ARGB_PRE)) {
            fromInts(ints.getData(), ints.getOffset(), sm.getScanlineStride(), w, h, type, px);
        } else if (whole && type == BufferedImage.TYPE_3BYTE_BGR && src.getDataBuffer() instanceof DataBufferByte bytes
                && bytes.getNumBanks() == 1 && src.getSampleModel() instanceof ComponentSampleModel sm) {
            fromBgr(bytes.getData(), bytes.getOffset(), sm.getScanlineStride(), w, h, px);
        } else {
            fromRgb(img, w, h, px);
        }
        WritableRaster raster = Raster.createInterleavedRaster(new DataBufferByte(px, px.length), w, h, w * 3, 3,
                new int[] {0, 1, 2}, null);
        return new BufferedImage(RGB, raster, false, null);
    }

    private static void fromInts(int[] data, int offset, int stride, int w, int h, int type, byte[] px) {
        boolean alpha = type != BufferedImage.TYPE_INT_RGB;
        boolean pre = type == BufferedImage.TYPE_INT_ARGB_PRE;
        int o = 0;
        for (int y = 0; y < h; y++) {
            int i = offset + y * stride;
            for (int x = 0; x < w; x++) {
                int p = data[i + x];
                int a = alpha ? p >>> 24 : 255;
                if (a == 255) {
                    px[o++] = (byte) (p >> 16);
                    px[o++] = (byte) (p >> 8);
                    px[o++] = (byte) p;
                } else if (pre) {
                    int white = 255 - a;
                    px[o++] = (byte) Math.min(255, ((p >> 16) & 0xFF) + white);
                    px[o++] = (byte) Math.min(255, ((p >> 8) & 0xFF) + white);
                    px[o++] = (byte) Math.min(255, (p & 0xFF) + white);
                } else {
                    px[o++] = (byte) blend((p >> 16) & 0xFF, a);
                    px[o++] = (byte) blend((p >> 8) & 0xFF, a);
                    px[o++] = (byte) blend(p & 0xFF, a);
                }
            }
        }
    }

    private static void fromBgr(byte[] data, int offset, int stride, int w, int h, byte[] px) {
        int o = 0;
        for (int y = 0; y < h; y++) {
            int i = offset + y * stride;
            for (int x = 0; x < w; x++, i += 3) {
                px[o++] = data[i + 2];
                px[o++] = data[i + 1];
                px[o++] = data[i];
            }
        }
    }

    private static void fromRgb(BufferedImage img, int w, int h, byte[] px) {
        int[] row = new int[w];
        int o = 0;
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                int a = p >>> 24;
                px[o++] = (byte) blend((p >> 16) & 0xFF, a);
                px[o++] = (byte) blend((p >> 8) & 0xFF, a);
                px[o++] = (byte) blend(p & 0xFF, a);
            }
        }
    }

    private static int blend(int c, int a) {
        return a == 255 ? c : (c * a + 255 * (255 - a) + 127) / 255;
    }
}

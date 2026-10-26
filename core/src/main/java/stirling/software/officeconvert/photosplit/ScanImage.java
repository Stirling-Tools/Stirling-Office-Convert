package stirling.software.officeconvert.photosplit;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.util.Arrays;

final class ScanImage {

    final int width;
    final int height;
    final int[] rgb;

    ScanImage(int width, int height, int[] rgb) {
        this.width = width;
        this.height = height;
        this.rgb = rgb;
    }

    static ScanImage of(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[] out = new int[Math.multiplyExact(w, h)];
        ColorModel cm = image.getColorModel();
        Raster raster = image.getRaster();
        int type = raster.getTransferType();
        if (cm instanceof ComponentColorModel && (type == DataBuffer.TYPE_BYTE || type == DataBuffer.TYPE_USHORT)
                && plainSpace(cm.getColorSpace())) {
            int bands = raster.getNumBands();
            boolean gray = cm.getNumColorComponents() == 1;
            boolean wide = type == DataBuffer.TYPE_USHORT;
            int[] row = new int[w * bands];
            for (int y = 0; y < h; y++) {
                raster.getPixels(0, y, w, 1, row);
                int o = y * w;
                for (int x = 0; x < w; x++) {
                    int p = x * bands;
                    if (gray) {
                        int g = eight(row[p], wide);
                        out[o + x] = g << 16 | g << 8 | g;
                    } else {
                        out[o + x] = eight(row[p], wide) << 16 | eight(row[p + 1], wide) << 8 | eight(row[p + 2], wide);
                    }
                }
            }
        } else {
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                image.getRGB(0, y, w, 1, row, 0, w);
                for (int x = 0; x < w; x++) {
                    out[y * w + x] = row[x] & 0xFFFFFF;
                }
            }
        }
        return new ScanImage(w, h, out);
    }

    private static boolean plainSpace(ColorSpace cs) {
        return cs.isCS_sRGB() || cs.getType() == ColorSpace.TYPE_GRAY;
    }

    private static int eight(int v, boolean wide) {
        return wide ? Math.min(255, (int) Math.rint(v / 256.0)) : v;
    }

    BufferedImage toImage() {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] data = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(rgb, 0, data, 0, rgb.length);
        return image;
    }

    ScanImage crop(int x, int y, int w, int h) {
        int[] out = new int[w * h];
        for (int r = 0; r < h; r++) {
            System.arraycopy(rgb, (y + r) * width + x, out, r * w, w);
        }
        return new ScanImage(w, h, out);
    }

    ScanImage pad(int border, int fill) {
        int w = width + 2 * border;
        int h = height + 2 * border;
        int[] out = new int[Math.multiplyExact(w, h)];
        Arrays.fill(out, fill);
        for (int r = 0; r < height; r++) {
            System.arraycopy(rgb, r * width, out, (r + border) * w + border, width);
        }
        return new ScanImage(w, h, out);
    }

    int[] gray() {
        int[] g = new int[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            int p = rgb[i];
            g[i] = ((p >> 16 & 0xFF) * 4899 + (p >> 8 & 0xFF) * 9617 + (p & 0xFF) * 1868 + 8192) >> 14;
        }
        return g;
    }
}

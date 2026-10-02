package stirling.software.officeconvert.topdf.pptx;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;

final class PixelRows {

    private PixelRows() {}

    static void read(BufferedImage src, int x, int y, int w, int[] row) {
        switch (src.getType()) {
            case BufferedImage.TYPE_INT_ARGB -> src.getRaster().getDataElements(x, y, w, 1, row);
            case BufferedImage.TYPE_INT_RGB -> {
                src.getRaster().getDataElements(x, y, w, 1, row);
                for (int i = 0; i < w; i++) {
                    row[i] = 0xFF000000 | row[i] & 0xFFFFFF;
                }
            }
            case BufferedImage.TYPE_3BYTE_BGR -> {
                byte[] rgb = (byte[]) src.getRaster().getDataElements(x, y, w, 1, null);
                for (int i = 0, j = 0; i < w; i++, j += 3) {
                    row[i] = 0xFF000000 | (rgb[j] & 0xFF) << 16 | (rgb[j + 1] & 0xFF) << 8 | rgb[j + 2] & 0xFF;
                }
            }
            case BufferedImage.TYPE_4BYTE_ABGR -> {
                byte[] rgba = (byte[]) src.getRaster().getDataElements(x, y, w, 1, null);
                for (int i = 0, j = 0; i < w; i++, j += 4) {
                    row[i] = (rgba[j + 3] & 0xFF) << 24 | (rgba[j] & 0xFF) << 16 | (rgba[j + 1] & 0xFF) << 8
                            | rgba[j + 2] & 0xFF;
                }
            }
            default -> {
                if (grey(src)) {
                    greyRow(src, x, y, w, row);
                } else {
                    src.getRGB(x, y, w, 1, row, 0, w);
                }
            }
        }
    }

    private static boolean grey(BufferedImage src) {
        ColorModel cm = src.getColorModel();
        Raster raster = src.getRaster();
        int transfer = raster.getTransferType();
        return cm instanceof ComponentColorModel && cm.getNumColorComponents() == 1
                && cm.getColorSpace().getType() == ColorSpace.TYPE_GRAY && !cm.isAlphaPremultiplied()
                && (transfer == DataBuffer.TYPE_BYTE || transfer == DataBuffer.TYPE_USHORT)
                && raster.getNumBands() == cm.getNumComponents() && cm.getComponentSize(0) >= 8
                && cm.getComponentSize(cm.getNumComponents() - 1) >= 8;
    }

    private static void greyRow(BufferedImage src, int x, int y, int w, int[] row) {
        Raster raster = src.getRaster();
        int bands = raster.getNumBands();
        int shift = src.getColorModel().getComponentSize(0) - 8;
        int alphaShift = src.getColorModel().getComponentSize(bands - 1) - 8;
        int[] s = raster.getPixels(x, y, w, 1, (int[]) null);
        for (int i = 0, j = 0; i < w; i++, j += bands) {
            int g = s[j] >> shift;
            int a = bands > 1 ? s[j + 1] >> alphaShift : 0xFF;
            row[i] = a << 24 | g * 0x010101;
        }
    }

    static void write(BufferedImage argb, int y, int w, int[] row) {
        argb.getRaster().setDataElements(0, y, w, 1, row);
    }
}

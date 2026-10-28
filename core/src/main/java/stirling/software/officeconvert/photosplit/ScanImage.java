package stirling.software.officeconvert.photosplit;

import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferInt;
import java.awt.image.Raster;
import java.awt.image.SinglePixelPackedSampleModel;

final class ScanImage implements Picture {

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
        if (image.getType() == BufferedImage.TYPE_INT_RGB && image.getRaster().getSampleModel() instanceof SinglePixelPackedSampleModel sm
                && sm.getScanlineStride() == w && image.getRaster().getSampleModelTranslateX() == 0
                && image.getRaster().getSampleModelTranslateY() == 0
                && image.getRaster().getDataBuffer() instanceof DataBufferInt data && data.getNumBanks() == 1
                && data.getOffset() == 0 && data.getData().length == w * h) {
            return new ScanImage(w, h, data.getData());
        }
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
                PhotoSplitter.checkpoint();
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
                PhotoSplitter.checkpoint();
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

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int rgb(int x, int y) {
        return rgb[y * width + x];
    }

    @Override
    public boolean gray() {
        return false;
    }

    static ScanImage copy(Picture picture) {
        int w = picture.width();
        int h = picture.height();
        int[] out = new int[Math.multiplyExact(w, h)];
        fill(picture, out);
        return new ScanImage(w, h, out);
    }

    static BufferedImage image(Picture picture) {
        BufferedImage image = new BufferedImage(picture.width(), picture.height(), BufferedImage.TYPE_INT_RGB);
        fill(picture, ((DataBufferInt) image.getRaster().getDataBuffer()).getData());
        return image;
    }

    private static void fill(Picture picture, int[] out) {
        int w = picture.width();
        int h = picture.height();
        for (int y = 0; y < h; y++) {
            PhotoSplitter.checkpoint();
            for (int x = 0; x < w; x++) {
                out[y * w + x] = picture.rgb(x, y);
            }
        }
    }
}

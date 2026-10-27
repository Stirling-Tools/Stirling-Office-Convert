package stirling.software.officeconvert.photosplit;

import java.awt.image.BufferedImage;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.SinglePixelPackedSampleModel;
import java.awt.image.WritableRaster;
import java.util.Arrays;

final class Source implements Picture {

    private final int width;
    private final int height;
    private final int[] ints;
    private final byte[] bytes;
    private final int stride;
    private final int pixelStride;
    private final int red;
    private final int green;
    private final int blue;
    private final boolean gray;

    private Source(int width, int height, int[] ints, byte[] bytes, int stride, int pixelStride, int red, int green, int blue,
            boolean gray) {
        this.width = width;
        this.height = height;
        this.ints = ints;
        this.bytes = bytes;
        this.stride = stride;
        this.pixelStride = pixelStride;
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.gray = gray;
    }

    static Source of(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        WritableRaster raster = image.getRaster();
        boolean origin = raster.getSampleModelTranslateX() == 0 && raster.getSampleModelTranslateY() == 0;
        int type = image.getType();
        if (origin && (type == BufferedImage.TYPE_INT_RGB || type == BufferedImage.TYPE_INT_ARGB)
                && raster.getSampleModel() instanceof SinglePixelPackedSampleModel sm
                && raster.getDataBuffer() instanceof DataBufferInt data && data.getNumBanks() == 1 && data.getOffset() == 0) {
            return new Source(w, h, data.getData(), null, sm.getScanlineStride(), 1, 0, 0, 0, false);
        }
        if (origin && (type == BufferedImage.TYPE_3BYTE_BGR || type == BufferedImage.TYPE_4BYTE_ABGR
                || type == BufferedImage.TYPE_BYTE_GRAY) && raster.getSampleModel() instanceof ComponentSampleModel sm
                && raster.getDataBuffer() instanceof DataBufferByte data && data.getNumBanks() == 1 && data.getOffset() == 0) {
            int[] offsets = sm.getBandOffsets();
            boolean grey = type == BufferedImage.TYPE_BYTE_GRAY;
            return new Source(w, h, null, data.getData(), sm.getScanlineStride(), sm.getPixelStride(), offsets[0],
                    grey ? offsets[0] : offsets[1], grey ? offsets[0] : offsets[2], grey);
        }
        ScanImage copy = ScanImage.of(image);
        return new Source(w, h, copy.rgb, null, w, 1, 0, 0, 0, grayModel(image));
    }

    private static boolean grayModel(BufferedImage image) {
        return image.getColorModel().getNumColorComponents() == 1;
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
    public boolean gray() {
        return gray;
    }

    @Override
    public int rgb(int x, int y) {
        if (ints != null) {
            return ints[y * stride + x] & 0xFFFFFF;
        }
        int i = y * stride + x * pixelStride;
        return (bytes[i + red] & 0xFF) << 16 | (bytes[i + green] & 0xFF) << 8 | bytes[i + blue] & 0xFF;
    }

    void taps(int bx, int by, int[] out) {
        boolean inside = bx >= 1 && by >= 1 && bx + 2 < width && by + 2 < height;
        if (inside && ints != null) {
            int base = (by - 1) * stride + bx - 1;
            for (int i = 0; i < 4; i++) {
                int row = base + i * stride;
                out[4 * i] = ints[row];
                out[4 * i + 1] = ints[row + 1];
                out[4 * i + 2] = ints[row + 2];
                out[4 * i + 3] = ints[row + 3];
            }
            return;
        }
        if (inside) {
            int base = (by - 1) * stride + (bx - 1) * pixelStride;
            for (int i = 0; i < 4; i++) {
                int row = base + i * stride;
                for (int j = 0; j < 4; j++) {
                    int k = row + j * pixelStride;
                    out[4 * i + j] = (bytes[k + red] & 0xFF) << 16 | (bytes[k + green] & 0xFF) << 8 | bytes[k + blue] & 0xFF;
                }
            }
            return;
        }
        for (int i = 0; i < 4; i++) {
            int yy = Math.clamp(by - 1 + i, 0, height - 1);
            for (int j = 0; j < 4; j++) {
                out[4 * i + j] = rgb(Math.clamp(bx - 1 + j, 0, width - 1), yy);
            }
        }
    }

    ScanImage shrink(int factor) {
        int w = (width + factor - 1) / factor;
        int h = (height + factor - 1) / factor;
        int[] out = new int[Math.multiplyExact(w, h)];
        int[] r = new int[w];
        int[] g = new int[w];
        int[] b = new int[w];
        int[] n = new int[w];
        for (int ty = 0; ty < h; ty++) {
            PhotoSplitter.checkpoint();
            Arrays.fill(r, 0);
            Arrays.fill(g, 0);
            Arrays.fill(b, 0);
            Arrays.fill(n, 0);
            for (int y = ty * factor; y < Math.min(height, (ty + 1) * factor); y++) {
                PhotoSplitter.checkpoint();
                int base = y * stride;
                for (int x = 0; x < width; x++) {
                    int p;
                    if (ints != null) {
                        p = ints[base + x];
                    } else {
                        int k = base + x * pixelStride;
                        p = (bytes[k + red] & 0xFF) << 16 | (bytes[k + green] & 0xFF) << 8 | bytes[k + blue] & 0xFF;
                    }
                    int tx = x / factor;
                    r[tx] += p >> 16 & 0xFF;
                    g[tx] += p >> 8 & 0xFF;
                    b[tx] += p & 0xFF;
                    n[tx]++;
                }
            }
            for (int tx = 0; tx < w; tx++) {
                int k = n[tx];
                out[ty * w + tx] = (r[tx] + k / 2) / k << 16 | (g[tx] + k / 2) / k << 8 | (b[tx] + k / 2) / k;
            }
        }
        return new ScanImage(w, h, out);
    }
}

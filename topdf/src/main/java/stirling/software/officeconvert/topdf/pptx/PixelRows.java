package stirling.software.officeconvert.topdf.pptx;

import java.awt.image.BufferedImage;

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
            default -> src.getRGB(x, y, w, 1, row, 0, w);
        }
    }

    static void write(BufferedImage argb, int y, int w, int[] row) {
        argb.getRaster().setDataElements(0, y, w, 1, row);
    }
}

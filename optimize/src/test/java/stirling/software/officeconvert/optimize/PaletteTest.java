package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.util.Random;

import org.junit.jupiter.api.Test;

class PaletteTest {

    private static BufferedImage noise(int type, int w, int h, int levels, long seed) {
        BufferedImage image = new BufferedImage(w, h, type);
        Random r = new Random(seed);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setRGB(x, y, r.nextInt(levels) * 255 / Math.max(1, levels - 1) << 16
                        | r.nextInt(levels) * 255 / Math.max(1, levels - 1) << 8 | r.nextInt(256));
            }
        }
        return image;
    }

    // The plain search the memo and early exit must agree with, ties going to the first entry
    private static int nearest(int[] palette, int rgb) {
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            int dr = (rgb >> 16 & 0xFF) - (palette[i] >> 16 & 0xFF);
            int dg = (rgb >> 8 & 0xFF) - (palette[i] >> 8 & 0xFF);
            int db = (rgb & 0xFF) - (palette[i] & 0xFF);
            int distance = 2 * dr * dr + 4 * dg * dg + 3 * db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    @Test
    void indicesMatchAPlainNearestColourSearch() {
        for (int colours : new int[] {2, 16, 256}) {
            BufferedImage image = noise(BufferedImage.TYPE_INT_RGB, 211, 97, 64, colours);
            int[] palette = Palette.medianCut(image, colours);
            byte[] expected = new byte[211 * 97];
            for (int y = 0; y < 97; y++) {
                for (int x = 0; x < 211; x++) {
                    expected[y * 211 + x] = (byte) nearest(palette, image.getRGB(x, y) & 0xFFFFFF);
                }
            }
            assertArrayEquals(expected, Palette.indices(image, palette));
        }
    }

    @Test
    void tiesGoToTheFirstPaletteEntry() {
        BufferedImage image = new BufferedImage(3, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x808080);
        image.setRGB(1, 0, 0x000000);
        image.setRGB(2, 0, 0xFFFFFF);
        int[] palette = {0x7F8080, 0x818080, 0x000000, 0x000000, 0xFFFFFF};
        assertArrayEquals(new byte[] {0, 2, 4}, Palette.indices(image, palette));
    }

    @Test
    void bulkRowsReadTheSameColoursAsSinglePixels() {
        byte[] grey = new byte[256];
        for (int i = 0; i < 256; i++) {
            grey[i] = (byte) (255 - i);
        }
        BufferedImage indexed = new BufferedImage(53, 21, BufferedImage.TYPE_BYTE_INDEXED,
                new IndexColorModel(8, 256, grey, grey, grey));
        for (BufferedImage image : new BufferedImage[] {noise(BufferedImage.TYPE_INT_RGB, 53, 21, 256, 1),
            noise(BufferedImage.TYPE_3BYTE_BGR, 53, 21, 256, 2), noise(BufferedImage.TYPE_BYTE_GRAY, 53, 21, 256, 3),
            noise(BufferedImage.TYPE_INT_ARGB, 53, 21, 256, 4), indexed}) {
            int[] row = new int[image.getWidth()];
            for (int y = 0; y < image.getHeight(); y++) {
                Palette.rgbRow(image, y, row);
                for (int x = 0; x < image.getWidth(); x++) {
                    assertEquals(image.getRGB(x, y), row[x]);
                }
            }
        }
    }
}

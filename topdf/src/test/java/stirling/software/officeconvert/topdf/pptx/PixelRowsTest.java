package stirling.software.officeconvert.topdf.pptx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.awt.image.BufferedImage;
import java.util.Random;

import org.junit.jupiter.api.Test;

class PixelRowsTest {

    private static final int[] TYPES = {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_RGB,
        BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_4BYTE_ABGR, BufferedImage.TYPE_INT_ARGB_PRE,
        BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_USHORT_565_RGB, BufferedImage.TYPE_BYTE_INDEXED};

    @Test
    void rowsReadAsGetRgbReadsThemForEveryImageType() {
        Random random = new Random(3);
        for (int type : TYPES) {
            BufferedImage image = new BufferedImage(37, 11, type);
            for (int y = 0; y < 11; y++) {
                for (int x = 0; x < 37; x++) {
                    image.setRGB(x, y, random.nextInt());
                }
            }
            for (BufferedImage src : new BufferedImage[] {image, image.getSubimage(5, 2, 29, 7)}) {
                int w = src.getWidth() - 3;
                for (int y = 0; y < src.getHeight(); y++) {
                    int[] expected = new int[w + 2];
                    int[] actual = new int[w + 2];
                    src.getRGB(2, y, w, 1, expected, 0, w);
                    PixelRows.read(src, 2, y, w, actual);
                    assertArrayEquals(expected, actual, "type " + type + " row " + y);
                }
            }
        }
    }

    @Test
    void rowsWriteAsSetRgbWritesThem() {
        Random random = new Random(5);
        BufferedImage expected = new BufferedImage(23, 9, BufferedImage.TYPE_INT_ARGB);
        BufferedImage actual = new BufferedImage(23, 9, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 9; y++) {
            int[] row = new int[25];
            for (int x = 0; x < row.length; x++) {
                row[x] = random.nextInt();
            }
            expected.setRGB(0, y, 23, 1, row, 0, 23);
            PixelRows.write(actual, y, 23, row);
        }
        for (int y = 0; y < 9; y++) {
            assertArrayEquals(expected.getRGB(0, y, 23, 1, null, 0, 23), actual.getRGB(0, y, 23, 1, null, 0, 23));
        }
    }
}

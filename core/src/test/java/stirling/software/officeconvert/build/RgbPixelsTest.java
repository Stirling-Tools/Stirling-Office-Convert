package stirling.software.officeconvert.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class RgbPixelsTest {

    private static BufferedImage noise(int type, int w, int h, boolean translucent) {
        BufferedImage img = new BufferedImage(w, h, type);
        Random r = new Random(w * 31L + h + type);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = translucent ? r.nextInt(256) : 255;
                img.setRGB(x, y, a << 24 | r.nextInt(1 << 24));
            }
        }
        return img;
    }

    private static BufferedImage drawnOnWhite(BufferedImage img) {
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, 0, 0, Color.WHITE, null);
        g.dispose();
        return out;
    }

    private static byte[] jpeg(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", out);
        return out.toByteArray();
    }

    private static void assertSamePixels(BufferedImage want, BufferedImage got, int tolerance) {
        assertEquals(want.getWidth(), got.getWidth());
        assertEquals(want.getHeight(), got.getHeight());
        for (int y = 0; y < want.getHeight(); y++) {
            for (int x = 0; x < want.getWidth(); x++) {
                int a = want.getRGB(x, y);
                int b = got.getRGB(x, y);
                for (int shift = 0; shift <= 16; shift += 8) {
                    assertEquals((a >> shift) & 0xFF, (b >> shift) & 0xFF, tolerance, "pixel " + x + "," + y);
                }
            }
        }
    }

    @Test
    void opaqueIntAndBgrImagesEncodeToTheSameJpegBytes() throws Exception {
        for (int type : new int[] {BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_3BYTE_BGR,
                BufferedImage.TYPE_INT_ARGB}) {
            BufferedImage img = noise(type, 173, 61, false);
            assertArrayEquals(jpeg(drawnOnWhite(img)), jpeg(RgbPixels.onWhite(img)), "type " + type);
        }
    }

    @Test
    void translucentPixelsAreCompositedOnWhiteLikeJava2d() {
        for (int type : new int[] {BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_ARGB_PRE,
                BufferedImage.TYPE_4BYTE_ABGR}) {
            BufferedImage img = noise(type, 97, 43, true);
            assertSamePixels(drawnOnWhite(img), RgbPixels.onWhite(img), 1);
        }
    }

    @Test
    void subImagesReadOnlyTheirOwnPixels() {
        BufferedImage img = noise(BufferedImage.TYPE_INT_ARGB, 120, 80, true);
        BufferedImage part = img.getSubimage(17, 9, 50, 31);
        assertSamePixels(drawnOnWhite(part), RgbPixels.onWhite(part), 1);
    }

    @Test
    void greyImagesStayGreyForJpeg() {
        BufferedImage grey = new BufferedImage(10, 10, BufferedImage.TYPE_BYTE_GRAY);
        assertSame(grey, RgbPixels.forJpeg(grey));
        BufferedImage rgb = noise(BufferedImage.TYPE_INT_RGB, 10, 10, false);
        assertSamePixels(rgb, RgbPixels.forJpeg(rgb), 0);
    }
}

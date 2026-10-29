package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Random;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class CoverPictureTest {

    @Test
    void aTextPageBecomesASmallPalettePng() throws IOException {
        BufferedImage page = textPage();
        byte[] cover = CoverPicture.encode(page);
        assertTrue(CoverPicture.isPng(cover));
        assertTrue(cover.length < PdfBook.jpeg(page).length);
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(cover));
        assertEquals(page.getWidth(), back.getWidth());
        assertEquals(page.getHeight(), back.getHeight());
        assertTrue(rmsError(page, back) <= CoverPicture.MAX_ERROR, "error " + rmsError(page, back));
        assertEquals(Color.WHITE.getRGB(), back.getRGB(2, 2));
        assertEquals(new Color(0x1f4e79).getRGB(), back.getRGB(30, 30));
    }

    @Test
    void aPhotoStaysJpeg() throws IOException {
        BufferedImage photo = new BufferedImage(400, 560, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(7);
        for (int y = 0; y < photo.getHeight(); y++) {
            for (int x = 0; x < photo.getWidth(); x++) {
                int r = (x * 255 / photo.getWidth() + random.nextInt(40)) & 0xff;
                int g = (y * 255 / photo.getHeight() + random.nextInt(40)) & 0xff;
                int b = ((x + y) * 128 / photo.getWidth() + random.nextInt(40)) & 0xff;
                photo.setRGB(x, y, r << 16 | g << 8 | b);
            }
        }
        assertNull(CoverPicture.palettePng(photo));
        byte[] cover = CoverPicture.encode(photo);
        assertNotNull(cover);
        assertFalse(CoverPicture.isPng(cover));
        assertEquals(0xff, cover[0] & 0xff);
        assertEquals(0xd8, cover[1] & 0xff);
    }

    @Test
    void aSmoothGradientTooRichForThePaletteStaysJpeg() throws IOException {
        BufferedImage sky = new BufferedImage(400, 560, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < sky.getHeight(); y++) {
            for (int x = 0; x < sky.getWidth(); x++) {
                sky.setRGB(x, y, (x * 255 / sky.getWidth()) << 16 | (y * 255 / sky.getHeight()) << 8 | 0x80);
            }
        }
        assertFalse(CoverPicture.isPng(CoverPicture.encode(sky)));
    }

    @Test
    void aSmallPictureOnATextPageKeepsTheCoverJpeg() throws IOException {
        BufferedImage page = textPage();
        for (int y = 0; y < 120; y++) {
            for (int x = 0; x < 120; x++) {
                page.setRGB(400 + x, 600 + y, (x * 2) << 16 | (y * 2) << 8 | (240 - x - y / 2));
            }
        }
        assertNull(CoverPicture.palettePng(page));
        assertFalse(CoverPicture.isPng(CoverPicture.encode(page)));
    }

    @Test
    void aTwoColourPageUsesAOneBitPalette() throws IOException {
        BufferedImage page = new BufferedImage(200, 280, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, 280);
        g.setColor(Color.BLACK);
        g.fillRect(20, 20, 160, 10);
        g.dispose();
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(CoverPicture.encode(page)));
        assertEquals(1, back.getColorModel().getPixelSize());
        assertEquals(Color.BLACK.getRGB(), back.getRGB(100, 25));
        assertEquals(Color.WHITE.getRGB(), back.getRGB(100, 100));
    }

    private static BufferedImage textPage() {
        BufferedImage page = new BufferedImage(566, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = page.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, page.getWidth(), page.getHeight());
        g.setColor(new Color(0x1f4e79));
        g.fillRect(20, 20, 526, 40);
        g.setFont(new Font(Font.SERIF, Font.PLAIN, 9));
        g.setColor(new Color(0x222222));
        for (int line = 0; line < 60; line++) {
            g.drawString("The quick brown fox jumps over the lazy dog, line " + line + " of the annual report text.",
                    30, 90 + line * 11);
        }
        g.dispose();
        return page;
    }

    private static double rmsError(BufferedImage a, BufferedImage b) {
        double sum = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                for (int shift = 0; shift <= 16; shift += 8) {
                    int d = (p >> shift & 0xff) - (q >> shift & 0xff);
                    sum += d * d;
                }
            }
        }
        return Math.sqrt(sum / (a.getWidth() * a.getHeight() * 3.0));
    }
}

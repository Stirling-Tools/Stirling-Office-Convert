package stirling.software.officeconvert.lineart;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class SauvolaTest {

    @Test
    void eightBitLevelsMatchRoundingEveryFloat() {
        for (long bits = 0; bits <= 0xFFFFFFFFL; bits += 4093) {
            float f = Float.intBitsToFloat((int) bits);
            assertEquals(Math.clamp(Math.round(f / 257.0), 0, 255), Binarizer.eight(f), Float.toString(f));
        }
        for (int v = 0; v < 65536; v++) {
            for (float f : new float[] {v, v + 0.5f, Math.nextDown(v + 0.5f), Math.nextUp(v + 0.5f), v * 257f,
                v * 257f + 128.5f, Math.nextDown(v * 257f + 128.5f), Math.nextUp(v * 257f + 128.5f)}) {
                assertEquals(Math.clamp(Math.round(f / 257.0), 0, 255), Binarizer.eight(f), Float.toString(f));
            }
        }
    }

    @Test
    void wholePicturesMatchTheReferenceSauvola() {
        for (BufferedImage picture : pictures()) {
            int w = picture.getWidth();
            int h = picture.getHeight();
            Tint tint = new Tint(w, h);
            float[] grey = Binarizer.grey(picture, null, tint, 0);
            for (int window : new int[] {0, 3, 15, 51}) {
                for (double k : new double[] {0, 0.2, 0.5, 1}) {
                    LineArt.Options options = LineArt.Options.defaults().window(window).k(k);
                    Bitmap expected = SauvolaReference.binarize(grey, tint, w, h, Binarizer.window(window, w, h), k);
                    assertEquals(expected, Binarizer.binarize(picture, options),
                            w + "x" + h + " window " + window + " k " + k);
                }
            }
        }
    }

    @Test
    void bandsMatchTheReferenceSauvola() throws IOException {
        BufferedImage picture = panels(301, 997, 7);
        int w = picture.getWidth();
        int h = picture.getHeight();
        Tint tint = new Tint(w, h);
        float[] grey = Binarizer.grey(picture, null, tint, 0);
        for (int window : new int[] {0, 9, 31}) {
            LineArt.Options options = LineArt.Options.defaults().window(window);
            Bitmap expected = SauvolaReference.binarize(grey, tint, w, h, Binarizer.window(window, w, h),
                    options.k());
            for (int rows : new int[] {2, 7, 64, 300, 996}) {
                Source banded = new Source(w, h) {
                    @Override
                    void bands(int n, boolean keyed, Band band) throws IOException {
                        for (int y = 0; y < height; y += rows) {
                            band.accept(y, picture.getSubimage(0, y, width, Math.min(rows, height - y)));
                        }
                    }
                };
                assertEquals(expected, Binarizer.binarize(banded, options, rows), "window " + window + " rows " + rows);
            }
        }
    }

    private static List<BufferedImage> pictures() {
        List<BufferedImage> pictures = new ArrayList<>();
        pictures.add(Pictures.scan(211, 157, 3, 0.5));
        pictures.add(Pictures.scan(64, 9, 4));
        pictures.add(Pictures.scan(5, 300, 5));
        pictures.add(panels(333, 251, 6));
        pictures.add(gradient(203, 147));
        pictures.add(noise(97, 61, 8));
        pictures.add(flat(70, 50, 0));
        pictures.add(flat(70, 50, 255));
        pictures.add(flat(70, 50, 128));
        return pictures;
    }

    private static BufferedImage panels(int w, int h, long seed) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        Random random = new Random(seed);
        for (int i = 0; i < 12; i++) {
            g.setColor(new Color(random.nextInt(256), random.nextInt(256), random.nextInt(256)));
            g.fillRect(random.nextInt(w), random.nextInt(h), 10 + random.nextInt(w / 2), 10 + random.nextInt(h / 2));
        }
        g.setColor(Color.BLACK);
        for (int i = 0; i < 30; i++) {
            g.drawLine(random.nextInt(w), random.nextInt(h), random.nextInt(w), random.nextInt(h));
        }
        g.dispose();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (random.nextInt(4) == 0) {
                    int rgb = image.getRGB(x, y);
                    int d = random.nextInt(21) - 10;
                    int r = Math.clamp(((rgb >> 16) & 0xFF) + d, 0, 255);
                    int gr = Math.clamp(((rgb >> 8) & 0xFF) + d, 0, 255);
                    int b = Math.clamp((rgb & 0xFF) + d, 0, 255);
                    image.setRGB(x, y, r << 16 | gr << 8 | b);
                }
            }
        }
        return image;
    }

    private static BufferedImage gradient(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.getRaster().setSample(x, y, 0, 30 + 225 * (x + y) / (w + h));
            }
        }
        return image;
    }

    private static BufferedImage noise(int w, int h, long seed) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(seed);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setRGB(x, y, random.nextInt(1 << 24));
            }
        }
        return image;
    }

    private static BufferedImage flat(int w, int h, int v) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.getRaster().setSample(x, y, 0, v);
            }
        }
        return image;
    }
}

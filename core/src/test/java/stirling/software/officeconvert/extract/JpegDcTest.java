package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class JpegDcTest {

    private static final String FORMAT = "javax_imageio_jpeg_image_1.0";

    @Test
    void neverCallsAWhitePictureDark() throws IOException {
        Random random = new Random(7);
        int dark = 0;
        int checked = 0;
        for (int i = 0; i < 120; i++) {
            int w = 17 + random.nextInt(140);
            int h = 17 + random.nextInt(140);
            BufferedImage img = switch (i % 4) {
                case 0 -> photo(w, h, random);
                case 1 -> nearWhite(w, h, random, 230);
                case 2 -> nearWhite(w, h, random, 248);
                default -> speck(w, h, random);
            };
            for (boolean progressive : new boolean[] {false, true}) {
                for (int restart : new int[] {0, 1 + random.nextInt(5)}) {
                    byte[] jpeg = encode(img, progressive, restart, i % 3 == 0, 0.3f + random.nextFloat() * 0.7f);
                    boolean claimed = JpegDc.dark(jpeg, w, h);
                    checked++;
                    if (claimed) {
                        dark++;
                        assertTrue(showsInk(jpeg), "picture " + i + " claimed dark but decodes white");
                    }
                }
            }
        }
        assertTrue(dark > checked / 4, "the quick check should settle most photographs: " + dark + "/" + checked);
    }

    @Test
    void photographsAreSettledInBothModes() throws IOException {
        Random random = new Random(3);
        BufferedImage img = photo(320, 200, random);
        for (boolean progressive : new boolean[] {false, true}) {
            for (int restart : new int[] {0, 2}) {
                byte[] jpeg = encode(img, progressive, restart, false, 0.8f);
                assertTrue(JpegDc.dark(jpeg, 320, 200));
                try (PDDocument doc = new PDDocument()) {
                    PDImageXObject x = JPEGFactory.createFromByteArray(doc, jpeg);
                    assertTrue(JpegDc.dark(x));
                }
            }
        }
    }

    @Test
    void whiteAndMismatchedPicturesAreLeftToTheFullCheck() throws IOException {
        BufferedImage white = plain(64, 48, Color.WHITE);
        for (boolean progressive : new boolean[] {false, true}) {
            assertFalse(JpegDc.dark(encode(white, progressive, 0, false, 0.9f), 64, 48));
        }
        byte[] photo = encode(photo(64, 48, new Random(1)), false, 0, false, 0.9f);
        assertFalse(JpegDc.dark(photo, 64, 47), "size differs from the picture's own");
        assertFalse(JpegDc.dark(Arrays.copyOf(photo, 40), 64, 48));
        assertFalse(JpegDc.dark(new byte[0], 64, 48));
        BufferedImage gray = new BufferedImage(64, 48, BufferedImage.TYPE_BYTE_GRAY);
        assertFalse(JpegDc.dark(encode(gray, false, 0, false, 0.9f), 64, 48), "one component is not handled");
    }

    @Test
    void blocksOutsideThePictureAreIgnored() throws IOException {
        BufferedImage img = plain(112, 112, Color.WHITE);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(104, 0, 8, 112);
        g.fillRect(0, 104, 112, 8);
        g.dispose();
        for (int restart : new int[] {0, 2}) {
            byte[] jpeg = encode(img, false, restart, false, 0.9f);
            assertTrue(JpegDc.dark(jpeg, 112, 112));
            byte[] cropped = resize(jpeg, 104, 104);
            assertFalse(showsInk(cropped), "the dark strips lie outside the smaller frame");
            assertFalse(JpegDc.dark(cropped, 104, 104));
        }
    }

    @Test
    void aLoneDarkBlockIsFoundWhereItIs() throws IOException {
        BufferedImage img = plain(96, 80, Color.WHITE);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(40, 60, 90));
        g.fillRect(72, 64, 8, 8);
        g.dispose();
        for (boolean progressive : new boolean[] {false, true}) {
            for (int restart : new int[] {0, 1, 3}) {
                assertTrue(JpegDc.dark(encode(img, progressive, restart, true, 0.9f), 96, 80));
            }
        }
    }

    private static boolean showsInk(byte[] jpeg) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            BufferedImage img = JPEGFactory.createFromByteArray(doc, jpeg).getImage();
            int[] row = new int[img.getWidth()];
            for (int y = 0; y < img.getHeight(); y++) {
                img.getRGB(0, y, row.length, 1, row, 0, row.length);
                for (int p : row) {
                    if (((p >> 16) & 0xFF) < 250 || ((p >> 8) & 0xFF) < 250 || (p & 0xFF) < 250) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static boolean has(byte[] jpeg, int marker) {
        for (int i = 0; i + 1 < jpeg.length; i++) {
            if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == marker) {
                return true;
            }
        }
        return false;
    }

    private static byte[] resize(byte[] jpeg, int width, int height) {
        byte[] out = jpeg.clone();
        for (int i = 2; i + 8 < out.length; i++) {
            int m = out[i + 1] & 0xFF;
            if ((out[i] & 0xFF) == 0xFF && (m == 0xC0 || m == 0xC2)) {
                out[i + 5] = (byte) (height >> 8);
                out[i + 6] = (byte) height;
                out[i + 7] = (byte) (width >> 8);
                out[i + 8] = (byte) width;
                return out;
            }
        }
        throw new AssertionError("no frame header");
    }

    private static BufferedImage plain(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static BufferedImage photo(int w, int h, Random random) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int base = random.nextInt(200);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = clamp(base + x * 55 / w + random.nextInt(30));
                int gr = clamp(base / 2 + y * 90 / h + random.nextInt(30));
                int b = clamp(255 - base / 3 - (x + y) * 40 / (w + h) + random.nextInt(20));
                img.setRGB(x, y, r << 16 | gr << 8 | b);
            }
        }
        return img;
    }

    private static BufferedImage nearWhite(int w, int h, Random random, int floor) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = floor + random.nextInt(256 - floor);
                img.setRGB(x, y, v << 16 | clamp(v + random.nextInt(9) - 4) << 8 | clamp(v + random.nextInt(9) - 4));
            }
        }
        return img;
    }

    private static BufferedImage speck(int w, int h, Random random) {
        BufferedImage img = plain(w, h, Color.WHITE);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(random.nextInt(256), random.nextInt(256), random.nextInt(256)));
        g.fillRect(random.nextInt(w), random.nextInt(h), 1 + random.nextInt(6), 1 + random.nextInt(6));
        g.dispose();
        return img;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static byte[] encode(BufferedImage img, boolean progressive, int restart, boolean fullChroma,
            float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            if (progressive) {
                param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            }
            IIOMetadata meta = writer.getDefaultImageMetadata(new ImageTypeSpecifier(img), param);
            Element tree = (Element) meta.getAsTree(FORMAT);
            Element markers = (Element) tree.getElementsByTagName("markerSequence").item(0);
            if (restart > 0) {
                IIOMetadataNode dri = new IIOMetadataNode("dri");
                dri.setAttribute("interval", Integer.toString(restart));
                markers.insertBefore(dri, markers.getElementsByTagName("sof").item(0));
            }
            NodeList specs = tree.getElementsByTagName("componentSpec");
            for (int i = 0; i < specs.getLength(); i++) {
                String f = i == 0 && !fullChroma ? "2" : "1";
                ((Element) specs.item(i)).setAttribute("HsamplingFactor", f);
                ((Element) specs.item(i)).setAttribute("VsamplingFactor", f);
            }
            meta.setFromTree(FORMAT, tree);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(img, null, meta), param);
            }
            byte[] bytes = out.toByteArray();
            assertEquals(0xD8, bytes[1] & 0xFF);
            assertEquals(restart > 0, has(bytes, 0xDD), "restart interval written");
            assertEquals(progressive, has(bytes, 0xC2), "progressive frame written");
            return bytes;
        } finally {
            writer.dispose();
        }
    }
}

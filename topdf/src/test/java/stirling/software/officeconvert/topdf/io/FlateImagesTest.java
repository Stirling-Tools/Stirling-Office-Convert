package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;
import java.util.zip.InflaterInputStream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

class FlateImagesTest {

    @Test
    void keepsEveryPixelAndTheAlphaOfATranslucentPicture() throws Exception {
        BufferedImage src = picture(BufferedImage.TYPE_4BYTE_ABGR, 97, 61, true);
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject x = FlateImages.encode(doc, src);
            assertEquals(PDDeviceRGB.INSTANCE, x.getColorSpace());
            assertNotNull(x.getSoftMask());
            assertSameColors(src, x.getOpaqueImage());
            BufferedImage mask = x.getSoftMask().getOpaqueImage();
            for (int y = 0; y < src.getHeight(); y++) {
                for (int i = 0; i < src.getWidth(); i++) {
                    assertEquals(src.getRGB(i, y) >>> 24, mask.getRGB(i, y) & 0xFF, "alpha at " + i + "," + y);
                }
            }
        }
    }

    @Test
    void opaquePicturesGetNoMaskAndGrayStaysGray() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int type : new int[] {BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_INT_RGB,
                BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_BYTE_INDEXED}) {
                BufferedImage src = picture(type, 64, 33, false);
                PDImageXObject x = FlateImages.encode(doc, src);
                assertNull(x.getSoftMask(), "type " + type);
                assertSameColors(src, x.getOpaqueImage());
            }
            BufferedImage gray = picture(BufferedImage.TYPE_BYTE_GRAY, 40, 30, false);
            PDImageXObject g = FlateImages.encode(doc, gray);
            assertEquals(PDDeviceGray.INSTANCE, g.getColorSpace());
            BufferedImage back = g.getOpaqueImage();
            for (int y = 0; y < 30; y++) {
                for (int i = 0; i < 40; i++) {
                    assertEquals(gray.getRaster().getSample(i, y, 0), back.getRaster().getSample(i, y, 0));
                }
            }
        }
    }

    @Test
    void readsPartOfALargerRaster() throws Exception {
        BufferedImage big = picture(BufferedImage.TYPE_4BYTE_ABGR, 120, 90, true);
        BufferedImage part = big.getSubimage(17, 9, 50, 40);
        try (PDDocument doc = new PDDocument()) {
            assertSameColors(part, FlateImages.encode(doc, part).getOpaqueImage());
        }
    }

    @Test
    void choosesUpForSmoothPicturesAndNoneForStripes() throws Exception {
        BufferedImage stripes = new BufferedImage(300, 200, BufferedImage.TYPE_3BYTE_BGR);
        BufferedImage smooth = new BufferedImage(300, 200, BufferedImage.TYPE_3BYTE_BGR);
        Random r = new Random(3);
        for (int y = 0; y < 200; y++) {
            for (int i = 0; i < 300; i++) {
                stripes.setRGB(i, y, y % 2 == 0 ? 0 : 0x202020);
                int v = Math.min(255, y + i % 7 + r.nextInt(2));
                smooth.setRGB(i, y, v << 16 | v << 8 | v);
            }
        }
        try (PDDocument doc = new PDDocument()) {
            assertEquals(0, firstRowFilter(FlateImages.encode(doc, stripes)));
            assertEquals(2, firstRowFilter(FlateImages.encode(doc, smooth)));
            assertSameColors(smooth, FlateImages.encode(doc, smooth).getOpaqueImage());
        }
    }

    private static int firstRowFilter(PDImageXObject x) throws IOException {
        try (InputStream in = new InflaterInputStream(x.getCOSObject().createRawInputStream())) {
            return in.read();
        }
    }

    @Test
    void onlyPlainRgbAndPalettePngsGoToPdfboxAsTheyAre() throws Exception {
        assertTrue(PictureDecoder.passesThrough(png(picture(BufferedImage.TYPE_INT_RGB, 20, 10, false))));
        assertTrue(PictureDecoder.passesThrough(png(picture(BufferedImage.TYPE_BYTE_INDEXED, 20, 10, false))));
        assertFalse(PictureDecoder.passesThrough(png(picture(BufferedImage.TYPE_INT_ARGB, 20, 10, true))));
        assertFalse(PictureDecoder.passesThrough(png(picture(BufferedImage.TYPE_BYTE_GRAY, 20, 10, false))));
        assertFalse(PictureDecoder.passesThrough(new byte[] {(byte) 0x89, 'P', 'N', 'G'}));
    }

    @Test
    void aTranslucentPngDecodesWithItsMask() throws Exception {
        BufferedImage src = picture(BufferedImage.TYPE_INT_ARGB, 80, 50, true);
        try (PDDocument doc = new PDDocument()) {
            DecodedPicture p = PictureDecoder.decode(doc, png(src));
            PDImageXObject x = p.image();
            assertTrue(x.getCOSObject().containsKey(COSName.SMASK));
            assertEquals(COSName.FLATE_DECODE, x.getCOSObject().getDictionaryObject(COSName.FILTER));
            assertSameColors(src, x.getOpaqueImage());
            assertEquals(60f, p.naturalWidth(), 0.01f);
        }
    }

    private static void assertSameColors(BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth());
        assertEquals(expected.getHeight(), actual.getHeight());
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int i = 0; i < expected.getWidth(); i++) {
                assertEquals(expected.getRGB(i, y) & 0xFFFFFF, actual.getRGB(i, y) & 0xFFFFFF, "pixel " + i + "," + y);
            }
        }
    }

    private static BufferedImage picture(int type, int w, int h, boolean translucent) {
        BufferedImage img = new BufferedImage(w, h, type);
        Graphics2D g = img.createGraphics();
        try {
            g.setPaint(new GradientPaint(0, 0, new Color(250, 20, 40, translucent ? 60 : 255), w, h,
                    new Color(10, 200, 90, 255)));
            g.fillRect(0, 0, w, h);
            g.setColor(new Color(0, 0, 255, translucent ? 0 : 255));
            g.fillRect(w / 4, h / 4, w / 3, h / 3);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}

package stirling.software.officeconvert.topdf.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

class FastPicturesTest {

    @Test
    void streamsEveryEightBitPngKindRowByRow() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            BufferedImage rgba = noisy(BufferedImage.TYPE_4BYTE_ABGR, 131, 77, true);
            PDImageXObject x = PngStream.encode(doc, png(rgba, false));
            assertNotNull(x);
            assertEquals(PDDeviceRGB.INSTANCE, x.getColorSpace());
            assertSameRgb(rgba, x.getOpaqueImage());
            assertSameAlpha(rgba, x.getSoftMask().getOpaqueImage());

            BufferedImage rgb = noisy(BufferedImage.TYPE_3BYTE_BGR, 64, 51, false);
            PDImageXObject y = PngStream.encode(doc, png(rgb, false));
            assertNull(y.getSoftMask());
            assertSameRgb(rgb, y.getOpaqueImage());

            BufferedImage gray = noisy(BufferedImage.TYPE_BYTE_GRAY, 45, 29, false);
            PDImageXObject g = PngStream.encode(doc, png(gray, false));
            assertEquals(PDDeviceGray.INSTANCE, g.getColorSpace());
            assertSameSamples(gray, g.getOpaqueImage());
        }
    }

    @Test
    void grayWithAlphaStaysGrayWithItsOwnValues() throws Exception {
        BufferedImage ga = grayAlpha(DataBuffer.TYPE_BYTE, 70, 40);
        try (PDDocument doc = new PDDocument()) {
            for (PDImageXObject x : new PDImageXObject[] {PngStream.encode(doc, png(ga, false)),
                FlateImages.encode(doc, ga), PictureDecoder.decode(doc, png(ga, false)).image()}) {
                assertEquals(PDDeviceGray.INSTANCE, x.getColorSpace());
                BufferedImage back = x.getOpaqueImage();
                BufferedImage mask = x.getSoftMask().getOpaqueImage();
                for (int y = 0; y < 40; y++) {
                    for (int i = 0; i < 70; i++) {
                        assertEquals(ga.getRaster().getSample(i, y, 0), back.getRaster().getSample(i, y, 0));
                        assertEquals(ga.getRaster().getSample(i, y, 1), mask.getRaster().getSample(i, y, 0));
                    }
                }
            }
        }
    }

    @Test
    void sixteenBitSamplesKeepTheirHighByte() throws Exception {
        BufferedImage g16 = new BufferedImage(33, 21, BufferedImage.TYPE_USHORT_GRAY);
        WritableRaster r = g16.getRaster();
        for (int y = 0; y < 21; y++) {
            for (int i = 0; i < 33; i++) {
                r.setSample(i, y, 0, (i * 1987 + y * 3001) & 0xFFFF);
            }
        }
        try (PDDocument doc = new PDDocument()) {
            assertNull(PngStream.encode(doc, png(g16, false)));
            PDImageXObject x = PictureDecoder.decode(doc, png(g16, false)).image();
            assertEquals(PDDeviceGray.INSTANCE, x.getColorSpace());
            BufferedImage back = x.getOpaqueImage();
            for (int y = 0; y < 21; y++) {
                for (int i = 0; i < 33; i++) {
                    assertEquals(r.getSample(i, y, 0) >> 8, back.getRaster().getSample(i, y, 0));
                }
            }
        }
    }

    @Test
    void palettesWithTransparencyKeepTheirMask() throws Exception {
        byte[] reds = {(byte) 255, 0, 0, (byte) 128};
        byte[] greens = {0, (byte) 255, 0, (byte) 128};
        byte[] blues = {0, 0, (byte) 255, (byte) 128};
        IndexColorModel icm = new IndexColorModel(2, 4, reds, greens, blues, 3);
        BufferedImage img = new BufferedImage(19, 11, BufferedImage.TYPE_BYTE_BINARY, icm);
        for (int y = 0; y < 11; y++) {
            for (int i = 0; i < 19; i++) {
                img.getRaster().setSample(i, y, 0, (i + y) % 4);
            }
        }
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject x = FlateImages.encode(doc, img);
            assertSameRgb(img, x.getOpaqueImage());
            assertSameAlpha(img, x.getSoftMask().getOpaqueImage());
        }
    }

    @Test
    void otherColorSpacesAreConvertedInBulkLikeGetRgb() throws Exception {
        ColorSpace linear = ColorSpace.getInstance(ColorSpace.CS_LINEAR_RGB);
        ComponentColorModel cm = new ComponentColorModel(linear, true, false, Transparency.TRANSLUCENT,
                DataBuffer.TYPE_BYTE);
        BufferedImage img = new BufferedImage(cm, cm.createCompatibleWritableRaster(90, 150), false, null);
        Random random = new Random(5);
        for (int y = 0; y < 150; y++) {
            for (int i = 0; i < 90; i++) {
                for (int b = 0; b < 3; b++) {
                    img.getRaster().setSample(i, y, b, random.nextInt(256));
                }
                img.getRaster().setSample(i, y, 3, y < 70 ? 255 : 40);
            }
        }
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject x = FlateImages.encode(doc, img);
            BufferedImage back = x.getOpaqueImage();
            for (int y = 0; y < 150; y++) {
                for (int i = 0; i < 90; i++) {
                    int want = img.getRGB(i, y);
                    int got = back.getRGB(i, y);
                    for (int shift = 0; shift < 24; shift += 8) {
                        int d = Math.abs((want >> shift & 0xFF) - (got >> shift & 0xFF));
                        assertTrue(d <= 2, "pixel " + i + "," + y + " " + Integer.toHexString(want) + " "
                                + Integer.toHexString(got));
                    }
                }
            }
            assertNotNull(x.getSoftMask());
        }
    }

    @Test
    void opaqueRgbaGetsNoMaskAndDamagedStreamsFail() throws Exception {
        BufferedImage opaque = noisy(BufferedImage.TYPE_4BYTE_ABGR, 40, 30, false);
        byte[] png = png(opaque, false);
        try (PDDocument doc = new PDDocument()) {
            assertNull(PngStream.encode(doc, png).getSoftMask());
            byte[] cut = Arrays.copyOf(png, png.length / 2);
            assertThrows(IOException.class, () -> PngStream.encode(doc, cut));
            assertNull(PngStream.encode(doc, png(opaque, true)));
            BufferedImage decoded = PictureDecoder.decode(doc, png(opaque, true)).image().getOpaqueImage();
            assertSameRgb(opaque, decoded);
        }
    }

    @Test
    void leavesColourKeysAndOpaquePalettesToTheDecoder() throws Exception {
        byte[] rgb = png(noisy(BufferedImage.TYPE_3BYTE_BGR, 12, 9, false), false);
        int idat = 8;
        while (!new String(rgb, idat + 4, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("IDAT")) {
            idat += 12 + ((rgb[idat] & 0xFF) << 24 | (rgb[idat + 1] & 0xFF) << 16 | (rgb[idat + 2] & 0xFF) << 8
                    | (rgb[idat + 3] & 0xFF));
        }
        ByteArrayOutputStream keyed = new ByteArrayOutputStream();
        keyed.write(rgb, 0, idat);
        keyed.writeBytes(new byte[] {0, 0, 0, 6, 't', 'R', 'N', 'S', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        keyed.write(rgb, idat, rgb.length - idat);
        byte[] palette = png(new BufferedImage(12, 9, BufferedImage.TYPE_BYTE_INDEXED), false);
        try (PDDocument doc = new PDDocument()) {
            assertNotNull(PngStream.encode(doc, rgb));
            assertNull(PngStream.encode(doc, keyed.toByteArray()));
            assertNull(PngStream.encode(doc, palette));
            assertTrue(PictureDecoder.passesThrough(palette));
        }
    }

    @Test
    void palettesStayIndexedWithTheirTransparencyAsAMask() throws Exception {
        for (int bits : new int[] {1, 2, 4, 8}) {
            int n = 1 << bits;
            byte[] r = new byte[n];
            byte[] g = new byte[n];
            byte[] b = new byte[n];
            byte[] a = new byte[n];
            for (int i = 0; i < n; i++) {
                r[i] = (byte) (i * 37);
                g[i] = (byte) (255 - i * 11);
                b[i] = (byte) (i * 97);
                a[i] = (byte) (i % 3 == 0 ? 0 : i % 3 == 1 ? 128 : 255);
            }
            IndexColorModel icm = new IndexColorModel(bits, n, r, g, b, a);
            BufferedImage img = bits == 8 ? new BufferedImage(37, 23, BufferedImage.TYPE_BYTE_INDEXED, icm)
                    : new BufferedImage(37, 23, BufferedImage.TYPE_BYTE_BINARY, icm);
            Random random = new Random(bits);
            for (int y = 0; y < 23; y++) {
                for (int i = 0; i < 37; i++) {
                    img.getRaster().setSample(i, y, 0, y < 5 ? i % n : random.nextInt(n));
                }
            }
            byte[] png = png(img, false);
            assertEquals(3, png[25], "a palette PNG");
            assertEquals(bits, png[24]);
            assertTrue(!PictureDecoder.passesThrough(png));
            try (PDDocument doc = new PDDocument()) {
                for (PDImageXObject x : new PDImageXObject[] {PngStream.encode(doc, png),
                    PictureDecoder.decode(doc, png).image()}) {
                    assertTrue(x.getColorSpace() instanceof org.apache.pdfbox.pdmodel.graphics.color.PDIndexed);
                    assertEquals(bits, x.getBitsPerComponent());
                    assertSameRgb(img, x.getOpaqueImage());
                    assertSameAlpha(img, x.getSoftMask().getOpaqueImage());
                }
                byte[] cut = Arrays.copyOf(png, png.length - 30);
                assertThrows(IOException.class, () -> PngStream.encode(doc, cut));
            }
        }
        byte[] solid = {(byte) 255, (byte) 255};
        IndexColorModel opaque = new IndexColorModel(1, 2, new byte[] {0, (byte) 255}, new byte[2], new byte[2], solid);
        BufferedImage img = new BufferedImage(9, 4, BufferedImage.TYPE_BYTE_BINARY, opaque);
        img.getRaster().setSample(3, 2, 0, 1);
        try (PDDocument doc = new PDDocument()) {
            byte[] png = png(img, false);
            PDImageXObject x = PngStream.encode(doc, png);
            if (x != null) {
                assertNull(x.getSoftMask());
                assertSameRgb(img, x.getOpaqueImage());
            }
        }
    }

    @Test
    void readsJpegFramesWithoutADecoder() throws Exception {
        BufferedImage photo = noisy(BufferedImage.TYPE_3BYTE_BGR, 123, 45, false);
        byte[] baseline = jpeg(photo, false);
        PictureDecoder.JpegFrame f = PictureDecoder.JpegFrame.of(baseline);
        assertEquals(new PictureDecoder.JpegFrame(0xC0, 8, 123, 45, 3), f);
        assertTrue(f.passesThrough());
        PictureDecoder.JpegFrame p = PictureDecoder.JpegFrame.of(jpeg(photo, true));
        assertEquals(0xC2, p.marker());
        assertTrue(p.passesThrough());
        byte[] gray = jpeg(noisy(BufferedImage.TYPE_BYTE_GRAY, 20, 10, false), false);
        assertEquals(1, PictureDecoder.JpegFrame.of(gray).components());
        assertEquals(123, PictureDecoder.pixelSize(baseline).width);
        assertEquals(45, PictureDecoder.pixelSize(baseline).height);
        assertNull(PictureDecoder.JpegFrame.of(Arrays.copyOf(baseline, 20)));
        assertNull(PictureDecoder.JpegFrame.of(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDA, 0, 2}));
        byte[] twelve = baseline.clone();
        int at = sof(twelve);
        twelve[at + 4] = 12;
        assertTrue(!PictureDecoder.JpegFrame.of(twelve).passesThrough());
        try (PDDocument doc = new PDDocument()) {
            DecodedPicture d = PictureDecoder.decode(doc, baseline);
            assertEquals(COSName.DCT_DECODE, d.image().getCOSObject().getDictionaryObject(COSName.FILTER));
            assertEquals(PDDeviceRGB.INSTANCE, d.image().getColorSpace());
            assertEquals(123, d.pixelWidth());
            BufferedImage back = d.image().getImage();
            BufferedImage expected = ImageIO.read(new java.io.ByteArrayInputStream(baseline));
            assertEquals(expected.getRGB(60, 20), back.getRGB(60, 20));
        }
    }

    @Test
    void cmykJpegsKeepPdfboxsInvertedDecode() throws Exception {
        byte[] jpeg = jpeg(noisy(BufferedImage.TYPE_3BYTE_BGR, 16, 16, false), false);
        int at = sof(jpeg);
        byte[] cmyk = new byte[jpeg.length + 3];
        System.arraycopy(jpeg, 0, cmyk, 0, at + 9);
        cmyk[at + 3] += 3;
        cmyk[at + 9] = 4;
        System.arraycopy(jpeg, at + 10, cmyk, at + 10, 9);
        cmyk[at + 19] = 4;
        cmyk[at + 20] = 0x11;
        cmyk[at + 21] = 0;
        System.arraycopy(jpeg, at + 19, cmyk, at + 22, jpeg.length - at - 19);
        PictureDecoder.JpegFrame f = PictureDecoder.JpegFrame.of(cmyk);
        assertEquals(4, f.components());
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject x = PictureDecoder.decode(doc, cmyk).image();
            assertEquals(PDDeviceCMYK.INSTANCE, x.getColorSpace());
            assertEquals(8, x.getDecode().size());
            assertEquals(1f, ((org.apache.pdfbox.cos.COSNumber) x.getDecode().get(0)).floatValue());
        }
    }

    private static int sof(byte[] jpeg) {
        for (int i = 2; i + 1 < jpeg.length; i++) {
            if ((jpeg[i] & 0xFF) == 0xFF && ((jpeg[i + 1] & 0xFF) == 0xC0 || (jpeg[i + 1] & 0xFF) == 0xC2)) {
                return i;
            }
        }
        throw new AssertionError("no frame header");
    }

    private static BufferedImage grayAlpha(int transfer, int w, int h) {
        ComponentColorModel cm = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY), true, false,
                Transparency.TRANSLUCENT, transfer);
        BufferedImage img = new BufferedImage(cm, cm.createCompatibleWritableRaster(w, h), false, null);
        for (int y = 0; y < h; y++) {
            for (int i = 0; i < w; i++) {
                img.getRaster().setSample(i, y, 0, (i * 7 + y * 3) % 256);
                img.getRaster().setSample(i, y, 1, (i + y * 11) % 256);
            }
        }
        return img;
    }

    private static BufferedImage noisy(int type, int w, int h, boolean translucent) {
        BufferedImage img = new BufferedImage(w, h, type);
        Graphics2D g = img.createGraphics();
        try {
            g.setPaint(new GradientPaint(0, 0, new Color(250, 20, 40, translucent ? 60 : 255), w, h,
                    new Color(10, 200, 90, 255)));
            g.fillRect(0, 0, w, h);
            g.setColor(Color.BLUE);
            g.fillRect(w / 4, h / 4, w / 3, h / 3);
        } finally {
            g.dispose();
        }
        Random random = new Random(w * 31L + h);
        for (int n = 0; n < w * h / 5; n++) {
            int x = random.nextInt(w);
            int y = random.nextInt(h);
            int a = translucent ? random.nextInt(256) : 255;
            img.setRGB(x, y, a << 24 | random.nextInt(1 << 24));
        }
        return img;
    }

    private static byte[] png(BufferedImage img, boolean interlaced) throws IOException {
        return write(img, "png", interlaced);
    }

    private static byte[] jpeg(BufferedImage img, boolean progressive) throws IOException {
        return write(img, "jpeg", progressive);
    }

    private static byte[] write(BufferedImage img, String format, boolean progressive) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName(format).next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (progressive) {
                param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            }
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static void assertSameRgb(BufferedImage expected, BufferedImage actual) {
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int i = 0; i < expected.getWidth(); i++) {
                assertEquals(expected.getRGB(i, y) & 0xFFFFFF, actual.getRGB(i, y) & 0xFFFFFF, "pixel " + i + "," + y);
            }
        }
    }

    private static void assertSameAlpha(BufferedImage expected, BufferedImage mask) {
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int i = 0; i < expected.getWidth(); i++) {
                assertEquals(expected.getRGB(i, y) >>> 24, mask.getRaster().getSample(i, y, 0), "alpha " + i + "," + y);
            }
        }
    }

    private static void assertSameSamples(BufferedImage expected, BufferedImage actual) {
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int i = 0; i < expected.getWidth(); i++) {
                assertEquals(expected.getRaster().getSample(i, y, 0), actual.getRaster().getSample(i, y, 0));
            }
        }
    }
}

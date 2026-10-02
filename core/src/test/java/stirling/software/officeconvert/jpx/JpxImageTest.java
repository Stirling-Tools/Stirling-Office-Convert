package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class JpxImageTest {

    private static byte[] codestream(String name) {
        return JpxSamples.resource(name);
    }

    private static BufferedImage image(byte[] data) throws IOException {
        return JpxDecoder.decode(data).toBufferedImage();
    }

    private static int[] rgb(BufferedImage img, int x, int y) {
        return img.getRaster().getPixel(x, y, (int[]) null);
    }

    @Test
    void paletteIndicesResolveThroughTheComponentMapping() throws IOException {
        ByteArrayOutputStream pclr = new ByteArrayOutputStream();
        pclr.writeBytes(new byte[] {1, 0, 3, 7, 7, 7});
        for (int i = 0; i < 256; i++) {
            pclr.writeBytes(new byte[] {(byte) i, (byte) (255 - i), (byte) (i * 3)});
        }
        byte[] cmap = {0, 0, 1, 0, 0, 0, 1, 1, 0, 0, 1, 2};
        BufferedImage img = image(JpxSamples.jp2(codestream("gray-lossless.j2k"), 1, 8,
                JpxSamples.enumerated(ColourSpec.SRGB), JpxSamples.box("pclr", pclr.toByteArray()),
                JpxSamples.box("cmap", cmap)));
        assertEquals(3, img.getRaster().getNumBands());
        for (int y = 0; y < JpxSamples.HEIGHT; y += 5) {
            for (int x = 0; x < JpxSamples.WIDTH; x += 3) {
                int v = JpxSamples.value(x, y, 0);
                assertEquals(Arrays.toString(new int[] {v, 255 - v, v * 3 & 255}), Arrays.toString(rgb(img, x, y)));
            }
        }
    }

    @Test
    void channelDefinitionsOrderColoursAndFindTheAlpha() throws IOException {
        byte[] cdef = {0, 3, 0, 0, 0, 0, 0, 3, 0, 1, 0, 0, 0, 2, 0, 2, 0, 0, 0, 1};
        BufferedImage swapped = image(JpxSamples.jp2(codestream("rgb-lossless.j2k"), 3, 8,
                JpxSamples.enumerated(ColourSpec.SRGB), JpxSamples.box("cdef", cdef)));
        assertEquals(JpxSamples.value(7, 9, 2), rgb(swapped, 7, 9)[0]);
        assertEquals(JpxSamples.value(7, 9, 0), rgb(swapped, 7, 9)[2]);
        BufferedImage alpha = image(codestream("rgba.jp2"));
        assertEquals(Transparency.TRANSLUCENT, alpha.getTransparency());
        assertEquals(JpxSamples.value(30, 20, 3), alpha.getAlphaRaster().getSample(30, 20, 0));
        assertEquals(JpxSamples.value(30, 20, 1), rgb(alpha, 30, 20)[1]);
    }

    @Test
    void syccConvertsToRgb() throws IOException {
        BufferedImage img = image(JpxSamples.jp2(codestream("rgb-lossless.j2k"), 3, 8,
                JpxSamples.enumerated(ColourSpec.SYCC)));
        int x = 3;
        int y = 4;
        double l = JpxSamples.value(x, y, 0);
        double cb = JpxSamples.value(x, y, 1) - 128;
        double cr = JpxSamples.value(x, y, 2) - 128;
        assertEquals(Math.clamp(Math.round(l + 1.402 * cr), 0, 255), rgb(img, x, y)[0]);
        assertEquals(Math.clamp(Math.round(l - 0.344136 * cb - 0.714136 * cr), 0, 255), rgb(img, x, y)[1]);
        assertEquals(Math.clamp(Math.round(l + 1.772 * cb), 0, 255), rgb(img, x, y)[2]);
    }

    @Test
    void embeddedIccProfilesBecomeTheColourSpace() throws IOException {
        byte[] gray = ICC_Profile.getInstance(ColorSpace.CS_GRAY).getData();
        BufferedImage g = image(JpxSamples.jp2(codestream("gray-lossless.j2k"), 1, 8,
                JpxSamples.box("colr", new byte[] {2, 0, 0}, gray)));
        assertEquals(ColorSpace.TYPE_GRAY, g.getColorModel().getColorSpace().getType());
        byte[] srgb = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
        BufferedImage c = image(JpxSamples.jp2(codestream("rgb-lossless.j2k"), 3, 8,
                JpxSamples.box("colr", new byte[] {2, 0, 0}, srgb)));
        assertSame(ColorSpace.getInstance(ColorSpace.CS_sRGB), c.getColorModel().getColorSpace());
        BufferedImage broken = image(JpxSamples.jp2(codestream("rgb-lossless.j2k"), 3, 8,
                JpxSamples.box("colr", new byte[] {2, 0, 0}, new byte[200])));
        assertEquals(ColorSpace.TYPE_RGB, broken.getColorModel().getColorSpace().getType());
    }

    @Test
    void deepSamplesBecomeSixteenBitImages() throws IOException {
        BufferedImage g16 = image(codestream("gray16.j2k"));
        BufferedImage g12 = image(codestream("gray12.j2k"));
        assertEquals(DataBuffer.TYPE_USHORT, g16.getRaster().getDataBuffer().getDataType());
        assertEquals(JpxSamples.value16(11, 13), g16.getRaster().getSample(11, 13, 0));
        assertEquals(Math.round(JpxSamples.value12(11, 13) * 65535.0 / 4095), g12.getRaster().getSample(11, 13, 0));
    }

    @Test
    void greyscaleKeepsItsToneWhenConvertedToRgb() throws IOException {
        BufferedImage img = image(codestream("gray-lossless.j2k"));
        for (int x = 0; x < JpxSamples.WIDTH; x += 4) {
            int v = JpxSamples.value(x, 7, 0);
            assertEquals(v, img.getRGB(x, 7) & 0xFF);
            assertEquals(v, img.getRGB(x, 7) >> 16 & 0xFF);
        }
    }

    @Test
    void oneBitGreyBecomesABinaryImage() throws IOException {
        Siz siz = new Siz(9, 3, 0, 0, 9, 3, 0, 0, new int[] {1}, new boolean[1], new int[] {1}, new int[] {1});
        JpxRaster raster = new JpxRaster(siz, 0, 100);
        raster.set(0, 4, 1);
        BufferedImage img = new ImageComposer(raster, new Jp2Boxes()).compose();
        assertEquals(BufferedImage.TYPE_BYTE_BINARY, img.getType());
        assertEquals(1, img.getRaster().getSample(4, 0, 0));
        assertEquals(0, img.getRaster().getSample(3, 0, 0));
    }

    @Test
    void fourComponentsWithoutColourBoxesAreCmyk() throws IOException {
        Siz siz = new Siz(2, 2, 0, 0, 2, 2, 0, 0, new int[] {8, 8, 8, 8}, new boolean[4], new int[] {1, 1, 1, 1},
                new int[] {1, 1, 1, 1});
        BufferedImage img = new ImageComposer(new JpxRaster(siz, 0, 100), new Jp2Boxes()).compose();
        assertEquals(ColorSpace.TYPE_CMYK, img.getColorModel().getColorSpace().getType());
        assertFalse(img.getColorModel().hasAlpha());
        assertTrue(img.getRaster().getNumBands() == 4);
    }
}

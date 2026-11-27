package stirling.software.officeconvert.topdf.xps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

class XpsTiffTest {

    @Test
    void lzwRgbMatchesImageIo() throws IOException {
        same(tiff(320, 211, 3, 5, 1, pixels(320, 211, 3)));
    }

    @Test
    void lzwGrayWithPredictorMatchesImageIo() throws IOException {
        same(tiff(257, 190, 1, 5, 2, pixels(257, 190, 1)));
    }

    @Test
    void uncompressedRgbMatchesImageIo() throws IOException {
        same(tiff(64, 48, 3, 1, 1, pixels(64, 48, 3)));
    }

    @Test
    void truncatedLzwStillDecodes() throws IOException {
        byte[] t = tiff(120, 80, 3, 5, 1, pixels(120, 80, 3));
        byte[] cut = java.util.Arrays.copyOf(t, t.length - 900);
        ByteBuffer.wrap(cut).order(ByteOrder.LITTLE_ENDIAN).putInt(stripCountAt(cut), cut.length - stripOffset(cut));
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = XpsTiff.decode(doc, cut);
            assertNotNull(image);
            assertEquals(120, image.getImage().getWidth());
        }
    }

    @Test
    void unknownLayoutFallsBack() throws IOException {
        byte[] t = tiff(10, 10, 3, 5, 1, pixels(10, 10, 3));
        ByteBuffer.wrap(t).order(ByteOrder.LITTLE_ENDIAN).putShort(10 + 12 * 4 + 8, (short) 4);
        try (PDDocument doc = new PDDocument()) {
            assertNull(XpsTiff.decode(doc, t));
        }
    }

    @Test
    void hostileHeadersFallBackWithoutDecoding() throws IOException {
        byte[] huge = tiff(10, 10, 3, 5, 1, pixels(10, 10, 3));
        ByteBuffer b = ByteBuffer.wrap(huge).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(10 + 8, 200_000);
        b.putInt(10 + 12 + 8, 200_000);
        byte[] outside = tiff(10, 10, 3, 5, 1, pixels(10, 10, 3));
        ByteBuffer.wrap(outside).order(ByteOrder.LITTLE_ENDIAN).putInt(10 + 12 * 5 + 8, Integer.MAX_VALUE - 4);
        byte[] lying = tiff(10, 10, 3, 1, 1, pixels(10, 10, 3));
        ByteBuffer.wrap(lying).order(ByteOrder.LITTLE_ENDIAN).putInt(10 + 12 * 8 + 8, 5);
        byte[] garbage = tiff(64, 64, 3, 5, 1, pixels(64, 64, 3));
        int at = ByteBuffer.wrap(garbage).order(ByteOrder.LITTLE_ENDIAN).getInt(10 + 12 * 5 + 8);
        for (int i = at + 1; i < garbage.length; i++) {
            garbage[i] = (byte) 0xFF;
        }
        try (PDDocument doc = new PDDocument()) {
            assertNull(XpsTiff.decode(doc, huge));
            assertNull(XpsTiff.decode(doc, outside));
            assertNull(XpsTiff.decode(doc, lying));
            assertNull(XpsTiff.decode(doc, java.util.Arrays.copyOf(huge, 12)));
            PDImageXObject partial = XpsTiff.decode(doc, garbage);
            assertNotNull(partial);
            assertEquals(64, partial.getImage().getWidth());
        }
    }

    private static void same(byte[] tiff) throws IOException {
        BufferedImage expected = ImageIO.read(new ByteArrayInputStream(tiff));
        assertNotNull(expected);
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = XpsTiff.decode(doc, tiff);
            assertNotNull(image);
            BufferedImage actual = image.getImage();
            assertEquals(expected.getWidth(), actual.getWidth());
            assertEquals(expected.getHeight(), actual.getHeight());
            for (int y = 0; y < expected.getHeight(); y++) {
                for (int x = 0; x < expected.getWidth(); x++) {
                    if (expected.getRaster().getNumBands() == 1) {
                        assertEquals(expected.getRaster().getSample(x, y, 0), actual.getRaster().getSample(x, y, 0),
                                "pixel " + x + "," + y);
                    } else {
                        assertEquals(expected.getRGB(x, y), actual.getRGB(x, y), "pixel " + x + "," + y);
                    }
                }
            }
        }
    }

    private static byte[] pixels(int w, int h, int spp) {
        Random r = new Random(w * 31L + h);
        byte[] p = new byte[w * h * spp];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int c = 0; c < spp; c++) {
                    int v = (x * (c + 1) + y * 3) & 0xFF;
                    if (r.nextInt(4) == 0) {
                        v = r.nextInt(256);
                    }
                    p[(y * w + x) * spp + c] = (byte) v;
                }
            }
        }
        return p;
    }

    private static int stripOffset(byte[] t) {
        return ByteBuffer.wrap(t).order(ByteOrder.LITTLE_ENDIAN).getInt(10 + 12 * 5 + 8);
    }

    private static int stripCountAt(byte[] t) {
        return 10 + 12 * 8 + 8;
    }

    static byte[] tiff(int w, int h, int spp, int compression, int predictor, byte[] pixels) {
        byte[] data = pixels.clone();
        if (predictor == 2) {
            int stride = w * spp;
            for (int y = 0; y < h; y++) {
                for (int i = y * stride + stride - 1; i >= y * stride + spp; i--) {
                    data[i] -= data[i - spp];
                }
            }
        }
        byte[] strip = compression == 5 ? lzw(data) : data;
        int entries = 10;
        int ifd = 8;
        int extra = ifd + 2 + entries * 12 + 4;
        int stripAt = extra + 8;
        ByteBuffer b = ByteBuffer.allocate(stripAt + strip.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifd);
        b.putShort((short) entries);
        entry(b, 256, 4, 1, w);
        entry(b, 257, 4, 1, h);
        if (spp == 3) {
            entry(b, 258, 3, 3, extra);
        } else {
            entry(b, 258, 3, 1, 8);
        }
        entry(b, 259, 3, 1, compression);
        entry(b, 262, 3, 1, spp == 3 ? 2 : 1);
        entry(b, 273, 4, 1, stripAt);
        entry(b, 277, 3, 1, spp);
        entry(b, 278, 4, 1, h);
        entry(b, 279, 4, 1, strip.length);
        entry(b, 317, 3, 1, predictor);
        b.putInt(0);
        b.putShort((short) 8).putShort((short) 8).putShort((short) 8).putShort((short) 0);
        b.put(strip);
        return b.array();
    }

    private static void entry(ByteBuffer b, int tag, int type, int count, int value) {
        b.putShort((short) tag).putShort((short) type).putInt(count);
        if (type == 3 && count == 1) {
            b.putShort((short) value).putShort((short) 0);
        } else {
            b.putInt(value);
        }
    }

    static byte[] lzw(byte[] data) {
        Bits out = new Bits();
        Map<Integer, Integer> dict = new HashMap<>();
        int next = 258;
        int width = 9;
        out.write(256, width);
        int w = -1;
        for (byte bb : data) {
            int k = bb & 0xFF;
            if (w < 0) {
                w = k;
                continue;
            }
            Integer c = dict.get(w << 8 | k);
            if (c != null) {
                w = c;
                continue;
            }
            out.write(w, width);
            dict.put(w << 8 | k, next++);
            if (next == 4094) {
                out.write(256, width);
                dict.clear();
                next = 258;
                width = 9;
            } else if (next > (1 << width) - 1) {
                width++;
            }
            w = k;
        }
        out.write(w, width);
        next++;
        if (next > (1 << width) - 1 && width < 12) {
            width++;
        }
        out.write(257, width);
        return out.bytes();
    }

    private static final class Bits {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private long acc;

        private int have;

        void write(int code, int width) {
            acc = acc << width | code;
            have += width;
            while (have >= 8) {
                out.write((int) (acc >>> (have - 8)) & 0xFF);
                have -= 8;
            }
        }

        byte[] bytes() {
            if (have > 0) {
                out.write((int) (acc << (8 - have)) & 0xFF);
            }
            return out.toByteArray();
        }
    }
}

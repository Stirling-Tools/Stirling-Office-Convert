package stirling.software.officeconvert.lineart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Random;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CodecsTest {

    @ParameterizedTest
    @EnumSource(value = LineArt.Codec.class, names = {"JBIG2", "CCITT_G4", "FLATE"})
    void everyCodecDecodesToTheSameBitmap(LineArt.Codec codec) throws IOException {
        for (int[] size : new int[][] {{301, 203}, {1, 1}, {8, 3}, {9, 40}, {1000, 3}, {3, 500}}) {
            BufferedImage picture = Pictures.drawing(size[0], size[1], Color.BLACK, Color.WHITE, size[0] * 31L + size[1]);
            assertRoundTrip(picture, codec);
        }
        assertRoundTrip(noise(257, 129, 7), codec);
        assertRoundTrip(solid(77, 33, Color.BLACK), codec);
        assertRoundTrip(solid(77, 33, Color.WHITE), codec);
        assertRoundTrip(stripes(150, 90), codec);
    }

    @ParameterizedTest
    @EnumSource(value = LineArt.Codec.class, names = {"JBIG2", "CCITT_G4", "FLATE"})
    void longRunsOfEveryLengthSurvive(LineArt.Codec codec) throws IOException {
        int[] runs = {1, 2, 3, 63, 64, 65, 127, 128, 1727, 1728, 1729, 1791, 1792, 1856, 2559, 2560, 2561, 5000, 5121};
        int w = 0;
        for (int r : runs) {
            w += r;
        }
        BufferedImage image = solid(w, runs.length * 2, Color.WHITE);
        for (int y = 0; y < runs.length * 2; y++) {
            int x = 0;
            for (int i = 0; i < runs.length; i++) {
                int run = runs[(i + y) % runs.length];
                if ((i + y) % 2 == 0) {
                    for (int k = 0; k < run; k++) {
                        image.setRGB(x + k, y, 0);
                    }
                }
                x += run;
            }
        }
        assertRoundTrip(image, codec);
    }

    @Test
    void jbig2BeatsGroup4OnLineArt() throws IOException {
        Bitmap bitmap = LineArt.binarize(Pictures.drawing(1200, 900, Color.BLACK, Color.WHITE, 3),
                LineArt.Options.defaults().method(LineArt.Method.FIXED));
        int jbig2 = Codecs.jbig2(bitmap).data().length;
        int g4 = Codecs.g4(bitmap).data().length;
        assertTrue(jbig2 < g4, jbig2 + " >= " + g4);
    }

    @Test
    void repeatedShapesUseASymbolDictionaryAndStayExact() throws IOException {
        BufferedImage page = glyphs(900, 700, 11);
        Bitmap bitmap = LineArt.binarize(page, LineArt.Options.defaults().method(LineArt.Method.FIXED));
        byte[] symbols = Jbig2Symbols.encode(bitmap);
        byte[] generic = Jbig2Generic.encode(bitmap);
        assertTrue(symbols != null && symbols.length < generic.length * 3 / 4, symbols.length + " against " + generic.length);
        assertRoundTrip(page, LineArt.Codec.JBIG2);
        assertRoundTrip(glyphs(37, 29, 2), LineArt.Codec.JBIG2);
        assertRoundTrip(glyphs(3000, 40, 5), LineArt.Codec.JBIG2);
    }

    @Test
    void symbolsGiveUpPastTheRunLimitButNotBeforeIt() {
        Bitmap many = Bitmap.blank(4100, 1100);
        for (int y = 0; y < many.height(); y++) {
            for (int x = 0; x < many.width(); x += 2) {
                many.set(x, y);
            }
        }
        assertNull(Jbig2Symbols.encode(many));
        Bitmap clean = LineArt.binarize(glyphs(901, 700, 4), LineArt.Options.defaults().method(LineArt.Method.FIXED));
        assertNotNull(Jbig2Symbols.encode(clean));
    }

    static BufferedImage glyphs(int w, int h, long seed) {
        BufferedImage image = solid(w, h, Color.WHITE);
        Random random = new Random(seed);
        int[][] shapes = new int[12][];
        for (int i = 0; i < shapes.length; i++) {
            shapes[i] = new int[] {3 + random.nextInt(14), 4 + random.nextInt(18), random.nextInt()};
        }
        for (int y = 2; y + 24 < h; y += 26) {
            for (int x = 1; x + 20 < w; ) {
                int[] s = shapes[random.nextInt(shapes.length)];
                for (int dy = 0; dy < s[1]; dy++) {
                    for (int dx = 0; dx < s[0]; dx++) {
                        boolean edge = dx == 0 || dy == 0 || dx == s[0] - 1 || dy == s[1] - 1;
                        if (edge || ((s[2] >>> ((dx * 7 + dy * 3) & 31)) & 1) == 1) {
                            image.setRGB(x + dx, y + 20 - s[1] + dy, 0);
                        }
                    }
                }
                x += s[0] + 1 + random.nextInt(4);
            }
            if (random.nextInt(3) == 0) {
                image.setRGB(random.nextInt(w), y + 22, 0);
            }
        }
        return image;
    }

    @Test
    void smallestPicksTheShortestStream() throws IOException {
        Bitmap bitmap = LineArt.binarize(noise(64, 64, 1), LineArt.Options.defaults().method(LineArt.Method.FIXED));
        int smallest = Codecs.encode(bitmap, LineArt.Codec.SMALLEST).data().length;
        for (LineArt.Codec c : new LineArt.Codec[] {LineArt.Codec.JBIG2, LineArt.Codec.CCITT_G4, LineArt.Codec.FLATE}) {
            assertTrue(smallest <= Codecs.encode(bitmap, c).data().length, c.name());
        }
    }

    private static void assertRoundTrip(BufferedImage picture, LineArt.Codec codec) throws IOException {
        LineArt.Options options = LineArt.Options.defaults().method(LineArt.Method.FIXED).codec(codec);
        Bitmap expected = LineArt.binarize(picture, options);
        try (PDDocument doc = Pictures.reload(convert(picture, options))) {
            PDImageXObject image = Pictures.firstImage(doc);
            assertEquals(1, image.getBitsPerComponent());
            COSName filter = image.getCOSObject().getCOSName(COSName.FILTER);
            assertEquals(switch (codec) {
                case JBIG2 -> COSName.JBIG2_DECODE;
                case CCITT_G4 -> COSName.CCITTFAX_DECODE;
                default -> COSName.FLATE_DECODE;
            }, filter);
            BufferedImage decoded = image.getImage();
            for (int y = 0; y < picture.getHeight(); y++) {
                for (int x = 0; x < picture.getWidth(); x++) {
                    assertEquals(expected.ink(x, y), Pictures.dark(decoded.getRGB(x, y)),
                            codec + " " + picture.getWidth() + "x" + picture.getHeight() + " at " + x + "," + y);
                }
            }
        }
    }

    private static PDDocument convert(BufferedImage picture, LineArt.Options options) throws IOException {
        PDDocument doc = Pictures.onePage(picture);
        LineArt.Result result = LineArt.convert(doc, options);
        assertEquals(1, result.converted(), result.warnings().toString());
        return doc;
    }

    private static BufferedImage noise(int w, int h, long seed) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(seed);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setRGB(x, y, random.nextBoolean() ? 0 : 0xFFFFFF);
            }
        }
        return image;
    }

    private static BufferedImage solid(int w, int h, Color colour) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                image.setRGB(x, y, colour.getRGB());
            }
        }
        return image;
    }

    private static BufferedImage stripes(int w, int h) {
        BufferedImage image = solid(w, h, Color.WHITE);
        for (int y = 0; y < h; y++) {
            if (y / 10 % 2 == 0) {
                continue;
            }
            for (int x = 0; x < w; x++) {
                if (x % 7 < 3) {
                    image.setRGB(x, y, 0);
                }
            }
        }
        return image;
    }
}

package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JpxSyntheticPixelsTest {

    @Test
    void alphaChannelKeepsItsSampleValues() throws IOException {
        BufferedImage image = JpxDecoder.decode(SyntheticCodestream.rgba()).toBufferedImage();
        assertEquals(true, image.getColorModel().hasAlpha());
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int sign = ((x + 3 * y + 3) & 1) == 0 ? 1 : -1;
                assertEquals(128 + sign, image.getRGB(x, y) >>> 24);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void waveletsReconstructConstantPixelsAtEveryResolution(boolean reversible) throws IOException {
        for (int reduce = 0; reduce <= 2; reduce++) {
            JpxRaster raster = JpxDecoder.decode(SyntheticCodestream.wavelet(reversible),
                    JpxOptions.defaults().withReduce(reduce)).raster();
            assertEquals(4 >> reduce, raster.width());
            assertEquals(4 >> reduce, raster.height());
            for (int y = 0; y < raster.height(); y++) {
                for (int x = 0; x < raster.width(); x++) {
                    int coefficient = reversible ? 1 : (int) Math.rint(3 * Math.scalb(0.5, 8 - 9));
                    assertEquals(128 + coefficient, raster.sample(0, x, y));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void progressionOrdersPreserveSignedCoefficients(int order) throws IOException {
        check(7, 5, 3, 8, order, true, false, false, false, false);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void irreversibleQuantizationRoundsComputedCoefficients(int order) throws IOException {
        check(7, 5, 3, 8, order, false, false, false, false, false);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void progressionChangesAndPacketMarkersPreservePixels(int order) throws IOException {
        check(7, 5, 3, 8, order, true, true, true, false, false);
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 16})
    void tilesKeepTheirLocationAndSamplePrecision(int depth) throws IOException {
        check(7, 5, 1, depth, 0, true, true, false, true, false);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void subsampledComponentsKeepTheirPixels(int order) throws IOException {
        check(8, 6, 3, 8, order, true, true, false, false, true);
    }

    private static void check(int width, int height, int components, int depth, int order,
            boolean reversible, boolean markers, boolean poc, boolean tiles, boolean subsampled)
            throws IOException {
        JpxRaster raster = JpxDecoder.decode(SyntheticCodestream.samples(width, height, components,
                depth, order, reversible, markers, poc, tiles, subsampled)).raster();
        assertEquals(width, raster.width());
        assertEquals(height, raster.height());
        assertEquals(components, raster.components());
        for (int c = 0; c < components; c++) {
            int step = subsampled && c > 0 ? 2 : 1;
            assertEquals(depth, raster.depth(c));
            assertEquals(width / step, raster.width(c));
            assertEquals(height / step, raster.height(c));
            for (int y = 0; y < raster.height(c); y++) {
                for (int x = 0; x < raster.width(c); x++) {
                    int sign = ((x * step + 3 * y * step + c) & 1) == 0 ? 1 : -1;
                    int coefficient = reversible ? sign : (int) Math.rint(sign * 1.5);
                    assertEquals((1 << (depth - 1)) + coefficient, raster.sample(c, x, y),
                            "component " + c + " at " + x + "," + y);
                }
            }
        }
    }
}

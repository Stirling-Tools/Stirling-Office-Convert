package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Random;

import org.junit.jupiter.api.Test;

class JpxEncoderTest {

    static JpxEncoder.Planes photo(int w, int h, int comps, int depth, long seed) {
        Random rnd = new Random(seed);
        int[][] s = new int[comps][w * h];
        int max = (1 << depth) - 1;
        for (int c = 0; c < comps; c++) {
            double fx = 0.02 + 0.01 * c;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    double v = 0.5 + 0.3 * Math.sin(x * fx + y * 0.013) * Math.cos(y * 0.031 - c)
                            + 0.1 * ((x / 17 + y / 23) % 2) + 0.05 * rnd.nextGaussian();
                    s[c][y * w + x] = Math.clamp(Math.round(v * max), 0, max);
                }
            }
        }
        int[] depths = new int[comps];
        java.util.Arrays.fill(depths, depth);
        JpxEncoder.Colour colour = comps >= 3 ? JpxEncoder.Colour.SRGB : JpxEncoder.Colour.GREY;
        return new JpxEncoder.Planes(w, h, s, depths, null, colour, -1);
    }

    static int[][] decoded(byte[] data) throws IOException {
        JpxImage img = JpxDecoder.decode(data);
        JpxRaster r = img.raster();
        int[][] out = new int[r.components()][r.width(0) * r.height(0)];
        for (int c = 0; c < r.components(); c++) {
            for (int y = 0; y < r.height(c); y++) {
                for (int x = 0; x < r.width(c); x++) {
                    out[c][y * r.width(c) + x] = r.sample(c, x, y);
                }
            }
        }
        return out;
    }

    static double psnr(JpxEncoder.Planes p, int[][] d) {
        double se = 0;
        long n = 0;
        int[][] s = p.samples();
        for (int c = 0; c < s.length; c++) {
            for (int i = 0; i < p.width() * p.height(); i++) {
                double e = s[c][i] - d[c][i];
                se += e * e;
                n++;
            }
        }
        double peak = (1 << p.depths()[0]) - 1;
        return 10 * Math.log10(peak * peak / (se / n));
    }

    @Test
    void losslessRoundTripsExactly() throws IOException {
        int[][] sizes = {{64, 64}, {131, 77}, {1, 9}, {300, 5}, {257, 263}};
        for (int[] size : sizes) {
            for (int comps : new int[] {1, 3, 4}) {
                for (int depth : new int[] {1, 8, 12, 16}) {
                    JpxEncoder.Planes p = photo(size[0], size[1], comps, depth, size[0] * 31L + comps);
                    byte[] data = JpxEncoder.encode(p, JpxEncoder.Options.exact());
                    int[][] d = decoded(data);
                    for (int c = 0; c < comps; c++) {
                        assertArrayEquals(p.samples()[c], d[c], size[0] + "x" + size[1] + " c" + comps + " d"
                                + depth);
                    }
                }
            }
        }
    }

    @Test
    void losslessTilesLayersAndOrders() throws IOException {
        JpxEncoder.Planes p = photo(200, 150, 3, 8, 7);
        for (JpxEncoder.Order order : JpxEncoder.Order.values()) {
            JpxEncoder.Options o = JpxEncoder.Options.exact().withTileSize(64).withLayers(3).withOrder(order)
                    .withBlockSize(32).withCodestream(true);
            int[][] d = decoded(JpxEncoder.encode(p, o));
            for (int c = 0; c < 3; c++) {
                assertArrayEquals(p.samples()[c], d[c], order.name());
            }
        }
    }

    @Test
    void lossyMeetsRateAndQuality() throws IOException {
        JpxEncoder.Planes p = photo(256, 192, 3, 8, 3);
        for (double bpp : new double[] {0.25, 0.5, 1, 2}) {
            byte[] data = JpxEncoder.encode(p, JpxEncoder.Options.rate(bpp));
            assertTrue(data.length <= bpp * 256 * 192 / 8, bpp + " bpp gave " + data.length);
            assertTrue(data.length >= 0.8 * bpp * 256 * 192 / 8, bpp + " bpp gave only " + data.length);
            double q = psnr(p, decoded(data));
            assertTrue(q > 20, bpp + " bpp gave " + q + " dB");
        }
        for (double target : new double[] {30, 36, 42}) {
            byte[] data = JpxEncoder.encode(p, JpxEncoder.Options.quality(target));
            double q = psnr(p, decoded(data));
            assertEquals(target, q, 1.0, "target " + target + " gave " + q + " at " + data.length);
        }
    }

    static JpxEncoder.Planes subsampled(int w, int h, int[] dx, int[] dy, int depth, long seed) {
        Random rnd = new Random(seed);
        int n = dx.length;
        int[][] s = new int[n][];
        int max = (1 << depth) - 1;
        for (int c = 0; c < n; c++) {
            int cw = Math.ceilDiv(w, dx[c]);
            int ch = Math.ceilDiv(h, dy[c]);
            s[c] = new int[cw * ch];
            for (int y = 0; y < ch; y++) {
                for (int x = 0; x < cw; x++) {
                    double v = 0.5 + 0.35 * Math.sin(x * dx[c] * 0.03 + c) * Math.cos(y * dy[c] * 0.021)
                            + 0.04 * rnd.nextGaussian();
                    s[c][y * cw + x] = Math.clamp(Math.round(v * max), 0, max);
                }
            }
        }
        int[] depths = new int[n];
        java.util.Arrays.fill(depths, depth);
        JpxEncoder.Colour colour = n == 3 ? JpxEncoder.Colour.SYCC : JpxEncoder.Colour.GREY;
        return new JpxEncoder.Planes(w, h, s, depths, null, colour, -1, dx, dy);
    }

    static int[][] nativeSamples(byte[] data) throws IOException {
        JpxRaster r = JpxDecoder.decode(data).raster();
        int[][] out = new int[r.components()][];
        for (int c = 0; c < r.components(); c++) {
            out[c] = new int[r.width(c) * r.height(c)];
            for (int y = 0; y < r.height(c); y++) {
                for (int x = 0; x < r.width(c); x++) {
                    out[c][y * r.width(c) + x] = r.sample(c, x, y);
                }
            }
        }
        return out;
    }

    @Test
    void subsampledComponentsRoundTripExactly() throws IOException {
        int[][][] factors = {{{1, 2, 2}, {1, 2, 2}}, {{1, 2, 1, 2}, {1, 1, 2, 2}}, {{3, 3, 3}, {2, 2, 2}},
            {{1, 4}, {1, 1}}};
        for (int[][] f : factors) {
            for (int[] size : new int[][] {{131, 77}, {12, 9}, {300, 256}}) {
                JpxEncoder.Planes p = subsampled(size[0], size[1], f[0], f[1], 8, size[0]);
                for (JpxEncoder.Order order : JpxEncoder.Order.values()) {
                    JpxEncoder.Options o = JpxEncoder.Options.exact().withOrder(order).withLayers(2).withTileSize(64);
                    byte[] data = JpxEncoder.encode(p, o);
                    int[][] d = nativeSamples(data);
                    for (int c = 0; c < f[0].length; c++) {
                        assertArrayEquals(p.samples()[c], d[c], java.util.Arrays.toString(f[0]) + " " + size[0]
                                + " " + order + " component " + c);
                    }
                }
                byte[] whole = JpxEncoder.encode(p, JpxEncoder.Options.exact().withCodestream(true));
                assertArrayEquals(p.samples()[f[0].length - 1], nativeSamples(whole)[f[0].length - 1]);
            }
        }
    }

    @Test
    void subsampledComponentsMeetARateAndKeepTheirShape() throws IOException {
        JpxEncoder.Planes p = subsampled(320, 240, new int[] {1, 2, 2}, new int[] {1, 2, 2}, 8, 3);
        byte[] data = JpxEncoder.encode(p, JpxEncoder.Options.rate(1));
        assertTrue(data.length <= 320 * 240 / 8, "size " + data.length);
        int[][] d = nativeSamples(data);
        JpxEncoder.Planes back = new JpxEncoder.Planes(320, 240, d, p.depths(), null, JpxEncoder.Colour.SYCC, -1,
                p.dx(), p.dy());
        assertTrue(p.psnr(back) > 26, "psnr " + p.psnr(back));
        assertTrue(p.ssim(back) > 0.6, "ssim " + p.ssim(back));
        assertThrows(IllegalArgumentException.class, () -> new JpxEncoder.Planes(10, 10, new int[][] {new int[25]},
                new int[] {8}, null, JpxEncoder.Colour.GREY, -1, new int[] {0}, new int[] {1}));
    }

    @Test
    void threadsDoNotChangeTheOutput() throws IOException {
        JpxEncoder.Planes p = photo(700, 450, 3, 8, 21);
        for (JpxEncoder.Options o : new JpxEncoder.Options[] {JpxEncoder.Options.exact(),
            JpxEncoder.Options.rate(1).withTileSize(256), JpxEncoder.Options.quality(38).withBlockSize(32)}) {
            byte[] one = JpxEncoder.encode(p, o.withThreads(1));
            assertArrayEquals(one, JpxEncoder.encode(p, o.withThreads(4)));
            assertArrayEquals(one, JpxEncoder.encode(p, o));
        }
    }

    @Test
    void anInterruptedThreadStopsEncodingOnEveryThreadCount() {
        JpxEncoder.Planes p = photo(512, 512, 3, 8, 9);
        for (int threads : new int[] {1, 4}) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(java.io.InterruptedIOException.class,
                        () -> JpxEncoder.encode(p, JpxEncoder.Options.exact().withThreads(threads)));
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void invalidOptionsAndPlanesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> JpxEncoder.Options.exact().withLevels(13));
        assertThrows(IllegalArgumentException.class, () -> JpxEncoder.Options.exact().withLayers(0));
        assertThrows(IllegalArgumentException.class, () -> JpxEncoder.Options.exact().withBlockSize(128));
        assertThrows(IllegalArgumentException.class, () -> JpxEncoder.Options.rate(-1));
        assertThrows(IllegalArgumentException.class, () -> JpxEncoder.Options.exact().withThreads(-1));
        assertThrows(IllegalArgumentException.class, () -> new JpxEncoder.Planes(10, 10, new int[][] {new int[50]},
                new int[] {8}, null, JpxEncoder.Colour.GREY, -1));
        assertThrows(IllegalArgumentException.class, () -> new JpxEncoder.Planes(10, 10, new int[][] {new int[100]},
                new int[] {17}, null, JpxEncoder.Colour.GREY, -1));
    }

    @Test
    void exactModeRefusesSamplesItCannotKeep() {
        java.awt.color.ColorSpace grey = java.awt.color.ColorSpace.getInstance(java.awt.color.ColorSpace.CS_GRAY);
        java.awt.image.ComponentColorModel cm = new java.awt.image.ComponentColorModel(grey, false, false,
                java.awt.Transparency.OPAQUE, java.awt.image.DataBuffer.TYPE_FLOAT);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(cm, cm.createCompatibleWritableRaster(8, 8),
                false, null);
        img.getRaster().setSample(3, 3, 0, 0.123456f);
        assertThrows(IllegalArgumentException.class, () -> JpxEncoder.encode(img, JpxEncoder.Options.exact()));
        assertDoesNotThrow(() -> JpxEncoder.encode(img, JpxEncoder.Options.rate(1)));
    }

    @Test
    void samplesChangedAfterCheckingAreRefused() {
        int[] row = new int[16];
        JpxEncoder.Planes p = new JpxEncoder.Planes(4, 4, new int[][] {row}, new int[] {1}, null,
                JpxEncoder.Colour.GREY, -1);
        row[5] = 2;
        assertThrows(IllegalArgumentException.class, () -> JpxEncoder.encode(p, JpxEncoder.Options.exact()));
        p.samples()[0][5] = 3;
        assertThrows(IllegalArgumentException.class, () -> JpxEncoder.encode(p, JpxEncoder.Options.rate(1)));
    }

    @Test
    void aNegativeMeasuredPsnrStillSearches() {
        JpxEncoder.Planes source = new JpxEncoder.Planes(1, 1, new int[][] {{1}, {0}}, new int[] {1, 16}, null,
                JpxEncoder.Colour.GREY, 1);
        JpxEncoder.Planes reference = new JpxEncoder.Planes(1, 1, new int[][] {{1}, {65535}}, new int[] {1, 16},
                null, JpxEncoder.Colour.GREY, 1);
        assertTrue(source.psnr(reference) < 0);
        assertDoesNotThrow(() -> JpxEncoder.encodeAsClose(source, reference, 1 << 20));
    }

    @Test
    void subsampledPicturesAreComparedOnTheirOwnGrids() throws IOException {
        JpxEncoder.Planes source = subsampled(64, 64, new int[] {1, 2, 2}, new int[] {1, 2, 2}, 8, 5);
        int[][] s = source.samples();
        for (int c = 0; c < s.length; c++) {
            s[c] = s[c].clone();
            for (int i = 0; i < s[c].length; i += 7) {
                s[c][i] = Math.clamp(s[c][i] + 9, 0, 255);
            }
        }
        JpxEncoder.Planes reference = new JpxEncoder.Planes(64, 64, s, source.depths(), null, source.colour(), -1,
                source.dx(), source.dy());
        byte[] data = JpxEncoder.encodeAsClose(source, reference, 1 << 20);
        assertNotNull(data);
        assertEquals(3, nativeSamples(data).length);
    }

    @Test
    void colourOutsideThePartOneSetIsBrandedJpx() throws IOException {
        JpxEncoder.Planes cmyk = photo(16, 16, 4, 8, 2);
        JpxEncoder.Planes tagged = new JpxEncoder.Planes(16, 16, cmyk.samples(), cmyk.depths(), null,
                JpxEncoder.Colour.CMYK, -1);
        byte[] jpx = JpxEncoder.encode(tagged, JpxEncoder.Options.exact());
        assertEquals("jpx ", new String(jpx, 20, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("rreq", new String(jpx, 36, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals(21, java.nio.ByteBuffer.wrap(jpx, 32, 4).getInt());
        int[][] back = nativeSamples(jpx);
        for (int c = 0; c < 4; c++) {
            assertArrayEquals(cmyk.samples()[c], back[c]);
        }
        byte[] jp2 = JpxEncoder.encode(photo(16, 16, 3, 8, 2), JpxEncoder.Options.exact());
        assertEquals("jp2 ", new String(jp2, 20, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("jp2h", new String(jp2, 36, 4, java.nio.charset.StandardCharsets.US_ASCII));
    }
}

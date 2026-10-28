package stirling.software.officeconvert.photosplit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.function.Executable;

import stirling.software.officeconvert.photosplit.SyntheticScan.Style;

class HardeningTest {

    @TempDir
    Path dir;

    private static BufferedImage scan() {
        return new SyntheticScan(900, 700, 21)
                .photo(320, 240, 250, 220, 4, Style.GRADIENT, 0)
                .photo(300, 260, 640, 460, -3, Style.TEXTURE, 0)
                .render()
                .scan();
    }

    @Test
    void smallTolerancesSplitInsteadOfThrowing() {
        BufferedImage scan = scan();
        for (PhotoSplitter.Method method : PhotoSplitter.Method.values()) {
            for (int tolerance : new int[] {0, 1, 5}) {
                PhotoSplitter.Options options = PhotoSplitter.Options.defaults().method(method).tolerance(tolerance);
                List<PhotoSplitter.Photo> photos = assertDoesNotThrow(() -> PhotoSplitter.split(scan, options),
                        method + " tolerance " + tolerance);
                assertTrue(!photos.isEmpty(), method + " tolerance " + tolerance);
            }
        }
    }

    @Test
    void nonsenseOptionsAreRefused() {
        PhotoSplitter.Options defaults = PhotoSplitter.Options.defaults();
        assertThrows(IllegalArgumentException.class, () -> defaults.tolerance(-1));
        assertThrows(IllegalArgumentException.class, () -> defaults.tolerance(256));
        assertThrows(IllegalArgumentException.class, () -> defaults.minArea(-1));
        assertThrows(IllegalArgumentException.class, () -> defaults.minContourArea(-1));
        assertDoesNotThrow(() -> defaults.tolerance(0).tolerance(255).minArea(0).minContourArea(0));
    }

    @Test
    void houghAccumulatorSizeCannotOverflow() {
        float[] thetas = assertDoesNotThrow(() -> HoughLines.thetas(new byte[0], 10, 10_000_000, 200));
        assertEquals(0, thetas.length);
    }

    @Test
    void tallThinCropAngleUsesABoundedAccumulator() {
        int w = 20;
        int h = 100_000;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                px[y * w + x] = x < w / 2 ? 0x202020 : 0xF0F0F0;
            }
        }
        ScanImage crop = new ScanImage(w, h, px);
        long bytes = allocated(() -> ScriptMethod.angle(crop, 10));
        assertTrue(bytes < 40L << 20, "allocated " + (bytes >> 20) + " MB");
    }

    @Test
    void largeCropAngleMatchesTheSamePictureAnalysedAtFullSize() {
        ScanImage small = tilted(1500);
        ScanImage large = tilted(3000);
        double expected = ScriptMethod.angle(small, 10);
        double[] angle = new double[1];
        long bytes = allocated(() -> angle[0] = ScriptMethod.angle(large, 10));
        assertTrue(bytes < 100L << 20, "allocated " + (bytes >> 20) + " MB");
        assertTrue(Math.abs(expected) > 10, "expected " + expected);
        assertEquals(expected, angle[0], 1.5);
    }

    private static ScanImage tilted(int n) {
        BufferedImage image = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(246, 246, 243));
        g.fillRect(0, 0, n, n);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.rotate(Math.toRadians(20), n / 2.0, n / 2.0);
        g.setColor(new Color(60, 90, 150));
        g.fillRect(n / 5, n * 3 / 10, n * 3 / 5, n * 2 / 5);
        g.dispose();
        return ScanImage.of(image);
    }

    @Test
    void pngWithPdfMarkerInItsFirstKilobyteIsReadAsAnImage() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(scan(), "png", out);
        byte[] png = out.toByteArray();
        byte[] text = "Comment\0%PDF-1.7".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        chunk.write(new byte[] {0, 0, 0, (byte) text.length});
        byte[] typed = new byte[4 + text.length];
        System.arraycopy("tEXt".getBytes(StandardCharsets.ISO_8859_1), 0, typed, 0, 4);
        System.arraycopy(text, 0, typed, 4, text.length);
        chunk.write(typed);
        CRC32 crc = new CRC32();
        crc.update(typed);
        long c = crc.getValue();
        chunk.write(new byte[] {(byte) (c >> 24), (byte) (c >> 16), (byte) (c >> 8), (byte) c});
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(png, 0, 33);
        file.write(chunk.toByteArray());
        file.write(png, 33, png.length - 33);
        Path input = dir.resolve("marked.png");
        Files.write(input, file.toByteArray());
        List<Path> written = PhotoSplitter.split(input, dir.resolve("out"), PhotoSplitter.Options.defaults());
        assertEquals(2, written.size());
    }

    @Test
    void longPixelLoopsStopWhenInterrupted() {
        int n = 240;
        byte[] lShape = new byte[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                lShape[y * n + x] = x < n / 2 || y >= n / 2 ? (byte) 1 : 0;
            }
        }
        Region region = new Region(0, 0, n - 1, n - 1, lShape.clone());
        int[] px = new int[n * n];
        for (int i = 0; i < px.length; i++) {
            px[i] = lShape[i] == 1 ? 0x3050A0 : 0xF6F6F3;
        }
        ScanImage scan = new ScanImage(n, n, px);
        PhotoSplitter.Options options = PhotoSplitter.Options.defaults().minArea(100).minContourArea(10);
        Bed bed = Bed.fit(scan, px, 30);
        assertStops("hough voting", () -> HoughLines.thetas(new byte[1], n, n, 200));
        assertStops("dilate", () -> Morphology.dilate(lShape.clone(), n, n, 2));
        assertStops("contours", () -> OuterContours.find(lShape.clone(), n, n));
        assertStops("label", () -> Region.label(lShape.clone(), n, n));
        assertStops("bed fit", () -> Bed.fit(scan, px, 30));
        assertStops("bed foreground", () -> bed.foreground(px, n, n, 10, new byte[n * n]));
        assertStops("pieces", () -> Pieces.separate(region, options));
        assertStops("seams", () -> Seams.split(List.of(region), new byte[n * n], scan, options));
        assertStops("steps", () -> Steps.cut(region, scan, options));
        assertStops("edge fit", () -> EdgeFit.refine(scan, new RotatedRect(n / 2.0, n / 2.0, 200, 200, 0)));
    }

    private static void assertStops(String what, Executable work) {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, work, what);
        } finally {
            Thread.interrupted();
        }
    }

    private static long allocated(Runnable work) {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        work.run();
        return threads.getCurrentThreadAllocatedBytes() - before;
    }
}

package stirling.software.officeconvert.photosplit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FullSizeMemoryTest {

    @TempDir
    Path dir;

    @Test
    void tallThinScriptSplitKeepsOneFullSizeCopy() throws IOException {
        Path input = scan(24, 200_000, 3);
        long decoded = decodedBytes(input);
        long bytes = allocated(() -> assertEquals(1, PhotoSplitter.split(input, dir.resolve("script"),
                PhotoSplitter.Options.defaults().method(PhotoSplitter.Method.SCRIPT)).size()));
        assertTrue(bytes < 4 * decoded, "allocated " + (bytes >> 20) + " MB for a " + (decoded >> 20) + " MB scan");
    }

    @Test
    void straightenExportWritesCropsWithoutCopyingThem() throws IOException {
        Path input = scan(1600, 2400, 200);
        long decoded = decodedBytes(input);
        PhotoSplitter.Options options = PhotoSplitter.Options.defaults();
        long analysis = allocated(() -> assertEquals(1, StraightenMethod.find(Source.of(ImageIO.read(input.toFile())), options).size()));
        long whole = allocated(() -> assertEquals(1, PhotoSplitter.split(input, dir.resolve("straighten"), options).size()));
        long export = whole - analysis;
        assertTrue(export < decoded / 4, "export allocated " + (export >> 20) + " MB for a " + (decoded >> 20) + " MB scan");
    }

    @Test
    void decodedScanIsReleasedOnceItHasBeenConverted() throws Exception {
        int w = 5000;
        int h = 4000;
        ComponentColorModel model = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_sRGB), false, false,
                Transparency.OPAQUE, DataBuffer.TYPE_USHORT);
        WritableRaster raster = model.createCompatibleWritableRaster(w, h);
        int[] row = new int[3 * w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean photo = x > w / 8 && x < w / 2 && y > h / 8 && y < h / 2;
                for (int b = 0; b < 3; b++) {
                    row[3 * x + b] = photo ? 8000 + (x * 37 + y * 11 + b * 5000) % 40000 : 62000;
                }
            }
            raster.setPixels(0, y, w, 1, row);
        }
        Path input = dir.resolve("deep.png");
        ImageIO.write(new BufferedImage(model, raster, false, null), "png", input.toFile());
        raster = null;
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "-Xmx224m", "-cp", System.getProperty("java.class.path"),
                Split.class.getName(), input.toString(), dir.resolve("out").toString())
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().close();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), out);
        assertTrue(out.contains("1 photo"), out);
    }

    static final class Split {
        public static void main(String[] args) throws IOException {
            int n = PhotoSplitter.split(Path.of(args[0]), Path.of(args[1]), PhotoSplitter.Options.defaults()).size();
            System.out.println(n + " photo");
        }
    }

    @Test
    void scanNeedingMoreThanTheHeapIsRefusedUpFront() {
        long limit = Runtime.getRuntime().maxMemory();
        IOException refused = assertThrows(IOException.class, () -> PhotoSplitter.budget("big.png", limit + 1));
        assertTrue(refused.getMessage().startsWith("big.png needs about "), refused.getMessage());
        assertDoesNotThrow(() -> PhotoSplitter.budget("small.png", limit / 2));
    }

    @Test
    void peakEstimateCountsTheDecodedRasterAndAnyConversion() {
        long pixels = 1_000_000;
        ImageTypeSpecifier rgb = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_3BYTE_BGR);
        ImageTypeSpecifier binary = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_BYTE_BINARY);
        assertEquals(3 * pixels, PhotoSplitter.decodedBytes(rgb, pixels));
        assertEquals(pixels / 8, PhotoSplitter.decodedBytes(binary, pixels));
        long straighten = PhotoSplitter.peakBytes(pixels, pixels, false, PhotoSplitter.Method.STRAIGHTEN);
        long converted = PhotoSplitter.peakBytes(pixels, pixels, true, PhotoSplitter.Method.STRAIGHTEN);
        assertEquals(3 * pixels, converted - straighten);
        long big = 400_000_000;
        assertTrue(PhotoSplitter.peakBytes(big, 3 * big, false, PhotoSplitter.Method.SCRIPT)
                > PhotoSplitter.peakBytes(big, 3 * big, false, PhotoSplitter.Method.STRAIGHTEN));
    }

    private interface Work {
        void run() throws IOException;
    }

    private static long allocated(Work work) throws IOException {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        work.run();
        return threads.getCurrentThreadAllocatedBytes() - before;
    }

    private static long decodedBytes(Path input) throws IOException {
        BufferedImage image = ImageIO.read(input.toFile());
        return 3L * image.getWidth() * image.getHeight();
    }

    private Path scan(int w, int h, int margin) throws IOException {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        int inset = Math.max(3, w / 8);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = 3 * (y * w + x);
                boolean photo = x >= inset && x < w - inset && y >= margin && y < h - margin;
                int v = photo ? 60 + (x * 7 + y / 97) % 90 : 246;
                data[i] = (byte) (photo ? v / 2 : v);
                data[i + 1] = (byte) v;
                data[i + 2] = (byte) (photo ? 200 - v / 2 : v);
            }
        }
        Path file = dir.resolve(w + "x" + h + ".png");
        ImageIO.write(image, "png", file.toFile());
        return file;
    }
}

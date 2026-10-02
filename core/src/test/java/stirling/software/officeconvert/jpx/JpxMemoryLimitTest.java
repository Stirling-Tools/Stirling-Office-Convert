package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.management.ManagementFactory;

import org.junit.jupiter.api.Test;

class JpxMemoryLimitTest {

    private static long allocatedByDecoding(byte[] data) throws IOException {
        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        JpxDecoder.decode(data);
        return threads.getCurrentThreadAllocatedBytes() - before;
    }

    @Test
    void positionalProgressionsDoNotListEveryPrecinctUpFront() throws IOException {
        byte[] data = SyntheticCodestream.grey(2048, 2048).coding(Progression.PCRL, 1, 0, true).tile();
        long allocated = allocatedByDecoding(data);
        assertTrue(allocated < 96L << 20, "allocated " + (allocated >> 20) + " MB");
    }

    @Test
    void longThinTilesKeepTheTransformScratchSmall() throws IOException {
        byte[] data = SyntheticCodestream.grey(1 << 22, 1).coding(Progression.LRCP, 1, 1, false).tile();
        long allocated = allocatedByDecoding(data);
        assertTrue(allocated < 128L << 20, "allocated " + (allocated >> 20) + " MB");
    }

    @Test
    void subsampledComponentsAreBudgetedAtTheComposedSize() throws IOException {
        int[] steps = {255, 254, 253};
        byte[] data = new SyntheticCodestream(1 << 15, 1 << 15, steps, steps).coding(Progression.LRCP, 1, 0, false)
                .tile();
        assertArrayEquals(new int[] {1 << 15, 1 << 15, 3}, JpxDecoder.size(data));
        assertThrows(JpxException.class, () -> JpxDecoder.decode(data));
    }

    @Test
    void sizeMatchesTheComposedImageOfSubsampledComponents() throws IOException {
        byte[] data = JpxSamples.resource("yuv420.j2k");
        BufferedImage img = JpxDecoder.decode(data).toBufferedImage();
        assertArrayEquals(new int[] {img.getWidth(), img.getHeight(), 3}, JpxDecoder.size(data));
    }
}

package stirling.software.officeconvert.photosplit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.memory.Admission;
import stirling.software.officeconvert.photosplit.SyntheticScan.Style;

class MemoryGateTest {

    @TempDir
    Path dir;

    @Test
    void splitWaitsForMemoryHeldByOtherConversions() throws Exception {
        BufferedImage scan = new SyntheticScan(900, 700, 21)
                .photo(320, 240, 250, 220, 4, Style.GRADIENT, 0)
                .photo(300, 260, 640, 460, -3, Style.TEXTURE, 0)
                .render()
                .scan();
        Path input = dir.resolve("scan.png");
        ImageIO.write(scan, "png", input.toFile());
        Path out = dir.resolve("out");
        CompletableFuture<List<Path>> split;
        Admission.Ticket hog = Admission.jvm().enter(Long.MAX_VALUE);
        try {
            split = CompletableFuture.supplyAsync(() -> {
                try {
                    return PhotoSplitter.split(input, out, PhotoSplitter.Options.defaults());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(500);
            assertTrue(!split.isDone(), "the split ran while another conversion held the memory");
            assertEquals(1, Admission.jvm().running());
        } finally {
            hog.close();
        }
        assertEquals(2, split.get(1, TimeUnit.MINUTES).size());
        assertEquals(0, Admission.jvm().running());
        assertTrue(Files.exists(out.resolve("scan_2.png")));
    }
}

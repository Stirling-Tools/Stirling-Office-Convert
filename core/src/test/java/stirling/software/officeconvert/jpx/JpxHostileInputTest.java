package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

class JpxHostileInputTest {

    private static final String[] SEEDS = {"rgb-allmodes.j2k", "rgb-poc.j2k", "rgb-markers.j2k", "rgba.jp2",
            "gray16.j2k", "yuv420.j2k", "rgb-97-layers.j2k"};

    private static void survive(byte[] data) {
        try {
            JpxDecoder.decode(data, new JpxOptions(0, null, 1 << 22)).toBufferedImage();
        } catch (IOException e) {
            return;
        }
    }

    @Test
    void truncatedCodestreamsDecodeOrFailCleanly() {
        byte[] data = JpxSamples.resource("rgb-markers.j2k");
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            for (int n = 0; n < data.length; n += 37) {
                survive(Arrays.copyOf(data, n));
            }
        });
    }

    @Test
    void corruptedFilesOnlyFailWithIoExceptions() {
        Random random = new Random(20261002);
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            for (int i = 0; i < 1500; i++) {
                byte[] d = JpxSamples.resource(SEEDS[i % SEEDS.length]).clone();
                int edits = 1 + random.nextInt(12);
                for (int e = 0; e < edits; e++) {
                    int at = random.nextInt(random.nextBoolean() ? Math.min(d.length, 200) : d.length);
                    d[at] = (byte) random.nextInt(256);
                }
                survive(d);
            }
        });
    }

    @Test
    void oversizedImagesAreRefusedBeforeAllocating() {
        byte[] d = JpxSamples.resource("gray-lossless.j2k").clone();
        d[8] = 0x40;
        d[12] = 0x40;
        assertThrows(JpxException.class, () -> JpxDecoder.decode(d));
    }

    @Test
    void decodingStopsWhenTheThreadIsInterrupted() {
        byte[] d = JpxSamples.resource("rgb-lossless.j2k");
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> JpxDecoder.decode(d));
        } finally {
            Thread.interrupted();
        }
    }
}

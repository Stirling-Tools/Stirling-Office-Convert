package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Random;

import javax.imageio.stream.MemoryCacheImageInputStream;

import org.junit.jupiter.api.Test;

class Jp2BoxesTest {

    private static final int SIGNATURE = 0x6A502020;

    @Test
    void anExtendedLengthThatOverflowsTheEndDoesNotRestartParsing() {
        ByteBuffer file = ByteBuffer.allocate(28);
        file.putInt(12).putInt(SIGNATURE).putInt(0x0D0A870A);
        file.putInt(1).putInt(0x786D6C20).putLong(0x7FFFFFFFFFFFFFF4L);
        byte[] data = file.array();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThrows(JpxException.class, () -> Jp2Boxes.read(data));
            assertFalse(JpxDecoder.palette(data));
            assertThrows(JpxException.class, () -> JpxDecoder.size(data));
            JpxImageReader reader = new JpxImageReader(new JpxImageReaderSpi());
            try (MemoryCacheImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
                reader.setInput(in);
                assertThrows(IOException.class, () -> reader.getWidth(0));
            } finally {
                reader.dispose();
            }
        });
    }

    @Test
    void randomBoxLengthsAlwaysFinish() {
        Random random = new Random(20261122);
        long[] edges = {0, 1, 7, 8, 9, 16, 0xFFFFFFFFL, 0x7FFFFFFFL, 0x80000000L};
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            for (int round = 0; round < 20_000; round++) {
                ByteBuffer file = ByteBuffer.allocate(12 + 8 * 24);
                file.putInt(12).putInt(SIGNATURE).putInt(0x0D0A870A);
                while (file.remaining() >= 16) {
                    long length = random.nextBoolean() ? edges[random.nextInt(edges.length)] : random.nextInt(64);
                    file.putInt((int) length).putInt(random.nextBoolean() ? 0x6A703268 : 0x6A703263);
                    if (length == 1) {
                        file.putLong(random.nextBoolean() ? random.nextLong() : Long.MAX_VALUE - random.nextInt(64));
                    }
                }
                try {
                    Jp2Boxes.read(file.array());
                } catch (JpxException expected) {
                    // Refusing a malformed file is fine; only a hang or another exception fails.
                }
            }
        });
    }
}

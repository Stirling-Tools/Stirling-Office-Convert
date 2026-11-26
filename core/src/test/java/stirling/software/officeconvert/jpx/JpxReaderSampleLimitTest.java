package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.stream.MemoryCacheImageInputStream;

import org.junit.jupiter.api.Test;


class JpxReaderSampleLimitTest {

    @Test
    void theRegisteredReaderKeepsTheLibraryWideSampleLimit() throws IOException {
        assertEquals(JpxOptions.DEFAULT_MAX_SAMPLES, JpxImageReader.MAX_SAMPLES);
        byte[] data = SyntheticCodestream.grey(1 << 14, (1 << 13) + 1).coding(Progression.LRCP, 1, 0, false).tile();
        JpxImageReader reader = new JpxImageReader(new JpxImageReaderSpi());
        try (MemoryCacheImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            reader.setInput(in);
            assertEquals(1 << 14, reader.getWidth(0));
            JpxException e = assertThrows(JpxException.class, () -> reader.read(0, null));
            assertTrue(e.getMessage().endsWith("more than " + JpxImageReader.MAX_SAMPLES), e.getMessage());
        } finally {
            reader.dispose();
        }
    }
}

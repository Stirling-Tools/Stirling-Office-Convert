package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.stream.MemoryCacheImageInputStream;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.extract.ImageBudget;

class JpxReaderSampleLimitTest {

    @Test
    void theRegisteredReaderKeepsTheDocumentedSampleLimit() throws IOException {
        assertEquals(ImageBudget.MAX_JPX_SAMPLES, JpxImageReader.MAX_SAMPLES);
        byte[] data = SyntheticCodestream.grey(1 << 13, (1 << 13) + 1).coding(Progression.LRCP, 1, 0, false).tile();
        JpxImageReader reader = new JpxImageReader(new JpxImageReaderSpi());
        try (MemoryCacheImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            reader.setInput(in);
            assertEquals(1 << 13, reader.getWidth(0));
            assertThrows(IOException.class, () -> reader.read(0, null));
        } finally {
            reader.dispose();
        }
    }
}

package stirling.software.officeconvert.legacy;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;

import org.junit.jupiter.api.Test;

class PptWriterTest {

    @Test
    void beginStopsWhenTheThreadIsInterrupted() throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PptWriter writer = new PptWriter(out);
            Thread.currentThread().interrupt();
            try {
                assertThrows(
                        InterruptedIOException.class, () -> writer.begin(720f, 540f, 1, 10_000));
            } finally {
                Thread.interrupted();
            }
        }
    }
}

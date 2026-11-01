package stirling.software.officeconvert.extract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import stirling.software.officeconvert.testing.BrotliData;

class SalvageBrotliTest {

    @Test
    void decodesAnObjectStream() throws IOException {
        assertArrayEquals(BrotliData.HELLO_TEXT.getBytes(StandardCharsets.ISO_8859_1),
                Salvage.brotli(BrotliData.HELLO, 1 << 20, BrotliBudget.create()));
    }

    @Test
    void aCancelledRebuildStopsInsteadOfSkippingTheStream() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> Salvage.brotli(BrotliData.HELLO, 1 << 20, BrotliBudget.create()));
        } finally {
            Thread.interrupted();
        }
    }
}

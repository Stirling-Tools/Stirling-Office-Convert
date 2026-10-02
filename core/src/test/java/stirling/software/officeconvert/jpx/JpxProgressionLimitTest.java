package stirling.software.officeconvert.jpx;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.InterruptedIOException;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class JpxProgressionLimitTest {

    private static byte[] emptyComponentChanges() {
        return SyntheticCodestream.grey(64, 64).coding(Progression.LRCP, 65535, 0, false)
                .progressionChanges(9361, 1, 65535, Progression.LRCP).tile();
    }

    @Test
    void progressionChangesOverNoComponentsStillCountTowardsTheLimit() {
        byte[] data = emptyComponentChanges();
        assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> assertThrows(JpxException.class, () -> JpxDecoder.decode(data)));
    }

    @Test
    void progressionChangesStopWhenTheThreadIsInterrupted() {
        byte[] data = emptyComponentChanges();
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> JpxDecoder.decode(data));
            } finally {
                Thread.interrupted();
            }
        });
    }

    @Test
    void repeatedPositionalChangesOverManyPrecinctsFinishQuickly() {
        byte[] data = SyntheticCodestream.grey(1024, 1024).coding(Progression.LRCP, 1, 0, true)
                .progressionChanges(3000, 0, 0, Progression.PCRL).tile();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> JpxDecoder.decode(data));
    }
}

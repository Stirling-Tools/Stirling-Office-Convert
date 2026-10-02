package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GridInputTest {

    @TempDir
    Path dir;

    @Test
    void theDbaseEstimateStopsWhenInterrupted() throws Exception {
        Path dbf = Files.write(dir.resolve("t.dbf"), new byte[64]);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> GridInput.dbaseCells(dbf));
        } finally {
            Thread.interrupted();
        }
    }
}

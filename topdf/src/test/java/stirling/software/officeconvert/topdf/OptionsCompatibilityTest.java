package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class OptionsCompatibilityTest {

    @Test
    void theOptionsConstructorsOfTheFirstReleaseStillWork() {
        OfficeToPdf.Options three = new OfficeToPdf.Options(Duration.ofSeconds(30), List.of(), 5);
        assertEquals(OfficeToPdf.Options.DEFAULT_MAX_SCRATCH_BYTES, three.maxScratchBytes());
        OfficeToPdf.Options four = new OfficeToPdf.Options(Duration.ofSeconds(30), List.of(), 5, 1L << 20);
        assertEquals(5, four.maxPages());
        assertEquals(1L << 20, four.maxScratchBytes());
        assertNull(four.displayName());
        assertNull(four.password());
    }
}

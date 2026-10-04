package stirling.software.officeconvert.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class LimitsTest {

    private static final String[] NO_ARGS = {};

    @Test
    void conversionsAreTimedByDefault() {
        Limits limits = Limits.from(Map.of(), NO_ARGS);
        assertEquals(180, limits.timeoutSeconds());
        assertTrue(limits.json(null).contains("\"timeoutSeconds\":180"), limits.json(null));
    }

    @Test
    void aTimeoutIsKeptAndNeverTurnedOff() {
        assertEquals(30, Limits.from(Map.of("CONVERT_TIMEOUT_SECONDS", "30"), NO_ARGS).timeoutSeconds());
        IllegalArgumentException zero = assertThrows(IllegalArgumentException.class,
                () -> Limits.from(Map.of("CONVERT_TIMEOUT_SECONDS", "0"), NO_ARGS));
        assertTrue(zero.getMessage().contains("CONVERT_TIMEOUT_SECONDS must be 1 or more"), zero.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Limits.from(Map.of("CONVERT_TIMEOUT_SECONDS", "-5"), NO_ARGS));
    }
}

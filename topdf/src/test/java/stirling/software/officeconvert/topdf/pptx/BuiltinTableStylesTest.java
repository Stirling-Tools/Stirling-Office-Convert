package stirling.software.officeconvert.topdf.pptx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BuiltinTableStylesTest {

    private static final String MEDIUM_STYLE_2_ACCENT_1 = "{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}";

    @AfterEach
    void forget() {
        BuiltinTableStyles.forget();
    }

    @Test
    void aFailedFirstReadIsTriedAgain() {
        BuiltinTableStyles.forget();
        assertTrue(BuiltinTableStyles.entries(() -> {
            throw new InterruptedIOException("Conversion interrupted");
        }).isEmpty());
        assertTrue(BuiltinTableStyles.entries(() -> {
            throw new IOException("unreadable");
        }).isEmpty());
        assertNotNull(BuiltinTableStyles.get(MEDIUM_STYLE_2_ACCENT_1));
        assertFalse(BuiltinTableStyles.entries(() -> null).isEmpty());
    }
}

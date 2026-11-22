package stirling.software.officeconvert.sink;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** The creation and modification time written into document properties. */
public final class Timestamps {

    /** System property naming a fixed time in epoch seconds; overrides the SOURCE_DATE_EPOCH environment variable. */
    public static final String PROPERTY = "stirling.officeconvert.sourceDateEpoch";

    private Timestamps() {}

    /** The fixed time when one is configured, otherwise the current time, to the second. */
    public static Instant now() {
        String fixed = System.getProperty(PROPERTY);
        if (fixed == null || fixed.isBlank()) {
            fixed = System.getenv("SOURCE_DATE_EPOCH");
        }
        if (fixed != null && !fixed.isBlank()) {
            try {
                return Instant.ofEpochSecond(Long.parseLong(fixed.strip()));
            } catch (NumberFormatException | DateTimeException e) {
                // A malformed value falls back to the clock, as reproducible-build tools do
            }
        }
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }
}

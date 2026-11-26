package stirling.software.officeconvert.topdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

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

    @Test
    void theTwelveArgumentConstructorKeepsTheHostClockZoneAndLocale() {
        OfficeToPdf.Options o = new OfficeToPdf.Options(Duration.ofSeconds(30), List.of(), 5, 0,
                OfficeToPdf.Options.defaults().fonts(), null, null, null, false, false, false, 1f);
        assertNull(o.locale());
        assertNull(o.zone());
        assertNull(o.clock());
        assertEquals(Locale.getDefault(Locale.Category.FORMAT), o.formatLocale());
        assertEquals(ZoneId.systemDefault(), o.now().getZone());
        Clock fixed = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        assertEquals(Instant.EPOCH, o.clock(fixed).now().toInstant());
        assertEquals(ZoneOffset.UTC, o.clock(fixed).now().getZone());
        assertEquals(ZoneId.of("Europe/Paris"), o.clock(fixed).zone(ZoneId.of("Europe/Paris")).now().getZone());
    }
}

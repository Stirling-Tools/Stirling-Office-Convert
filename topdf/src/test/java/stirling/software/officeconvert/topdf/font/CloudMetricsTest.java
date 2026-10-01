package stirling.software.officeconvert.topdf.font;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.TestFonts;

class CloudMetricsTest {

    @TempDir
    Path dir;

    @Test
    void officeFontsWithoutAFreeTwinKeepTheirOwnAdvances() {
        assertEquals(0.556f, CloudMetrics.of("Univers", false, false).advance('a'), 1e-6);
        assertEquals(0.722f, CloudMetrics.of("Univers", false, false).advance('H'), 1e-6);
        assertEquals(0.757f, CloudMetrics.of("Seaford", false, false).advance('H'), 1e-6);
        assertEquals(0.688f, CloudMetrics.of("Grandview", false, false).advance('H'), 1e-6);
        assertEquals(0.656f, CloudMetrics.of("Abadi", false, false).advance('H'), 1e-6);
        assertEquals(0.615f, CloudMetrics.of("Arial Nova Cond", false, false).advance('H'), 1e-6);
        assertEquals(0.443f, CloudMetrics.of("Sabon Next LT", false, false).advance('a'), 1e-6);
        assertTrue(Float.isNaN(CloudMetrics.of("Sabon Next LT", false, false).advance('H')));
        assertNotNull(CloudMetrics.of("Univers Light", true, false));
    }

    @Test
    void aStandInIsScaledToTheMissingFontsWidthAndLineHeight() throws Exception {
        Path fonts = Files.createDirectories(dir.resolve("fonts"));
        Files.write(fonts.resolve("sans.ttf"), TestFonts.renamed("Liberation Sans"));
        CloudFonts cloud = new CloudFonts(FontLibrary.of(List.of(fonts)));
        CloudFonts.Emulation univers = cloud.emulate("Univers", false, false);
        assertEquals("Liberation Sans", univers.face().family());
        assertTrue(univers.scale() > 103 && univers.scale() < 120, "scale " + univers.scale());
        assertEquals(0.989f, univers.vertical()[0], 1e-4);
        assertEquals(0.201f, univers.vertical()[1], 1e-4);
        assertEquals(0.556f, univers.metrics().advance('a'), 1e-6);
        CloudFonts.Emulation light = cloud.emulate("Univers Condensed Light", false, false);
        assertTrue(light.scale() < 100, "scale " + light.scale());
    }
}

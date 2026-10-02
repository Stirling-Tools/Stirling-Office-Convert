package stirling.software.officeconvert.topdf.odf;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OdfEstimateTest {

    @TempDir
    Path dir;

    private Path text(String name, String path, String body) throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put(path, OdfFixtures.content("", OdfFixtures.text(body)).getBytes(StandardCharsets.UTF_8));
        return OdfFixtures.write(dir, name, OdfFixtures.zip(OdfFixtures.TEXT, parts));
    }

    @Test
    void theEstimateFindsContentStoredUnderALeadingSlash() throws IOException {
        String body = ("<text:p>" + "word ".repeat(40) + "</text:p>").repeat(50_000);
        long plain = OdfPackage.estimate(text("plain.odt", "content.xml", body));
        long slashed = OdfPackage.estimate(text("slashed.odt", "/content.xml", body));
        long backslashed = OdfPackage.estimate(text("backslashed.odt", "\\content.xml", body));
        assertTrue(Math.abs(plain - slashed) < 1024, plain + " vs " + slashed);
        assertTrue(Math.abs(plain - backslashed) < 1024, plain + " vs " + backslashed);
    }
}

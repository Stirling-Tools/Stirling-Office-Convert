package stirling.software.officeconvert.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Keeps the defaults the limits document promises equal to the ones the code applies. */
class AdmissionDefaultsDocTest {

    private static Path doc(String name) {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path doc = dir.resolve("docs").resolve(name);
            if (Files.isRegularFile(doc)) {
                return doc;
            }
        }
        throw new AssertionError("docs/" + name + " not found above " + Path.of("").toAbsolutePath());
    }

    // The Default column of the admission table row that names the property
    private static String documented(List<String> lines, String property) {
        for (String line : lines) {
            if (line.startsWith("|") && line.contains("`" + property + "`")) {
                return line.split("\\|")[2].strip();
            }
        }
        throw new AssertionError(property + " has no row in docs/limits.md");
    }

    @Test
    void theDocumentedDefaultsAreTheOnesTheCodeApplies() throws IOException {
        Admission.Limits limits = Admission.Limits.defaults();
        List<String> lines = Files.readAllLines(doc("limits.md"));
        assertTrue(documented(lines, Admission.BUDGET_PROPERTY).startsWith(limits.memoryBudgetPercent() + " %"));
        assertEquals(0, limits.maxConcurrent());
        assertTrue(documented(lines, Admission.CONCURRENT_PROPERTY).startsWith("auto"));
        assertEquals(Duration.ZERO, limits.maxWait());
        assertTrue(documented(lines, Admission.WAIT_PROPERTY).startsWith("0 "));
        assertTrue(documented(lines, Admission.OVERSIZE_PROPERTY).startsWith(limits.refuseOversize() + " "));
        assertTrue(documented(lines, Admission.READMIT_PROPERTY).startsWith(String.valueOf(limits.readmitStopped())));
        String usage = Files.readString(doc("usage.md")).replaceAll("\\s+", " ");
        assertTrue(usage.contains("`-D" + Admission.WAIT_PROPERTY + "` (default " + limits.maxWait().toSeconds() + ","),
                "docs/usage.md states another default wait");
    }
}

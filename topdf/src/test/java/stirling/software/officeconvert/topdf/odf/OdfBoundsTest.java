package stirling.software.officeconvert.topdf.odf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.testing.Allocation;

class OdfBoundsTest {

    @TempDir
    Path dir;

    private static String nestedTable(int depth) {
        String inner = depth == 0 ? "<text:p>cell text that is repeated</text:p>" : nestedTable(depth - 1);
        return "<table:table><table:table-column table:number-columns-repeated=\"10\"/>"
                + "<table:table-row table:number-rows-repeated=\"1000\"><table:table-cell"
                + " table:number-columns-repeated=\"10\">" + inner + "</table:table-cell></table:table-row></table:table>";
    }

    private static void assertBounded(Allocation.Measured m, OdfPackage.Outcome[] outcome) {
        assertTrue(m.bytes() < 2048L << 20, "allocated " + m.megabytes() + " MB");
        if (m.failure() != null) {
            assertTrue(m.failure() instanceof IOException, String.valueOf(m.failure()));
        } else {
            assertTrue(outcome[0].lost(), "a truncated document must say so");
            assertFalse(outcome[0].warnings().isEmpty());
        }
    }

    @Test
    void nestedRepeatedTablesStopAtTheDocumentBudget() throws IOException {
        Path p = OdfFixtures.write(dir, "nested.odt", OdfFixtures.odf(OdfFixtures.TEXT,
                OdfFixtures.content("", OdfFixtures.text(nestedTable(2))), null));
        OdfPackage.Outcome[] outcome = new OdfPackage.Outcome[1];
        Allocation.Measured m = Allocation.measure(() -> outcome[0] = OdfPackage.write(p, new ByteArrayOutputStream()));
        assertBounded(m, outcome);
    }

    @Test
    void repeatedSpacesStopAtTheDocumentBudget() throws IOException {
        String p = "<text:p>" + "<text:s text:c=\"10000\"/>".repeat(10_000) + "</text:p>";
        Path in = OdfFixtures.write(dir, "spaces.odt", OdfFixtures.odf(OdfFixtures.TEXT,
                OdfFixtures.content("", OdfFixtures.text(p.repeat(20))), null));
        OdfPackage.Outcome[] outcome = new OdfPackage.Outcome[1];
        Allocation.Measured m = Allocation.measure(() -> outcome[0] = OdfPackage.write(in, new ByteArrayOutputStream()));
        assertBounded(m, outcome);
    }

    @Test
    void repeatedSlideTableCellsStopAtTheDocumentBudget() throws IOException {
        String cell = "<text:p>" + "x".repeat(100_000) + "</text:p>";
        String table = "<draw:frame svg:x=\"1cm\" svg:y=\"1cm\" svg:width=\"20cm\" svg:height=\"10cm\"><table:table>"
                + "<table:table-row table:number-rows-repeated=\"200\"><table:table-cell"
                + " table:number-columns-repeated=\"64\">" + cell + "</table:table-cell></table:table-row></table:table>"
                + "</draw:frame>";
        String pages = "<draw:page draw:name=\"One\">" + table + table + "</draw:page>";
        Path in = OdfFixtures.write(dir, "table.odp", OdfFixtures.odf(OdfFixtures.PRESENTATION,
                OdfFixtures.content("", "<office:presentation>" + pages + "</office:presentation>"), null));
        OdfPackage.Outcome[] outcome = new OdfPackage.Outcome[1];
        Allocation.Measured m = Allocation.measure(() -> outcome[0] = OdfPackage.write(in, new ByteArrayOutputStream()));
        assertBounded(m, outcome);
    }
}

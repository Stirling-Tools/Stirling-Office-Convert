package stirling.software.officeconvert.topdf.odf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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

    private Allocation.Measured spreadsheet(String rows, OdfPackage.Outcome[] outcome) throws IOException {
        Path in = OdfFixtures.write(dir, "book.ods", OdfFixtures.odf(OdfFixtures.SPREADSHEET, OdfFixtures.content("",
                "<office:spreadsheet><table:table table:name=\"A\">" + rows + "</table:table></office:spreadsheet>"),
                null));
        return Allocation.measure(() -> outcome[0] = OdfPackage.write(in, new ByteArrayOutputStream()));
    }

    @Test
    void repeatedSpreadsheetRowsCountEveryCellTheyWrite() throws IOException {
        String row = "<table:table-row table:number-rows-repeated=\"10000\"><table:table-cell"
                + " table:number-columns-repeated=\"16384\" office:value-type=\"float\" office:value=\"1\"/>"
                + "</table:table-row>";
        OdfPackage.Outcome[] outcome = new OdfPackage.Outcome[1];
        assertBounded(spreadsheet(row.repeat(3), outcome), outcome);
    }

    @Test
    void repeatedSpreadsheetRowsCountEveryMergeTheyAdd() throws IOException {
        String row = "<table:table-row table:number-rows-repeated=\"10000\"><table:table-cell"
                + " office:value-type=\"float\" office:value=\"1\"/><table:table-cell table:number-columns-spanned=\"2\"/><table:covered-table-cell/>".repeat(8000)
                + "</table:table-row>";
        OdfPackage.Outcome[] outcome = new OdfPackage.Outcome[1];
        assertBounded(spreadsheet(row, outcome), outcome);
    }

    private static final long QUICK_NANOS = 20_000_000_000L;

    private static final long TIGHT_BYTES = 768L << 20;

    private static OdfPackage.Outcome hostile(Path in) {
        OdfPackage.Outcome[] outcome = new OdfPackage.Outcome[1];
        long start = System.nanoTime();
        Allocation.Measured m = Allocation.measure(() -> outcome[0] = OdfPackage.write(in, new ByteArrayOutputStream()));
        long nanos = System.nanoTime() - start;
        assertNull(m.failure(), String.valueOf(m.failure()));
        assertTrue(m.bytes() < TIGHT_BYTES, "allocated " + m.megabytes() + " MB");
        assertTrue(nanos < QUICK_NANOS, "took " + nanos / 1_000_000 + " ms");
        assertEquals(List.of(WorkBudget.WARNING), outcome[0].warnings());
        assertTrue(outcome[0].lost(), "a truncated document must say so");
        return outcome[0];
    }

    private Path textDocument(String name, String body) throws IOException {
        return OdfFixtures.write(dir, name, OdfFixtures.odf(OdfFixtures.TEXT,
                OdfFixtures.content("", OdfFixtures.text(body)), null));
    }

    @Test
    void coveredCellsCountAgainstTheDocumentBudget() throws IOException {
        String row = "<table:table-row table:number-rows-repeated=\"1000\"><table:table-cell><text:p>x</text:p>"
                + "</table:table-cell><table:covered-table-cell table:number-columns-repeated=\"62\"/></table:table-row>";
        String table = "<table:table><table:table-column table:number-columns-repeated=\"63\"/>" + row.repeat(50)
                + "</table:table>";
        hostile(textDocument("covered.odt", table.repeat(20)));
    }

    @Test
    void verticallyMergedFillerCellsCountAgainstTheDocumentBudget() throws IOException {
        String rows = "<table:table-row><table:table-cell table:number-rows-spanned=\"50000\""
                + " table:number-columns-repeated=\"63\"><text:p>x</text:p></table:table-cell></table:table-row>"
                + ("<table:table-row table:number-rows-repeated=\"1000\"><table:covered-table-cell"
                        + " table:number-columns-repeated=\"63\"/></table:table-row>").repeat(49);
        String table = "<table:table><table:table-column table:number-columns-repeated=\"63\"/>" + rows
                + "</table:table>";
        hostile(textDocument("vmerge.odt", table.repeat(20)));
    }

    private Path presentation(String cell) throws IOException {
        String rows = ("<table:table-row table:number-rows-repeated=\"200\">" + cell + "</table:table-row>").repeat(5);
        String frame = "<draw:frame svg:x=\"1cm\" svg:y=\"1cm\" svg:width=\"20cm\" svg:height=\"10cm\"><table:table>"
                + "<table:table-column table:number-columns-repeated=\"64\"/>" + rows + "</table:table></draw:frame>";
        return OdfFixtures.write(dir, "tables.odp", OdfFixtures.odf(OdfFixtures.PRESENTATION, OdfFixtures.content("",
                "<office:presentation><draw:page draw:name=\"One\">" + frame.repeat(300)
                        + "</draw:page></office:presentation>"), null));
    }

    @Test
    void coveredSlideTableCellsCountAgainstTheDocumentBudget() throws IOException {
        hostile(presentation("<table:covered-table-cell table:number-columns-repeated=\"64\"/>"));
    }

    @Test
    void emptySlideTableCellsCountAgainstTheDocumentBudget() throws IOException {
        hostile(presentation("<table:table-cell table:number-columns-repeated=\"64\"/>"));
    }

    @Test
    void chartDataTablesCountAgainstTheDocumentBudget() throws IOException {
        String label = "<table:table-cell table:number-columns-repeated=\"64\" office:value-type=\"string\"><text:p>"
                + "<text:s text:c=\"1000\"/>".repeat(32) + "x</text:p></table:table-cell>";
        String chart = "<draw:frame svg:width=\"10cm\" svg:height=\"5cm\" text:anchor-type=\"paragraph\"><draw:object>"
                + "<office:document xmlns:chart=\"urn:oasis:names:tc:opendocument:xmlns:chart:1.0\" office:version=\"1.2\">"
                + "<office:automatic-styles><style:style style:name=\"pa\" style:family=\"chart\">"
                + "<style:chart-properties chart:series-source=\"rows\"/></style:style></office:automatic-styles>"
                + "<office:body><office:chart><chart:chart chart:class=\"chart:bar\"><chart:plot-area"
                + " chart:style-name=\"pa\">" + "<chart:series/>".repeat(64) + "</chart:plot-area><table:table"
                + " table:name=\"local-table\"><table:table-header-rows><table:table-row>" + label.repeat(63)
                + "</table:table-row></table:table-header-rows><table:table-rows>"
                + ("<table:table-row><table:table-cell table:number-columns-repeated=\"4000\" office:value-type=\"float\""
                        + " office:value=\"1\"/></table:table-row>").repeat(64)
                + "</table:table-rows></table:table></chart:chart></office:chart></office:body></office:document>"
                + "</draw:object></draw:frame>";
        hostile(textDocument("chart.odt", "<text:p>" + chart + "</text:p>"));
    }

    @Test
    void errorCellTextCountsAgainstTheDocumentBudget() throws IOException {
        String cell = "<table:table-cell table:number-columns-repeated=\"16384\" office:value-type=\"string\""
                + " calcext:value-type=\"error\"><text:p>" + "E".repeat(32_000) + "</text:p></table:table-cell>";
        Path in = OdfFixtures.write(dir, "errors.ods", OdfFixtures.odf(OdfFixtures.SPREADSHEET, OdfFixtures.content("",
                "<office:spreadsheet><table:table table:name=\"A\"><table:table-row table:number-rows-repeated=\"3\">"
                        + cell + "</table:table-row></table:table></office:spreadsheet>").replace("<office:document-content ",
                        "<office:document-content xmlns:calcext=\"urn:org:documentfoundation:names:experimental:calc:"
                                + "xmlns:calcext:1.0\" "), null));
        hostile(in);
    }

    @Test
    void repeatedNumbersStopAtTheDocumentBudget() throws IOException {
        String row = "<table:table-row table:number-rows-repeated=\"10000\"><table:table-cell"
                + " table:number-columns-repeated=\"400\" office:value-type=\"float\" office:value=\"1.25\"/>"
                + "</table:table-row>";
        Path in = OdfFixtures.write(dir, "cells.ods", OdfFixtures.odf(OdfFixtures.SPREADSHEET, OdfFixtures.content("",
                "<office:spreadsheet><table:table table:name=\"A\">" + row.repeat(3) + "</table:table>"
                        + "</office:spreadsheet>"), null));
        hostile(in);
    }

    @Test
    void honestRowsAreCappedPerSheetWithAnAccurateWarning() throws IOException {
        String row = "<table:table-row><table:table-cell office:value-type=\"float\" office:value=\"1\""
                + " table:number-columns-repeated=\"30\"/></table:table-row>";
        String big = "<table:table table:name=\"Big\">" + row.repeat(140_000) + "</table:table>";
        String small = "<table:table table:name=\"Small\">" + row.repeat(10) + "</table:table>";
        Path in = OdfFixtures.write(dir, "sheets.ods", OdfFixtures.odf(OdfFixtures.SPREADSHEET, OdfFixtures.content("",
                "<office:spreadsheet>" + big + small + "</office:spreadsheet>"), null));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OdfPackage.Outcome outcome = OdfPackage.write(in, out);
        assertTrue(outcome.lost());
        assertEquals(List.of("Only the first " + SheetLimits.MAX_CELLS + " cells of the sheet \"Big\" were converted"),
                outcome.warnings());
        String second = OdfFixtures.rewrite(in).get("xl/worksheets/sheet2.xml");
        assertTrue(second.contains("<row r=\"10\">"), second.substring(0, Math.min(400, second.length())));
    }
}

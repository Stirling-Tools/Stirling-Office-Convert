package stirling.software.officeconvert.topdf.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.Duration;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.Fixtures;

class XlsxRobustnessTest {

    @TempDir
    Path dir;

    private static byte[] corner(String far) {
        return new RawXlsx().sheet("S", "<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "first") + "</row><row r=\""
                + far.replaceAll("[A-Z]", "") + "\">" + RawXlsx.inline(far, "last") + "</row></sheetData>").bytes();
    }

    @Test
    void aFarCornerCellPrintsOnlyThePagesWithContent() throws Exception {
        long start = System.nanoTime();
        for (int maxPages : new int[] {10, 0}) {
            Path in = Fixtures.write(dir, "far.xlsx", corner("XFD1048576"));
            Path out = dir.resolve("far" + maxPages + ".pdf");
            OfficeToPdf.Result r = OfficeToPdf.convert(in, out,
                    OfficeToPdf.Options.defaults().maxPages(maxPages).timeout(Duration.ofMinutes(1)));
            assertEquals(2, r.pages());
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                assertEquals(2, d.getNumberOfPages());
            }
        }
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(60).toNanos());
    }

    @Test
    void aTimeoutStopsTheConversionOnTime() throws Exception {
        Path in = Fixtures.write(dir, "corner.xlsx", corner("BIO1048576"));
        long start = System.nanoTime();
        OfficeToPdf.Result r = OfficeToPdf.convert(in, dir.resolve("corner.pdf"),
                OfficeToPdf.Options.defaults().timeout(Duration.ofSeconds(20)));
        assertEquals(2, r.pages());
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(20).toNanos());
    }

    @Test
    void paginationNoticesAnInterrupt() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> Paginator.spans(0, Grid.MAX_ROWS - 1, i -> 1, -1, -1, 10, null, Integer.MAX_VALUE));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void manyOverlappingMergesNeitherExhaustMemoryNorStall() throws Exception {
        StringBuilder merges = new StringBuilder("<mergeCells count=\"100000\">");
        for (int i = 0; i < 100_000; i++) {
            int c = (i % 8000) * 2;
            merges.append("<mergeCell ref=\"").append(column(c)).append("1:").append(column(c + 1)).append("2000\"/>");
        }
        merges.append("</mergeCells>");
        byte[] xlsx = new RawXlsx().sheet("S", "<sheetData><row r=\"1\">" + RawXlsx.inline("A1", "merged")
                + "</row></sheetData>" + merges).bytes();
        long start = System.nanoTime();
        XlsxTesting.Converted c = XlsxTesting.convert(dir, "merges.xlsx", xlsx);
        assertTrue(c.all().contains("merged"), c.all());
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(90).toNanos());
    }

    @Test
    void theMergeIndexFindsTheCoveringMerge() {
        java.util.List<org.apache.poi.ss.util.CellRangeAddress> list = new java.util.ArrayList<>();
        for (int r = 0; r < 300; r += 3) {
            for (int c = 0; c < 30; c += 2) {
                list.add(new org.apache.poi.ss.util.CellRangeAddress(r, r + 2, c, c + 1));
            }
        }
        list.add(org.apache.poi.ss.util.CellRangeAddress.valueOf("AH1:AJ5000"));
        MergeIndex index = new MergeIndex(list);
        for (int r = 0; r < 300; r++) {
            for (int c = 0; c < 30; c++) {
                org.apache.poi.ss.util.CellRangeAddress m = index.covering(r, c);
                assertEquals(r / 3 * 3, m.getFirstRow());
                assertEquals(c / 2 * 2, m.getFirstColumn());
            }
        }
        assertEquals("AH1:AJ5000", index.covering(4000, 34).formatAsString());
        assertEquals(null, index.covering(4000, 30));
        int[] seen = new int[1];
        index.intersecting(10, 12, 3, 4, m -> seen[0]++);
        assertEquals(4, seen[0]);
    }

    private static String column(int c) {
        StringBuilder b = new StringBuilder();
        for (int n = c + 1; n > 0; n = (n - 1) / 26) {
            b.insert(0, (char) ('A' + (n - 1) % 26));
        }
        return b.toString();
    }
}

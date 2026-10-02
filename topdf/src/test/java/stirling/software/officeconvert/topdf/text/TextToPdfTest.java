package stirling.software.officeconvert.topdf.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.topdf.OfficeToPdf;
import stirling.software.officeconvert.topdf.testing.Fixtures;

class TextToPdfTest {

    @TempDir
    Path dir;

    record Pdf(int pages, String text, PDRectangle size, OfficeToPdf.Result result) {}

    Pdf convert(String name, byte[] content, int maxPages) throws IOException {
        Path in = Files.write(dir.resolve(name), content);
        Path out = dir.resolve(name + ".pdf");
        OfficeToPdf.Result r = OfficeToPdf.convert(in, out,
                OfficeToPdf.Options.defaults().timeout(Duration.ofMinutes(2)).maxPages(maxPages));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            return new Pdf(doc.getNumberOfPages(), new PDFTextStripper().getText(doc), doc.getPage(0).getMediaBox(), r);
        }
    }

    Pdf convert(String name, String content) throws IOException {
        return convert(name, content.getBytes(StandardCharsets.UTF_8), 0);
    }

    static String lines(int n) {
        StringBuilder s = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            s.append("line ").append(i).append('\n');
        }
        return s.toString();
    }

    @Test
    void plainTextFillsSixtyFourLinesAnA4Page() throws IOException {
        Pdf p = convert("notes.txt", lines(64));
        assertEquals(1, p.pages());
        assertEquals(PDRectangle.A4.getWidth(), p.size().getWidth(), 0.5);
        assertEquals(PDRectangle.A4.getHeight(), p.size().getHeight(), 0.5);
        assertEquals(2, convert("more.log", lines(65)).pages());
        Pdf three = convert("three.text", lines(130));
        assertEquals(3, three.pages());
        assertTrue(three.text().lines().anyMatch("line 1"::equals)
                && three.text().lines().anyMatch("line 130"::equals), three.text());
        assertFalse(three.result().truncated());
    }

    @Test
    void formFeedsStartNewPages() throws IOException {
        Pdf p = convert("ff.txt", "one\ftwo\n\f\fthree\n");
        assertEquals(4, p.pages());
        assertTrue(p.text().contains("one") && p.text().contains("three"), p.text());
        assertEquals(1, convert("empty.txt", "").pages());
    }

    @Test
    void textPastThePageLimitIsCutAndReported() throws IOException {
        Pdf p = convert("long.txt", lines(5000).getBytes(StandardCharsets.UTF_8), 2);
        assertEquals(2, p.pages());
        assertTrue(p.result().pageLimitReached() && p.result().truncated());
        assertTrue(p.result().warnings().stream().anyMatch(w -> w.startsWith("Stopped at the page limit")),
                p.result().warnings().toString());
        assertTrue(p.result().warnings().stream().noneMatch(w -> w.contains("past the page limit")),
                p.result().warnings().toString());
    }

    @Test
    void aCsvPrintsLikeLibreOfficeWithTheNameOnTopAndPageNumbersBelow() throws IOException {
        StringBuilder csv = new StringBuilder("item,amount\n");
        for (int i = 0; i < 199; i++) {
            csv.append("thing ").append(i).append(',').append(i * 1.25).append('\n');
        }
        Pdf p = convert("ledger.csv", csv.toString());
        assertEquals(4, p.pages());
        assertTrue(p.text().contains("ledger") && p.text().contains("Page 1") && p.text().contains("Page 4"), p.text());
        assertTrue(p.text().contains("thing 198") && p.text().contains("247.5"), p.text());
        assertEquals(PDRectangle.A4.getWidth(), p.size().getWidth(), 0.5);
    }

    @Test
    void tabSeparatedFilesSplitIntoColumns() throws IOException {
        Pdf p = convert("data.tsv", "left\tright\n1\t2\n");
        assertTrue(p.text().contains("left") && p.text().contains("right"), p.text());
        assertFalse(p.text().contains("leftright"), p.text());
    }

    Pdf stream(OfficeToPdf.Format format, byte[] content, OfficeToPdf.Options options) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        OfficeToPdf.Result r = OfficeToPdf.convert(new java.io.ByteArrayInputStream(content), format, out, options);
        try (PDDocument doc = Loader.loadPDF(out.toByteArray())) {
            return new Pdf(doc.getNumberOfPages(), new PDFTextStripper().getText(doc), doc.getPage(0).getMediaBox(), r);
        }
    }

    @Test
    void streamsOfTextAndTablesConvertByTheirFormat() throws IOException {
        Pdf text = stream(OfficeToPdf.Format.TEXT, lines(65).getBytes(StandardCharsets.UTF_8),
                OfficeToPdf.Options.defaults());
        assertEquals(2, text.pages());
        assertTrue(text.text().contains("line 65"), text.text());
        Pdf csv = stream(OfficeToPdf.Format.CSV, "item,amount\nrent,12.5\n".getBytes(StandardCharsets.UTF_8),
                OfficeToPdf.Options.defaults().displayName("Household budget.csv"));
        assertTrue(csv.text().contains("Household budget") && !csv.text().contains("budget.csv")
                && csv.text().contains("12.5"), csv.text());
        Pdf tsv = stream(OfficeToPdf.Format.TSV, "left\tright\n".getBytes(StandardCharsets.UTF_8),
                OfficeToPdf.Options.defaults());
        assertFalse(tsv.text().contains("leftright"), tsv.text());
        Pdf word = stream(OfficeToPdf.Format.TEXT, Fixtures.docx("Real Word body"), OfficeToPdf.Options.defaults());
        assertTrue(word.text().contains("Real Word body"), word.text());
    }

    @Test
    void aStreamWithoutANamePrintsNoTemporaryFileName() throws IOException {
        for (OfficeToPdf.Format format : new OfficeToPdf.Format[] {OfficeToPdf.Format.CSV, OfficeToPdf.Format.TSV}) {
            Pdf p = stream(format, "item,amount\tqty\nrent,12.5\t3\n".getBytes(StandardCharsets.UTF_8),
                    OfficeToPdf.Options.defaults());
            assertTrue(p.text().contains("12.5") && !p.text().contains("office-to-pdf"), p.text());
        }
    }

    @Test
    void aRenamedCsvPrintsTheNameTheCallerGives() throws IOException {
        Path in = Files.write(dir.resolve("upload-7f3a.csv"), "a,b\n".getBytes(StandardCharsets.UTF_8));
        Path out = dir.resolve("named.pdf");
        OfficeToPdf.convert(in, out, OfficeToPdf.Options.defaults().displayName("C:\\Users\\me\\Team list.csv"));
        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            String t = new PDFTextStripper().getText(doc);
            assertTrue(t.contains("Team list") && !t.contains("upload-7f3a") && !t.contains("Users"), t);
        }
    }

    @Test
    void contentDecidesWhenATextNameHoldsAnOfficeFile() throws IOException {
        Pdf p = convert("report.txt", Fixtures.docx("Real Word body"), 0);
        assertTrue(p.text().contains("Real Word body"), p.text());
    }

    @Test
    void textNamesAreRecognisedAndEstimated() throws IOException {
        for (String n : new String[] {"a.txt", "a.TEXT", "a.log", "a.asc"}) {
            assertEquals(OfficeToPdf.Format.TEXT, OfficeToPdf.Format.of(Path.of(n)), n);
        }
        assertEquals(OfficeToPdf.Format.CSV, OfficeToPdf.Format.of(Path.of("a.csv")));
        for (String n : new String[] {"a.csv", "a.tsv", "a.tab"}) {
            assertEquals(n.endsWith("csv") ? OfficeToPdf.Format.CSV : OfficeToPdf.Format.TSV,
                    OfficeToPdf.Format.of(Path.of(n)), n);
            assertTrue(OfficeToPdf.Format.recognises(Path.of(n)), n);
        }
        Path big = Files.write(dir.resolve("big.csv"), "1,2\n".repeat(10_000).getBytes(StandardCharsets.US_ASCII));
        assertTrue(OfficeToPdf.memoryEstimate(big) > Files.size(big));
    }
}

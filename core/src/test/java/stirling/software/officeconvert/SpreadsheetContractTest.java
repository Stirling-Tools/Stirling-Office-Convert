package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpreadsheetContractTest {

    @TempDir Path dir;

    private static final class Tracked extends ByteArrayOutputStream {
        boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void callerStreamStaysOpenAndBlankPagesStillMakeAWorkbook() throws IOException {
        for (PdfToXlsx.Format f : PdfToXlsx.Format.values()) {
            Tracked out = new Tracked();
            try (PDDocument doc = new PDDocument()) {
                doc.addPage(new PDPage());
                PdfToXlsx.convert(doc, out, PdfToXlsx.Options.defaults().withFormat(f));
            }
            assertFalse(out.closed, "caller's stream closed");
            assertTrue(SpreadsheetPdfs.parts(out.toByteArray()).size() >= 5, "complete package written");
        }
    }

    @Test
    void failedConversionLeavesNoFile() throws IOException {
        Path pdf = dir.resolve("broken.pdf");
        Files.writeString(pdf, "not a pdf");
        Path xlsx = dir.resolve("broken.xlsx");
        assertThrows(IOException.class, () -> PdfToXlsx.convert(pdf, xlsx, PdfToXlsx.Options.defaults()));
        try (var files = Files.list(dir)) {
            assertTrue(files.allMatch(pdf::equals), "only the input remains");
        }
    }

    @Test
    void thePathOverloadWritesTheWorkbook() throws IOException {
        Path pdf = SpreadsheetPdfs.report(dir, 1);
        Path ods = dir.resolve("report.ods");
        PdfToXlsx.convert(pdf, ods, PdfToXlsx.Options.defaults().withFormat(PdfToXlsx.Format.of(ods)));
        assertTrue(SpreadsheetPdfs.parts(Files.readAllBytes(ods)).containsKey("content.xml"));
        try (var files = Files.list(dir)) {
            assertEquals(0, files.filter(p -> p.getFileName().toString().endsWith(".part")).count());
        }
    }

    @Test
    void anInterruptedThreadStops() throws IOException {
        Path pdf = SpreadsheetPdfs.report(dir, 2);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class,
                        () -> PdfToXlsx.convert(doc, new ByteArrayOutputStream(), PdfToXlsx.Options.defaults()));
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void badPageNumbersAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> PdfToXlsx.Options.defaults().withPages(-1, 0));
        assertThrows(IOException.class, () -> {
            try (PDDocument doc = new PDDocument()) {
                PdfToXlsx.convert(doc, new ByteArrayOutputStream(), PdfToXlsx.Options.defaults());
            }
        });
    }
}

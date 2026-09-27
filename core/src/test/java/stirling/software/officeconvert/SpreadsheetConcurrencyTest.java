package stirling.software.officeconvert;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpreadsheetConcurrencyTest {

    @TempDir Path dir;

    @Test
    void parallelConversionsMatchSequentialOnes() throws Exception {
        List<Path> pdfs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            pdfs.add(SpreadsheetPdfs.report(dir, i));
        }
        PdfToXlsx.Format[] formats = {PdfToXlsx.Format.XLSX, PdfToXlsx.Format.ODS};
        List<Map<String, byte[]>> expected = new ArrayList<>();
        for (Path pdf : pdfs) {
            for (PdfToXlsx.Format f : formats) {
                expected.add(convert(pdf, f));
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Map<String, byte[]>>> runs = new ArrayList<>();
            for (int round = 0; round < 5; round++) {
                for (Path pdf : pdfs) {
                    for (PdfToXlsx.Format f : formats) {
                        runs.add(pool.submit(() -> convert(pdf, f)));
                    }
                }
            }
            for (int i = 0; i < runs.size(); i++) {
                Map<String, byte[]> want = expected.get(i % expected.size());
                Map<String, byte[]> got = runs.get(i).get();
                assertEquals(want.keySet(), got.keySet());
                for (String part : want.keySet()) {
                    assertArrayEquals(want.get(part), got.get(part), part + " of workbook " + i % expected.size());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, byte[]> convert(Path pdf, PdfToXlsx.Format format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PdfToXlsx.convert(doc, out, PdfToXlsx.Options.defaults().withFormat(format));
        }
        Map<String, byte[]> parts = SpreadsheetPdfs.parts(out.toByteArray());
        parts.remove("docProps/core.xml");
        parts.remove("meta.xml");
        return parts;
    }
}

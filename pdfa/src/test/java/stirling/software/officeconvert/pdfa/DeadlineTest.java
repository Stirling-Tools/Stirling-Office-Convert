package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeadlineTest {

    @TempDir
    Path dir;

    @Test
    void aTimedOutConversionHasStoppedWorkingWhenItReturns() throws Exception {
        Path in = Hostile.write(dir, "slow", Hostile::slowFlatten);
        PdfToPdfA.Options options = PdfToPdfA.Options.defaults().level(PdfALevel.A1B).timeout(Duration.ofSeconds(1));
        long start = System.nanoTime();
        assertThrows(PdfToPdfA.TimedOut.class, () -> PdfToPdfA.convert(in, dir.resolve("out.pdf"), options));
        assertTrue(System.nanoTime() - start < 15_000_000_000L);
        assertFalse(workerAlive());
    }

    @Test
    void aTimedOutConversionOfAnOpenDocumentLeavesItAlone() throws Exception {
        Path in = Hostile.write(dir, "slow", Hostile::slowFlatten);
        PdfToPdfA.Options options = PdfToPdfA.Options.defaults().level(PdfALevel.A1B).timeout(Duration.ofSeconds(1));
        try (PDDocument doc = Loader.loadPDF(in.toFile())) {
            assertThrows(PdfToPdfA.TimedOut.class,
                    () -> PdfToPdfA.convert(doc, new ByteArrayOutputStream(), options));
            assertFalse(workerAlive());
            String before = snapshot(doc);
            Thread.sleep(1500);
            assertEquals(before, snapshot(doc));
        }
    }

    private static String snapshot(PDDocument doc) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append(doc.getDocumentCatalog().getCOSObject().keySet()).append(doc.getNumberOfPages());
        for (PDPage p : doc.getPages()) {
            b.append(p.getCOSObject().keySet()).append(p.getResources().getCOSObject().keySet());
            try (InputStream in = p.getContents()) {
                b.append(Arrays.hashCode(in.readAllBytes()));
            }
        }
        return b.toString();
    }

    private static boolean workerAlive() {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().equals("pdf-to-pdfa") && t.isAlive());
    }
}

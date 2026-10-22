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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
        Set<Thread> before = workers();
        long start = System.nanoTime();
        assertThrows(PdfToPdfA.TimedOut.class, () -> PdfToPdfA.convert(in, dir.resolve("out.pdf"), options));
        assertTrue(System.nanoTime() - start < Duration.ofMinutes(1).toNanos());
        assertFalse(newWorkerAlive(before));
    }

    @Test
    void aTimedOutConversionOfAnOpenDocumentLeavesItAlone() throws Exception {
        Path in = Hostile.write(dir, "slow", Hostile::slowFlatten);
        PdfToPdfA.Options options = PdfToPdfA.Options.defaults().level(PdfALevel.A1B).timeout(Duration.ofSeconds(1));
        try (PDDocument doc = Loader.loadPDF(in.toFile())) {
            Set<Thread> running = workers();
            assertThrows(PdfToPdfA.TimedOut.class,
                    () -> PdfToPdfA.convert(doc, new ByteArrayOutputStream(), options));
            assertFalse(newWorkerAlive(running));
            String before = snapshot(doc);
            Thread.sleep(1500);
            assertEquals(before, snapshot(doc));
        }
    }

    @Test
    void aWorkerThatIgnoresInterruptsIsAbandonedAfterTheGracePeriod() throws Exception {
        // a loaded machine may not start the worker within a short timeout, so longer ones are tried
        for (long millis = 100; !abandonsASpinningWorker(millis); millis *= 2) {
            assertTrue(millis < 25_600, "the worker never started, even within " + millis + " ms");
        }
    }

    private static boolean abandonsASpinningWorker(long millis) throws Exception {
        AtomicBoolean release = new AtomicBoolean();
        AtomicReference<Thread> spinner = new AtomicReference<>();
        Set<Thread> before = workers();
        long start = System.nanoTime();
        long nanos;
        Thread started;
        try {
            assertThrows(PdfToPdfA.TimedOut.class, () -> Deadline.run(Duration.ofMillis(millis), Duration.ofMillis(500),
                    () -> {
                        spinner.set(Thread.currentThread());
                        while (!release.get()) {
                            Thread.onSpinWait();
                        }
                        return null;
                    }));
            nanos = System.nanoTime() - start;
            started = spinner.get();
            if (started != null) {
                assertTrue(started.isAlive(), "the worker is left running");
            }
        } finally {
            release.set(true);
        }
        for (Thread t : workers()) {
            if (!before.contains(t)) {
                t.join(Duration.ofMinutes(1));
                assertFalse(t.isAlive());
            }
        }
        if (started == null) {
            return false;
        }
        assertTrue(nanos >= Duration.ofMillis(millis + 500).toNanos(), "the grace period was cut short");
        // the default grace is 10 s, so only the one given ends sooner
        assertTrue(nanos < Duration.ofMillis(millis + 10_000).toNanos(), "took " + nanos / 1_000_000 + " ms");
        return true;
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

    private static Set<Thread> workers() {
        Set<Thread> all = new HashSet<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals("pdf-to-pdfa")) {
                all.add(t);
            }
        }
        return all;
    }

    // only this test's worker counts: another test may have left its own one stopping
    private static boolean newWorkerAlive(Set<Thread> before) {
        return workers().stream().anyMatch(t -> !before.contains(t) && t.isAlive());
    }
}

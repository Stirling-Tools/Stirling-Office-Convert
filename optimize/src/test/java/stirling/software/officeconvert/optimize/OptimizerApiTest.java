package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import stirling.software.officeconvert.memory.Admission;

class OptimizerApiTest {

    @TempDir
    Path dir;

    @Test
    void interruptedDocumentCallsDoNotStartSaving() throws Exception {
        AtomicBoolean saved = new AtomicBoolean();
        try (PDDocument document = new PDDocument() {
            @Override
            public void save(File file) throws IOException {
                saved.set(true);
                super.save(file);
            }

            @Override
            public void save(OutputStream out) throws IOException {
                saved.set(true);
                super.save(out);
            }
        }) {
            document.addPage(new PDPage());
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> PdfOptimizer.optimize(document,
                        new ByteArrayOutputStream(), PdfOptimizer.Options.defaults()));
                assertFalse(saved.get());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void savingUsesTheDeadlineWorkerAndAdmission() throws Exception {
        Thread caller = Thread.currentThread();
        int before = Admission.jvm().running();
        try (PDDocument document = new PDDocument() {
            private void check() {
                assertFalse(Thread.currentThread() == caller);
                assertTrue(Admission.jvm().running() > before);
            }

            @Override
            public void save(File file) throws IOException {
                check();
                super.save(file);
            }

            @Override
            public void save(OutputStream out) throws IOException {
                check();
                super.save(out);
            }
        }) {
            document.addPage(new PDPage());
            AtomicBoolean closed = new AtomicBoolean();
            ByteArrayOutputStream output = new ByteArrayOutputStream() {
                @Override
                public void close() {
                    closed.set(true);
                }
            };
            PdfOptimizer.optimize(document, output, PdfOptimizer.Options.defaults());
            assertFalse(document.getDocument().isClosed());
            assertFalse(closed.get());
            assertEquals(before, Admission.jvm().running());
            try (PDDocument result = Loader.loadPDF(output.toByteArray())) {
                assertEquals(1, result.getNumberOfPages());
            }
        }
    }

    @Test
    void theDeadlineIncludesDocumentSerialization() throws Exception {
        try (PDDocument document = new PDDocument() {
            @Override
            public void save(OutputStream out) throws IOException {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Saving interrupted");
                }
                super.save(out);
            }
        }) {
            document.addPage(new PDPage());
            int before = Admission.jvm().running();
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(PdfOptimizer.TimedOut.class,
                    () -> PdfOptimizer.optimize(document, new ByteArrayOutputStream(),
                            PdfOptimizer.Options.defaults().timeout(Duration.ofMillis(20)))));
            assertFalse(document.getDocument().isClosed());
            assertEquals(before, Admission.jvm().running());
        }
    }

    @Test
    void documentSerializationStopsAtTheInputLimit() throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            Path saved = dir.resolve("bounded.pdf");
            assertThrows(IOException.class, () -> PdfOptimizer.save(document, saved, 128));
            assertTrue(Files.size(saved) <= 128);
            assertFalse(document.getDocument().isClosed());
        }
    }

    @Test
    void salvageObservesCancellationBeforeScanning() throws Exception {
        Path input = RawPdf.page("", "").write(dir, "cancel-salvage");
        Path output = dir.resolve("cancelled.pdf");
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> Salvage.rebuild(input, output));
            assertFalse(Files.exists(output));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void saveableEncryptedDocumentsStayEncryptedThroughTheStreamApi() throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            var permissions = new org.apache.pdfbox.pdmodel.encryption.AccessPermission();
            permissions.setCanPrint(false);
            var policy = new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "user", permissions);
            policy.setEncryptionKeyLength(128);
            policy.setPreferAES(true);
            document.protect(policy);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            PdfOptimizer.optimize(document, output, PdfOptimizer.Options.defaults().password("user"));
            assertFalse(document.getDocument().isClosed());
            try (PDDocument result = Loader.loadPDF(output.toByteArray(), "user")) {
                assertTrue(result.isEncrypted());
                assertFalse(result.getCurrentAccessPermission().canPrint());
                assertEquals(1, result.getNumberOfPages());
            }
        }
    }

    @Test
    void failedSalvageLeavesNoTemporaryFiles() throws Exception {
        Path input = Files.createDirectory(dir.resolve("not-a-pdf"));
        assertThrows(IOException.class, () -> PdfOptimizer.optimize(input, dir.resolve("out.pdf")));
        try (var files = Files.list(dir)) {
            assertEquals(java.util.List.of(input), files.toList());
        }
    }
}

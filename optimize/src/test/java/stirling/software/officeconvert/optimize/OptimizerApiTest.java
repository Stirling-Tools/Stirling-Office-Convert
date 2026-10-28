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
    void salvageRetainsCatalogAndPageGenerations() throws Exception {
        Path input = dir.resolve("generations.pdf");
        String damaged = "%PDF-1.4\n1 5 obj <</Type/Catalog/Pages 2 4 R>> endobj\n"
                + "3 7 obj <</Type/Page/Parent 2 4 R/MediaBox[0 0 200 200]>> endobj\n";
        Files.writeString(input, damaged);
        Path output = dir.resolve("generations-rebuilt.pdf");
        assertEquals(1, Salvage.rebuild(input, output).pages());
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            assertEquals(1, document.getNumberOfPages());
            assertTrue(document.getDocument().getXrefTable().containsKey(new org.apache.pdfbox.cos.COSObjectKey(3, 7)));
        }
    }

    @Test
    void stalePageTreeGenerationsDoNotSupplyInheritedAppearance() throws Exception {
        Path input = dir.resolve("stale-parent-generation.pdf");
        Files.writeString(input, "%PDF-1.4\n1 0 obj <</Type/Catalog/Pages 2 4 R>> endobj\n"
                + "2 0 obj <</Type/Pages/Kids[3 7 R]/Count 1/MediaBox[0 0 123 456]>> endobj\n"
                + "3 7 obj <</Type/Page/Parent 2 4 R>> endobj\n");
        Path output = dir.resolve("stale-parent-generation-rebuilt.pdf");
        assertEquals(1, Salvage.rebuild(input, output).pages());
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            assertEquals(612, document.getPage(0).getMediaBox().getWidth());
        }
    }

    @Test
    void salvageRetainsInheritedPageAppearance() throws Exception {
        Path input = inheritedDamage();
        Path output = dir.resolve("inherited-page-appearance-rebuilt.pdf");
        assertEquals(1, Salvage.rebuild(input, output).pages());
        assertInheritedAppearance(output);
    }

    private Path inheritedDamage() throws IOException {
        Path input = dir.resolve("inherited-page-appearance.pdf");
        String content = "BT /F1 12 Tf 40 100 Td (Recovered inherited appearance) Tj ET";
        String damaged = "%PDF-1.4\n1 0 obj <</Type/Catalog/Pages 2 0 R>> endobj\n"
                + "2 0 obj <</Type/Pages/Kids[2 0 R]/Count 1/MediaBox[10 20 310 420]"
                + "/CropBox[20 30 300 400]/Rotate 90/Resources<</Font<</F1 5 2 R>>>>>> endobj\n"
                + "3 0 obj <</Type/Page/Parent 2 0 R/Contents 4 0 R>> endobj\n"
                + "4 0 obj " + RawPdf.stream("", content) + " endobj\n"
                + "5 2 obj <</Type/Font/Subtype/Type1/BaseFont/Helvetica>> endobj\n";
        Files.writeString(input, damaged);
        return input;
    }

    private static void assertInheritedAppearance(Path output) throws IOException {
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            assertEquals(1, document.getNumberOfPages());
            PDPage page = document.getPage(0);
            assertEquals(300, page.getMediaBox().getWidth());
            assertEquals(400, page.getMediaBox().getHeight());
            assertEquals(280, page.getCropBox().getWidth());
            assertEquals(370, page.getCropBox().getHeight());
            assertEquals(90, page.getRotation());
            assertEquals("Helvetica", page.getResources().getFont(org.apache.pdfbox.cos.COSName.getPDFName("F1"))
                    .getName());
            String extracted = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertTrue(extracted.replaceAll("\\R", "").contains("Recovered inherited appearance"), extracted);
        }
    }

    @Test
    void anUnreadablePageTreeDoesNotBecomeASuccessfulEmptyPdf() throws Exception {
        Path input = inheritedDamage();
        try (PDDocument document = Loader.loadPDF(input.toFile())) {
            assertEquals(0, document.getNumberOfPages());
        }
        Path output = dir.resolve("recovered-nonempty.pdf");
        int before = Admission.jvm().running();
        var result = PdfOptimizer.optimize(input, output, PdfOptimizer.Options.defaults());
        assertInheritedAppearance(output);
        assertTrue(result.rewritten());
        assertEquals(before, Admission.jvm().running());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("page tree was rebuilt")));
        try (var files = Files.list(dir)) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().endsWith(".part")));
        }
    }

    @Test
    void aValidEmptyPageTreeDoesNotRestoreUnreachablePages() throws Exception {
        RawPdf raw = RawPdf.page("", "");
        raw.set(2, "<</Type/Pages/Kids[]/Count 0>>");
        Path input = raw.write(dir, "intentionally-empty");
        Path output = dir.resolve("intentionally-empty-optimized.pdf");
        var result = PdfOptimizer.optimize(input, output, PdfOptimizer.Options.defaults().alwaysRewrite(true));
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            assertEquals(0, document.getNumberOfPages());
        }
        assertFalse(result.warnings().stream().anyMatch(warning -> warning.contains("page tree was rebuilt")));
    }

    @Test
    void encryptedEmptyDocumentsAreNotSalvaged() throws Exception {
        Path input = dir.resolve("encrypted-empty.pdf");
        try (PDDocument document = new PDDocument()) {
            var permissions = new org.apache.pdfbox.pdmodel.encryption.AccessPermission();
            permissions.setCanPrint(false);
            var policy = new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "user", permissions);
            policy.setEncryptionKeyLength(128);
            policy.setPreferAES(true);
            document.protect(policy);
            document.save(input.toFile());
        }
        Path output = dir.resolve("encrypted-empty-optimized.pdf");
        var result = PdfOptimizer.optimize(input, output,
                PdfOptimizer.Options.defaults().password("user").alwaysRewrite(true));
        try (PDDocument document = Loader.loadPDF(output.toFile(), "user")) {
            assertTrue(document.isEncrypted());
            assertFalse(document.getCurrentAccessPermission().canPrint());
            assertEquals(0, document.getNumberOfPages());
        }
        assertFalse(result.warnings().stream().anyMatch(warning -> warning.contains("page tree was rebuilt")));
        try (var files = Files.list(dir)) {
            assertFalse(files.anyMatch(file -> file.getFileName().toString().endsWith(".part")));
        }
    }

    @Test
    void oversizedPageTreeReferencesDoNotEscapeAsNumberErrors() throws Exception {
        Path input = dir.resolve("huge-reference.pdf");
        Files.writeString(input, "%PDF-1.4\n1 0 obj <</Type/Catalog/Pages 999999999999999999999999 0 R>> endobj\n"
                + "3 0 obj <</Type/Page/MediaBox[0 0 200 200]>> endobj\n");
        Path output = dir.resolve("bounded-reference.pdf");
        assertEquals(1, Salvage.rebuild(input, output).pages());
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            assertEquals(1, document.getNumberOfPages());
        }
    }

    @Test
    void salvageWritesAsciiCrossReferencesInEveryLocale() throws Exception {
        Path input = RawPdf.page("", "").write(dir, "localized-salvage");
        Path output = dir.resolve("localized-salvage-rebuilt.pdf");
        java.util.Locale previous = java.util.Locale.getDefault(java.util.Locale.Category.FORMAT);
        try {
            java.util.Locale.setDefault(java.util.Locale.Category.FORMAT,
                    java.util.Locale.forLanguageTag("ar-EG-u-nu-arab"));
            assertEquals(1, Salvage.rebuild(input, output).pages());
        } finally {
            java.util.Locale.setDefault(java.util.Locale.Category.FORMAT, previous);
        }
        String rebuilt = Files.readString(output, java.nio.charset.StandardCharsets.ISO_8859_1);
        int start = rebuilt.lastIndexOf("\nxref\n") + 1;
        int end = rebuilt.indexOf("trailer", start);
        String[] lines = rebuilt.substring(start, end).split("\\r?\\n");
        for (int i = 2; i < lines.length; i++) {
            assertTrue(lines[i].matches("[0-9]{10} [0-9]{5} [fn]"), lines[i]);
        }
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
            assertEquals(1, document.getNumberOfPages());
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

package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AttachedPdfAValidationTest {

    @TempDir
    Path dir;

    @org.junit.jupiter.api.Test
    void verificationOfDifferentProfilesIsIndependentAcrossThreads() throws Exception {
        Path input = Samples.write(dir, "s21_tagged");
        java.util.List<byte[]> archives = new java.util.ArrayList<>();
        for (PdfALevel level : java.util.List.of(PdfALevel.A1B, PdfALevel.A2B, PdfALevel.A4)) {
            Path output = dir.resolve("parallel-" + level + ".pdf");
            PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults().level(level));
            archives.add(Files.readAllBytes(output));
        }
        java.util.List<java.util.concurrent.Callable<Boolean>> checks = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            byte[] bytes = archives.get(i % archives.size());
            checks.add(() -> {
                try (PDDocument doc = new PDDocument()) {
                    var stream = doc.getDocument().createCOSStream();
                    try (var output = stream.createOutputStream()) {
                        output.write(bytes);
                    }
                    return AttachedPdfA.check(stream, true);
                }
            });
        }
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            for (var result : executor.invokeAll(checks)) {
                assertTrue(result.get());
            }
        }
    }

    @org.junit.jupiter.api.Test
    void unsafeAttachmentMetadataIsRefusedBeforeFullValidation() throws Exception {
        RawPdf raw = RawPdf.page("", "");
        raw.add(RawPdf.stream("/Type/Metadata/Subtype/XML", "<!DOCTYPE x [<!ENTITY external SYSTEM"
                + " 'http://127.0.0.1:1/never-fetched'>]><x>&external;</x>"));
        raw.set(1, "<</Type/Catalog/Pages 2 0 R/Metadata 5 0 R>>");
        try (PDDocument doc = new PDDocument()) {
            var stream = doc.getDocument().createCOSStream();
            try (var output = stream.createOutputStream()) {
                output.write(raw.bytes());
            }
            assertThrows(java.io.IOException.class, () -> AttachedPdfA.check(stream));
        }
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A2B", "A4"})
    void anAttachmentBeyondTheVerificationBudgetRefusesConversionWithoutReplacingOutput(PdfALevel level)
            throws Exception {
        Path plain = Samples.write(dir, "s21_tagged");
        Path archive = dir.resolve("large-archive.pdf");
        PdfToPdfA.convert(plain, archive, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        try (PDDocument doc = Loader.loadPDF(Files.readAllBytes(archive))) {
            var stream = doc.getDocument().createCOSStream();
            try (var out = stream.createOutputStream()) {
                out.write(new byte[(int) AttachedPdfA.MAX_BYTES + 1]);
            }
            doc.getDocumentCatalog().getCOSObject().setItem("PrivateData", stream);
            doc.save(archive.toFile(), org.apache.pdfbox.pdfwriter.compress.CompressParameters.NO_COMPRESSION);
        }
        VeraPdf.assertCompliant(archive, PdfALevel.A1B);
        Path input = Hostile.write(dir, "oversized-attachment", doc -> {
            Hostile.plainPage(doc);
            PDComplexFileSpecification spec = new PDComplexFileSpecification();
            spec.setFile("large.pdf");
            spec.setEmbeddedFile(new PDEmbeddedFile(doc, new ByteArrayInputStream(Files.readAllBytes(archive))));
            PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
            tree.setNames(Map.of("large.pdf", spec));
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
            names.setEmbeddedFiles(tree);
            doc.getDocumentCatalog().setNames(names);
        });
        Path output = dir.resolve("existing.pdf");
        byte[] original = {1, 2, 3};
        Files.write(output, original);
        assertThrows(java.io.IOException.class,
                () -> PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults().level(level)));
        assertArrayEquals(original, Files.readAllBytes(output));
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A2B", "A4"})
    void aPdfADeclarationAloneCannotQualifyAnAttachment(PdfALevel level) throws Exception {
        RawPdf fake = RawPdf.page("", "0 0 1 rg 10 10 50 50 re f");
        fake.add(RawPdf.stream("/Type/Metadata/Subtype/XML", "<x:xmpmeta xmlns:x='adobe:ns:meta/'>"
                + "<rdf:RDF xmlns:rdf='" + XmpCarryOver.RDF + "'><rdf:Description"
                + " xmlns:pdfaid='http://www.aiim.org/pdfa/ns/id/' pdfaid:part='2' pdfaid:conformance='B'/>"
                + "</rdf:RDF></x:xmpmeta>"));
        fake.set(1, "<</Type/Catalog/Pages 2 0 R/Metadata 5 0 R>>");
        Path input = Hostile.write(dir, "attachment", doc -> {
            Hostile.plainPage(doc);
            PDComplexFileSpecification spec = new PDComplexFileSpecification();
            spec.setFile("claimed.pdf");
            spec.setFileUnicode("claimed.pdf");
            PDEmbeddedFile file = new PDEmbeddedFile(doc, new ByteArrayInputStream(fake.bytes()));
            file.setSubtype("application/pdf");
            spec.setEmbeddedFile(file);
            spec.setEmbeddedFileUnicode(file);
            PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
            tree.setNames(Map.of("claimed.pdf", spec));
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
            names.setEmbeddedFiles(tree);
            doc.getDocumentCatalog().setNames(names);
        });
        Path output = dir.resolve("validated-" + level + ".pdf");
        var result = PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(output, level);
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("embedded")), result.warnings().toString());
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            var files = doc.getDocumentCatalog().getNames().getEmbeddedFiles().getNames();
            assertTrue(files == null || files.isEmpty());
        }
    }
}

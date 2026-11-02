package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class AttachmentPreservationTest {

    private static final COSName AF = COSName.getPDFName("AF");

    private static final byte[] BINARY = {0, 1, 2, 10, 13, 40, 41, 92, 127, (byte) 128, (byte) 255};

    @TempDir
    Path dir;

    @ParameterizedTest
    @EnumSource(PdfALevel.class)
    void associatedFilesSurviveAtEveryLevelThatAllowsTheirPayload(PdfALevel level) throws Exception {
        Path input = Samples.write(dir, "s21_tagged");
        byte[] archive = archive();
        try (PDDocument doc = Loader.loadPDF(Files.readAllBytes(input))) {
            COSArray files = new COSArray();
            files.add(spec(doc, "archive.pdf", archive));
            files.add(spec(doc, "Résumé-合同.bin", BINARY));
            doc.getDocumentCatalog().getCOSObject().setItem(AF, files);
            doc.getPage(0).getCOSObject().setItem(AF, files);
            doc.save(input.toFile());
        }
        Path output = dir.resolve("associated-" + level + ".pdf");
        PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(output, level);
        Map<String, byte[]> files = files(output);
        assertEquals(level.part() == 1 ? 0 : level.part() == 3 ? 2 : 1, files.size());
        if (level.part() > 1) {
            assertArrayEquals(archive, files.get("archive.pdf"));
        }
        if (level.part() == 3) {
            assertArrayEquals(BINARY, files.get("Résumé-合同.bin"));
        }
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A3A", "A3B", "A3U", "A4"})
    void removingAnAnnotationRetainsItsEligibleAttachedFile(PdfALevel level) throws Exception {
        byte[] payload = level.part() == 4 ? archive() : BINARY;
        for (boolean hidden : List.of(false, true)) {
            Path input = Samples.write(dir, "s21_tagged");
            try (PDDocument doc = Loader.loadPDF(Files.readAllBytes(input))) {
                PDAnnotationFileAttachment annotation = new PDAnnotationFileAttachment();
                annotation.setFile(spec(doc, "attachment.bin", payload));
                annotation.setRectangle(new PDRectangle(20, 20, 20, 20));
                annotation.setPrinted(true);
                annotation.setHidden(hidden);
                annotation.setNoView(!hidden);
                doc.getPage(0).getAnnotations().add(annotation);
                doc.save(input.toFile());
            }
            Path output = dir.resolve("annotation-" + level + "-" + hidden + ".pdf");
            PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults().level(level));
            VeraPdf.assertCompliant(output, level);
            assertArrayEquals(payload, files(output).get("attachment.bin"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DOS", "Mac", "Unix", "Vendor"})
    void legacyEmbeddedStreamsAreNormalisedWithoutChangingTheirBytes(String platform) throws Exception {
        Path input = Samples.write(dir, "s21_tagged");
        try (PDDocument doc = Loader.loadPDF(Files.readAllBytes(input))) {
            PDComplexFileSpecification fs = spec(doc, "legacy.bin", BINARY);
            COSDictionary ef = fs.getCOSObject().getCOSDictionary(COSName.EF);
            COSStream file = ef.getCOSStream(COSName.F);
            ef.clear();
            ef.setItem(COSName.getPDFName(platform), file);
            tree(doc, Map.of("legacy.bin", fs));
            doc.save(input.toFile());
        }
        Path output = dir.resolve("legacy-" + platform + ".pdf");
        PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults().level(PdfALevel.A3B));
        VeraPdf.assertCompliant(output, PdfALevel.A3B);
        assertArrayEquals(BINARY, files(output).get("legacy.bin"));
    }

    @Test
    void allDistinctPlatformPayloadsAndDuplicateFilenamesSurvive() throws Exception {
        Path input = Samples.write(dir, "s21_tagged");
        try (PDDocument doc = Loader.loadPDF(Files.readAllBytes(input))) {
            PDComplexFileSpecification fs = spec(doc, "same.bin", BINARY);
            COSDictionary ef = fs.getCOSObject().getCOSDictionary(COSName.EF);
            ef.clear();
            for (String platform : List.of("DOS", "Mac", "Unix")) {
                ef.setItem(COSName.getPDFName(platform), new PDEmbeddedFile(doc,
                        new ByteArrayInputStream(platform.getBytes(StandardCharsets.UTF_8))));
            }
            COSArray associated = new COSArray();
            associated.add(fs);
            associated.add(spec(doc, "same.bin", BINARY));
            doc.getDocumentCatalog().getCOSObject().setItem(AF, associated);
            doc.save(input.toFile());
        }
        Path output = dir.resolve("distinct.pdf");
        PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults().level(PdfALevel.A3B));
        VeraPdf.assertCompliant(output, PdfALevel.A3B);
        assertEquals(2, files(output).size());
        Set<String> payloads = new HashSet<>();
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            CosWalk.walk(doc, object -> {
                if (object instanceof COSDictionary fs && fs.getDictionaryObject(COSName.EF) instanceof COSDictionary ef) {
                    for (var value : ef.getValues()) {
                        var actual = value instanceof org.apache.pdfbox.cos.COSObject indirect ? indirect.getObject() : value;
                        if (actual instanceof COSStream stream) {
                            try (var in = stream.createInputStream()) {
                                payloads.add(java.util.HexFormat.of().formatHex(in.readAllBytes()));
                            }
                        }
                    }
                }
            });
        }
        assertEquals(4, payloads.size());
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A3A", "A3B", "A3U"})
    void unreadableRetainedAttachmentsRefuseConversionWithoutReplacingOutput(PdfALevel level) throws Exception {
        Path input = Samples.write(dir, "s21_tagged");
        try (PDDocument doc = Loader.loadPDF(Files.readAllBytes(input))) {
            PDComplexFileSpecification fs = spec(doc, "unreadable.bin", BINARY);
            fs.getEmbeddedFile().getCOSObject().setItem(COSName.FILTER, COSName.getPDFName("UnknownDecode"));
            tree(doc, Map.of("unreadable.bin", fs));
            doc.save(input.toFile());
        }
        byte[] originalInput = Files.readAllBytes(input);
        Path output = dir.resolve("existing.pdf");
        Files.write(output, BINARY);
        assertThrows(java.io.IOException.class,
                () -> PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults().level(level)));
        assertArrayEquals(BINARY, Files.readAllBytes(output));
        assertArrayEquals(originalInput, Files.readAllBytes(input));
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A3A", "A3B", "A3U"})
    void retainedLzwAttachmentsAreRecompressedWithoutChangingPayloadBytes(PdfALevel level) throws Exception {
        Path input = Samples.write(dir, "s21_tagged");
        try (PDDocument doc = Loader.loadPDF(Files.readAllBytes(input))) {
            PDComplexFileSpecification fs = spec(doc, "compressed.bin", BINARY);
            try (var out = fs.getEmbeddedFile().getCOSObject().createOutputStream(COSName.LZW_DECODE)) {
                out.write(BINARY);
            }
            tree(doc, Map.of("compressed.bin", fs));
            doc.save(input.toFile());
        }
        Path output = dir.resolve("recompressed.pdf");
        PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(output, level);
        assertArrayEquals(BINARY, files(output).get("compressed.bin"));
    }

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A2B", "A4"})
    void anIncompatibleVariantDoesNotDiscardTheOtherEligibleVariant(PdfALevel level) throws Exception {
        byte[] archive = archive();
        Path input = Samples.write(dir, "s21_tagged");
        try (PDDocument doc = Loader.loadPDF(Files.readAllBytes(input))) {
            PDComplexFileSpecification fs = spec(doc, "variants.pdf", archive);
            fs.setEmbeddedFileUnicode(new PDEmbeddedFile(doc, new ByteArrayInputStream(BINARY)));
            tree(doc, Map.of("variants.pdf", fs));
            doc.save(input.toFile());
        }
        Path output = dir.resolve("variant-" + level + ".pdf");
        var result = PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(output, level);
        assertArrayEquals(archive, files(output).get("variants.pdf"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("embedded")), result.warnings().toString());
    }

    private byte[] archive() throws Exception {
        Path input = Samples.write(dir, "s21_tagged");
        Path output = dir.resolve("archive.pdf");
        PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        return Files.readAllBytes(output);
    }

    private static PDComplexFileSpecification spec(PDDocument doc, String name, byte[] bytes) throws Exception {
        PDComplexFileSpecification fs = new PDComplexFileSpecification();
        fs.setFile(name);
        fs.setFileUnicode(name);
        PDEmbeddedFile file = new PDEmbeddedFile(doc, new ByteArrayInputStream(bytes));
        file.setSubtype("application/octet-stream");
        fs.setEmbeddedFile(file);
        fs.setEmbeddedFileUnicode(file);
        return fs;
    }

    private static void tree(PDDocument doc, Map<String, PDComplexFileSpecification> files) throws Exception {
        PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
        tree.setNames(files);
        PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
        names.setEmbeddedFiles(tree);
        doc.getDocumentCatalog().setNames(names);
    }

    private static Map<String, byte[]> files(Path pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            var names = doc.getDocumentCatalog().getNames();
            if (names == null || names.getEmbeddedFiles() == null) {
                return Map.of();
            }
            var files = names.getEmbeddedFiles().getNames();
            assertNotNull(files);
            Map<String, byte[]> out = new java.util.TreeMap<>();
            for (var entry : files.entrySet()) {
                var file = entry.getValue().getEmbeddedFileUnicode();
                if (file == null) {
                    file = entry.getValue().getEmbeddedFile();
                }
                assertNotNull(file);
                out.put(entry.getKey(), file.toByteArray());
            }
            return out;
        }
    }
}

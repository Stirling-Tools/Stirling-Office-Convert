package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AttachmentPayloadTypesTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @EnumSource(PdfALevel.class)
    void mixedFileTypesRemainOpaqueAndSurviveWheneverTheTargetAllowsThem(PdfALevel level) throws Exception {
        Path input = Samples.write(dir, "s21_tagged");
        Map<String, byte[]> allowed = new TreeMap<>();
        for (PdfALevel archiveLevel : List.of(PdfALevel.A1B, PdfALevel.A2B)) {
            Path archive = dir.resolve(archiveLevel + ".pdf");
            PdfToPdfA.convert(input, archive, PdfToPdfA.Options.defaults().level(archiveLevel));
            allowed.put(archiveLevel + ".pdf", Files.readAllBytes(archive));
        }
        Map<String, byte[]> files = new TreeMap<>(allowed);
        int index = 0;
        for (String extension : List.of("doc", "docx", "docm", "xls", "xlsx", "xlsm", "xlsb", "ppt", "pptx",
                "pptm", "odt", "ods", "odp", "eml", "msg", "pdf", "epub", "rtf", "json", "xml", "csv", "zip",
                "7z", "png", "jpg", "tiff", "svg", "mp3", "mp4", "u3d", "dwg", "js", "exe", "ttf", "unknown")) {
            byte[] bytes = new byte[256 + index++];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) (i + index);
            }
            files.put("opaque." + extension, bytes);
        }
        files.put("empty.bin", new byte[0]);
        files.put("合同-Résumé-📎", new byte[] {0, (byte) 255, 1});
        try (PDDocument doc = Loader.loadPDF(Files.readAllBytes(input))) {
            Map<String, PDComplexFileSpecification> specifications = new TreeMap<>();
            for (var entry : files.entrySet()) {
                PDComplexFileSpecification fs = new PDComplexFileSpecification();
                fs.setFile(entry.getKey());
                fs.setFileUnicode(entry.getKey());
                PDEmbeddedFile data = new PDEmbeddedFile(doc, new ByteArrayInputStream(entry.getValue()));
                fs.setEmbeddedFile(data);
                fs.setEmbeddedFileUnicode(data);
                specifications.put(entry.getKey(), fs);
            }
            PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
            tree.setNames(specifications);
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
            names.setEmbeddedFiles(tree);
            doc.getDocumentCatalog().setNames(names);
            doc.save(input.toFile());
        }
        Path output = dir.resolve("mixed-" + level + ".pdf");
        PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(output, level);
        Map<String, byte[]> expected = level.part() == 1 ? Map.of() : level.part() == 3 ? files : allowed;
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            var tree = doc.getDocumentCatalog().getNames().getEmbeddedFiles();
            Map<String, PDComplexFileSpecification> actual = tree == null || tree.getNames() == null
                    ? Map.of() : tree.getNames();
            assertEquals(expected.keySet(), actual.keySet());
            for (var entry : expected.entrySet()) {
                assertArrayEquals(entry.getValue(), actual.get(entry.getKey()).getEmbeddedFile().toByteArray());
                assertArrayEquals(entry.getValue(), actual.get(entry.getKey()).getEmbeddedFileUnicode().toByteArray());
            }
        }
    }
}

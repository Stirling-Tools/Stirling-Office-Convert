package stirling.software.officeconvert.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import stirling.software.officeconvert.pdfa.PdfALevel;
import stirling.software.officeconvert.pdfa.PdfToPdfA;

class PackagedPdfATest {

    private static final byte[] XMP = ("<?xpacket begin='\uFEFF' id='W5M0MpCehiHzreSzNTczkc9d'?>"
            + "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
            + "<rdf:Description rdf:about='' xmlns:dc='http://purl.org/dc/elements/1.1/'>"
            + "<dc:title><rdf:Alt><rdf:li xml:lang='x-default'>Packaged metadata</rdf:li></rdf:Alt></dc:title>"
            + "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end='w'?>").getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A2B", "A3B", "A4"})
    void packagedCliPreservesAttachmentsAndObjectMetadata(PdfALevel level) throws Exception {
        Path plain = dir.resolve("plain.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(plain.toFile());
        }
        Path archive = dir.resolve("archive.pdf");
        PdfToPdfA.convert(plain, archive, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        byte[] payload = Files.readAllBytes(archive);
        Path input = dir.resolve("input.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            PDMetadata metadata = new PDMetadata(doc);
            metadata.importXMPMetadata(XMP);
            doc.getPage(0).setMetadata(metadata);
            PDComplexFileSpecification file = new PDComplexFileSpecification();
            file.setFile("archive.pdf");
            file.setEmbeddedFile(new PDEmbeddedFile(doc, new ByteArrayInputStream(payload)));
            COSArray associated = new COSArray();
            associated.add(file);
            doc.getDocumentCatalog().getCOSObject().setItem("AF", associated);
            doc.save(input.toFile());
        }
        Path output = dir.resolve("output.pdf");
        Path log = dir.resolve("cli.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx256m", "-jar", System.getProperty("officeconvert.packagedJar"), input.toString(), "--pdfa",
                level.part() + level.conformance().toLowerCase(Locale.ROOT), "-o", output.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Packaged CLI did not finish within 60 seconds");
            assertEquals(0, process.exitValue(), Files.readString(log));
        } finally {
            process.destroyForcibly();
        }
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            var names = doc.getDocumentCatalog().getNames();
            assertNotNull(names);
            var tree = names.getEmbeddedFiles();
            assertNotNull(tree);
            var files = tree.getNames();
            assertNotNull(files);
            assertEquals(1, files.size());
            assertArrayEquals(payload, files.get("archive.pdf").getEmbeddedFileUnicode().toByteArray());
            assertArrayEquals(XMP, doc.getPage(0).getMetadata().toByteArray());
        }
    }
}

package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ObjectMetadataPreservationTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @EnumSource(PdfALevel.class)
    void alreadyCompliantPageMetadataIsKeptVerbatim(PdfALevel level) throws Exception {
        Path plain = Samples.write(dir, "s21_tagged");
        Path input = dir.resolve("compliant-" + level + ".pdf");
        PdfToPdfA.convert(plain, input, PdfToPdfA.Options.defaults().level(level));
        byte[] xmp = ("<?xpacket begin='\uFEFF' id='W5M0MpCehiHzreSzNTczkc9d'?>"
                + "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='" + XmpCarryOver.RDF + "'>"
                + "<rdf:Description rdf:about='' xmlns:dc='http://purl.org/dc/elements/1.1/'>"
                + "<dc:title><rdf:Alt><rdf:li xml:lang='x-default'>Page title 合同</rdf:li></rdf:Alt></dc:title>"
                + "</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end='w'?>").getBytes(StandardCharsets.UTF_8);
        try (PDDocument doc = Loader.loadPDF(Files.readAllBytes(input))) {
            PDMetadata metadata = new PDMetadata(doc);
            metadata.importXMPMetadata(xmp);
            doc.getPage(0).setMetadata(metadata);
            try (var out = Files.newOutputStream(input)) {
                PdfWriter.write(doc, out, level);
            }
        }
        VeraPdf.assertCompliant(input, level);
        Path output = dir.resolve("preserved-" + level + ".pdf");
        PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(output, level);
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            assertArrayEquals(xmp, doc.getPage(0).getMetadata().toByteArray());
        }
    }

    @ParameterizedTest
    @EnumSource(PdfALevel.class)
    void predefinedPagePropertiesSurviveAndOnlyUnsupportedPropertiesAreRemoved(PdfALevel level) throws Exception {
        String xmp = "<?xpacket begin='\uFEFF' id='W5M0MpCehiHzreSzNTczkc9d'?>"
                + "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='" + XmpCarryOver.RDF + "'>"
                + "<rdf:Description rdf:about='' xmlns:dc='http://purl.org/dc/elements/1.1/' xmlns:custom='urn:custom:page'>"
                + "<dc:title><rdf:Alt><rdf:li xml:lang='x-default'>Page title 合同</rdf:li></rdf:Alt></dc:title>"
                + "<custom:private>unsupported</custom:private></rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end='w'?>";
        Path input = Samples.write(dir, "s21_tagged");
        try (PDDocument doc = Loader.loadPDF(Files.readAllBytes(input))) {
            PDMetadata metadata = new PDMetadata(doc);
            metadata.importXMPMetadata(xmp.getBytes(StandardCharsets.UTF_8));
            doc.getPage(0).setMetadata(metadata);
            doc.save(input.toFile());
        }
        Path output = dir.resolve("metadata-" + level + ".pdf");
        var result = PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(output, level);
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            var metadata = doc.getPage(0).getMetadata();
            assertNotNull(metadata);
            String preserved = new String(metadata.toByteArray(), StandardCharsets.UTF_8);
            assertTrue(preserved.contains("Page title 合同"), preserved);
            if (level.part() == 2 || level.part() == 3) {
                assertFalse(preserved.contains("unsupported"), preserved);
                assertTrue(result.warnings().stream().anyMatch(w -> w.contains("metadata")), result.warnings().toString());
            } else {
                assertTrue(preserved.contains("unsupported"), preserved);
            }
        }
    }
}

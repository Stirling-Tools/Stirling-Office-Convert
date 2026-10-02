package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XmpOnlyMetadataTest {

    @TempDir
    Path dir;

    @Test
    void typedXmpValuesFillInfoAndKeepLanguageAlternativesAndHistory() throws Exception {
        RawPdf pdf = sample(false);
        Path input = Hostile.write(dir, "xmp-only", pdf);
        for (PdfALevel level : new PdfALevel[] {PdfALevel.A1B, PdfALevel.A2B}) {
            Path output = dir.resolve(level + ".pdf");
            PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults().level(level));
            VeraPdf.assertCompliant(output, level);
            try (PDDocument doc = Loader.loadPDF(output.toFile())) {
                var info = doc.getDocumentInformation();
                assertEquals("Title", info.getTitle());
                assertEquals("Alice; Bob", info.getAuthor());
                assertEquals("Description", info.getSubject());
                assertEquals("Camera", info.getCreator());
                assertEquals("False", info.getTrapped());
                assertEquals(2020, info.getCreationDate().get(java.util.Calendar.YEAR));
                String xmp = new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
                for (String value : new String[] {"Titre", "Bob", "blue", "London", "saved", "2020-01-02"}) {
                    assertTrue(xmp.contains(value), xmp);
                }
                var xml = XmpCarryOver.parse(xmp.getBytes(StandardCharsets.UTF_8));
                assertEquals(1, xml.getElementsByTagNameNS(XmpProperties.DC, "title").getLength());
                assertEquals(1, xml.getElementsByTagNameNS("http://www.aiim.org/pdfa/ns/id/", "part").getLength());
            }
        }
    }

    @Test
    void infoValuesTakePrecedenceWhileOtherLanguagesAndCreatorsRemain() throws Exception {
        RawPdf pdf = sample(false);
        pdf.add("<</Title(Info title)/Author(Info author)>>");
        pdf.trailerExtra += "/Info 6 0 R";
        Path output = dir.resolve("info.pdf");
        PdfToPdfA.convert(Hostile.write(dir, "info", pdf), output, PdfToPdfA.Options.defaults());
        VeraPdf.assertCompliant(output, PdfALevel.A2B);
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            String xmp = new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
            assertTrue(xmp.contains("Info title") && xmp.contains("Info author") && xmp.contains("Titre")
                    && xmp.contains("Bob"), xmp);
            assertFalse(xmp.contains(">Alice<"), xmp);
        }
    }

    @Test
    void incorrectlyTypedPredefinedPropertiesAreDropped() throws Exception {
        Path output = dir.resolve("invalid.pdf");
        PdfToPdfA.convert(Hostile.write(dir, "invalid", sample(true)), output, PdfToPdfA.Options.defaults());
        VeraPdf.assertCompliant(output, PdfALevel.A2B);
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            assertNull(doc.getDocumentInformation().getTitle());
            assertNull(doc.getDocumentInformation().getAuthor());
            assertNull(doc.getDocumentInformation().getCreationDate());
            String xmp = new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
            assertFalse(xmp.contains("History") || xmp.contains("Rating"), xmp);
        }
    }

    private static RawPdf sample(boolean invalid) {
        String properties = "<dc:title><rdf:Alt><rdf:li xml:lang='x-default'>Title</rdf:li>"
                + "<rdf:li xml:lang='fr'>Titre</rdf:li></rdf:Alt></dc:title>"
                + "<dc:creator><rdf:Seq><rdf:li>Alice</rdf:li><rdf:li>Bob</rdf:li></rdf:Seq></dc:creator>"
                + "<dc:description><rdf:Alt><rdf:li xml:lang='x-default'>Description</rdf:li></rdf:Alt></dc:description>"
                + "<xmp:CreatorTool>Camera</xmp:CreatorTool><xmp:CreateDate>2020-01-02T03:04:05Z</xmp:CreateDate>"
                + "<xmp:Label>blue</xmp:Label><xmp:Rating>4</xmp:Rating><photoshop:City>London</photoshop:City>"
                + "<pdf:Trapped>False</pdf:Trapped><xmpMM:History><rdf:Seq><rdf:li rdf:parseType='Resource'>"
                + "<stEvt:action>saved</stEvt:action><stEvt:when>2020-01-02T03:04:05Z</stEvt:when>"
                + "</rdf:li></rdf:Seq></xmpMM:History>";
        if (invalid) {
            properties = properties.replace("xml:lang='x-default'", "").replace("<rdf:Seq><rdf:li>Alice</rdf:li>"
                    + "<rdf:li>Bob</rdf:li></rdf:Seq>", "Alice")
                    .replace("2020-01-02T03:04:05Z", "not a date").replace("<xmp:Rating>4", "<xmp:Rating>banana");
        }
        String xmp = "<rdf:RDF xmlns:rdf='" + XmpCarryOver.RDF + "'><rdf:Description"
                + " xmlns:dc='" + XmpProperties.DC + "' xmlns:xmp='" + XmpProperties.XMP + "' xmlns:pdf='"
                + XmpProperties.PDF + "' xmlns:photoshop='" + XmpProperties.PHOTOSHOP + "' xmlns:xmpMM='"
                + XmpProperties.MM + "' xmlns:stEvt='http://ns.adobe.com/xap/1.0/sType/ResourceEvent#'>"
                + properties + "</rdf:Description></rdf:RDF>";
        RawPdf pdf = RawPdf.page("", "");
        pdf.add(RawPdf.stream("/Type/Metadata/Subtype/XML", xmp));
        pdf.set(1, "<</Type/Catalog/Pages 2 0 R/Metadata 5 0 R>>");
        return pdf;
    }
}

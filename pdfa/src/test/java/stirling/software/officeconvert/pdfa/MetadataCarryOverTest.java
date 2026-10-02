package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataCarryOverTest {

    private static final String FX = "urn:factur-x:pdfa:CrossIndustryDocument:invoice:1p0#";

    @TempDir
    Path dir;

    @Test
    void facturXExtensionSchemasAndRightsSurviveTheConversion() throws Exception {
        RawPdf r = RawPdf.page("", "0 0 1 rg 10 10 50 50 re f");
        r.add(RawPdf.stream("/Type/Metadata/Subtype/XML", invoiceXmp()));
        r.set(1, "<</Type/Catalog/Pages 2 0 R/Metadata 5 0 R>>");
        Path in = Hostile.write(dir, "invoice", r);
        for (PdfALevel level : new PdfALevel[] {PdfALevel.A3B, PdfALevel.A1B}) {
            Path out = dir.resolve("invoice-" + level + ".pdf");
            PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
            VeraPdf.assertCompliant(out, level);
            try (PDDocument d = Loader.loadPDF(out.toFile())) {
                String xmp = new String(d.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
                assertTrue(xmp.contains("INVOICE") && xmp.contains("EN 16931"), xmp);
                assertTrue(xmp.contains("pdfaExtension:schemas"), xmp);
                assertTrue(xmp.contains("Copyright ACME"), xmp);
                assertTrue(xmp.contains("<pdfaid:part>" + level.part() + "</pdfaid:part>"), xmp);
                assertFalse(xmp.contains("Undescribed"), xmp);
            }
        }
    }

    private static String invoiceXmp() {
        StringBuilder props = new StringBuilder();
        for (String name : new String[] {"DocumentFileName", "DocumentType", "Version", "ConformanceLevel"}) {
            props.append("<rdf:li rdf:parseType='Resource'><pdfaProperty:name>").append(name)
                    .append("</pdfaProperty:name><pdfaProperty:valueType>Text</pdfaProperty:valueType>")
                    .append("<pdfaProperty:category>external</pdfaProperty:category>")
                    .append("<pdfaProperty:description>").append(name).append("</pdfaProperty:description></rdf:li>");
        }
        return "<?xpacket begin='﻿' id='W5M0MpCehiHzreSzNTczkc9d'?>"
                + "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
                + "<rdf:Description rdf:about='' xmlns:dc='http://purl.org/dc/elements/1.1/'>"
                + "<dc:rights><rdf:Alt><rdf:li xml:lang='x-default'>Copyright ACME</rdf:li></rdf:Alt></dc:rights>"
                + "</rdf:Description>"
                + "<rdf:Description rdf:about='' xmlns:fx='" + FX + "' fx:ConformanceLevel='EN 16931'>"
                + "<fx:DocumentType>INVOICE</fx:DocumentType><fx:DocumentFileName>factur-x.xml</fx:DocumentFileName>"
                + "<fx:Version>1.0</fx:Version></rdf:Description>"
                + "<rdf:Description rdf:about='' xmlns:c='http://example.com/custom/'><c:Undescribed>x</c:Undescribed>"
                + "</rdf:Description>"
                + "<rdf:Description rdf:about='' xmlns:pdfaExtension='http://www.aiim.org/pdfa/ns/extension/'"
                + " xmlns:pdfaSchema='http://www.aiim.org/pdfa/ns/schema#'"
                + " xmlns:pdfaProperty='http://www.aiim.org/pdfa/ns/property#'>"
                + "<pdfaExtension:schemas><rdf:Bag><rdf:li rdf:parseType='Resource'>"
                + "<pdfaSchema:schema>Factur-X PDFA Extension Schema</pdfaSchema:schema>"
                + "<pdfaSchema:namespaceURI>" + FX + "</pdfaSchema:namespaceURI>"
                + "<pdfaSchema:prefix>fx</pdfaSchema:prefix>"
                + "<pdfaSchema:property><rdf:Seq>" + props + "</rdf:Seq></pdfaSchema:property>"
                + "</rdf:li></rdf:Bag></pdfaExtension:schemas></rdf:Description>"
                + "</rdf:RDF></x:xmpmeta><?xpacket end='w'?>";
    }
}

package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfA4CustomXmpTest {

    private static final String XMP = "<?xpacket begin='﻿' id='W5M0MpCehiHzreSzNTczkc9d'?>"
            + "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
            + "<rdf:Description rdf:about='' xmlns:pdfaid='http://www.aiim.org/pdfa/ns/id/'>"
            + "<pdfaid:part>2</pdfaid:part><pdfaid:conformance>B</pdfaid:conformance></rdf:Description>"
            + "<rdf:Description rdf:about='' xmlns:dc='http://purl.org/dc/elements/1.1/'>"
            + "<dc:title><rdf:Alt><rdf:li xml:lang='x-default'>Stale XMP title</rdf:li></rdf:Alt></dc:title>"
            + "</rdf:Description>"
            + "<rdf:Description rdf:about='' xmlns:pdfx='http://ns.adobe.com/pdfx/1.3/'"
            + " pdfx:Department='Treasury'><pdfx:ProjectCode>PRJ-7731</pdfx:ProjectCode></rdf:Description>"
            + "<rdf:Description rdf:about='' xmlns:xmpMM='http://ns.adobe.com/xap/1.0/mm/'"
            + " xmlns:stEvt='http://ns.adobe.com/xap/1.0/sType/ResourceEvent#'><xmpMM:History><rdf:Seq>"
            + "<rdf:li rdf:parseType='Resource'><stEvt:action>saved</stEvt:action>"
            + "<stEvt:softwareAgent>Word Processor 16</stEvt:softwareAgent>"
            + "<stEvt:when>2026-10-01T10:00:00Z</stEvt:when></rdf:li>"
            + "</rdf:Seq></xmpMM:History></rdf:Description>"
            + "</rdf:RDF></x:xmpmeta><?xpacket end='w'?>";

    @TempDir
    Path dir;

    @Test
    void partFourKeepsCustomPropertiesAndRewritesIdentification() throws Exception {
        String xmp = convert(PdfALevel.A4);
        assertTrue(xmp.contains("<pdfx:ProjectCode>PRJ-7731</pdfx:ProjectCode>"), xmp);
        assertTrue(xmp.contains("<pdfx:Department>Treasury</pdfx:Department>"), xmp);
        assertTrue(xmp.contains("<xmpMM:History>") && xmp.contains("Word Processor 16"), xmp);
        assertTrue(xmp.contains("<pdfaid:part>4</pdfaid:part>"), xmp);
        assertTrue(xmp.contains("<pdfaid:rev>2020</pdfaid:rev>"), xmp);
        assertFalse(xmp.contains("conformance"), xmp);
        assertFalse(xmp.contains("<pdfaid:part>2</pdfaid:part>"), xmp);
        assertEquals(1, count(xmp, "<dc:title>"), xmp);
        assertTrue(xmp.contains("Info title wins"), xmp);
        assertFalse(xmp.contains("Stale XMP title"), xmp);
        assertFalse(xmp.contains("pdfaExtension"), xmp);
    }

    @Test
    void partTwoStillDropsUndescribedCustomProperties() throws Exception {
        String xmp = convert(PdfALevel.A2B);
        assertFalse(xmp.contains("PRJ-7731"), xmp);
        assertFalse(xmp.contains("Treasury"), xmp);
        assertTrue(xmp.contains("<pdfaid:part>2</pdfaid:part>"), xmp);
        assertTrue(xmp.contains("<pdfaid:conformance>B</pdfaid:conformance>"), xmp);
        assertEquals(1, count(xmp, "<dc:title>"), xmp);
        assertTrue(xmp.contains("Info title wins"), xmp);
    }

    private String convert(PdfALevel level) throws Exception {
        Path in = dir.resolve("custom-xmp.pdf");
        try (PDDocument d = new PDDocument()) {
            FontSamples.page(d, "BT /A 14 Tf 50 780 Td (Custom metadata) Tj ET", FontSamples.trueType(d));
            d.getDocumentInformation().setTitle("Info title wins");
            PDMetadata metadata = new PDMetadata(d);
            metadata.importXMPMetadata(XMP.getBytes(StandardCharsets.UTF_8));
            d.getDocumentCatalog().setMetadata(metadata);
            d.save(in.toFile());
        }
        Path out = dir.resolve("custom-xmp-" + level + ".pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        VeraPdf.assertCompliant(out, level);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            return new String(d.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static int count(String text, String needle) {
        return (int) Pattern.compile(Pattern.quote(needle)).matcher(text).results().count();
    }
}

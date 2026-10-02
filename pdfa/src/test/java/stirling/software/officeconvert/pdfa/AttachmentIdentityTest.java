package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;

import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AttachmentIdentityTest {

    @ParameterizedTest
    @ValueSource(strings = {"<pdfaid:part>2</pdfaid:part>",
            "<pdfaid:part>2</pdfaid:part><pdfaid:conformance>Z</pdfaid:conformance>",
            "<pdfaid:part>1</pdfaid:part><pdfaid:conformance>U</pdfaid:conformance>",
            "<!-- <pdfaid:part>2</pdfaid:part><pdfaid:conformance>B</pdfaid:conformance> -->"})
    void malformedPdfAClaimsAreRefused(String properties) throws Exception {
        assertFalse(check(properties, "http://www.aiim.org/pdfa/ns/id/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "B", "U"})
    void correctlyNamespacedPartTwoClaimsAreRecognised(String conformance) throws Exception {
        String properties = "<pdfaid:part>2</pdfaid:part><pdfaid:conformance>" + conformance + "</pdfaid:conformance>";
        assertTrue(check(properties, "http://www.aiim.org/pdfa/ns/id/"));
        assertFalse(check(properties, "http://example.com/not-pdfa/"));
    }

    @org.junit.jupiter.api.Test
    void nestedFilesAreCheckedWhenEmbeddedFileTypeIsMissing() throws Exception {
        RawPdf pdf = RawPdf.page("", "");
        String xmp = "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
                + "<rdf:Description xmlns:pdfaid='http://www.aiim.org/pdfa/ns/id/' pdfaid:part='2'"
                + " pdfaid:conformance='B'/></rdf:RDF>";
        pdf.add(RawPdf.stream("/Type/Metadata/Subtype/XML", xmp));
        pdf.add("<</Type/Filespec/EF<</F 7 0 R>>>>");
        pdf.add(RawPdf.stream("", "not a PDF"));
        pdf.set(1, "<</Type/Catalog/Pages 2 0 R/Metadata 5 0 R/AF[6 0 R]>>");
        try (PDDocument doc = new PDDocument()) {
            COSStream stream = doc.getDocument().createCOSStream();
            try (OutputStream out = stream.createOutputStream()) {
                out.write(pdf.bytes());
            }
            assertFalse(AttachedPdfA.check(stream));
        }
    }

    private static boolean check(String properties, String namespace) throws Exception {
        RawPdf pdf = RawPdf.page("", "");
        pdf.add(RawPdf.stream("/Type/Metadata/Subtype/XML", "<x:xmpmeta xmlns:x='adobe:ns:meta/'>"
                + "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
                + "<rdf:Description rdf:about='' xmlns:pdfaid='" + namespace + "'>" + properties
                + "</rdf:Description></rdf:RDF></x:xmpmeta>"));
        pdf.set(1, "<</Type/Catalog/Pages 2 0 R/Metadata 5 0 R>>");
        try (PDDocument doc = new PDDocument()) {
            COSStream stream = doc.getDocument().createCOSStream();
            try (OutputStream out = stream.createOutputStream()) {
                out.write(pdf.bytes());
            }
            return AttachedPdfA.check(stream);
        }
    }
}

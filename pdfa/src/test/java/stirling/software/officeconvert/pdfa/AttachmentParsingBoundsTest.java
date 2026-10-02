package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

class AttachmentParsingBoundsTest {

    @Test
    void hugeArraysStopWhileParsingAndSmallAttachmentsStillPass() throws Exception {
        RawPdf pdf = RawPdf.page("", "");
        pdf.add(RawPdf.stream("/Type/Metadata/Subtype/XML", "<rdf:RDF"
                + " xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'><rdf:Description"
                + " xmlns:pdfaid='http://www.aiim.org/pdfa/ns/id/' pdfaid:part='2' pdfaid:conformance='B'/></rdf:RDF>"));
        pdf.add("[" + "1.5 ".repeat(AttachmentParser.MAX_OBJECTS + 1) + "]");
        pdf.set(1, "<</Type/Catalog/Pages 2 0 R/Metadata 5 0 R/Big 6 0 R>>");
        try (RandomAccessReadBuffer source = new RandomAccessReadBuffer(pdf.bytes())) {
            AttachmentParser parser = new AttachmentParser(source);
            try (PDDocument doc = parser.parse(false)) {
                assertThrows(IOException.class, () -> {
                    CosWalk.walk(doc, object -> {});
                    parser.checkBudget();
                });
            }
        }
        try (PDDocument doc = new PDDocument()) {
            COSStream file = doc.getDocument().createCOSStream();
            try (OutputStream out = file.createOutputStream(COSName.FLATE_DECODE)) {
                out.write(pdf.bytes());
            }
            assertFalse(AttachedPdfA.check(file));
            pdf.set(6, "[1.5]");
            try (OutputStream out = file.createOutputStream(COSName.FLATE_DECODE)) {
                out.write(pdf.bytes());
            }
            assertTrue(AttachedPdfA.check(file));
        }
    }

    @Test
    void excessiveNestingIsRejectedBeforeStackExhaustion() throws Exception {
        RawPdf pdf = RawPdf.page("", "");
        pdf.set(1, "<</Type/Catalog/Pages 2 0 R/Deep " + "[".repeat(80) + "0" + "]".repeat(80) + ">>");
        try (RandomAccessReadBuffer source = new RandomAccessReadBuffer(pdf.bytes())) {
            AttachmentParser parser = new AttachmentParser(source);
            assertThrows(IOException.class, () -> parser.parse(false));
        }
    }

    @Test
    void compressedObjectStreamsHaveAnAggregateDecodedBudget() throws Exception {
        try (RandomAccessReadBuffer source = new RandomAccessReadBuffer(
                "%PDF-1.7".getBytes(StandardCharsets.US_ASCII))) {
            AttachmentParser parser = new AttachmentParser(source);
            assertThrows(IOException.class, () -> {
                org.apache.pdfbox.cos.COSDictionary dictionary = new org.apache.pdfbox.cos.COSDictionary();
                dictionary.setItem(COSName.TYPE, COSName.OBJ_STM);
                dictionary.setInt(COSName.N, AttachmentParser.MAX_OBJECTS + 1);
                parser.parseCOSStream(dictionary);
            });
        }
    }
}

package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataStreamsTest {

    @TempDir
    Path dir;

    @Test
    void pageMetadataIsCompressedAndTheDocumentsStaysUnfiltered() throws Exception {
        Path out = optimize(xmp(""), true);

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertNull(catalogMetadata(doc).getDictionaryObject(COSName.FILTER));
            assertEquals(COSName.FLATE_DECODE, pageMetadata(doc).getDictionaryObject(COSName.FILTER));
        }
    }

    @Test
    void pageMetadataIsCompressedWhenTheDocumentHasNone() throws Exception {
        Path out = optimize(null, true);

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertEquals(COSName.FLATE_DECODE, pageMetadata(doc).getDictionaryObject(COSName.FILTER));
        }
    }

    @Test
    void everyMetadataStreamOfAPdfAStaysUnfiltered() throws Exception {
        Path out = optimize(xmp("<pdfaid:part>2</pdfaid:part><pdfaid:conformance>B</pdfaid:conformance>"), true);

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            assertNull(catalogMetadata(doc).getDictionaryObject(COSName.FILTER));
            assertNull(pageMetadata(doc).getDictionaryObject(COSName.FILTER));
        }
    }

    private Path optimize(String catalogXmp, boolean pageXmp) throws Exception {
        Path in = dir.resolve("in.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            if (catalogXmp != null) {
                doc.getDocumentCatalog().getCOSObject().setItem(COSName.METADATA, metadata(doc, catalogXmp));
            }
            if (pageXmp) {
                page.getCOSObject().setItem(COSName.METADATA, metadata(doc, xmp("") + " ".repeat(20_000)));
            }
            doc.save(in.toFile());
        }
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults().alwaysRewrite(true));
        return out;
    }

    private static COSStream metadata(PDDocument doc, String xmp) throws Exception {
        COSStream stream = doc.getDocument().createCOSStream();
        stream.setItem(COSName.TYPE, COSName.METADATA);
        stream.setItem(COSName.SUBTYPE, COSName.getPDFName("XML"));
        try (OutputStream out = stream.createRawOutputStream()) {
            out.write(xmp.getBytes(StandardCharsets.UTF_8));
        }
        return stream;
    }

    private static String xmp(String pdfa) {
        return "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?><x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"
                + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\""
                + " xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\">" + pdfa + "</rdf:Description></rdf:RDF>"
                + "</x:xmpmeta><?xpacket end=\"w\"?>";
    }

    private static COSStream catalogMetadata(PDDocument doc) {
        return (COSStream) doc.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.METADATA);
    }

    private static COSStream pageMetadata(PDDocument doc) {
        return (COSStream) doc.getPage(0).getCOSObject().getDictionaryObject(COSName.METADATA);
    }
}

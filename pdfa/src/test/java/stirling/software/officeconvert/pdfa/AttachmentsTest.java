package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AttachmentsTest {

    @TempDir
    Path dir;

    @Test
    void pdfA2KeepsARealPdfAAttachmentAndDropsOneThatOnlyClaimsToBe() throws Exception {
        Path plain = Hostile.write(dir, "plain", Hostile::plainPage);
        Path archived = dir.resolve("archived.pdf");
        PdfToPdfA.convert(plain, archived, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        RawPdf fake = RawPdf.page("", "0 0 1 rg 10 10 50 50 re f");
        fake.add(RawPdf.stream("/Type/Metadata/Subtype/XML",
                "<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
                        + "<rdf:Description rdf:about='' xmlns:pdfaid='http://www.aiim.org/pdfa/ns/id/' "
                        + "pdfaid:part='2' pdfaid:conformance='B'/></rdf:RDF></x:xmpmeta>"));
        fake.set(1, "<</Type/Catalog/Pages 2 0 R/Metadata 5 0 R/OpenAction<</S/JavaScript/JS(app.alert(1))>>>>");
        Path in = Hostile.write(dir, "attached", d -> {
            Hostile.plainPage(d);
            PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
            tree.setNames(Map.of("real.pdf", spec(d, "real.pdf", Files.readAllBytes(archived)), "fake.pdf",
                    spec(d, "fake.pdf", fake.bytes())));
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(d.getDocumentCatalog());
            names.setEmbeddedFiles(tree);
            d.getDocumentCatalog().setNames(names);
        });
        Path out = dir.resolve("attached-2b.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        VeraPdf.assertCompliant(out, PdfALevel.A2B);
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            var files = d.getDocumentCatalog().getNames().getEmbeddedFiles().getNames();
            assertEquals(1, files.size(), files.keySet().toString());
            assertTrue(files.containsKey("real.pdf"));
        }
    }

    private static PDComplexFileSpecification spec(PDDocument d, String name, byte[] bytes) throws IOException {
        PDEmbeddedFile ef = new PDEmbeddedFile(d, new ByteArrayInputStream(bytes));
        ef.setSubtype("application/pdf");
        PDComplexFileSpecification fs = new PDComplexFileSpecification();
        fs.setFile(name);
        fs.setFileUnicode(name);
        fs.setEmbeddedFile(ef);
        fs.setEmbeddedFileUnicode(ef);
        return fs;
    }
}

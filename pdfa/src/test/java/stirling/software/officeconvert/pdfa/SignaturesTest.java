package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SignaturesTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @EnumSource(value = PdfALevel.class, names = {"A1B", "A2B"})
    void signedFieldsBecomePartOfThePageAndTheSignatureGoes(PdfALevel level) throws Exception {
        Path in = RuleSamples.write(dir, "d02_signature");
        Path out = dir.resolve("out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertTrue(d.getPage(0).getAnnotations().isEmpty());
            assertNull(d.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.PERMS));
            var acro = d.getDocumentCatalog().getAcroForm(null);
            assertTrue(acro == null || acro.getFields().isEmpty());
        }
        assertTrue(ColourRenderingTest.differing(ColourRenderingTest.render(in), ColourRenderingTest.render(out)) < 0.001);
    }
}

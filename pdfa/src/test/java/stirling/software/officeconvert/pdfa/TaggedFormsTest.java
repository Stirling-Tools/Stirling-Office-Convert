package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaggedFormsTest {

    @TempDir
    Path dir;

    @Test
    void aFormThatDrawsItselfIsCheckedWithoutOverflowingTheStack() throws Exception {
        Path in = Hostile.write(dir, "self", d -> Hostile.taggedForm(d, "self"));
        Path out = dir.resolve("self-out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A1A));
        VeraPdf.assertCompliant(out, PdfALevel.A1A);
    }

    @Test
    void untaggedTextInAFormWithStructParentsIsRefusedForLevelA() throws Exception {
        Path in = Hostile.write(dir, "structparents", d -> Hostile.taggedForm(d, "structparents"));
        IOException e = assertThrows(IOException.class, () -> PdfToPdfA.convert(in, dir.resolve("sp-out.pdf"),
                PdfToPdfA.Options.defaults().level(PdfALevel.A2A)));
        assertTrue(e.getMessage().contains("text outside the structure tree"), e.getMessage());
    }

    @Test
    void formTextTaggedThroughAStreamReferenceStaysTagged() throws Exception {
        Path in = Hostile.write(dir, "mcid", d -> Hostile.taggedForm(d, "mcid"));
        Path out = dir.resolve("mcid-out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2A));
        VeraPdf.assertCompliant(out, PdfALevel.A2A);
        try (PDDocument d = Loader.loadPDF(out.toFile()); InputStream c = d.getPage(0).getContents()) {
            String content = new String(c.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertFalse(content.contains("/Artifact BMC\n/Fm0 Do") || content.contains("/Artifact BMC /Fm0 Do"),
                    content);
            assertTrue(content.contains("/Fm0 Do"), content);
        }
    }
}

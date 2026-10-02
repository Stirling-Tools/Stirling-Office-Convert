package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentRewriteTest {

    @TempDir
    Path dir;

    @Test
    void aContentStreamSharedByTwoPagesKeepsEachPagesOwnText() throws Exception {
        Path in = Hostile.write(dir, "shared", Hostile::sharedContents);
        for (PdfALevel level : new PdfALevel[] {PdfALevel.A1B, PdfALevel.A2B}) {
            Path out = dir.resolve("shared-" + level + ".pdf");
            PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(level));
            VeraPdf.assertCompliant(out, level);
            String first = pageText(out, 1);
            String second = pageText(out, 2);
            assertTrue(first.contains("SharedHeader") && first.contains("AlphaPage"), first);
            assertFalse(first.contains("BravoPage"), first);
            assertTrue(second.contains("SharedHeader") && second.contains("BravoPage"), second);
            assertFalse(second.contains("AlphaPage"), second);
        }
    }

    static String pageText(Path pdf, int page) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf.toFile())) {
            PDFTextStripper s = new PDFTextStripper();
            s.setStartPage(page);
            s.setEndPage(page);
            return s.getText(d);
        }
    }
}

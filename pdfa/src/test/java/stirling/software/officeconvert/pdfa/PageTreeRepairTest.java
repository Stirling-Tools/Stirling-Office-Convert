package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PageTreeRepairTest {

    @TempDir
    Path dir;

    @Test
    void aPageWithoutTypeUnderABrokenTreeIsKept() throws Exception {
        Path in = RuleSamples.write(dir, "r05_page_tree");
        Path out = dir.resolve("out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertEquals(1, d.getNumberOfPages());
        }
        assertTrue(Converted.text(out).contains("Syntax sample"));
    }

    @Test
    void aPageTreeWithoutKidsIsRebuiltFromThePagesInTheFile() throws Exception {
        RawPdf r = RawPdf.page(RawPdf.helvetica(), "BT /F1 12 Tf 72 700 Td (Kidless tree) Tj ET");
        r.set(2, "<</Type/Pages/Count 1>>");
        Path in = dir.resolve("kidless.pdf");
        Files.write(in, r.bytes());
        Path out = dir.resolve("kidless-out.pdf");
        PdfToPdfA.Result result = PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A2B));
        assertTrue(result.warnings().contains("Rebuilt a page tree that was not well formed"),
                result.warnings().toString());
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertEquals(1, d.getNumberOfPages());
        }
        assertTrue(Converted.text(out).contains("Kidless tree"));
    }

    @Test
    void aPdfWithoutPagesIsRefused() throws Exception {
        RawPdf r = RawPdf.page(RawPdf.helvetica(), "");
        r.set(2, "<</Type/Pages/Kids[]/Count 0>>");
        Path in = dir.resolve("empty.pdf");
        Files.write(in, r.bytes());
        IOException e = assertThrows(IOException.class,
                () -> PdfToPdfA.convert(in, dir.resolve("o.pdf"), PdfToPdfA.Options.defaults()));
        assertTrue(e.getMessage().contains("no pages"), e.getMessage());
    }
}

package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LimitsTest {

    @TempDir
    Path dir;

    @Test
    void partOneLimitsOnNumbersStringsAndPageTreesAreMet() throws Exception {
        Path in = dir.resolve("limits.pdf");
        try (PDDocument d = new PDDocument()) {
            for (int i = 0; i < 8300; i++) {
                d.addPage(new PDPage());
            }
            PDPage first = d.getPage(0);
            PDStream s = new PDStream(d);
            try (OutputStream o = s.createOutputStream(COSName.FLATE_DECODE)) {
                o.write("0 0 1 rg 10 10 50000.5 20 re f".getBytes(StandardCharsets.US_ASCII));
            }
            first.setContents(s);
            d.getDocumentInformation().setSubject("x".repeat(70_000));
            d.save(in.toFile());
        }
        Path out = dir.resolve("out.pdf");
        PdfToPdfA.convert(in, out, PdfToPdfA.Options.defaults().level(PdfALevel.A1B));
        try (PDDocument d = Loader.loadPDF(out.toFile())) {
            assertEquals(8300, d.getNumberOfPages());
            COSArray kids = (COSArray) d.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.PAGES)
                    .getDictionaryObject(COSName.KIDS);
            assertTrue(kids.size() <= Limits.MAX_KIDS);
            assertTrue(d.getDocumentInformation().getSubject().length() < 65_536);
        }
        VeraPdf.assertCompliant(out, PdfALevel.A1B);
    }
}

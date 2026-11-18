package stirling.software.officeconvert.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmptyNameTest {

    @TempDir
    Path dir;

    @Test
    void anEmptyNameKeyBeforeAReferenceStaysAKey() throws Exception {
        Path in = dir.resolve("in.pdf");
        Files.writeString(in, """
                %PDF-1.7
                1 0 obj <</Type/Catalog/Pages 2 0 R/Glyphs 4 0 R/Widths 7 0 R>> endobj
                2 0 obj <</Type/Pages/Kids[3 0 R]/Count 1>> endobj
                3 0 obj <</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>> endobj
                4 0 obj <</ 5 0 R/A 6 0 R>> endobj
                5 0 obj <</Kind/Space/Length 8>> stream
                0 0 m S

                endstream endobj
                6 0 obj <</Kind/Letter/Length 8>> stream
                1 1 m S

                endstream endobj
                7 0 obj <</ 7/B 8>> endobj
                trailer <</Root 1 0 R>>
                %%EOF
                """, StandardCharsets.ISO_8859_1);
        Path out = dir.resolve("out.pdf");
        PdfOptimizer.optimize(in, out, PdfOptimizer.Options.defaults().alwaysRewrite(true));

        try (PDDocument doc = Loader.loadPDF(out.toFile())) {
            COSDictionary glyphs = doc.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.getPDFName("Glyphs"));
            assertEquals(2, glyphs.size(), glyphs.keySet().toString());
            COSDictionary space = assertInstanceOf(COSDictionary.class, glyphs.getDictionaryObject(COSName.getPDFName("")));
            assertEquals(COSName.getPDFName("Space"), space.getCOSName(COSName.getPDFName("Kind")));
            assertEquals(COSName.getPDFName("Letter"),
                    glyphs.getCOSDictionary(COSName.getPDFName("A")).getCOSName(COSName.getPDFName("Kind")));
            COSDictionary widths = doc.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.getPDFName("Widths"));
            assertEquals(7, widths.getInt(COSName.getPDFName("")));
            assertEquals(8, widths.getInt(COSName.getPDFName("B")));
        }
    }

    @Test
    void anEmptyNameOperandStaysApartFromTheNumberAfterIt() {
        assertEquals("/ 12 Tf", new String(Content.minify("/  12 Tf".getBytes(StandardCharsets.ISO_8859_1)),
                StandardCharsets.ISO_8859_1));
    }
}

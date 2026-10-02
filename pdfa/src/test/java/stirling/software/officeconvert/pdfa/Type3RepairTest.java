package stirling.software.officeconvert.pdfa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Type3RepairTest {

    @TempDir
    Path dir;

    @Test
    void sharedGlyphProgramsRetainEachCodesOwnAdvance() throws Exception {
        RawPdf pdf = RawPdf.page("/Font<</T3<</Type/Font/Subtype/Type3/FontBBox[0 0 750 750]"
                + "/FontMatrix[0.001 0 0 0.001 0 0]/CharProcs<</a 5 0 R>>"
                + "/Encoding<</Type/Encoding/Differences[97 /a /a]>>"
                + "/FirstChar 97/LastChar 98/Widths[900 600]>>>>", "BT /T3 50 Tf 20 100 Td (ab) Tj ET");
        pdf.add(RawPdf.stream("", "500 0 0 0 500 500 d1 0 0 500 500 re f"));
        Path output = dir.resolve("shared-out.pdf");
        PdfToPdfA.convert(Hostile.write(dir, "shared", pdf), output, PdfToPdfA.Options.defaults());
        VeraPdf.assertCompliant(output, PdfALevel.A2B);
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            PDType3Font font = (PDType3Font) doc.getPage(0).getResources().getFont(COSName.getPDFName("T3"));
            assertEquals(900, font.getWidthFromFont(97));
            assertEquals(600, font.getWidthFromFont(98));
        }
    }

    @Test
    void glyphWidthsMatchTheAdvanceAndMissingGlyphsHaveAppearances() throws Exception {
        RawPdf pdf = RawPdf.page("/Font<</T3<</Type/Font/Subtype/Type3/FontBBox[0 0 750 750]"
                + "/FontMatrix[0.001 0 0 0.001 0 0]/CharProcs<</a 5 0 R>>"
                + "/Encoding<</Type/Encoding/Differences[97 /a /b /c]>>"
                + "/FirstChar 97/LastChar 99/Widths[900 600 700]>>>>",
                "BT /T3 50 Tf 20 100 Td (abc) Tj ET");
        pdf.add(RawPdf.stream("", "500 0 0 0 500 500 d1 0 0 500 500 re f"));
        Path input = Hostile.write(dir, "type3", pdf);
        Path output = dir.resolve("out.pdf");
        PdfToPdfA.convert(input, output, PdfToPdfA.Options.defaults());
        VeraPdf.assertCompliant(output, PdfALevel.A2B);
        try (PDDocument doc = Loader.loadPDF(output.toFile())) {
            PDType3Font font = (PDType3Font) doc.getPage(0).getResources().getFont(COSName.getPDFName("T3"));
            assertEquals(900, font.getWidthFromFont(97));
            assertNotNull(font.getCharProc(98));
            assertNotNull(font.getCharProc(99));
            assertEquals(600, font.getWidthFromFont(98));
            assertEquals(700, font.getWidthFromFont(99));
        }
    }
}
